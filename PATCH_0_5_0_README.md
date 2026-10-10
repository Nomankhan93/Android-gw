# Android 0.5.0 — Cloud SIM Inventory & Explicit Binding

## Scope

0.5.0 adds the Android side of the already-existing BulkText cloud device/SIM contract. It reports phone metadata and active SIM subscriptions to `report_gateway_device_inventory`, receives the SIM binding selected in BulkText Web, and represents the authoritative binding as **Ready**, **Unbound**, or **Missing**.

This patch does **not** implement cloud message jobs or cloud-triggered SMS sending.

## Cloud contract

RPC request uses exactly:

- `p_device_id`
- `p_credential`
- `p_device`
- `p_sims`

Device payload includes manufacturer/model, Android release/SDK, app version/code, and battery percent when available.

Each SIM payload includes subscription ID, slot index, carrier/display name, country ISO, embedded flag, and a defensive SHA-256 fingerprint built only from non-secret subscription metadata. Phone number, ICCID, IMSI, IMEI, and device serial are not collected.

## Explicit binding rule

BulkText Web is authoritative for SIM selection. Android does not bind a SIM itself.

- `unbound`: Web has not selected a SIM.
- `ready`: the exact server-bound subscription ID + slot is present locally.
- `missing`: the server-selected SIM is absent or no longer matches the active Android inventory.

A missing binding never falls back to another active SIM.

## Local SMS lab

The existing 0.3 transport lab remains manual and unchanged. 0.5 only establishes cloud inventory/binding state for later queue/execution phases. Cloud campaign sending remains disabled.

## Validation

Run once in Android Studio / Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\validate-release.ps1
```

This runs clean, unit tests, assembleDebug and lintDebug, then verifies versionName `0.5.0`, versionCode `6`, and prints the fresh APK SHA-256.
