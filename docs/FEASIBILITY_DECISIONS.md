# 0.1 decisions and open gates

## Decided for this prototype

Local native Android app, user-started small tests, explicit SIM binding, persistent intent-before-send journal, no automated resend. SMS API permission only plus phone-state and notifications; no inbox access. No cloud services or external SMS gateway charges. Platform subscription remains future scope.

Distribution: signed test build for controlled installation. This does not establish public-distribution approval. Google Play eligibility, relevant exception claims, developer verification/distribution requirements and operator suitability remain release gates. Do not make this a default SMS handler just to bypass policy: a true default handler would require a separate complete messaging product.

Native platform Views/SQLite keep the lab small; Kotlin remains the primary implementation language. Compose/Room can be adopted in the later product after feasibility passes.

## Open gates / evidence required

- Intended Android/OEM versions and real physical-device test results.
- Operator confirmation of intended automated/consented use and bundle eligibility. A bundle allowance does not prove this usage is allowed. No tariff or throughput guarantee is supplied.
- Actual delivery callback availability and multipart accounting.
- Current installation/permission restrictions on intended devices.
- Public distribution route and review obligations; user update/signing process.
- Sustained background architecture for later cloud gateway. This short session is not evidence that 24/7 unattended sending will work.

## Read references before public release

Verified for design on 2026-09-29; re-check on release:
- https://developer.android.com/reference/android/telephony/SmsManager
- https://developer.android.com/reference/android/telephony/SubscriptionInfo
- https://developer.android.com/develop/background-work/services/fgs/service-types#short-service
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://support.google.com/googleplay/android-developer/answer/10208820

No claim of Play approval or supported operator economics is made by this build.
