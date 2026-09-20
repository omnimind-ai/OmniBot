# Provider settings validation — 2026-09-16

Requirement: minimal coupling; missing credentials should identify the selected
Provider, empty authentication headers must not replace valid credentials,
and discovery must wait for persistence and stop if persistence fails.

Implementation stays in ModelProviderSettingPage. Concurrent save callers await
the same Future and reuse the existing queued-save loop. Validation reuses
needsPresetCredentials and the existing header error field. No new service,
Agent lifecycle, retry path or credential store. Custom/local anonymous endpoints
remain allowed. Authorization/x-api-key/api-key with whitespace-only values
are rejected in the editor; invalid headers cannot silently fall back to a
previous value during save.

Executable cases in model_provider_setting_page_test.dart:
- empty credentials discovery for deepseek: no request, named Provider message;
- empty credentials discovery for custom: request allowed;
- empty Authorization is rejected and correcting it recovers;
- refresh waits for in-flight credential save (failure=false/true): one request
  after success, no request and visible error after failed persistence.

Run: cd ui && flutter test --no-pub test/features/home/pages/model_provider_setting/model_provider_setting_page_test.dart test/services/model_provider_config_service_test.dart test/services/model_provider_live_discovery_test.dart
Result: 47 passed, including earlier delayed-edit and reopen regression cases.
Samples use synthetic keys only.

Device inventory: emulator-5580 only. **待真机验证** for settings edits, refresh,
restart and recovery on an actual phone. The previously distributed Release APK
predates these changes; these results do not apply this new patch to that APK.

Validation: changed settings page `dart analyze` reports No issues found.
`:app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart` succeeded
in 3m 13s using Android Studio JBR; Kotlin daemon failed initially and Gradle's
fallback compiler completed successfully. No device installation this turn.
