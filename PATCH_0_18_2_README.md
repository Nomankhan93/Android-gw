# BulkText Android 0.18.2 — Recovery Request Apply/ACK + Auto Recovery Sync While UNKNOWN

## Why
Real-device 0.18.1 acceptance showed a control-plane deadlock: UNKNOWN correctly blocked SMS execution, but the UI also blocked enabling the background gateway. Manual cloud-job sync refreshed the queue but did not poll Web recovery requests. The result was UNKNOWN remaining unresolved until app interaction, contrary to the background-gateway product model.

## Fix
- UNKNOWN no longer blocks starting the persistent background gateway.
- UNKNOWN still blocks every new SmsManager execution.
- Background service continues callback outbox, queue, and recovery polling while paused.
- Manual `Sync cloud jobs (manual / recovery)` now polls recovery before and after queue sync.
- `skip_unknown` remains no-resend recovery and is ACKed to Cloud after local application.
- Recovery-mode status is explicit in the UI/foreground notification.
- Exact SIM checks and pre-activation READY-job guard remain unchanged.

## Release
- versionName: 0.18.2
- versionCode: 14

## Acceptance
1. Update-install over 0.18.1; do not clear app data.
2. With existing UNKNOWN attempts, enable background gateway. It must enter recovery mode instead of refusing to start.
3. Do not press manual sync. Confirm Last sync advances automatically.
4. Existing Web `skip_unknown` requests must apply/ACK without resending the ambiguous SMS.
5. UNKNOWN must reach 0.
6. No duplicate SMS may be observed.
7. Only post-activation READY jobs may auto-execute after recovery clears.
