# Web 0.18.5 — Delivery & Recovery Contract Hardening

Baseline: supplied Web 0.18.4 source. Migration: `20261010000100_delivery_recovery_contract_hardening.sql`.
Deploy the database migration before this Web build and before an Android v2 consumer. No hosted change was made while building this patch.

## Behavior

- SENT requires complete successful SENT callbacks. Delivery requires successful status-report evidence for every part in addition to complete SENT. `resultCode=-1` alone does not prove delivery.
- Callback event UUID payloads are immutable. Matching replay is idempotent. Changed payloads for the same event, conflicting SENT results for a part and contradictory terminal delivery observations latch UNKNOWN and retain the conflict. Later matching callbacks do not clear that conflict.
- Timeout ambiguity may improve to SENT when complete non-conflicting SENT evidence arrives. Operator `resolved_at`/`skip_without_retry` is independent and never cleared by callback aggregation.
- Recovery rows have pending/applied/superseded/rejected outcomes. Listing retires ALL obsolete rows before taking the first 25. An ACK arriving after late SENT is superseded, not a perpetual error. Rejected local apply can be explicitly requested again under a new request UUID.
- Claim scans eligible dispatches, retaining exact-SIM checks. A dispatch for an obsolete binding cannot starve a later valid binding. Suppressed/consent-ineligible queued jobs are recorded as blocked and skipped before leasing. Final compliance and segment checks also record durable blocks before registration. A pre-existing attempt is never erased or treated as proof of zero external effect.
- Permitted history deletion retains minimal attempt tombstones. Late callbacks from the same authenticated device receive a terminal acknowledgement rather than poisoning the outbox. Tombstones contain identifiers and deletion time, not recipient/message/PDU/credentials; retained until workspace deletion. No automatic purge job is included.
- Campaign Web status surfaces pre-send blocks, SIM gate reasons, conflicts, unverified legacy delivery and terminal recovery counts. Safe-retry UI typing/copy removed. No automatic resend is enabled.

## Historical data and rollout compatibility

Existing legacy DELIVERED claims are downgraded to SENT because their result-code-only records lack verified delivery evidence. Original events/parts/timestamps remain for audit. No historical UNKNOWN is promoted by the migration; known historical conflicts are latched. Resolved UNKNOWN stays resolved.

Android 0.18.4 keeps its existing RPC signatures. New SENT callbacks still work; legacy delivery observations are accepted but treated as unverified. Therefore the Web may show SENT while an old phone locally shows DELIVERED. This is deliberate until Android 0.18.5 submits v2 evidence.

A blocked legacy begin call returns an empty result, causing its registration parser to fail closed while committing the server block record. The phone may still keep that item READY. Local blocked-job advancement, per-device outbox isolation, ACK-pending durability and local resolution/conflict preservation require Android 0.18.5. This server patch does not claim to fix those phone-side behaviors.

## Gateway RPC contract

All device RPCs authenticate current active device credentials with workspace scoping, expiry and revocation checks. No service key is needed in Android. All v1 RPC names/argument and return shapes remain.

| RPC | Additions / terminal behavior |
|---|---|
| `report_gateway_message_attempt_event_v2` | Existing event arguments plus `p_delivery_format`, `p_delivery_status`, `p_delivery_pdu_sha256`; JSON result |
| `begin_gateway_message_attempt_v2` | Same five arguments as v1; JSON `registered`/`blocked`, `can_submit`, structured block code/reason |
| `acknowledge_gateway_message_recovery_v2` | Existing arguments plus `p_outcome`: `applied`, `not_applicable`, `rejected`; JSON terminal outcome |
| `list_gateway_message_recovery_requests` | Same v1 result columns, now only actionable pending rows |
| `get_campaign_contract_health` | Authenticated Web workspace/campaign RPC: blocked counts/reasons, conflict/legacy counts and recovery outcomes |

### Callback observations

Arguments: `p_device_id`, `p_credential`, `p_event_id`, `p_client_attempt_id`, `p_event_type` (`submitted`, `sent`, `delivery`, `unknown`), `p_part_index`, `p_result_code`, `p_occurred_at_ms`.

