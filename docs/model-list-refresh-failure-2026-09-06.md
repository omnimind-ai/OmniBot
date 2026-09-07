# Model-list refresh failure — PJE110

Reproduced through the phone's Model Providers refresh button. The configured base is `https://llmapi.paratera.com`; the request is the expected `/v1/models`. App log reports SSLHandshakeException caused by CertPathValidatorException (trust anchor not found). Independent native Android curl to the exact model endpoint also rejects a self-signed certificate before receiving HTTP. This is evidence of connection validation failure, not evidence of bad credentials, missing models, or a parsing defect.

The native boundary already supplies `provider_tls_certificate_failure`. The provider page discarded it in a catch-all toast, then displayed an empty add-model prompt. It now reuses the shared error formatter, keeps the actionable failure text visible with explicit Retry, and rejects errors from obsolete profile revisions/drafts. It does not retry automatically, bypass TLS, or invent cached model results.

18 provider-page tests passed, including durable TLS failure display, secret/raw-error suppression and successful explicit retry. Focused Dart analysis clean. APK build succeeded in23s; `/tmp/oob-model-refresh-errors-20260906.apk` installed with `adb install -r` Success on PJE110.

After installation, actual phone UI contains the actionable connection message and Retry; raw certificate exception and misleading add-model prompt are absent. Successful network-backed refresh still requires a trusted connection to the configured endpoint.
