# Android 0.18.6 — Validation Report

Date: 2026-10-11. Baseline: Approved Android 0.18.5-r1 source (`code 18`).
Target: **Android 0.18.6 — OEM & Background Resilience** (`code 19`).
SQLite Database Schema: **7** (Unchanged).

---

## Validation Execution Summary

### 1. Source & Contract Validation (`bash scripts/source-validate.sh`)
- PASS: 28 policy assertions
- PASS: 19 queue core assertions
- PASS: 0.18.6 source boundary contracts (static checks)
  - Asserted `versionName = "0.18.6"` and `versionCode = 19`
  - Asserted `GatewayBootReceiver` and `GatewayWatchdogService` in `AndroidManifest.xml`
  - Asserted **ZERO** `sendMultipartTextMessage` or `sendTextMessage` calls in `GatewayBootReceiver`, `GatewayWatchdogService`, `BackgroundGatewayService`, `CallbackSyncService`, and `CloudSmsResultReceiver`
  - Asserted exactly **ONE** `SmsManager` call site in the entire cloud gateway architecture (`CloudSendingService.kt`)
  - Certified transport hashes verified against `SHA256SUMS.txt`
- Result: **`PASS`** (Exit Code `0`)

### 2. Gradle Unit Test Suite (`.\gradlew.bat testDebugUnitTest`)
- **115 JUnit / Robolectric tests passed** (0 skipped, 0 failed)
- Stateful regression suite in `ReliabilityContract0186Test.kt`:
  - Test A & B: Watchdog, Boot Receiver, Callback Service, and Receiver have zero `SmsManager` calls.
  - Test C: Stateful database test: interrupted `SUBMITTING` recovers to `UNKNOWN`, never `READY`.
  - Test D: Stateful database test: callback-pending outbox survives process/store restart.
  - Test E: Stateful database test: recovery ACK pending outbox survives process restart.
  - Test F: Stateful database test: explicit `skip_unknown` action resolves job without resending.
  - Test G: Exact-SIM mismatch blocks SMS submission across all identity parameters.
  - Test H: Disabled gateway stays disabled after `BOOT_COMPLETED` broadcast.
  - Test I: Watchdog `onStartJob` execution never executes or submits `READY` queue jobs.
  - Test J: Battery optimization / power status reads safely under `PowerManager`.
  - Test K: OEM settings guidance provides safe Android Settings fallback for unknown manufacturers.
  - Test L: Attempt preparation reuses exact `clientAttemptId` UUID across store restart.
  - Test M: Crash after `beginSubmission` recovers job as ambiguous `UNKNOWN` on store restart.
- Result: **`SUCCESS`**

### 3. Gradle Assembly & Lint Gates (`.\gradlew.bat clean :app:assembleDebug :app:lintDebug`)
- `:app:assembleDebug`: **`SUCCESS`** — Produced `app-debug.apk` (`0.18.6` / `versionCode 19`)
- `:app:lintDebug`: **`SUCCESS`** — Clean lint check with zero errors

### 4. Release Metadata Verification (`python scripts/verify-release-metadata.py`)
- Output: `PASS: Android Gateway 0.18.6 / versionCode 19 APK metadata`
- APK Path: `app/build/outputs/apk/debug/app-debug.apk`
- APK SHA256: `d44ff0ebab8c3489d653c12c1e078818c114ab695c92177aaf73fe14e48e5028`
- Result: **`PASS`**

### 5. Git Status & Whitespace Verification (`git diff --check; git status`)
- `git diff --check`: **`PASS`** (Zero whitespace/syntax errors)
- `git status`: All changed/untracked files verified uncommitted.

---

## Environment & Physical Device Note
- **Source, JVM & Build Validation**: 100% executed and verified locally.
- **Physical Device Gate (vivo Y33s / V2109)**: Real device was not attached over USB during this execution pass. Physical device acceptance steps (FuntouchOS battery setting launcher, screen-off long idle pass, and reboot recovery) are detailed in `PATCH_0_18_6_README.md` for execution on the target vivo device.
