# Validation — Android 0.18.4

## Executed in this build workspace

- All production Kotlin compiled with Kotlin 2.2.20 targeting Java 17 against the Android 15 framework jar; Java Policy compiled with ECJ 3.38. Scratch BuildConfig/R stand-ins supplied compile-time names only. This is compatibility compilation, not an APK build. Android internal annotation strictness was disabled because the framework jar differs from public SDK stubs.
- 67 JUnit tests passed: 60 policy/repository/evidence/gate tests and 7 Robolectric API 35 lifecycle/SQLite tests. No real SMS or cloud calls were made.
- Lifecycle checks cover resultCode capture through actual goAsync, raw errorCode preservation, no false DELIVERED without PDU, mutable callback flags, valid GSM success vs pending/failure PDU, callback sync scheduling while disabled and durable generation invalidation.
- Gate tests cover stop during preparation, stale permits after re-enable, fresh explicit manual authorization, concurrent stop at the submission boundary and exception lock release.

- Full source validation passed: 28 Java policy assertions, 19 queue checks, 26 execution checks, 14 background checks, static boundary checks and all source checksums. Standalone compilers were supplied by workspace-only ECJ/Kotlin wrappers.
- Certified local-lab transport files remain byte-for-byte unchanged.

## Release gates still required locally

Android Gradle testDebugUnitTest, assembleDebug, lintDebug, APK metadata validation and real-device/carrier acceptance were not run here. No APK is included or certified. The supplied Gradle/AGP toolchain and local SDK configuration remain unchanged.

See PATCH_0_18_4_README.md for commands and device checks. Patch-package apply/backup/integrity checks are recorded in the package VALIDATION.md.
