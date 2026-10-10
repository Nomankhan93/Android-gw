# BulkText Android 0.18.3 — Background Sync Persistence + Callback Diagnostics

## Scope
This patch hardens the 0.18.2 background gateway without changing the send-safety contract. It is intended to be applied only to the supplied 0.18.2 / versionCode 14 baseline.

## Built
- Background gateway control loop survives transient cycle exceptions instead of silently losing its worker.
- Queue/callback/recovery polling remains active while UNKNOWN pauses SMS execution.
- Callback outbox flush and Web recovery polling remain automatic; manual Sync is diagnostics-only.
- Persistent health timestamps track last control cycle, callback flush and recovery sync across Activity refresh/reopen.
- Recovery pause is preserved when the gateway is enabled with an existing UNKNOWN job.
- Raw Android callback `resultCode` plus optional platform `errorCode` are stored in SQLite diagnostics.
- Per-part callback kind, part number, symbolic/raw result, optional errorCode, callback latency, duplicate count and timestamp are exposed in the Android UI and diagnostic JSON export.
- SQLite cloud queue schema advances from v5 to v6 with a forward-only `error_code` diagnostic column.
- Callback diagnostics remain privacy-bounded: no recipient number, message body, credential or Supabase key is added.

## Safety invariants unchanged
- `UNKNOWN` is never automatically resent.
- Callback failure after SmsManager remains ambiguous and not retry-safe.
- Exact Web-selected SIM is still mandatory; no fallback SIM is introduced.
- Pre-activation READY jobs are not silently auto-sent.
- The callback receiver and background service never call SmsManager.

## Release
- versionName: `0.18.3`
- versionCode: `15`
- local SQLite cloud queue: `v6`

## Real-device acceptance
1. Update-install over 0.18.2; do not clear app data.
2. Enable background gateway and do not press manual Sync. Confirm control/callback/recovery health timestamps advance.
3. Create an UNKNOWN case. Confirm foreground gateway remains active but no new SMS executes.
4. Apply Web `skip_unknown`. Confirm it is polled/ACKed automatically and sending state recalculates without manual Sync.
5. Send a multipart SMS on the target vivo/Ufone path. Confirm each SENT/DELIVERED callback shows raw `resultCode`, optional `errorCode`, part index and elapsed milliseconds in UI/export.
6. Force a callback/outbox network failure, restore network, and confirm pending events flush automatically.
7. Confirm no duplicate SMS and no automatic resend of UNKNOWN.
