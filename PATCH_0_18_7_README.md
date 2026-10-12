# Android 0.18.7 — Minimal Gateway UX

VersionCode 21; baseline Android 0.18.6-r1 / code 20. SQLite 7 and Web backend contract 0.18.5 remain unchanged. No data reset or migration.

The main screen shows connection, Web-selected SIM, gateway state, setup/refresh, Start/Stop and Diagnostics & settings. App label: BulkText Gateway. Teal controls and density-aware spacing use minimum 48dp helper buttons. Existing technical diagnostics and manual cloud actions are in a collapsed section. Local SMS lab requires another explicit confirmation to open; its independent SIM selector is not shown on the main screen.

Pairing success dismisses the keyboard. Phone/SMS permission setup is on the main screen. Foreground inventory refresh runs every 30 seconds while the activity is resumed; backend selection remains authoritative. Activity teardown removes UI callbacks and shuts down its executor. Background services and durable execution paths are unchanged.

Active requires enabled state, recent local activity and recent confirmed SIM binding; it does not mean SMS delivery. Setup, stopped, starting, stale activity, missing SIM and paused/UNKNOWN states remain distinct. Stop remains available even with lost permissions or expired pairing. Existing UNKNOWN and exact-SIM gates are preserved.

Battery guidance is offered once in paired/permissioned setup, can be dismissed with Not now, and returns if gateway activity is stale. Full guidance remains inside diagnostics. No permanent power dashboard is visible by default. Android/OEM restrictions can still delay activity.

## Validate in Android Studio PowerShell

```powershell
& {
    Set-Location "C:\Users\noman\AndroidStudioProjects\bulktext-android-0.6.0-ready"
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    if (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) { throw "Android Studio Java not found" }
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    .\gradlew.bat testDebugUnitTest :app:assembleDebug :app:lintDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle validation failed" }
    python scripts/verify-release-metadata.py
    if ($LASTEXITCODE -ne 0) { throw "APK metadata failed" }
    python scripts/source-checksums.py
    if ($LASTEXITCODE -ne 0) { throw "Source checksum check failed" }
}
```

Install only after validation, using adb install -r to retain app data. On vivo check existing pairing, first setup, Web SIM selection reflection, Stop with missing permissions, UNKNOWN pause, large font/readability, keyboard dismissal, battery Not now, diagnostics/lab visibility and screen-off recovery. These device checks are required; no physical-device or visual certification is claimed.
