begin;

-- BulkText 0.16 — Durable Cloud Queue
--
-- Consumes a fresh 0.15 authorization and freezes an immutable queue derived
-- only from campaign_recipients. Android may lease/download jobs with its
-- existing device credential, but this phase never calls SmsManager and never
-- records SENT/DELIVERED outcomes.

alter table public.campaign_send_authorizations
  add column if not exists consumed_at timestamptz;

create table public.campaign_dispatches (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  campaign_id uuid not null references public.campaigns(id) on delete restrict,
  authorization_id uuid not null references public.campaign_send_authorizations(id) on delete restrict,
  gateway_device_id uuid not null references public.gateway_devices(id) on delete restrict,
  gateway_sim_id uuid not null references public.gateway_device_sims(id) on delete restrict,
  sim_subscription_id integer not null check (sim_subscription_id >= 0),
  sim_slot_index integer not null check (sim_slot_index between 0 and 7),
  sim_identity_hash text not null check (sim_identity_hash ~ '^[0-9a-f]{64}$'),
  recipient_count integer not null check (recipient_count > 0),
  estimated_sms_units integer not null check (estimated_sms_units > 0),
  created_by uuid not null references auth.users(id) on delete restrict,
  enqueued_at timestamptz not null default now(),
  queue_version integer not null default 1 check (queue_version >= 1),
  unique (campaign_id),
  unique (authorization_id)
);

create index campaign_dispatches_org_enqueued_idx
on public.campaign_dispatches (organization_id, enqueued_at desc);

create index campaign_dispatches_device_enqueued_idx
on public.campaign_dispatches (gateway_device_id, enqueued_at, id);

create table public.campaign_message_jobs (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  dispatch_id uuid not null references public.campaign_dispatches(id) on delete cascade,
  campaign_id uuid not null references public.campaigns(id) on delete restrict,
  campaign_recipient_id bigint not null references public.campaign_recipients(id) on delete restrict,
  source_row_number integer not null check (source_row_number >= 2),
  normalized_e164 text not null check (normalized_e164 ~ '^\+923[0-9]{9}$'),
  rendered_message text not null check (char_length(rendered_message) between 1 and 16000),
  sms_encoding text not null check (sms_encoding in ('GSM-7','Unicode')),
  segment_count integer not null check (segment_count > 0),
  state text not null default 'queued' check (state in ('queued','leased','downloaded')),
  lease_owner_device_id uuid references public.gateway_devices(id) on delete restrict,
  lease_token uuid,
  lease_version integer not null default 0 check (lease_version >= 0),
  leased_at timestamptz,
  lease_expires_at timestamptz,
  downloaded_at timestamptz,
  created_at timestamptz not null default now(),
  unique (dispatch_id, campaign_recipient_id),
  check (
    (state = 'queued' and lease_owner_device_id is null and lease_token is null and leased_at is null and lease_expires_at is null and downloaded_at is null)
    or
    (state = 'leased' and lease_owner_device_id is not null and lease_token is not null and leased_at is not null and lease_expires_at is not null and downloaded_at is null)
    or
    (state = 'downloaded' and lease_owner_device_id is not null and lease_token is not null and leased_at is not null and lease_expires_at is not null and downloaded_at is not null)
  ),
  check (lease_expires_at is null or leased_at is null or lease_expires_at > leased_at)
);

create index campaign_message_jobs_dispatch_state_idx
on public.campaign_message_jobs (dispatch_id, state, source_row_number, id);

create index campaign_message_jobs_device_lease_idx
on public.campaign_message_jobs (lease_owner_device_id, state, lease_expires_at)
where lease_owner_device_id is not null;

