# Android 0.6.1 — Durable Cloud Job Client Stabilization

## Scope & Enhancements

0.6.1 builds upon the 0.6.0 durable cloud queue architecture with robust stabilization and error hardening:

1. **Hardened `CloudJobReconciler`**:
   - Re-returned jobs that were already acknowledged locally (`existing.acknowledged == true`) now strictly reject any incoming lease (same or newer version) with `CloudQueueIntegrityException`. Under the 0.16 SQL contract (`00320`), downloaded (ACKed) jobs are never requeued on the server.
2. **Comprehensive Credential Error Classification**:
   - HTTP status codes `401`, `403`, and Postgres error code `42501` (or authentication/permission required messages) across all queue claim, ACK, and release RPCs now map correctly to `CloudException.CredentialRejectedException()`.
3. **Safe Cloud Configuration Packaging**:
   - Environment variables, Gradle properties, and `local.properties` support Supabase URL and anon key provisioning without exposing secrets in source control.
4. **Zero Cloud SMS Invariant Maintained**:
   - 0.6.1 consumes the Web 0.16 durable queue and persists jobs securely to `cloud_queue.db`, but sends zero cloud-triggered SMS (`SmsManager` remains untouched).

## Version

- `versionName = 0.6.1`
- `versionCode = 8`

## Validation

From Windows PowerShell / Android Studio project root:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
Remove-Item env:ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
powershell -ExecutionPolicy Bypass -File .\scripts\validate-release.ps1
```
