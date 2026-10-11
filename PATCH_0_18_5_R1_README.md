# Android 0.18.5-r1 — RPC Response & Sync Status Correction

Baseline: Android 0.18.5 / code 17. Output: 0.18.5-r1 / code 18. SQLite remains version 7. Backend prerequisite remains Web 0.18.5; no Web/database migration is included.

## Why this correction is needed

The v2 begin-attempt, report-event and recovery-ACK functions return scalar JSON objects. Android 0.18.5 incorrectly sent their responses through the legacy single-row-array parser. A begin RPC could create a prepared attempt on the server while Android rejected its response before SMS submission. Callback reporting and recovery ACK response handling had the same defect.

Earlier tests exercised decoded response fields but missed the raw transport response shape. The new tests exercise public RPC methods using simulated HTTP connections returning raw response bodies, including malformed and incompatible shapes. They do not contact a live Supabase project.

## Changes

- Dedicated JSON-object parser for all three scalar v2 RPCs, with trailing-content/wrong-shape rejection. Legacy table RPCs retain their array parser.
- Prepared and Submitted are separate counters. Ready is execution eligibility and may overlap Prepared; these are not additive recipient totals. Android execution summary explicitly says all campaigns.
- Persist device-scoped begin/callback/recovery errors separately. A successful queue poll or recovery request cannot erase a callback error. Messages use fixed categories, never raw credentials, response bodies or recipient data.
- Preserve each operation's last successful timestamp across failure. Network failures now propagate from callback flush so they cannot be recorded as successful sync.
- Show NEEDS ATTENTION and operation errors in the gateway/execution sections. Service activation/attempt dispatch is labeled preparing, not proof of SMS sending.
- Hold new background SMS dispatch while callback/recovery synchronization reports failure; keep polling and retain the outbox. Begin failures remain retryable through the same existing attempt identity.

No queue reset, identity reset, recipient resend grant, backend mutation or theme redesign is part of this patch. Existing UNKNOWN handling, exact-SIM checks, safe stop, durable ACK/outbox and conflict rules remain.

## Apply and install

Use the guarded installer in the patch ZIP. It targets the unchanged 0.18.5 source delivered previously, backs up changed files and refuses edited/incompatible files. Keep local.properties and signing keys.

After apply, run in Android Studio PowerShell:

```powershell
cd "C:\Users\noman\AndroidStudioProjects\bulktext-android-0.6.0-ready"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean testDebugUnitTest :app:assembleDebug :app:lintDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle validation failed" }
python scripts/verify-release-metadata.py
if ($LASTEXITCODE -ne 0) { throw "APK metadata validation failed" }
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r ".\app\build\outputs\apk\debug\app-debug.apk"
```

Expected version: 0.18.5-r1 (18). Upgrade using the existing signing key; do not uninstall or clear data.

If the gateway remains enabled, eligible previously authorized unsent work can resume after the corrected response is accepted. Already-submitted/UNKNOWN messages do not gain permission to resend. Do not create duplicate test campaigns to work around a prepared attempt.

## Acceptance checks on the existing test campaign

1. Confirm 0.18.5-r1 and pairing/SIM remain present.
2. Let the existing prepared attempt progress using its original attempt ID; verify Prepared decreases and Submitted/Sent reflects actual callbacks. Compare the same campaign on Web, not Android's all-campaign total.
3. Disconnect/reconnect network: failure is visible, last successful callback/recovery timestamp is retained, and pending events replay without another SMS submission.
4. Explicit Web continue-without-resend recovery settles automatically, including replay after a lost ACK response.
5. Verify safe stop before the next SMS boundary, exact-SIM behavior and UNKNOWN no-auto-resend.

Source/JVM validation does not replace the local Gradle/APK and physical-device checks. See VALIDATION_0_18_5_R1.md.
