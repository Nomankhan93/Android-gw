# Android 0.18.4 — Callback Capture & Safe Stop

VersionName **0.18.4**, versionCode **16**, SQLite schema **6** (unchanged).
Applies to the supplied Android 0.18.3/code15 source, despite the archive/folder name containing 0.6.0.
No Web patch or Supabase migration is included or required for this hotfix.

## Changes

- Capture BroadcastReceiver resultCode before goAsync detaches PendingResult. Persist raw result/error and the callback outbox locally before scheduling network sync.
- Use explicit mutable callback PendingIntents so Android can attach errorCode and delivery PDU/format. Preserve unique attempt/part/kind actions.
- Require a successful parsed status-report PDU before reporting DELIVERED. Missing, malformed, pending and failed delivery reports stay diagnostic; resultCode=-1 alone is insufficient. Full delivery-status RPC semantics are deferred.
- Persist a send-generation token. Disable invalidates existing automatic and manual permits; re-enabling cannot revive them. Recheck under a shared lock at SUBMITTING/SmsManager after all network preparation.
- Stop after the already admitted recipient. Android cannot recall an SMS already handed to the modem; multipart parts of that admitted recipient may finish. In-flight callbacks continue settling and syncing. A new explicit manual confirmation can authorize sending while background mode is off.
- Add a persisted network-only callback JobScheduler worker. It never claims, recovers or sends SMS. Startup also schedules any pending outbox after interrupted-submission recovery.
- Update release validation to compare APK metadata with the source version instead of old hardcoded values.

Historical UNKNOWN rows are not reclassified or resent. Callback failures remain ambiguous and never grant automatic retry. The existing recovery/outbox contract limitations still require their planned later patches.

## Build on Windows

Apply the separate patch package first. Keep existing local.properties and signing configuration.
Open PowerShell in the project root with Android Studio's JDK available:

```powershell
.\gradlew.bat clean testDebugUnitTest :app:assembleDebug :app:lintDebug
python scripts/verify-release-metadata.py
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
For the additional standalone source/core checks use `bash scripts/source-validate.sh` with Java, javac, Kotlin CLI, Python and sha256sum available. `scripts/validate-release.ps1` runs both source and Gradle gates.
The project retains its supplied Gradle/AGP versions; no toolchain upgrade is included.

Upgrade a device with a compatible signing key using `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Do not uninstall or clear app data to apply this patch. If Android rejects the signing key, build with the original signing configuration.

## Device acceptance before production sending

Use controlled test recipients and a small Web-authorized campaign.

1. Confirm app version 0.18.4/code16. Pairing, queue, selected SIM and prior UNKNOWN history should remain.
2. Successful SENT callback: diagnostics show -1, job becomes SENT, next authorized recipient proceeds.
3. Carrier failure: preserve actual result/errorCode, remain UNKNOWN/non-retryable; do not automatically resend.
4. Disable during delayed inventory/begin-attempt network work: no SmsManager call for that recipient. Re-enable must not revive the old pending start.
5. Disable after one recipient enters SmsManager: allow its callbacks to settle; do not start the next recipient. No assertion that the already submitted SMS was cancelled.
6. Disable background sending, deliver a callback offline, then restore network: outbox sync should resume without any SMS submission. Reopen app if Android force-stopped it; persisted jobs are subject to OS scheduling.
7. Delivery report without PDU, pending PDU or failed PDU must not become DELIVERED. A successful report for every part can become DELIVERED.
8. Repeat on the actual dual-SIM device; verify no SIM fallback, multipart correlation and a fresh manual confirmation after stop.

Do not mark this release device-validated until these checks and the Gradle APK/lint gates pass locally.
