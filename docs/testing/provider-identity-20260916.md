# Model discovery Provider identity — 2026-09-16

User asked to recheck credential entry, Provider selection and model-list errors.

Confirmed boundary defect: `fetchModels()` resolves an omitted profileId from
getConfig, then loads that profile's revision/endpoint, but previously sent only
the caller's original optional id. The native handler consequently used the
current editing profile. A switch during those awaits could select another
profile; equal endpoint and revision values do not establish profile identity.
This is a potential wrong-credential request, not proof of the screenshot's cause.

Fix: send resolved targetProfileId with the snapshot on the existing MethodChannel.
No credential copying between profiles, alternate persistence or retry added.

Executable regression: `implicit discovery stays on resolved provider after editor switches`
in `ui/test/services/model_provider_config_service_test.dart`. Synthetic A/B have
the same endpoint/revision, A configured and B unconfigured; current selection
changes to B before dispatch. Expect request remains explicitly pinned to A and
contains no copied API key. Before: failed (id null). After: passed.

Run: `cd ui && flutter test --no-pub test/features/home/pages/model_provider_setting/model_provider_setting_page_test.dart test/services/model_provider_config_service_test.dart test/services/model_provider_live_discovery_test.dart`
Result: 42 passed, including prior credential-save race and refresh/reopen tests.
Dart analyze of changed service: no errors; one pre-existing unused-field warning
and four style info diagnostics.

Only emulator-5580 connected. **待真机验证**: on the affected phone verify A/B
selection, independent keys, refresh, repeated switching, exit/reopen and restart.
No actual device acceptance or screenshot root-cause attribution claimed.

APK build: assembleDevelopStandardDebug passed with Android Studio JBR.
New APK has not been installed on the user's physical phone.
