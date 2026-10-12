# Android 0.18.6-r1 validation

Baseline: supplied bulktext-android-0.18.6-current-updated-20261011-015103.zip.
Target: versionName 0.18.6-r1, versionCode 20, SQLite schema 7, existing Web 0.18.5 backend.

## Executed in the build workspace

- All production Java/Kotlin and test Kotlin compiled against Android API 35 using standalone ECJ/Kotlin compilers.
- JUnit/Robolectric: **128 tests passed**. Includes 16 new watchdog policy, cancellation, scheduling and enabled/disabled boot tests. Three weak baseline tests were replaced; the SQLite test fixture now clears the actual database.
- The standalone compiler harness uses generated R/BuildConfig stand-ins and Robolectric's offline Android runtime. It is not an APK build and does not validate Android resource packaging.
- Source validation runs the existing pure policy suites and static contract checks. Source hashes are generated from a portable allowlist, excluding Git state, builds, backups, credentials and old ZIPs.
- Installer validation covers check-only, clean apply, backups, local configuration preservation, repeated apply, conflict refusal, payload tampering and rollback after an injected write failure.
- Clean-source packaging validates the source manifest before archiving and excludes local-only files.

## Required on the user's build machine

Run Gradle unit tests, assembleDebug and lintDebug; then verify APK metadata and source checksums using the commands in PATCH_0_18_6_R1_README.md. This workspace did not produce or install an APK. Android resource packaging, Gradle dependency resolution and lint remain unverified here.

## Required on a physical device

Enabled and disabled reboot/upgrade, delayed first heartbeat, prolonged idle, network loss/recovery, Stop during sync and disable/re-enable must be checked on the target vivo phone. Verify an already-submitted or UNKNOWN message is never automatically resent. Use the acceptance checklist in PATCH_0_18_6_R1_README.md.

No hosted backend calls, real SMS sends or physical-device tests were performed. JobScheduler timing and foreground-service starts remain subject to Android/OEM restrictions; a battery exemption is not a delivery guarantee.
