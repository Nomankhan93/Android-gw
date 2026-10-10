# Android 0.18.0 — SENT / DELIVERED callbacks + attempt history + safe recovery

## What changed

- Registers a unique immutable cloud attempt before each `SmsManager` boundary.
- Uses per-part SENT and DELIVERED PendingIntents for multipart SMS.
- Persists callback truth to `cloud_queue.db` before cloud reporting.
- Adds a durable callback outbox for network interruption/replay.
- Classifies complete SENT success as `SENT`, all-part SENT failure with zero success as `FAILED`, mixed/ambiguous outcomes as `UNKNOWN`, and complete delivery success as `DELIVERED`.
- Keeps the background gateway online in recovery-only mode while UNKNOWN pauses irreversible SMS execution.
- Applies only explicit Web recovery requests:
  - `safe_retry`: only a conclusively failed attempt with zero SENT-success parts is staged READY.
  - `skip_unknown`: clears the safety pause without resending that recipient.
- A locally staged safe retry is never allowed to execute until its server recovery ACK succeeds.
- The failed attempt pointer is retained until the new PREPARED retry attempt atomically supersedes it, so a late conflicting callback can still cancel the retry and force UNKNOWN.
- Local cloud queue DB upgrades from v2 to v3 while preserving historical SUBMITTED/UNKNOWN safety states.

## Safety invariants

- Exact Web-selected SIM is rechecked before every SMS.
- No fallback SIM.
- Local attempt + cloud attempt registration + SUBMITTING are durable before `SmsManager`.
- UNKNOWN is never automatically retried.
- Mixed multipart SENT results are UNKNOWN.
- Conflicting duplicate callback payloads are treated as ambiguity, never retry evidence.
- Delivery failure does not authorize resend.
- Safe retry requires a new explicit Web request and cloud ACK.
- Existing certified local SMS-lab transport files remain byte-for-byte unchanged.

## Cloud dependency

Migration `20261001000340_delivery_attempts_callbacks_recovery.sql` must be deployed before production Android 0.18 execution. If attempt registration/recovery RPCs are unavailable, sending blocks before `SmsManager`.

## Version

`versionName = 0.18.0`, `versionCode = 12`.
