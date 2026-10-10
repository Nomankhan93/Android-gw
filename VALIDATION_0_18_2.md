# Android 0.18.2 Validation

Required release command on Windows PowerShell:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\scripts\validate-release.ps1
```

Expected metadata: versionName `0.18.2`, versionCode `14`.

Real-device acceptance additionally requires auto recovery sync while UNKNOWN with no resend.
