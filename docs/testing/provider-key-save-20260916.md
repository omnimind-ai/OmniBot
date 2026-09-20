# Provider credential save race — 2026-09-16

User report: model discovery returned HTTP 401, server reported missing Authorization.
The screenshot does not identify the Provider or prove which credential-loss path occurred.

Confirmed reproduction: clear the existing key, blur to start an asynchronous save,
enter a new synthetic key before the old save completes, complete the old save,
then blur again. Previously the old completion cleared the new edit's dirty flag,
so only the empty key was saved. The widget test failed before the patch (one save,
expected two) and passes after it (second save contains the new key).

Fix: acknowledge only the API-key/custom-header edit revision included in the
completed save. Each queued save reads the latest profile snapshot. Existing
native encrypted storage and omitted-versus-explicit credential replacement
semantics remain the owner of persistence.

Executable regression:
`cd ui && flutter test test/features/home/pages/model_provider_setting/model_provider_setting_page_test.dart test/services/model_provider_config_service_test.dart test/services/model_provider_live_discovery_test.dart`

Result: 41 tests passed. Includes existing draft refresh/reopen and focused-field
save behavior; the new deterministic test injects a delayed platform response.
Synthetic credentials only. Logs are in artifacts/provider-key-save-20260916/.

Device inventory: only emulator-5580; no physical device connected.
**待真机验证**: repeat editing/refresh, leave/reopen settings, restart app and fetch
models on the affected phone/Provider. Screenshot incident attribution remains
unconfirmed. Unit/widget results do not establish physical-device acceptance.

Build: `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart` passed (1m 5s). This turn did not install the new APK on a phone.
