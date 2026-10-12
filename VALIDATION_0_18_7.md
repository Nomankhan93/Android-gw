# Android 0.18.7 validation

All production Java/Kotlin and test sources compile against API 35 with standalone ECJ/Kotlin tooling. JVM/Robolectric suite includes seven minimal-status tests and an Activity view-hierarchy test for default hidden diagnostics/lab and safe Stop availability. The standalone harness uses R/BuildConfig stand-ins and an offline Android runtime; it is not an APK/resource-packaging build. Executed results are included in the patch evidence.

Existing pure policy/source checks and guarded installer checks are included. Portable checksum sorting is fixed. Installer accepts the exact shipped 0.18.6-r1 source or the known chat-provided sorting fix, refuses unknown changes, preserves local configuration and backs up replaced files.

Required user gates: Gradle testDebugUnitTest, assembleDebug, lintDebug, metadata and source hashes; then actual vivo screen/setup/Start/Stop, large font, keyboard and background tests. No APK, real-device visual rendering, real SMS or hosted backend calls were performed here. Earlier reports are historical.
