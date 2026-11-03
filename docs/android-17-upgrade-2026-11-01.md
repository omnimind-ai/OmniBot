# Android 17 / API 37 upgrade

## Build scope

The OmniBot host now targets Android 17 (`targetSdk = 37`). Its compile SDK was
already 37; minimum Android remains API 29. All host flavors inherit the same
target. The embedded terminal libraries use the host configuration; the separate
`ReTerminal/app` application is not part of the OmniBot build.

The existing AGP 9.3.2, Gradle 9.5.0, JDK 17, Kotlin 2.4.10 and Flutter 3.47.2
toolchain is retained. [AGP 9.3 officially supports API 37](https://developer.android.com/build/releases/agp-9-3-0-release-notes)
with Build Tools 36.0.0 and NDK 28.2.13676358. Generated `ui/.android` files stay
untracked and the existing host override still sets the Flutter compile SDK.
See the [official SDK setup guide](https://developer.android.com/about/versions/17/setup-sdk).

## Compatibility changes

- Local networking: declare `ACCESS_LOCAL_NETWORK`, expose its state and request
  action through the existing permission channel/registry, and request permission
  for explicit LAN connection/configuration actions. The shared native helper
  checks IPv4/IPv6 and resolved LAN destinations off the main thread; `.local`
  names are recognized before mDNS. Public internet and same-profile loopback
  operations do not require this prompt. Denial produces an actionable error;
  no ACP turn replay or network retry is added. See
  [local network permission](https://developer.android.com/privacy-and-security/local-network-permission).
- Music: promote the existing media playback service before requesting audio
  focus or resuming the player. Foreground promotion/focus failure releases the
  existing playback lifecycle and reports an error. Android 17 also requires
  background playback to originate from a visible/user-triggered operation;
  starting a service alone does not grant that capability. The existing alarm
  service uses `USAGE_ALARM` and exact alarms. See
  [background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio).
- Chat speech: its playback owner maintains a separate media foreground-service
  lease through synthesis and playback, including replay of historical messages.
  Multiple Flutter engines share the notification without sharing voice queues.
  Finishing an ACP prompt still finishes the turn normally. A new playback request
  that cannot acquire foreground capability reports a voice error. Pause retains
  resumable playback and its notification; stop, release or queue completion
  releases the lease. The notification can stop all speech playback.
- Terminal IME: preserve whether the focused terminal had an open keyboard across
  activity recreation, then restore once the new terminal view is ready. Do not
  reopen a keyboard from settings or reuse a view owned by the destroyed activity.
  The main Flutter activity already handles rotation/configuration changes. See
  [Android 17 all-app behavior changes](https://developer.android.com/about/versions/17/behavior-changes-all)
  and [reliable IME display](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility#show-reliably).
- Declare telephony hardware optional so the existing SMS permission does not
  filter out devices without it. This also resolves the manifest Lint error;
  see [implied hardware requirements](https://developer.android.com/develop/devices/chromeos/learn/manifest#implied-features).

## Reviewed existing behavior

The [target-specific changes](https://developer.android.com/about/versions/17/behavior-changes-17)
were reviewed against the production source:

- No large-screen orientation/resizability opt-out exists. MainActivity allows
  large-screen and multi-window rotation; edge-to-edge, insets and system back
  already use the existing platform/Flutter mechanisms.
- No private `MessageQueue` reflection, reflective writes to static final fields,
  legacy background-activity-start opt-in constant or Bluetooth RFCOMM read loop
  was found.
- QuickLog widgets use text/resource drawables and full updates, without bitmap
  payloads. Contacts read account columns from `RawContacts` with permission.
- Network Security Configuration is already present. The platform's certificate
  transparency and ECH defaults are retained; TLS verification is not weakened.
- Personal SMS queries accept the platform's delayed visibility of OTP messages;
  they must not be used as an immediate verification-code retrieval mechanism.
- Native libraries are packaged in the APK and extracted by PackageManager;
  application code uses `System.loadLibrary`, not writable `System.load` files.
  Flutter 3.47.2's ReLinker fallback still has a writable extraction path. It is
  not the normal packaged-library path; follow the
  [upstream Flutter fix](https://github.com/flutter/flutter/pull/189146) when
  updating Flutter rather than installing a custom loader here.

## Verification

Validation uses the host and embedded-terminal test namespaces; the separate
terminal installer combinatorial shell suite is outside this focused run:

```sh
./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest \
  --tests 'cn.com.omnimind.*' --tests 'com.rk.*' \
  :baselib:testDebugUnitTest :app:lintDevelopStandardDebug \
  :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart
cd ui
flutter test --no-pub test/services/permission_service_test.dart \
  test/features/home/pages/authorize_setting/authorize_setting_page_test.dart \
  test/features/home/pages/settings/settings_page_test.dart
flutter analyze --no-fatal-warnings --no-fatal-infos
```

Results on 2026-11-01:

- Host scoped tests: 1,422 cases validated (1,419 passed initially; all three
  terminal recreation cases passed after initializing the test Application).
- Base library: 287 tests passed. Flutter permission/settings: 14 tests passed.
- Flutter analysis: no errors; 513 warnings/info remain.
- Final Android Lint report: no errors; 363 warnings and 20 hints remain.
  The report was inspected explicitly because this project sets
  `abortOnError = false`.
- Debug APK assembly passed. Packaged metadata confirms compile/target/minimum
  SDK 37/37/29, version 0.7.0.2 (19).
- API 37 `ColorOS17_API37` emulator: upgrade installation, visible home/settings,
  cold start, and the nearby-device deny/allow/revoke flows were exercised.
  Denial leaves the local-service switch off; granting permits enabling it.
  The test service was disabled and local-network access was revoked afterwards.
- The unrelated full terminal installer package-combination shell check was
  interrupted; it is not included in the scoped app test result.

APK: `app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk`.
SHA-256:

```text
0cd77c4359cd2b578f6701f4136ca995b3bebcb1a46571b329176cab66d93783
```

Physical-device acceptance must cover granting/denying/revoking nearby-device access,
existing LAN providers and PC Bridge/MCP configurations, public-network and
loopback connections without permission, music start/resume from notifications,
exact-alarm ringing, terminal keyboard rotation, large-screen resizing and
conversation switching. Cross-profile loopback is blocked by Android 17 and must
not be worked around. Memory-pressure, OEM/Shizuku and release/R8 coverage remain
separate from build and unit-test verification.
