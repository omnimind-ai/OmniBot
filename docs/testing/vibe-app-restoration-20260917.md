# Vibe App restoration — 2026-09-17

User explicitly requested restoration of the previous Vibe App plugin after its
removal was identified. This supersedes the old blanket launcher prohibition;
AGENTS.md now preserves the narrower decision. The window.omni.app private Agent
session/event API remains removed. Apps use the existing plugin tool connectors.

Restored:
- Vibe Builder 0.2.1 catalog membership and runtime assets in standard/main builds.
- PluginAppActivity, launch identity/parser and shortcut manager recovered from
  commit 8203f5a42's parent; shortcuts now target PluginAppActivity directly, not
  the removed Flutter dashboard route.
- Enabled generated plugins expose Open App and Add to Home Screen from details.
  Disabled plugins reject native opening/calls. Uninstall disables their shortcut.
- Existing pool and connector-owned data are retained. Publishing does not pin
  shortcuts automatically; Skill instructions now match the actual UI flow.
- Native App stays scoped to its plugin's canonical directory and existing bridge.
  The Activity is not exported; calls recheck installed/enabled state.

Executed regression:
- sandbox JVM suite: 32 tests, zero failures/errors (including restored launch
  identity tests and pool dashboard/presentation checks).
- plugin_market_page_test.dart: 11 tests passed, including correct plugin ID for
  open/pin and disabled controls. Initial new assertion used the wrong Tooltip
  widget type; fixed the test. One earlier run hit a concurrently missing unrelated
  FunctionArgumentsDialog file; it existed again on subsequent execution.
- verify-vibe-package.py verifies actual APK catalog and all Vibe runtime assets,
  plus project_contract/check/publish declarations. Passed for standard debug APK.

Execution:
```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest --tests 'cn.com.omnimind.bot.plugin.sandbox.*' :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
cd ui
flutter test --no-pub test/features/home/pages/plugin_market/plugin_market_page_test.dart
```
From repository root:
```sh
python3 scripts/verify-vibe-package.py app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk
```

**待真机验证**: no physical device connected; only shared emulator-5580.
This change has not been installed or interactively accepted on a device.
Required actual operations: install/enable Vibe Builder, generate/check/publish a
synthetic App, open it from plugin details, invoke its declared tool, save data,
restart/reopen, update while retaining data, confirm a pinned shortcut in the
system dialog, then disable/uninstall and verify no enabled access remains.
