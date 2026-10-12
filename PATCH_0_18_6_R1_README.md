# Android 0.18.6-r1 — Watchdog Lifecycle & Validation Correction

Version 0.18.6-r1 / versionCode 20. Baseline: uploaded Android 0.18.6 / code 19. SQLite schema 7 and backend contract are unchanged. This is a source patch; no app data reset, credentials change, cloud migration or deployment.

## Corrected behavior

- Keep a matching persisted periodic watchdog job. Repeated app/service/boot scheduling no longer replaces that job. Scheduling failure is checked and recorded as an attention status.
- Missing first heartbeat uses a two-minute grace period from activation. A heartbeat older than the latest enable action cannot disguise failed startup. The periodic pass still runs on Android's best-effort schedule, not at a guaranteed two-minute deadline. Established loops become stale after 15 minutes; backward clock changes are treated as needing a check.
- Stop invalidates the enabled generation and cancels the periodic watchdog. A stopped/replaced/destroyed run cannot begin its next recovery or restart step. Already-issued network requests may complete; their durable results are allowed to settle. No claim of cancelling a remote committed operation is made.
- Main-thread completion is bound to the current job run. An old worker cannot finish a newer run. Android onStopJob cancellation stops subsequent actions; retry is requested only while enabled.
- Disabled app startup cleans up an old persisted watchdog. Boot/upgrade never enables the gateway, changes its activation time, or grants new send authorization.
- The watchdog remains control-plane-only itself. Restarting the foreground gateway can resume already-authorized eligible work through existing exact-SIM, generation, attempt and UNKNOWN checks. This is not a promise that reboot can never lead to authorized sending.
- The cloud gateway has one authoritative SMS call site. The separate local lab also has an SMS call site. No SMS transport code is changed.
- Battery exemption may reduce delays, but does not guarantee delivery or continuous execution. Android/OEM restrictions can still require opening the app.

## Scope after this patch

Minimal Gateway UX is the next separate phase: a small connection/SIM/gateway-state screen, advanced diagnostics behind a secondary action, and battery guidance during setup or a detected problem. This patch does not redesign the current screen.

## Validate in Android Studio PowerShell

```powershell
cd "C:\Users\noman\AndroidStudioProjects\bulktext-android-0.6.0-ready"
.\gradlew.bat testDebugUnitTest :app:assembleDebug :app:lintDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle validation failed" }
python scripts/verify-release-metadata.py
if ($LASTEXITCODE -ne 0) { throw "APK metadata validation failed" }
python scripts/source-checksums.py
if ($LASTEXITCODE -ne 0) { throw "Source checksum validation failed" }
```

In WSL, use `bash scripts/source-validate.sh` with Java/Kotlin tooling available. A missing compiler is an environment limitation, not a test PASS. The optional standalone Kotlin checks explicitly say DEFERRED when Kotlin is unavailable.

The source manifest now excludes Git internals, build/cache output, credentials, backups and archives. It includes reviewed source, scripts, resources, tests and documentation. Verify with `python scripts/source-checksums.py` or `sha256sum -c SHA256SUMS.txt`. Use `--write` only after reviewing intentional source changes; never regenerate to conceal a mismatch.

## Target vivo acceptance

After local build/lint success, install with `adb install -r` to preserve data. Check enabled and disabled reboot/upgrade, first-start failure, screen-off idle, network recovery, explicit Stop during sync, and disable/re-enable while an old operation completes. Existing UNKNOWN jobs must never automatically resend. Perform any actual send separately, only to your own test recipient with consent. Force-stop is not equivalent to an ordinary OS process kill and is not promised automatic recovery.
