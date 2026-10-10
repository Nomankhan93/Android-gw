# Physical-Device Acceptance Matrix — 0.3.0 Baseline

**Target Device**: Vivo Y33s (Model V2109)  
**Android Version**: Android 13 (API 33)  
**App Build**: BulkText Gateway 0.3.0 (`bulktext-gateway-0.3.0-debug.apk`)  
**SIM Configuration**: Dual SIM (SIM1: Ufone — Active package/balance; SIM2: Jazz — No package/balance)  
**Status**: PASSED — Physical Device Baseline Validated  

> This matrix validates the **0.3 local SMS transport baseline** only. Android 0.5.0 cloud pairing + SIM inventory/binding still require live acceptance against the linked Supabase project. The table below remains the proven 0.3 local SMS transport baseline.

---

| Test | Procedure | Expected Outcome | Actual Result (Vivo Y33s / Android 13) |
|---|---|---|---|
| **Permission Deny** | Deny SMS / phone permission on app launch | Cannot preview or start send; clear permission request shown | **PASSED**: Send action blocked with clear permission prompt in UI. |
| **Short English** | Single recipient, short ASCII text via Ufone SIM1 | 1 segment; selected line; sent and delivered callbacks received | **PASSED**: 1 segment dispatched via SIM1; sent and delivery callbacks confirmed. |
| **Urdu / Unicode** | Single recipient, Urdu text | Unicode 70-char split preview; sent and received without character corruption | **PASSED**: Urdu text correctly encoded, split, and received intact on recipient device. |
| **Multipart (10-part)** | Long message requiring multiple segments (up to 10 parts) | Per-part results tracked; aggregate success only when all parts succeed | **PASSED**: 10-part multipart message chunked, sent, aggregated, and delivered successfully. |
| **Deduplicate** | Same phone entered in `03...` and `+923...` formats | Normalized to single recipient in preview | **PASSED**: `03001234567` and `+923001234567` correctly deduplicated to 1 recipient. |
| **Limits Enforcement** | Attempt >5 recipients or >15 total segments | Rejected before creating sending session | **PASSED**: Batch limits strictly enforced prior to session creation. |
| **SIM Choice & Detection** | Explicitly select Ufone SIM1 vs Jazz SIM2 in separate sessions | Both SIMs detected; dispatch uses explicitly selected line | **PASSED**: Dual-SIM detected; Ufone SIM1 and Jazz SIM2 correctly selectable in UI dropdown. |
| **No Package / SIM Error** | Dispatch session via Jazz SIM2 (no package/balance) | Send failure surfaced immediately; no automatic SIM fallback | **PASSED**: Platform send failure caught and surfaced; Ufone SIM1 was **not** automatically used. |
| **SIM Change / Binding** | Attempt session with mismatched subscription ID | Session requires manual re-confirmation/selection | **PASSED**: Explicit subscription binding maintained; manual re-selection required. |
| **Lock Screen Operation** | Start multi-recipient batch and lock phone screen | Session continues to completion without part duplication or dropped SMS | **PASSED**: Bounded foreground service completed dispatch under lock screen seamlessly. |
| **Background Service** | Start session and press Home | Persistent notification visible; session completes in background | **PASSED**: Foreground notification visible; batch completed without OS process kill. |
| **Pause Session** | Tap Pause before next queued recipient | In-flight message finishes; queued messages remain pending | **PASSED**: Batch paused cleanly; no unsubmitted messages dispatched after pause. |
| **Cancel Session** | Tap Cancel during session | Queued jobs marked `CANCELLED`; previously sent SMS unchanged | **PASSED**: Queued jobs marked `CANCELLED` in persistent journal. |
| **Signal / Failure Pausing** | Induce send failure (no package / no signal) | Session pauses on failure; no blind automatic retry | **PASSED**: Session paused on failure; failed job preserved in journal without automatic retries. |
| **Internet-Off Operation** | Disable Wi-Fi and Mobile Data (airplane off, cellular phone service on) | App sends SMS locally over telephony with 100% success; zero cloud dependency | **PASSED**: Dispatched and delivered 100% locally with internet completely turned off. |
| **Diagnostic Export** | Export session diagnostics JSON | Sensitive phone numbers and message text redacted from export | **PASSED**: Exported JSON verified; numbers and message bodies omitted. |
| **Local Data Wiping** | Pause and delete local session history | App SQLite history cleared; sent cellular SMS not recalled | **PASSED**: App-private database cleared cleanly. |

---

## ADB Helper Commands

```bash
adb devices
adb shell am force-stop com.bulktext.gateway.prototype
adb shell am start -n com.bulktext.gateway.prototype/com.bulktext.gateway.MainActivity
```

---

## Physical Device Baseline Summary

The 0.3.0 feasibility prototype is successfully closed out as a physical-device-validated baseline on Vivo Y33s (Android 13). All core local SMS gateway mechanics — explicit SIM binding, multipart Unicode handling, lock-screen foreground persistence, internet-off sending, error surfacing, and fail-safe non-retrying session pausing — have been demonstrated on real hardware.
