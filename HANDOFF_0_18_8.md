# Android 0.18.8 — Adaptive Gateway Polling & Request Coordination

Baseline: uploaded Android 0.18.7, versionCode 21. New versionCode: 22.

## Changes
- Empty background cycles wait 15, then 30, then 60 seconds. Local ready/prepared/submitting/submitted/UNKNOWN work or pending callback events retain 15-second cadence. New remote work is discovered at the next poll, potentially after 60 seconds plus network time.
- One recovery poll per idle cycle. The second recovery validation is retained only before launching execution, with forced freshness.
- Shared application API transport serializes network operations. Complete queue sync and callback/recovery transactions are also serialized at their repositories. Successful recovery passes coalesce within one second; failures are not cached as success.
- Transient per-credential/per-endpoint cooldown: exponential 15–300 seconds with jitter. Retry-After seconds/date can extend cooldown up to one day. The coordinator never replays a request. Callback/lease/attempt durable retry semantics remain owned by the existing repositories.
- Rejected credentials stop transport requests for that credential for the process lifetime. A changed credential uses a separate identity. Process restart resets in-memory cooldown/rejection state.
- Idle-only inventory reuse is limited to 60 seconds and exact snapshot/credential identity. Snapshot changes bypass the cache; non-ready/error results invalidate it. Sending and manual inventory checks remain fresh. At steady idle cadence, reporting remains roughly once per minute; this is not a long heartbeat-only cache.

## Safety and limitations
Exact SIM binding is validated by the unchanged CloudSendingService immediately before preparation/submission. SMS submission code, SQLite schema, UNKNOWN no-resend semantics, server contracts and Web migrations are unchanged. Local SIM changes are discovered on the next snapshot; no new telephony listener is added.

A steady idle ready gateway uses approximately three RPCs per 60 seconds plus network time, versus four per 15 seconds previously. This is a code-path estimate, not a measured production guarantee; callbacks, ACKs, watchdog and manual work add requests. Serial transport can delay other operations behind the current request. Existing 10-second connect/read timeouts remain; this patch does not introduce a total RPC deadline. Cached or coalesced results are not used to authorize an SMS submission.

## Validation status
NOT Android-build-certified in this environment. Attempted `bash gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon`; Gradle 9.6.0 distribution download failed with `java.net.SocketException: Network is unreachable`, before compilation/tests. No APK or physical-device validation was performed.

Nine new JUnit tests are included (seven polling/coordinator cases, two inventory cases), covering idle/reset, failure backoff/reset, Retry-After, rejected credential isolation, domain failures, serialized transport, header parsing, cache expiry/pre-send freshness, snapshot/credential invalidation. Tests are provided but were not executed here.

Packaging verification: checksum-checked installer, reapplication, conflict refusal, unchanged sending/SQLite files and ZIP integrity checked locally. This does not replace the Android build gate.

## Apply and validate
Extract the ZIP, then run `bash /path/to/bulktext-android-0.18.8-patch/apply.sh /path/to/project`.
Installer checks the supplied baseline hashes before writes and creates `.patch-backups/0.18.8-*`. Local conflicting edits stop installation. Configuration and signing files are untouched.

In your configured Android environment:
```
./gradlew testDebugUnitTest assembleDebug lintDebug
git diff --check
```
If using Android Studio on Windows, the equivalent terminal command is `gradlew.bat testDebugUnitTest assembleDebug lintDebug`. Keep your existing SDK/JDK setup.

Only after all gates pass, install the debug APK as an update (do not uninstall or clear app data). Verify idle request counts over a fixed 10-minute interval, a small authorized exact-SIM campaign, callbacks/UNKNOWN recovery, network reconnect, manual/background overlap and gateway Stop. Retain the previous APK; source backup does not downgrade installed app versionCode automatically.
