# BulkText Android development handoff — 0.18.0

Current release: **0.18.0 / versionCode 12**.

0.18 extends the certified 0.17.2 background gateway with callback-aware attempt tracking and explicit recovery. Before `SmsManager`, Android creates/reuses a local client-attempt UUID, registers it through `begin_gateway_message_attempt`, then durably enters SUBMITTING. Multipart SENT/DELIVERED callbacks are written to SQLite before cloud reporting.

Safety rules:
- exact bound SIM only; no fallback;
- all SENT parts successful => SENT;
- all SENT parts failed with zero success => FAILED and potentially safe-retry eligible;
- mixed, conflicting, missing-after-timeout, or post-boundary ambiguous outcomes => UNKNOWN;
- UNKNOWN never auto retries;
- Web `skip_unknown` continues without resend;
- Web `safe_retry` is required before a FAILED job can become READY again;
- delivery failure alone never creates retry eligibility.

Cloud dependency: Web/Cloud migration `20261001000340_delivery_attempts_callbacks_recovery.sql` must be deployed before installing 0.18 on production devices. If the attempt RPC is unavailable, Android blocks before `SmsManager`.

Recovery race hardening: a safe-retry job keeps its failed attempt identity until a new PREPARED attempt supersedes it. Background execution returns early if recovery cloud acknowledgement fails, so a locally staged retry cannot cross `SmsManager` before server authorization is confirmed.
