# Android 0.18.1 validation

Built from the supplied current 0.18.0 gateway source.

Required acceptance after install-over-existing-app:
1. Existing callback-derived FAILED rows migrate to UNKNOWN.
2. Existing Web `skip_unknown` requests are consumed and ACKed without resend.
3. New all-failed SENT callbacks become UNKNOWN locally and in cloud.
4. Batch stops on first UNKNOWN.
5. No automatic or callback-derived safe retry.
6. Diagnostic export contains raw callback result codes without PII.
