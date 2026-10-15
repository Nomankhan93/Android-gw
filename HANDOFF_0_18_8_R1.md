# Android 0.18.8-r1 — Foreground Inventory Coordination

Baseline: Android 0.18.8 / versionCode 22. Release: 0.18.8-r1 / versionCode 23.

The foreground 30-second autoSync timer previously forced a new inventory RPC even when the shared repository had a recent successful snapshot from the background gateway. It now passes allowRecent=true through refreshCloudInventory. The repository's existing exact-snapshot/credential cache may serve a ready result for less than 60 seconds. Cache reads do not extend expiry.

Manual refresh, permission/pairing callbacks, manual queue sync, and pre-send inventory checks keep their fresh-report behavior. The periodic UI timer remains 30 seconds; the change reduces redundant network reports when the cache is eligible. Changed battery/SIM/device snapshot or credential still triggers a report. Unbound/error states are not cached. Existing queue auto-sync when the background gateway is disabled remains unchanged; this narrow correction does not guarantee all foreground requests stop or fall to one per minute.

Production changes: MainActivity.kt plus release metadata. No transport, scheduler, SMS submission, UNKNOWN recovery, SQLite, Web, or Supabase migration changes.

## Validation
- Diff review and whitespace check: passed.
- Installer baseline hashes, payload checksums, reapply, conflict refusal, ZIP integrity: passed.
- Two regression tests added to GatewayInventoryTest: shared foreground/background freshness without extending expiry; error and SIM-change invalidation with actual fake-API call counts.
- Android tests/build/lint NOT executed here: Gradle 9.6.0 distribution download failed with Network is unreachable before compilation. No APK/device certification claimed.
- Previous 0.18.8 build success does not certify this correction.

## Apply
Run `bash /path/to/bulktext-android-0.18.8-r1-patch/apply.sh /path/to/project` in WSL. The installer backs up changed files and refuses conflicting local edits. Existing configuration/signing files remain untouched.

Then use Windows PowerShell in the configured Android project:
```
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
git diff --check
```
After all gates pass, install as an update without uninstalling/clearing data. Check displayed version 0.18.8-r1. Keep the gateway screen open and idle for 10 minutes with the gateway enabled, avoiding manual refresh and campaigns; export logs for that exact interval. Verify manual refresh still reaches the backend and a small authorized exact-SIM campaign still completes normally.
