# BulkText 0.15.0 Handoff

## Product direction

BulkText remains individual-first. Existing organization tables remain only as the hidden personal-workspace tenancy boundary.

## Completed through 0.15

```text
0.4–0.13.1  foundation through individual UX cleanup
0.14        immutable campaign confirmation snapshot
0.14.x      campaign SQL lint stabilization (00305)
0.15        gateway preflight + 5-minute send authorization
```

## 0.15 architecture

```text
Confirmed campaign
→ current gateway/device/SIM/compliance preflight
→ short-lived auditable authorization
→ STOP
```

Authoritative rules:

- exact Web-bound SIM only;
- no silent SIM fallback;
- current suppression overrides old eligibility evidence;
- authorization expires after 5 minutes and is revocable;
- no queue in 0.15;
- no SMS sending in 0.15;
- do not claim exactly-once SMS delivery in future phases.

## Current migration head after deployment

```text
20261001000310_gateway_preflight_send_authorization.sql
```

Never rewrite `00300`, `00305`, or any other applied migration.

## 0.15 completion gate

- `npm run validate` PASS;
- `npm audit` = 0 vulnerabilities;
- dry-run shows only `00310` pending before push;
- `db lint --linked` clean after push;
- paired Android + original selected SIM → preflight Ready;
- authorization → Authorized;
- approximately 5-minute expiry verified;
- explicit revoke → Revoked;
- selected SIM disabled → preflight blocked/missing;
- other active SIM is not accepted;
- original SIM restored → preflight Ready;
- no queue jobs created;
- no SMS sent.

## Next

`0.16 — Durable Cloud Queue`

0.16 must require a fresh valid 0.15 authorization and atomically revalidate/consume it during idempotent queue creation. Android sending remains out of scope until the cloud queue and durable Android job client are proven.

## 0.16 Durable Cloud Queue

0.16 introduces `campaign_dispatches`, immutable `campaign_message_jobs`, and `campaign_queue_events`. A fresh 0.15 authorization is atomically revalidated and consumed during idempotent queue creation. Android queue RPCs use the existing device credential, enforce the frozen exact SIM identity, cap claims at 25 jobs with 2-minute leases, require durable download ACK, and safely recover only expired/un-ACKed leases. `downloaded` is not SMS submission. `campaign_send_enabled` remains false. Next: Android 0.6 Durable Cloud Job Client.
