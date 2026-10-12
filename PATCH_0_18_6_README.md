> Historical 0.18.6 notes. Superseded by PATCH_0_18_6_R1_README.md for watchdog lifecycle, validation and corrected guarantees.

# Android 0.18.6 — OEM & Background Resilience

Version **0.18.6**, versionCode **19**, SQLite schema **7** (unchanged).
Baseline: Approved Android 0.18.5-r1 source (`code 18`).
No backend database schema change is included or required.

## Overview & Background Problem Addressed

On aggressive Android OEM power management systems (e.g. vivo FuntouchOS/OriginOS, Xiaomi MIUI/HyperOS, OPPO/ColorOS, Realme), long-running background services can be restricted, Doze-paused, or killed after extended idle periods.

Android 0.18.6 improves background gateway reliability across these OEM power-saving environments while preserving the core safety invariant:

**No background recovery or watchdog mechanism may create an independent path that can submit an SMS outside of the existing authoritative CloudSendingService/SmsManager execution boundary.**

The cloud gateway has one authoritative `SmsManager` call site (`CloudSendingService.kt`). The separate local SMS lab retains its own `SendingService.kt` call site.

---

## Technical Changes & Architectural Principles

1. **Boot & Package Replacement Receiver (`GatewayBootReceiver`)**:
   - Listens for `ACTION_BOOT_COMPLETED` and `ACTION_MY_PACKAGE_REPLACED`.
   - Checks if the background gateway is enabled in preferences. If disabled, does nothing (never auto-enables).
   - If previously enabled by the user, schedules control-plane callback outbox sync and the watchdog, and attempts to re-establish the foreground gateway service.
   - Catches background start exceptions safely (`ForegroundServiceStartNotAllowedException`) without crashing or creating unsafe retry loops.
   - **Never calls `SmsManager` and never submits SMS.**

2. **Secondary Gateway Watchdog (`GatewayWatchdogService`)**:
   - Secondary `JobService` (`JobScheduler` ID 186) configured with `setPeriodic(15 * 60 * 1000L)`.
   - Execution is best-effort, Doze-tolerant, and OS-controlled (never a real-time gateway loop).
   - Checks if gateway is enabled. Flushes durable callback outbox and syncs recovery requests.
   - Inspects `lastControlCycleAt`. If stale (>15 minutes) due to Doze or OEM service kill, safely requests re-establishment of `BackgroundGatewayService`.
   - **Never claims jobs, never calls `SmsManager`, and never submits SMS.**

3. **Gateway Reliability & Power Status UI Section**:
   - Displays real-time device health: Operator Setting (`ENABLED`/`DISABLED`), Foreground Control Loop Health (`ACTIVE`, `RECENT`, `STALE`, `NOT YET OBSERVED`, `DISABLED`), Battery Optimization mode (`Unrestricted` vs `Optimized`), Power Saver mode, Notification Permission, Network status, and OEM Model details (`vivo V2109`, etc.).
   - Adds **"Review battery & OEM background settings"** user-controlled action.
   - Opens standard `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` and `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`.
   - Provides safe guidance for vivo (FuntouchOS), Xiaomi (MIUI), OPPO/Realme (ColorOS), with standard Android Settings fallbacks.
   - Fails safely on all devices/APIs; never auto-pops or repeatedly nags.

4. **Expanded Privacy-Bounded Diagnostics**:
   - Includes system health metadata in diagnostic JSON exports: Android release, SDK API level, manufacturer, model, battery optimization state, power-save mode, network transport status, gateway enablement, and sync timestamps (`lastControlCycleAt`, `lastCallbackSyncSuccessAt`, `lastRecoverySyncSuccessAt`).
   - **Strict Privacy**: Excludes recipient phone numbers, SMS bodies, Supabase credentials, auth tokens, and pairing secrets.

5. **Process Recovery & Safeguards Preserved**:
   - Process restart preserves all `SUBMITTED`, `SENT`, `DELIVERED`, `UNKNOWN`, and recovery states.
   - Interrupted `SUBMITTING` jobs recover as `UNKNOWN` (ambiguous boundary).
   - Absence of in-memory state is never treated as permission to resend.
   - Exact-SIM matching, UNKNOWN no-resend, and conflict latches remain strictly enforced.

---

## Build & Validation Commands

In PowerShell from the project root:

```powershell
.\gradlew.bat clean testDebugUnitTest :app:assembleDebug :app:lintDebug
bash scripts/source-validate.sh
python scripts/verify-release-metadata.py
git diff --check
git status
```

Expected output:
- `versionName = "0.18.6"`
- `versionCode = 19`
- `SQLite schema = 7`
- 115 JUnit / Robolectric tests PASS
- APK output: `app/build/outputs/apk/debug/app-debug.apk`

---

## Physical Device Acceptance Plan (vivo Y33s / V2109)

1. Upgrade existing 0.18.5-r1 install using `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
2. Confirm version `0.18.6 (19)` is displayed.
3. Confirm pairing credentials and installation ID are preserved.
4. Confirm exact SIM binding selection is preserved.
5. Confirm Gateway Reliability UI displays device model and battery optimization state.
6. Verify "Review battery & OEM background settings" button opens settings and displays vivo FuntouchOS guidance.
7. Enable gateway.
8. Perform 20-minute screen-off idle test.
9. Perform long idle test.
10. Test Battery Saver mode toggle.
11. Test network disconnect and reconnect.
12. Test process/service kill recovery (force-kill `BackgroundGatewayService`).
13. Test app swipe-away behavior.
14. Test device reboot while gateway is enabled (previously authorized eligible work may resume through existing send guards; UNKNOWN is never automatically resent).
15. Test device reboot while gateway is disabled (gateway remains disabled).
16. Test package upgrade (`adb install -r`) while gateway is enabled.
17. Verify pending callback outbox replays automatically.
18. Verify pending recovery ACK outbox replays automatically.
19. Verify UNKNOWN jobs remain blocked without resend.
20. Verify explicit `skip_unknown` continue-without-resend action does not resend.
21. Confirm no duplicate SMS is submitted under any recovery or restart path.
22. Confirm Web campaign state agrees with Android execution evidence.
