# Android 0.17.2 — Background Gateway Execution

## Goal
Send a Web-authorized campaign without returning to the Android app for every campaign.

## User flow
1. Open the Android gateway once after upgrading and enable **Background gateway**.
2. Keep the persistent **BulkText Gateway active** notification/service running.
3. In BulkText Web, confirm the campaign and use the existing **Send to Android** action.
4. Android 0.17.2 refreshes the exact Web-selected SIM, durably claims/ACKs new jobs, and submits them in bounded batches automatically.

## Safety boundaries
- Existing READY jobs that were already on the phone before the current background-gateway activation are **not** silently auto-executed.
- Every SMS re-checks gateway SIM id, subscription id, slot index and SIM identity hash immediately before SmsManager.
- No fallback SIM is allowed.
- SUBMITTING is persisted before SmsManager.
- Normal SmsManager return means SUBMITTED only, not SENT/DELIVERED.
- Any ambiguous post-boundary failure becomes UNKNOWN.
- Any UNKNOWN pauses background automation globally. UNKNOWN is never auto-retried.
- Manual sync and manual campaign execution remain recovery-only paths.

## Not in 0.17.2
SENT/DELIVERED callbacks, attempt history, delivery reconciliation and safe operator retry remain 0.18 scope.

## Android lifecycle note
The background gateway is a user-enabled foreground service. Normal app backgrounding does not require reopening the UI for each campaign. If Android/OEM force-stops the service or the phone is rebooted and the service is not restored, open BulkText once so the persistent gateway service can resume.
