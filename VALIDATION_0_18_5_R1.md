# Android 0.18.5-r1 validation

Date: 2026-10-11 UTC. Baseline: delivered Android 0.18.5 source / versionCode 17.
Output: 0.18.5-r1 / versionCode 18. Database version remains 7.

## Executed

- All Android app source compiled against API 35 with Java 17 / ECJ and Kotlin compiler, using isolated compile-only BuildConfig/R stubs.
- **103 JUnit tests passed**, including 31 Robolectric callback/SQLite/health tests.
- Six new raw HTTP-response tests exercise public begin/report/recovery methods via simulated HttpURLConnection responses; cover correct scalar objects, wrong shapes, incomplete data, trailing content, legacy array handling and nonterminal ACK refusal. No live network or SMS is used.
- Four new SQLite/preferences/repository regressions cover separate Prepared/Submitted counts, device/error isolation, preference reopening, recovery success not erasing callback error, credential failure retention, error clearing after success and network failure not overwriting the last successful timestamp.
- Existing no-resend, safe-stop, durable callback/recovery replay, conflict latch and DB upgrade regressions retained.
- Source validation and standalone core test evidence is in evidence/source-validation.txt in the ZIP.
- Installer tests and checksums are in evidence/installer.txt and PACKAGE_SHA256SUMS.txt.

## Limits

Android Gradle validation for this 0.18.5-r1 correction was completed successfully: testDebugUnitTest passed with 103 tests, assembleDebug passed, lintDebug passed, and APK metadata verification passed for versionName 0.18.5-r1 / versionCode 18. Physical-device SMS behavior and live Web-to-Android campaign state still require verification on the phone.

Robolectric output contains SDK 36 availability and SQLite CloseGuard teardown warnings; runtime tests explicitly use SDK 35. Kotlin compilation reports existing deprecations/bind-array inference warnings. The standalone compiler harness supplies the standard library explicitly and bundles it in core-test JARs; no assertions are skipped.

No hosted DB changes, app data reset, real SMS or physical-device delivery assertions were performed. Counters are device-wide unless explicitly campaign-scoped. Prepared can overlap Ready eligibility and is not evidence of submission.
