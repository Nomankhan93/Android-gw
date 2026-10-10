# Android 0.18.3 Validation

Primary Windows/Android Studio release command:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\scripts\validate-release.ps1
```

Expected metadata: versionName `0.18.3`, versionCode `15`.

Fast WSL/source checks:

```bash
bash scripts/source-validate.sh
```

Real-device acceptance is still mandatory for vivo/Ufone callback behavior. `UNKNOWN -> no automatic resend` is unchanged.
