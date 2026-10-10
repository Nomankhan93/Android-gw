# Android 0.17.0 — Cloud SMS Execution

## Baseline

This patch is built for the stabilized Android baseline:

- Android `0.6.1`
- `versionCode = 8`
- Web `0.16.2` / commit `331d56d`
- Cloud migration head `20261001000320`

It must **not** be applied to Android 0.6.0 or to the older 0.17 artifact.

## Scope

0.17 crosses the first cloud-to-`SmsManager` boundary only.

Authoritative flow:

```text
ACKed durable cloud job
→ explicit one-job user confirmation
→ refresh authoritative device/SIM inventory
→ re-check exact frozen SIM identity
→ re-check segment count
→ durably persist SUBMITTING
→ SmsManager on exact subscriptionId
→ normal return: SUBMITTED
→ ambiguous post-boundary failure/restart: UNKNOWN
```

## Safety invariants

- exact Web-bound SIM only;
- no fallback SIM;
- cloud job must already be durably persisted and ACKed;
- `SUBMITTING` is committed before `SmsManager`;
- restart while `SUBMITTING` becomes `UNKNOWN`;
- any exception after the durable submission boundary becomes `UNKNOWN`;
- `UNKNOWN` is never automatically retried;
- one explicit cloud job per user action;
- no automatic queue continuation;
- stable cloud job ID remains the execution identity;
- the separate local SMS lab transport is unchanged.

## Deliberately deferred to 0.18

0.17 does **not** implement:

- sent callbacks;
- delivery callbacks;
- message-attempt/part cloud tables;
- SENT / DELIVERED server statuses;
- automatic or manual retry workflows;
- cloud callback/status sync RPCs.

A normal `SmsManager` return is recorded only as `SUBMITTED`. It is not proof of carrier send or delivery.

## Local DB change

`cloud_queue.db` upgrades non-destructively from schema v1 to v2. It adds local execution state/timestamps and an execution event journal. This is an Android-local SQLite migration only; **no Supabase migration is added**.

## Version

- `versionName = 0.17.0`
- `versionCode = 9`
