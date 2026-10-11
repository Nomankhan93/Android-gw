# Android 0.18.5 — Recovery, Outbox & Delivery Reliability

Version 0.18.5 / code 17. Source baseline: supplied Android 0.18.4 / code 16 clean ZIP.
Prerequisite: Web 0.18.5 backend delivery/recovery migration. No database changes are included or run by this Android patch.

## Changes

- Use v2 begin/report/recovery ACK contracts. Only a registered, prepared, matching attempt with can_submit=true reaches the SMS boundary.
- Persist recovery outcome and ACK intent in one SQLite transaction. Replay ACK after lost responses even if the request no longer appears in the server list. Explicit not-applicable and unsafe-retry rejection outcomes prevent stale requests looping indefinitely.
- Scope callback outboxes and recovery ACKs to their original gateway identity. Re-pairing never reports old-device events using a new-device credential. Preserve old outbox rows; no reset step.
- Give each received callback observation its own UUID. Network retries retain the exact UUID, timestamp and payload. Conflicting reuse is rejected transactionally. Terminal tombstones and attempt-unavailable responses stop retries for that event without inventing delivery success or blocking later events.
- Capture status-report format, raw Android status and SHA-256 digest. Raw PDU bytes are not stored/uploaded. Delivery requires successful callback result and complete verified status evidence; pending/failure observations never grant resend. Conflicting terminal observations latch UNKNOWN.
- Preserve explicit operator resolution through late callbacks and late timeout handlers. UNKNOWN is never automatically resent.
- Persist pre-submission recipient/segment blocks so valid later jobs can advance. SIM-gate blocks wait at least 30 seconds, then require inventory/exact-SIM checks and a fresh backend grant. No alternate-SIM fallback.
- Display the actual build version and distinguish failed sync attempts from the last successful queue sync. Paired does not mean freshly authenticated.
- Add explicit nullable-service handling required by API 35 annotations in the existing local-lab code. Local-lab send policy and transport behavior otherwise remain unchanged.

## In-place database upgrade

SQLite version 6 -> 7 preserves queue rows, attempt identities, diagnostics and recovery decisions, adding evidence/disposition/owner fields, block state, conflict latch and recovery ACK outbox. Existing applied recoveries receive replayable ACK intents. Historical delivery rows without persisted proof become SENT, not READY: this avoids unsupported delivery claims and does not resend anything. Existing pending observations are retained and scoped to their original owner.

## Apply and build

Use the patch ZIP's README and guarded installer. Do not copy payload over a different project or edit guards. Close source editing/build tasks during application. Keep local.properties and signing material.

In Android Studio's PowerShell terminal (adjust JBR path only if Android Studio is elsewhere):

```powershell
cd "C:\Users\noman\AndroidStudioProjects\bulktext-android-0.6.0-ready"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean testDebugUnitTest :app:assembleDebug :app:lintDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle validation failed" }
python scripts/verify-release-metadata.py
```

Expected APK: app/build/outputs/apk/debug/app-debug.apk, versionName 0.18.5 / versionCode 17.
Upgrade with the same signing key (`adb install -r`); do not uninstall or clear data. APK build/lint/phone validation must pass before using live campaigns.

## Device acceptance checks

Use your own test recipients and a small test campaign; SMS charges may apply.

1. Upgrade an existing 0.18.4 install: pairing and queued state survive; header/inventory show 0.18.5 (17).
2. Submit one message; disconnect network after callbacks. Reconnect: the durable event uploads without a second SMS. SENT and DELIVERED remain distinct.
3. Resolve UNKNOWN with Web continue-without-resend. Restart Android or interrupt ACK response: recovery ACK eventually completes, sending can continue, that recipient is never resent.
4. Re-pair to another test device identity: old events do not upload as the new owner.
5. Put an ineligible recipient or segment mismatch before an eligible job: the blocked item submits no SMS, later work advances. Missing/wrong SIM cannot fall back to another SIM.
6. Disable while begin RPC is pending: no new SmsManager call after disable returns; already-submitted work still accepts callbacks.
7. Exercise GSM/CDMA and multipart status reports on available devices/carriers. Pending or absent reports must not display DELIVERED. Real CDMA/carrier behavior remains a physical-device gate.

Major theme/navigation redesign and Web draft/import UX are outside this reliability patch. Next planned patch: Web 0.18.6.
