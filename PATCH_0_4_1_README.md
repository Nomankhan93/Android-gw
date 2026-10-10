# Patch 0.4.1 — Cloud Pairing Contract & Validation Stabilization

## Corrected blockers

- Fixed `GatewayIdentityRepository` wiring in `GatewayApp` to use the real constructor names: `installationIdProvider`, `credentialStore`, `api`.
- Enforced exactly 12 normalized pairing-code characters, matching the backend.
- RPC payloads contain only exact PostgreSQL `p_*` parameter names.
- PostgREST table-returning RPCs are parsed as exactly one JSON-array row.
- `authenticate_gateway_device` now parses the actual backend fields instead of non-existent `valid/expired/revoked` booleans.
- Authentication and rotation verify the returned `device_id` matches the requested device.
- `credential_expires_at`, `paired_at`, and `server_time` use ISO-8601 parsing with no local fallback expiry.
- Endpoint-aware API error mapping prevents an authentication error from being mislabeled as an invalid pairing code.
- Persistent installation UUID writes are checked synchronously.
- Keystore credential writes remain synchronous and checked.
- If server credential rotation succeeds but replacement persistence fails, local credential state is cleared rather than retaining a credential already invalidated server-side.
- Forget-pairing UI accurately describes local-only credential deletion.
- Added real `org.json` reference implementation for local JVM RPC parser tests.
- Added configuration lookup from Gradle property, environment variable, or gitignored `local.properties`.

## Preserved SMS invariants

These files were intentionally not modified by this corrective patch:

- `Policy.java`
- `Telephony.kt`
- `SendingService.kt`
- `SmsResultReceiver.kt`
- `Store.kt`

Therefore 0.3 transport behavior remains the baseline: explicit subscription selection, no silent SIM fallback, durable pre-submit claim, UNKNOWN after uncertain interruption, and no blind retry.

## Validation boundary

This package was source-validated in the artifact environment (see `VALIDATION_RESULTS.md`). A complete Android Gradle build requires an installed Android SDK and Gradle dependency access, so the included validation script must be run in Android Studio before installing the APK on a phone.
