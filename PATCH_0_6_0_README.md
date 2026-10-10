# Android 0.6.0 — Durable Cloud Job Client

## Scope

0.6.0 consumes the finalized BulkText Web/Cloud 0.16 queue contract. It authenticates with the existing paired gateway credential, refreshes exact SIM inventory, claims a bounded batch, persists each job to an app-private SQLite journal, and ACKs only after durable persistence succeeds.

**0.6.0 sends zero cloud SMS.** It does not connect cloud jobs to `SmsManager`, `SendingService`, `SmsResultReceiver`, or the local SMS lab queue.

## Final 0.16 RPC contract consumed

- `claim_gateway_message_jobs(p_device_id, p_credential, p_limit)`
- `acknowledge_gateway_message_jobs(p_device_id, p_credential, p_receipts)`
- `release_gateway_message_job_leases(p_device_id, p_credential, p_receipts)`

Claimed jobs retain the frozen server execution identity: gateway SIM id, subscription id, slot index, and SIM identity hash. Batch size is bounded to 25; Android requests 20 by default. Server leases are 2 minutes.

## Durability / ACK ordering

```text
claim
→ validate exact device/SIM identity
→ persist complete job + lease locally
→ ACK durable receipt
→ STOP
```

If local persistence fails, Android does not ACK. Only work that was not durably accepted may be released. If persistence succeeds but ACK fails, the local job remains and is reconciled on the next sync.

## Re-lease recovery

A stable `job_id` may be returned later with a higher `lease_version` and new `lease_token` after an un-ACKed lease expires. Android keeps one local row for that job, verifies every immutable field is unchanged, and replaces only lease metadata. A changed immutable payload for the same job id is a hard integrity error and is not ACKed.

## Exact SIM safety

Every manual cloud sync refreshes phone/SIM inventory first. Queue acceptance requires the exact Web-bound `gateway_sim_id + subscription_id + slot_index + sim_identity_hash`. A missing or changed bound SIM blocks queue sync. Another active SIM is never substituted.

## Local journal

`cloud_queue.db` is separate from the existing local SMS lab `gateway.db`. It stores the future execution payload privately on-device, including recipient/message fields required for later 0.17 execution. Diagnostic export deliberately excludes full phone numbers, SMS bodies, credentials and Supabase keys.

## Version

- `versionName = 0.6.0`
- `versionCode = 7`

## Validation

From Windows PowerShell / Android Studio project root:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
Remove-Item env:ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
powershell -ExecutionPolicy Bypass -File .\scripts\validate-release.ps1
```

Then perform the Vivo V2109 real-device acceptance in `docs/DEVELOPMENT_HANDOFF.md`.
