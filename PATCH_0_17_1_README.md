# Android 0.17.1 — Simple Send Automation

- Foreground auto-sync: while the gateway app is open and safely paired, it checks the cloud queue every ~30 seconds. Manual sync remains recovery/diagnostics only.
- One `Send campaign` confirmation processes up to 100 READY jobs from one campaign sequentially.
- Exact SIM identity is re-reported/re-checked before every SmsManager call. No fallback SIM is allowed.
- Any pre-boundary blocker stops the batch. Any ambiguous post-boundary failure becomes UNKNOWN and stops the batch. UNKNOWN is never auto-retried.
- Queue UI no longer says “No cloud SMS submitted” when local execution has SUBMITTED jobs.
- 0.17.1 still does not implement SENT/DELIVERED callbacks, attempt history or retry resolution; those remain 0.18.