For `sent`/`delivery`, a valid zero-based part index and result code are mandatory. Non-part events must leave both null. Use a new UUID for every distinct observed callback, durably save its original time/payload once, and replay that identical observation on transport retry. Never manufacture success from a missing callback.

For `delivery`, the Android consumer must parse the PDU using its supplied format and confirm `isStatusReportMessage` before filling evidence. Supply format `3gpp`/`3gpp2`, **raw Android `SmsMessage.getStatus()`**, and lowercase SHA-256 of the original PDU bytes. Missing/invalid/non-status PDU: leave evidence null. The server trusts the authenticated gateway parser; a digest is correlation evidence, not a network signature and cannot independently prove PDU content.

- GSM raw 0–31 = success; 32–63 = pending; 64–255 = failed report. No delivery failure grants resend.
- CDMA uses Android's upper-word status encoding: low 16 bits must be zero. Raw zero is the conservative successful case; error class in bits 25–24 >=2 is failed; remaining supported codes pending. Preserve raw status, do not pass a right-shifted value.
- Later pending after a verified terminal report is retained in events but does not downgrade it. Contradictory successful/failed terminal reports latch conflict.

Result `outcome`:

| Value | Consumer action |
|---|---|
| accepted / duplicate | Mark observation synchronized; use returned transport truth independently of resolution |
| conflict | Mark synchronized, retain diagnostic conflict and UNKNOWN; never retry SMS |
| tombstoned | Mark outbox terminally acknowledged; do not recreate work/history or infer SENT |
| rejected + attempt_unavailable | Quarantine that event for this immutable device identity, continue later items, never resend |

Network/transient errors retry the same event UUID/payload. Authentication failures stop that identity's sync and require re-pair/credential handling; do not try another device's credential. Validation/identity errors are not a reason to resend SMS.

### Begin and recovery

Only `registered` with `can_submit=true` can authorize a fresh local submission boundary; preserve local durable no-repeat guards. Reusing completed attempt identities is not a new send grant. `blocked` always means do not call SmsManager. `recipient_ineligible` / `segment_mismatch` persist on the job and are not auto-cleared; a new campaign after explicit correction is the current resolution path. `sim_gate` has `recheck_after_inventory=true`; refresh exact inventory/binding and recheck, never fall back to another SIM.

ACK `outcome=applied` is sent only after durable local no-resend resolution. `not_applicable`/`rejected` report inability to apply; if cloud is already known/resolved, it becomes superseded, otherwise rejected without inventing local success. All terminal server outcomes return `terminal=true`. A later ACK replay returns the original terminal outcome. If the ACK response is lost, replay the SAME recovery UUID. The legacy ACK returns true for terminal processing, including superseded requests.

No callback or recovery API authorizes safe retry. A second client attempt for an already-attempted job is rejected.

## Validation and limits

`npm run test:delivery-contract` creates a disposable in-memory PGlite PostgreSQL engine, loads the real migration chain and real PL/pgSQL, provides minimal Supabase-owned auth/storage scaffolding, tests real credential authentication and switches client roles for privilege/RLS checks. It never reads .env or contacts hosted services. This is not a hosted Supabase/PostgREST or concurrent multi-session certification.

Run `npm run validate` for preflight, referenced-project TypeScript checking, unit tests and build. Existing bundle-size warning remains; bundle optimization is outside this correctness patch.

After the cloud migration, run `supabase/verify_fresh_schema.sql` in the hosted SQL editor (read-only assertions) and validate controlled Android v2 callbacks/recovery on the actual phone when the consumer patch is installed. Account-scoped drafts/import safety/history pagination remain Web 0.18.6 work.

Platform status reference: https://developer.android.com/reference/android/telephony/SmsMessage#getStatus()
CDMA encoding source: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telephony/java/com/android/internal/telephony/cdma/SmsMessage.java
PGlite: https://pglite.dev/extensions/
