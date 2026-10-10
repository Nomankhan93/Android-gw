$ErrorActionPreference = "Stop"
Set-Location (Resolve-Path (Join-Path $PSScriptRoot ".."))

function Run-Step([string]$Name, [scriptblock]$Command) {
    Write-Host "`n=== $Name ==="
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
}

Run-Step "Source/core validation" { bash scripts/source-validate.sh }
Run-Step "Gradle clean + unit tests" { .\gradlew.bat clean testDebugUnitTest }
Run-Step "Assemble debug APK" { .\gradlew.bat :app:assembleDebug }
Run-Step "Android lint" { .\gradlew.bat :app:lintDebug }

$metadataPath = "app/build/outputs/apk/debug/output-metadata.json"
$apkPath = "app/build/outputs/apk/debug/app-debug.apk"
if (!(Test-Path $metadataPath)) { throw "Missing $metadataPath" }
if (!(Test-Path $apkPath)) { throw "Missing $apkPath" }

$buildConfig = Get-Content "app/build.gradle.kts" -Raw
$expectedName = [regex]::Match($buildConfig, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$expectedCode = [int][regex]::Match($buildConfig, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (!$expectedName -or !$expectedCode) { throw "Cannot read source version metadata" }
$metadata = Get-Content $metadataPath -Raw | ConvertFrom-Json
$element = $metadata.elements | Select-Object -First 1
if ($element.versionName -ne $expectedName) { throw "Expected versionName $expectedName, found $($element.versionName)" }
if ([int]$element.versionCode -ne $expectedCode) { throw "Expected versionCode $expectedCode, found $($element.versionCode)" }

$hash = (Get-FileHash $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "`nPASS: Android Gateway $expectedName validation"
Write-Host "APK: $apkPath"
Write-Host "versionName: $($element.versionName)"
Write-Host "versionCode: $($element.versionCode)"
Write-Host "SHA256: $hash"