create table public.campaign_queue_events (
  id bigint generated always as identity primary key,
  organization_id uuid not null references public.organizations(id) on delete cascade,
  dispatch_id uuid not null references public.campaign_dispatches(id) on delete cascade,
  job_id uuid references public.campaign_message_jobs(id) on delete cascade,
  event_type text not null check (event_type in ('dispatch_created','job_leased','job_downloaded','lease_released','lease_expired')),
  actor_type text not null check (actor_type in ('user','device','system')),
  actor_user_id uuid references auth.users(id) on delete set null,
  actor_device_id uuid references public.gateway_devices(id) on delete set null,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index campaign_queue_events_dispatch_created_idx
on public.campaign_queue_events (dispatch_id, created_at, id);

alter table public.campaign_dispatches enable row level security;
alter table public.campaign_message_jobs enable row level security;
alter table public.campaign_queue_events enable row level security;

create policy "campaign_dispatches_select_owner"
on public.campaign_dispatches
for select
to authenticated
using (public.is_org_member(organization_id));

create policy "campaign_message_jobs_select_owner"
on public.campaign_message_jobs
for select
to authenticated
using (public.is_org_member(organization_id));

create policy "campaign_queue_events_select_owner"
on public.campaign_queue_events
for select
to authenticated
using (public.is_org_member(organization_id));

revoke all on table public.campaign_dispatches from public, anon, authenticated;
revoke all on table public.campaign_message_jobs from public, anon, authenticated;
revoke all on table public.campaign_queue_events from public, anon, authenticated;

grant select on table public.campaign_dispatches to authenticated;
grant select on table public.campaign_message_jobs to authenticated;
grant select on table public.campaign_queue_events to authenticated;
grant all on table public.campaign_dispatches to service_role;
grant all on table public.campaign_message_jobs to service_role;
grant all on table public.campaign_queue_events to service_role;

create or replace function public.prevent_send_authorization_after_dispatch()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  if exists (
    select 1
    from public.campaign_dispatches d
    where d.campaign_id = new.campaign_id
      and d.organization_id = new.organization_id
  ) then
    raise exception 'Campaign already has a durable dispatch' using errcode = '23505';
  end if;
  return new;
end;
$$;

create trigger campaign_send_authorizations_no_post_dispatch
before insert on public.campaign_send_authorizations
for each row execute function public.prevent_send_authorization_after_dispatch();

create or replace function public.get_latest_campaign_send_authorization(
  p_organization_id uuid,
  p_campaign_id uuid
)
returns table(
  authorization_id uuid,
  authorized_at timestamptz,
  expires_at timestamptz,
  revoked_at timestamptz,
  revocation_reason text,
  status text,
  gateway_device_id uuid,
  gateway_sim_id uuid,
  sim_subscription_id integer,
  sim_slot_index integer
)
language plpgsql
stable
security definer
set search_path = public
as $$
begin
  if auth.uid() is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;

  if not public.is_org_member(p_organization_id) then
    raise exception 'Personal workspace access required' using errcode = '42501';
  end if;

  if not exists (
    select 1
    from public.campaigns c
    where c.id = p_campaign_id
      and c.organization_id = p_organization_id
  ) then
    raise exception 'Campaign not found' using errcode = 'P0002';
  end if;

  return query
  select
    a.id,
    a.authorized_at,
    a.expires_at,
    a.revoked_at,
    a.revocation_reason,
    case
      when a.revoked_at is not null then 'revoked'
      when a.consumed_at is not null then 'consumed'
      when a.expires_at <= now() then 'expired'
      else 'authorized'
    end,
    a.gateway_device_id,
    a.gateway_sim_id,
    a.sim_subscription_id,
    a.sim_slot_index
  from public.campaign_send_authorizations a
  where a.organization_id = p_organization_id
    and a.campaign_id = p_campaign_id
  order by a.authorized_at desc, a.id desc
  limit 1;
end;
$$;

create or replace function public.enqueue_campaign_dispatch(
  p_organization_id uuid,
  p_campaign_id uuid,
  p_authorization_id uuid
)
returns table(
  dispatch_id uuid,
  authorization_id uuid,
  status text,
  created boolean,
  enqueued_at timestamptz,
  gateway_device_id uuid,
  gateway_sim_id uuid,
  sim_subscription_id integer,
  sim_slot_index integer,
  recipient_count integer,
  estimated_sms_units integer,
  queued_jobs integer,
  leased_jobs integer,
  downloaded_jobs integer
)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_actor uuid := auth.uid();
  v_now timestamptz := now();
  v_campaign public.campaigns%rowtype;
  v_authorization public.campaign_send_authorizations%rowtype;
  v_existing public.campaign_dispatches%rowtype;
  v_preflight record;
  v_dispatch_id uuid;
  v_job_count integer := 0;
begin
  if v_actor is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;

  if not public.can_manage_org(p_organization_id) then
    raise exception 'Personal workspace owner permission required to create a durable queue' using errcode = '42501';
  end if;

  select c.*
    into v_campaign
  from public.campaigns c
  where c.id = p_campaign_id
    and c.organization_id = p_organization_id
  for update;

  if v_campaign.id is null then
    raise exception 'Campaign not found' using errcode = 'P0002';
  end if;

  select d.*
    into v_existing
  from public.campaign_dispatches d
  where d.organization_id = p_organization_id
    and d.campaign_id = p_campaign_id
  for update;

  if v_existing.id is not null then
    return query
    select
      v_existing.id,
      v_existing.authorization_id,
      case
        when count(*) filter (where j.state = 'downloaded') = v_existing.recipient_count then 'downloaded'
        when count(*) filter (where j.state = 'leased') > 0 then 'leasing'
        else 'queued'
      end,
      false,
      v_existing.enqueued_at,
      v_existing.gateway_device_id,
      v_existing.gateway_sim_id,
      v_existing.sim_subscription_id,
      v_existing.sim_slot_index,
      v_existing.recipient_count,
      v_existing.estimated_sms_units,
      count(*) filter (where j.state = 'queued')::integer,
      count(*) filter (where j.state = 'leased')::integer,
      count(*) filter (where j.state = 'downloaded')::integer
    from public.campaign_message_jobs j
    where j.dispatch_id = v_existing.id;
    return;
  end if;

  select a.*
    into v_authorization
  from public.campaign_send_authorizations a
  where a.id = p_authorization_id
    and a.organization_id = p_organization_id
    and a.campaign_id = p_campaign_id
  for update;

  if v_authorization.id is null then
    raise exception 'Send authorization not found' using errcode = 'P0002';
  end if;

  if v_authorization.revoked_at is not null then
    raise exception 'Send authorization was revoked' using errcode = '22023';
  end if;

  if v_authorization.consumed_at is not null then
    raise exception 'Send authorization was already consumed' using errcode = '22023';
  end if;

  if v_authorization.expires_at <= v_now then
    raise exception 'Send authorization expired before queue creation' using errcode = '22023';
  end if;

  if v_authorization.gateway_device_id <> v_campaign.gateway_device_id_snapshot
     or v_authorization.gateway_sim_id <> v_campaign.gateway_sim_id_snapshot
     or v_authorization.sim_subscription_id <> v_campaign.sim_subscription_id
     or v_authorization.sim_slot_index <> v_campaign.sim_slot_index
     or v_authorization.sim_identity_hash is distinct from v_campaign.sim_identity_hash then
    raise exception 'Send authorization identity does not match the immutable campaign snapshot' using errcode = '22023';
  end if;

  select *
    into v_preflight
  from public.campaign_send_preflight_internal(p_organization_id, p_campaign_id);

  if not coalesce(v_preflight.ready, false) then
    raise exception 'Durable queue creation blocked: %', array_to_string(v_preflight.blockers, ' | ')
      using errcode = '22023';
  end if;

  if v_campaign.sim_identity_hash is null
     or v_preflight.current_sim_identity_hash is distinct from v_campaign.sim_identity_hash
     or v_preflight.current_subscription_id is distinct from v_campaign.sim_subscription_id
     or v_preflight.current_slot_index is distinct from v_campaign.sim_slot_index then
    raise exception 'Exact SIM identity changed before queue creation' using errcode = '22023';
  end if;

  insert into public.campaign_dispatches (
    organization_id,
    campaign_id,
    authorization_id,
    gateway_device_id,
    gateway_sim_id,
    sim_subscription_id,
    sim_slot_index,
    sim_identity_hash,
    recipient_count,
    estimated_sms_units,
    created_by,
    enqueued_at
  ) values (
    p_organization_id,
    p_campaign_id,
    p_authorization_id,
    v_campaign.gateway_device_id_snapshot,
    v_campaign.gateway_sim_id_snapshot,
    v_campaign.sim_subscription_id,
    v_campaign.sim_slot_index,
    v_campaign.sim_identity_hash,
    v_campaign.recipient_count,
    v_campaign.estimated_sms_units,
    v_actor,
    v_now
  )
  returning id into v_dispatch_id;

  insert into public.campaign_message_jobs (
    organization_id,
    dispatch_id,
    campaign_id,
    campaign_recipient_id,
    source_row_number,
    normalized_e164,
    rendered_message,
    sms_encoding,
    segment_count
  )
  select
    r.organization_id,
    v_dispatch_id,
    r.campaign_id,
    r.id,
    r.source_row_number,
    r.normalized_e164,
    r.rendered_message,
    r.sms_encoding,
    r.segment_count
  from public.campaign_recipients r
  where r.campaign_id = p_campaign_id
    and r.organization_id = p_organization_id
  order by r.source_row_number, r.id;

  get diagnostics v_job_count = row_count;

  if v_job_count <> v_campaign.recipient_count then
    raise exception 'Queue job count does not match immutable campaign snapshot' using errcode = '22023';
  end if;

  update public.campaign_send_authorizations a
  set consumed_at = v_now
  where a.id = p_authorization_id
    and a.consumed_at is null;

  if not found then
    raise exception 'Send authorization was consumed concurrently' using errcode = '40001';
  end if;

  insert into public.campaign_queue_events (
    organization_id,
    dispatch_id,
    event_type,
    actor_type,
    actor_user_id,
    metadata
  ) values (
    p_organization_id,
    v_dispatch_id,
    'dispatch_created',
    'user',
    v_actor,
    jsonb_build_object(
      'campaignId', p_campaign_id,
      'authorizationId', p_authorization_id,
      'recipientCount', v_campaign.recipient_count,
      'estimatedSmsUnits', v_campaign.estimated_sms_units,
      'gatewayDeviceId', v_campaign.gateway_device_id_snapshot,
      'gatewaySimId', v_campaign.gateway_sim_id_snapshot,
      'subscriptionId', v_campaign.sim_subscription_id,
      'slotIndex', v_campaign.sim_slot_index,
      'queueVersion', 1
    )
  );

  insert into public.audit_logs (
    organization_id,
    actor_user_id,
    action,
    target_type,
    target_id,
    metadata
  ) values (
    p_organization_id,
    v_actor,
    'campaign.queue_created',
    'campaign_dispatch',
    v_dispatch_id::text,
    jsonb_build_object(
      'campaignId', p_campaign_id,
      'authorizationId', p_authorization_id,
      'recipientCount', v_campaign.recipient_count,
      'gatewayDeviceId', v_campaign.gateway_device_id_snapshot,
      'gatewaySimId', v_campaign.gateway_sim_id_snapshot
    )
  );

  dispatch_id := v_dispatch_id;
  authorization_id := p_authorization_id;
  status := 'queued';
  created := true;
  enqueued_at := v_now;
  gateway_device_id := v_campaign.gateway_device_id_snapshot;
  gateway_sim_id := v_campaign.gateway_sim_id_snapshot;
  sim_subscription_id := v_campaign.sim_subscription_id;
  sim_slot_index := v_campaign.sim_slot_index;
  recipient_count := v_campaign.recipient_count;
  estimated_sms_units := v_campaign.estimated_sms_units;
  queued_jobs := v_job_count;
  leased_jobs := 0;
  downloaded_jobs := 0;
  return next;
end;
$$;

create or replace function public.get_campaign_dispatch(
  p_organization_id uuid,
  p_campaign_id uuid
)
returns table(
  dispatch_id uuid,
  authorization_id uuid,
  status text,
  enqueued_at timestamptz,
  gateway_device_id uuid,
  gateway_sim_id uuid,
  sim_subscription_id integer,
  sim_slot_index integer,
  recipient_count integer,
  estimated_sms_units integer,
  queued_jobs integer,
  leased_jobs integer,
  downloaded_jobs integer
)
language plpgsql
stable
security definer
set search_path = public
as $$
begin
  if auth.uid() is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;

  if not public.is_org_member(p_organization_id) then
    raise exception 'Personal workspace access required' using errcode = '42501';
  end if;

  return query
  select
    d.id,
    d.authorization_id,
    case
      when count(*) filter (where j.state = 'downloaded') = d.recipient_count then 'downloaded'
      when count(*) filter (where j.state = 'leased') > 0 then 'leasing'
      else 'queued'
    end,
    d.enqueued_at,
    d.gateway_device_id,
    d.gateway_sim_id,
    d.sim_subscription_id,
    d.sim_slot_index,
    d.recipient_count,
    d.estimated_sms_units,
    count(*) filter (where j.state = 'queued')::integer,
    count(*) filter (where j.state = 'leased')::integer,
    count(*) filter (where j.state = 'downloaded')::integer
  from public.campaign_dispatches d
  join public.campaign_message_jobs j on j.dispatch_id = d.id
  where d.organization_id = p_organization_id
    and d.campaign_id = p_campaign_id
  group by d.id;
end;
$$;

create or replace function public.authenticate_gateway_queue_device_internal(
  p_device_id uuid,
  p_credential text
)
returns table(
  device_id uuid,
  organization_id uuid,
  credential_id uuid,
  server_time timestamptz
)
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_now timestamptz := now();
  v_device public.gateway_devices%rowtype;
  v_credential public.gateway_device_credentials%rowtype;
begin
  if p_device_id is null or char_length(coalesce(p_credential, '')) < 20 then
    raise exception 'Invalid gateway credential' using errcode = '42501';
  end if;

  select d.*
    into v_device
  from public.gateway_devices d
  where d.id = p_device_id
    and d.status = 'active'
  for update;

  if v_device.id is null then
    raise exception 'Invalid gateway credential' using errcode = '42501';
  end if;

  select c.*
    into v_credential
  from public.gateway_device_credentials c
  where c.device_id = p_device_id
    and c.version = v_device.credential_version
    and c.secret_hash = encode(digest(coalesce(p_credential, ''), 'sha256'), 'hex')
    and c.revoked_at is null
    and c.expires_at > v_now
  for update;

  if v_credential.id is null then
    raise exception 'Invalid gateway credential' using errcode = '42501';
  end if;

  update public.gateway_device_credentials c
  set last_used_at = v_now
  where c.id = v_credential.id;

  update public.gateway_devices d
  set last_seen_at = v_now
  where d.id = v_device.id;

  device_id := v_device.id;
  organization_id := v_device.organization_id;
  credential_id := v_credential.id;
  server_time := v_now;
  return next;
end;
$$;

create or replace function public.assert_gateway_dispatch_identity_internal(
  p_dispatch_id uuid,
  p_device_id uuid
)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  v_dispatch public.campaign_dispatches%rowtype;
  v_device public.gateway_devices%rowtype;
  v_bound_sim_id uuid;
  v_sim public.gateway_device_sims%rowtype;
begin
  select d.*
    into v_dispatch
  from public.campaign_dispatches d
  where d.id = p_dispatch_id
    and d.gateway_device_id = p_device_id;

  if v_dispatch.id is null then
    raise exception 'Queue work is not assigned to this gateway device' using errcode = '42501';
  end if;

  select d.*
    into v_device
  from public.gateway_devices d
  where d.id = p_device_id
    and d.organization_id = v_dispatch.organization_id
    and d.status = 'active'
  for share;

  if v_device.id is null then
    raise exception 'Gateway device is not active' using errcode = '42501';
  end if;

  if v_device.last_inventory_at is null or v_device.last_inventory_at < now() - interval '5 minutes' then
    raise exception 'Gateway SIM inventory is stale; refresh inventory before claiming queue work' using errcode = '22023';
  end if;

  select b.sim_id
    into v_bound_sim_id
  from public.gateway_device_sim_bindings b
  where b.device_id = p_device_id
  for share;

  if v_bound_sim_id is null or v_bound_sim_id <> v_dispatch.gateway_sim_id then
    raise exception 'Web-selected SIM binding changed; queue claim is blocked' using errcode = '22023';
  end if;

  select s.*
    into v_sim
  from public.gateway_device_sims s
  where s.id = v_dispatch.gateway_sim_id
    and s.device_id = p_device_id
  for share;

  if v_sim.id is null or not v_sim.present then
    raise exception 'Exact Web-selected SIM is missing; no SIM fallback is permitted' using errcode = '22023';
  end if;

  if v_sim.subscription_id <> v_dispatch.sim_subscription_id
     or v_sim.slot_index <> v_dispatch.sim_slot_index
     or v_sim.sim_identity_hash is null
     or v_sim.sim_identity_hash is distinct from v_dispatch.sim_identity_hash then
    raise exception 'Exact Web-selected SIM identity changed; queue claim is blocked' using errcode = '22023';
  end if;

  return true;
end;
$$;

create or replace function public.requeue_expired_gateway_leases_internal(
  p_device_id uuid
)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  v_now timestamptz := now();
  v_job record;
  v_count integer := 0;
begin
  for v_job in
    select j.id, j.organization_id, j.dispatch_id, j.lease_version
    from public.campaign_message_jobs j
    join public.campaign_dispatches d on d.id = j.dispatch_id
    where d.gateway_device_id = p_device_id
      and j.state = 'leased'
      and j.lease_expires_at <= v_now
    for update of j skip locked
  loop
    update public.campaign_message_jobs j
    set state = 'queued',
        lease_owner_device_id = null,
        lease_token = null,
        leased_at = null,
        lease_expires_at = null
    where j.id = v_job.id
      and j.state = 'leased';

    if found then
      insert into public.campaign_queue_events (
        organization_id, dispatch_id, job_id, event_type, actor_type, actor_device_id, metadata
      ) values (
        v_job.organization_id, v_job.dispatch_id, v_job.id, 'lease_expired', 'system', p_device_id,
        jsonb_build_object('leaseVersion', v_job.lease_version)
      );
      v_count := v_count + 1;
    end if;
  end loop;

  return v_count;
end;
$$;

create or replace function public.claim_gateway_message_jobs(
  p_device_id uuid,
  p_credential text,
  p_limit integer default 20
)
returns table(
  job_id uuid,
  dispatch_id uuid,
  campaign_id uuid,
  source_row_number integer,
  normalized_e164 text,
  rendered_message text,
  sms_encoding text,
  segment_count integer,
  lease_token uuid,
  lease_version integer,
  leased_at timestamptz,
  lease_expires_at timestamptz,
  gateway_sim_id uuid,
  sim_subscription_id integer,
  sim_slot_index integer,
  sim_identity_hash text
)
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_auth record;
  v_now timestamptz := now();
  v_limit integer := least(greatest(coalesce(p_limit, 20), 1), 25);
  v_dispatch public.campaign_dispatches%rowtype;
  v_job public.campaign_message_jobs%rowtype;
  v_leased public.campaign_message_jobs%rowtype;
begin
  select *
    into v_auth
  from public.authenticate_gateway_queue_device_internal(p_device_id, p_credential);

  perform public.requeue_expired_gateway_leases_internal(p_device_id);

  select d.*
    into v_dispatch
  from public.campaign_dispatches d
  where d.organization_id = v_auth.organization_id
    and d.gateway_device_id = p_device_id
    and exists (
      select 1
      from public.campaign_message_jobs pending
      where pending.dispatch_id = d.id
        and pending.state = 'queued'
    )
  order by d.enqueued_at, d.id
  limit 1;

  if v_dispatch.id is null then
    return;
  end if;

  perform public.assert_gateway_dispatch_identity_internal(v_dispatch.id, p_device_id);

  for v_job in
    select j.*
    from public.campaign_message_jobs j
    where j.dispatch_id = v_dispatch.id
      and j.organization_id = v_auth.organization_id
      and j.state = 'queued'
    order by j.source_row_number, j.id
    limit v_limit
    for update skip locked
  loop
    update public.campaign_message_jobs j
    set state = 'leased',
        lease_owner_device_id = p_device_id,
        lease_token = gen_random_uuid(),
        lease_version = j.lease_version + 1,
        leased_at = v_now,
        lease_expires_at = v_now + interval '2 minutes'
    where j.id = v_job.id
      and j.state = 'queued'
    returning j.* into v_leased;

    if v_leased.id is null then
      continue;
    end if;

    insert into public.campaign_queue_events (
      organization_id, dispatch_id, job_id, event_type, actor_type, actor_device_id, metadata
    ) values (
      v_leased.organization_id,
      v_leased.dispatch_id,
      v_leased.id,
      'job_leased',
      'device',
      p_device_id,
      jsonb_build_object(
        'leaseVersion', v_leased.lease_version,
        'leaseExpiresAt', v_leased.lease_expires_at
      )
    );

    job_id := v_leased.id;
    dispatch_id := v_leased.dispatch_id;
    campaign_id := v_leased.campaign_id;
    source_row_number := v_leased.source_row_number;
    normalized_e164 := v_leased.normalized_e164;
    rendered_message := v_leased.rendered_message;
    sms_encoding := v_leased.sms_encoding;
    segment_count := v_leased.segment_count;
    lease_token := v_leased.lease_token;
    lease_version := v_leased.lease_version;
    leased_at := v_leased.leased_at;
    lease_expires_at := v_leased.lease_expires_at;
    gateway_sim_id := v_dispatch.gateway_sim_id;
    sim_subscription_id := v_dispatch.sim_subscription_id;
    sim_slot_index := v_dispatch.sim_slot_index;
    sim_identity_hash := v_dispatch.sim_identity_hash;
    return next;
  end loop;
end;
$$;

create or replace function public.acknowledge_gateway_message_jobs(
  p_device_id uuid,
  p_credential text,
  p_receipts jsonb
)
returns integer
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_auth record;
  v_now timestamptz := now();
  v_receipt jsonb;
  v_job_id uuid;
  v_lease_token uuid;
  v_lease_version integer;
  v_job public.campaign_message_jobs%rowtype;
  v_dispatch public.campaign_dispatches%rowtype;
  v_seen uuid[] := array[]::uuid[];
  v_count integer := 0;
begin
  select *
    into v_auth
  from public.authenticate_gateway_queue_device_internal(p_device_id, p_credential);

  if p_receipts is null or jsonb_typeof(p_receipts) <> 'array' then
    raise exception 'Download ACK receipts must be a JSON array' using errcode = '22023';
  end if;

  if jsonb_array_length(p_receipts) < 1 or jsonb_array_length(p_receipts) > 25 then
    raise exception 'Download ACK batch must contain between 1 and 25 jobs' using errcode = '22023';
  end if;

  for v_receipt in select value from jsonb_array_elements(p_receipts)
  loop
    begin
      v_job_id := (v_receipt ->> 'jobId')::uuid;
      v_lease_token := (v_receipt ->> 'leaseToken')::uuid;
      v_lease_version := (v_receipt ->> 'leaseVersion')::integer;
    exception when invalid_text_representation or numeric_value_out_of_range then
      raise exception 'Invalid download ACK receipt' using errcode = '22023';
    end;

    if v_job_id is null or v_lease_token is null or v_lease_version is null then
      raise exception 'Incomplete download ACK receipt' using errcode = '22023';
    end if;

    if v_job_id = any(v_seen) then
      raise exception 'Duplicate job in download ACK batch' using errcode = '22023';
    end if;
    v_seen := array_append(v_seen, v_job_id);

    select j.*
      into v_job
    from public.campaign_message_jobs j
    join public.campaign_dispatches d on d.id = j.dispatch_id
    where j.id = v_job_id
      and j.organization_id = v_auth.organization_id
      and d.gateway_device_id = p_device_id
    for update of j;

    if v_job.id is null then
      raise exception 'Queue job not found for this gateway device' using errcode = '42501';
    end if;

    if v_job.state = 'downloaded'
       and v_job.lease_owner_device_id = p_device_id
       and v_job.lease_token = v_lease_token
       and v_job.lease_version = v_lease_version then
      v_count := v_count + 1;
      continue;
    end if;

    if v_job.state <> 'leased'
       or v_job.lease_owner_device_id <> p_device_id
       or v_job.lease_token <> v_lease_token
       or v_job.lease_version <> v_lease_version then
      raise exception 'Stale or mismatched queue lease ACK' using errcode = '40001';
    end if;

    update public.campaign_message_jobs j
    set state = 'downloaded',
        downloaded_at = v_now
    where j.id = v_job_id;

    insert into public.campaign_queue_events (
      organization_id, dispatch_id, job_id, event_type, actor_type, actor_device_id, metadata
    ) values (
      v_job.organization_id,
      v_job.dispatch_id,
      v_job.id,
      'job_downloaded',
      'device',
      p_device_id,
      jsonb_build_object('leaseVersion', v_lease_version)
    );

    v_count := v_count + 1;
  end loop;

  return v_count;
end;
$$;

create or replace function public.release_gateway_message_job_leases(
  p_device_id uuid,
  p_credential text,
  p_receipts jsonb
)
returns integer
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_auth record;
  v_receipt jsonb;
  v_job_id uuid;
  v_lease_token uuid;
  v_lease_version integer;
  v_job public.campaign_message_jobs%rowtype;
  v_seen uuid[] := array[]::uuid[];
  v_count integer := 0;
begin
  select *
    into v_auth
  from public.authenticate_gateway_queue_device_internal(p_device_id, p_credential);

  if p_receipts is null or jsonb_typeof(p_receipts) <> 'array' then
    raise exception 'Lease release receipts must be a JSON array' using errcode = '22023';
  end if;

  if jsonb_array_length(p_receipts) < 1 or jsonb_array_length(p_receipts) > 25 then
    raise exception 'Lease release batch must contain between 1 and 25 jobs' using errcode = '22023';
  end if;

  for v_receipt in select value from jsonb_array_elements(p_receipts)
  loop
    begin
      v_job_id := (v_receipt ->> 'jobId')::uuid;
      v_lease_token := (v_receipt ->> 'leaseToken')::uuid;
      v_lease_version := (v_receipt ->> 'leaseVersion')::integer;
    exception when invalid_text_representation or numeric_value_out_of_range then
      raise exception 'Invalid lease release receipt' using errcode = '22023';
    end;

    if v_job_id is null or v_lease_token is null or v_lease_version is null then
      raise exception 'Incomplete lease release receipt' using errcode = '22023';
    end if;

    if v_job_id = any(v_seen) then
      raise exception 'Duplicate job in lease release batch' using errcode = '22023';
    end if;
    v_seen := array_append(v_seen, v_job_id);

    select j.*
      into v_job
    from public.campaign_message_jobs j
    join public.campaign_dispatches d on d.id = j.dispatch_id
    where j.id = v_job_id
      and j.organization_id = v_auth.organization_id
      and d.gateway_device_id = p_device_id
    for update of j;

    if v_job.id is null then
      raise exception 'Queue job not found for this gateway device' using errcode = '42501';
    end if;

    if v_job.state = 'queued' and v_job.lease_version = v_lease_version then
      v_count := v_count + 1;
      continue;
    end if;

    if v_job.state <> 'leased'
       or v_job.lease_owner_device_id <> p_device_id
       or v_job.lease_token <> v_lease_token
       or v_job.lease_version <> v_lease_version then
      raise exception 'Stale or mismatched queue lease release' using errcode = '40001';
    end if;

    update public.campaign_message_jobs j
    set state = 'queued',
        lease_owner_device_id = null,
        lease_token = null,
        leased_at = null,
        lease_expires_at = null
    where j.id = v_job_id;

    insert into public.campaign_queue_events (
      organization_id, dispatch_id, job_id, event_type, actor_type, actor_device_id, metadata
    ) values (
      v_job.organization_id,
      v_job.dispatch_id,
      v_job.id,
      'lease_released',
      'device',
      p_device_id,
      jsonb_build_object('leaseVersion', v_lease_version)
    );

    v_count := v_count + 1;
  end loop;

  return v_count;
end;
$$;

revoke all on function public.prevent_send_authorization_after_dispatch() from public, anon, authenticated;
revoke all on function public.get_latest_campaign_send_authorization(uuid, uuid) from public, anon;
revoke all on function public.enqueue_campaign_dispatch(uuid, uuid, uuid) from public, anon;
revoke all on function public.get_campaign_dispatch(uuid, uuid) from public, anon;
revoke all on function public.authenticate_gateway_queue_device_internal(uuid, text) from public, anon, authenticated;
revoke all on function public.assert_gateway_dispatch_identity_internal(uuid, uuid) from public, anon, authenticated;
revoke all on function public.requeue_expired_gateway_leases_internal(uuid) from public, anon, authenticated;
revoke all on function public.claim_gateway_message_jobs(uuid, text, integer) from public;
revoke all on function public.acknowledge_gateway_message_jobs(uuid, text, jsonb) from public;
revoke all on function public.release_gateway_message_job_leases(uuid, text, jsonb) from public;

grant execute on function public.get_latest_campaign_send_authorization(uuid, uuid) to authenticated, service_role;
grant execute on function public.enqueue_campaign_dispatch(uuid, uuid, uuid) to authenticated, service_role;
grant execute on function public.get_campaign_dispatch(uuid, uuid) to authenticated, service_role;
grant execute on function public.authenticate_gateway_queue_device_internal(uuid, text) to service_role;
grant execute on function public.assert_gateway_dispatch_identity_internal(uuid, uuid) to service_role;
grant execute on function public.requeue_expired_gateway_leases_internal(uuid) to service_role;
-- Android authenticates with the device credential rather than a user JWT, so
-- these narrowly scoped queue RPCs remain callable by anon/authenticated. The
-- credential is verified before any job data is returned or mutated.
grant execute on function public.claim_gateway_message_jobs(uuid, text, integer) to anon, authenticated, service_role;
grant execute on function public.acknowledge_gateway_message_jobs(uuid, text, jsonb) to anon, authenticated, service_role;
grant execute on function public.release_gateway_message_job_leases(uuid, text, jsonb) to anon, authenticated, service_role;

update public.app_meta
set value = coalesce(value, '{}'::jsonb) || jsonb_build_object(
  'version', '0.16.0',
  'phase', 'durable_cloud_queue',
  'queue_enabled', true,
  'queue_lease_seconds', 120,
  'queue_claim_max_jobs', 25,
  'queue_download_ack_required', true,
  'authorization_consumption_required', true,
  'exact_sim_queue_claim_required', true,
  'android_job_client_enabled', false,
  'campaign_send_enabled', false
),
updated_at = now()
where key = 'schema';

commit;
