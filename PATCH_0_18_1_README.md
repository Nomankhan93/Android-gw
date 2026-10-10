# Android 0.18.1 — Callback State Parity & Recovery Completion

This stabilization release responds to the 0.18 real-device vivo/Ufone acceptance result where SMS was physically received even though every Android SENT callback reported failure.

## Safety correction
- After SmsManager has been invoked, all-failed SENT callbacks are **UNKNOWN**, never retry-safe FAILED.
- Callback evidence alone never authorizes resend.
- UNKNOWN stops the batch and requires explicit Web `Continue without resending`.
- Obsolete `safe_retry` recovery requests are consumed without changing a job back to READY.

## Upgrade migration
`cloud_queue.db` upgrades v3 -> v4. Existing callback-derived local FAILED attempts are reclassified to UNKNOWN so they match Cloud 0.18.1 and already-queued `skip_unknown` recovery can be consumed.

**Install 0.18.1 over the existing app. Do not uninstall or clear app data before the first recovery test**, otherwise the local attempt IDs required to match queued recovery requests will be lost.

## Diagnostics / UX
- Privacy-safe diagnostic JSON now includes raw callback result codes, part index and sync status (no recipient number/body/credentials).
- Cloud Queue UI shows `Background sync active` when the service has recorded a sync even if the activity has not performed its own foreground sync.

## Version
`versionName = 0.18.1`, `versionCode = 13`.
