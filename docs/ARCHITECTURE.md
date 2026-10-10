# BulkText Android Gateway architecture and recovery contract

## Local SMS transport

`MainActivity -> Store.create -> user-started SendingService -> Store.claim -> SmsManager -> SmsResultReceiver -> Store.callback`

SQLite transactions persist job claims/parts **before** transport invocation. A crash between claim and Android SMS submission intentionally produces UNKNOWN instead of risking duplicate automatic submission.

Only QUEUED jobs are claimable. UNKNOWN/FAILED/PARTIAL/SENT/DELIVERED/CANCELLED/SUBMITTING are not blindly re-claimed. Process startup pauses RUNNING sessions and changes SUBMITTING to UNKNOWN. There is no automatic process/boot resume.

The selected Android `subscriptionId` is explicit. If that SIM identity is unavailable or changes, sending stops; another SIM is never silently substituted.

## Cloud identity layer (0.4.1 retained)

`MainActivity -> GatewayIdentityRepository -> GatewayApi -> Supabase RPC`

Pairing/auth concerns remain separate from `SendingService` and the SMS journal.

- A random installation UUID is generated once and persisted app-privately.
- The web-created 12-character pairing code is normalized and claimed through `claim_gateway_pairing`.
- The returned gateway credential is encrypted with Android Keystore AES/GCM and persisted synchronously.
- `authenticate_gateway_device` successful rows provide device identity, credential version/expiry, and server time. Invalid/revoked/expired server credentials are represented by RPC errors and map to re-pair-required state.
- A transport/network failure maps to Offline without deleting the local pairing.
- Local **Forget pairing** removes gateway credentials only; it does not claim to revoke the cloud device and does not delete SMS history.

## Network/security

The app has `INTERNET` and `ACCESS_NETWORK_STATE` for cloud identity. Supabase URL/anon key are build configuration values; the long-lived gateway credential is a separate secret and is never stored plaintext or logged.

## Cloud SIM inventory & explicit binding (0.5.0)

`MainActivity -> AndroidDeviceInventoryProvider -> GatewayInventoryRepository -> report_gateway_device_inventory`

- Android reports current device metadata and active SIM subscriptions only after `READ_PHONE_STATE` is available.
- SIM payload deliberately excludes phone number, ICCID, IMSI, IMEI and device serial.
- BulkText Web remains authoritative for selecting the sending SIM. Android receives `unbound`, `ready`, or `missing` from the server.
- `ready` is accepted only when the exact bound subscription ID + slot is also present in the same local inventory snapshot.
- If the bound SIM is absent, state becomes `missing`; another active SIM is never substituted.
- Offline inventory refresh does not invent or change a binding.

0.5 does not download campaign jobs and does not trigger SMS from cloud state. Later phases must persist each cloud job locally before `SmsManager` and retain the UNKNOWN/no-blind-retry contract.
