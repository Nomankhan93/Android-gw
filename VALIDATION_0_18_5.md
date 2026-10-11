# Android 0.18.5 validation

Date: 2026-10-10. Source baseline: user-supplied Android 0.18.4 clean ZIP.
Output version 0.18.5 / code 17; SQLite schema 7.

## Executed in this environment

- Compiled all app Kotlin and Policy.java against Android API 35 classes using Kotlin compiler / ECJ and Java 17. BuildConfig and resource identifiers used isolated compile-only stubs; these are not shipped as app source.
- JUnit: **93 tests passed**, including **27 Robolectric API 35 callback/SQLite tests** (20 recovery/reliability and 7 existing callback lifecycle tests). Other tests cover API parsing, identity, queue reconciliation, execution policy, delivery and safe stop.
- New v2 API tests cover begin grant identity/state, blocked begin, raw evidence payload, terminal rejection/tombstone, conflict with resolution, and terminal recovery ACK.
- SQLite/repository tests cover actual v6-to-v7 upgrade, resolution preservation, owner backfill, lost ACK replay after the server list becomes empty, not-applicable/rejected recovery outcomes, callback payload replay, changed-UUID-payload rollback, device isolation, auth failure retention, tombstones, blocked-job advancement, raw status semantics, conflict latch, late callback/timeout behavior and failed delivery broadcasts.
- Source validator checks transport policy, queue core, execution/background policy, boundary ordering, callback-only worker restrictions, v2 RPC presence and SHA-256 file integrity. See package evidence/source-validation.txt for the actual run.
- Guarded installer test evidence is supplied in package evidence/installer.txt: check-only, in-place application, backup/config preservation, idempotency, applied checksums, conflict/tamper refusal and injected write-error rollback.

## Limits and remaining release gates

No APK was produced in this environment. Gradle assembleDebug, Android lint and generated APK metadata validation remain required in Android Studio with the user's SDK/JDK and local configuration. The standalone compile is not a substitute for Gradle/resource/manifest validation.

Robolectric output contains SDK 36/Java 21 availability warnings (runtime tests explicitly use SDK 35) and SQLite CloseGuard test teardown warnings. Compiler output includes pre-existing deprecations and mixed SQL bind-array inference warnings. Tests completed successfully; these warnings do not establish physical-device reliability.

No hosted database was modified and no real SMS was sent here. Carrier/device delivery receipts, CDMA PDU behavior, actual reboot/background restrictions and in-place APK installation must be checked with the device acceptance list in PATCH_0_18_5_README.md. Keep existing pairing/queue data; do not uninstall/reset as a workaround.
