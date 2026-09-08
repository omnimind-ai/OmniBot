# Android / Flutter dependency upgrade

## Supported build environment

| Component | Version |
| --- | --- |
| Flutter | 3.47.2 (Dart 3.13.2) |
| JDK | 17 |
| Android Gradle Plugin | 9.3.2 |
| Gradle | 9.5.0 |
| Kotlin | 2.4.10 |
| Android compile / target / minimum SDK | 37 / 36 / 29 |
| SDK package | `platforms;android-37.0` |
| Build Tools / NDK | 36.0.0 / 28.2.13676358 |
| Node / pnpm | 22 / 10.28.0 |

The host's root version catalog also configures the embedded `:core:*` terminal
modules. The standalone `ReTerminal/` application, catalog and wrapper retain
main's configuration; upgrading that separate application is outside this PR.

## Build integration

The root script declares `dev.flutter.flutter-gradle-plugin` with `apply false`.
This makes `FlutterExtension` visible to Kotlin DSL scripts in embedded plugins.
Without the shared classpath, modern plugins fail to compile their
`flutter.compileSdkVersion` accessors. The generated `ui/.android/` files remain
untracked. The host uses `finalizeDsl` to align the generated `:flutter` library's
compile SDK with the host.

Removed all seven Android plugin overrides. The lockfile now resolves current
compatible platform implementations, including WebView, image picker, path
provider, shared preferences, lifecycle, URL launcher and video player.
Use `flutter pub get --enforce-lockfile` for reproducible builds.

Two AGP compatibility flags remain intentionally:

- `android.newDsl=false`: Flutter 3.47.2's add-to-app Gradle plugin still calls
  legacy Android extension APIs. Enabling the new DSL fails while applying the
  Flutter plugin in this project.
- `android.builtInKotlin=false`: the host and third-party plugins such as
  `just_audio` and `mobile_scanner` still apply the Kotlin Android plugin.
  Migrate all participants before enabling built-in Kotlin. Room and Glide
  still use the existing kapt configuration; no unused legacy-kapt alias is kept.

This is an AGP 9 build using its compatibility mode, not a completed migration
to built-in Kotlin or the new DSL. Remove the flags only after the official
Flutter add-to-app integration and every plugin support that combination.

## Flutter migration checks

- File Picker 12.2 returns a list directly. Shared attachment metadata handling
  preserves local paths, document URIs and Android SAF handles. Picker-reported
  sizes are read synchronously; an unknown size stays optional and never causes
  a file read or an extra asynchronous gap before `setState`.
- Riverpod's existing StateNotifier/ChangeNotifier providers use the official
  legacy imports. The Agent runtime remains outside Riverpod provider
  initialization; no prompt retry or ACP lifecycle has been added.
- Markdown uses `flutter_markdown_plus`; math rendering and route behavior remain
  covered by the existing tests.
- The Agent editor uses a Material background so SwitchListTile ink is visible
  and Flutter 3.47's material ancestry assertion is satisfied.

## Android 16 review

The app targets API 36. MainActivity and Flutter already opt into edge-to-edge,
with scroll/IME inset tests. The manifest enables predictive back, and native
terminal gates use supported back callbacks. There is no edge-to-edge opt-out
attribute to remove. Android 16 ignores portrait restrictions on displays with
smallest width >= 600dp, so large-screen rotation and split-screen remain part of
device acceptance. Source inspection and unit tests do not establish full device
compatibility.

## Verification commands

```sh
cd ui
flutter pub get --enforce-lockfile
flutter analyze --no-fatal-warnings --no-fatal-infos
flutter test
cd ..
./gradlew --no-daemon help
./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest \
  :app:lintDevelopStandardDebug :app:assembleDevelopStandardDebug \
  -Ptarget=lib/main_standard.dart
```

CI runs the entire Flutter suite and Android unit tests, lint and Debug APK
assembly. Release signing and publishing are separate operations.

## Results (2026-09-08)

- Flutter locked install: passed; analysis: no errors (585 warnings/info).
- Flutter tests: 1173 passed.
- Android app unit tests: 967 passed, no failures or skips.
- Gradle help, build task-graph dry run and Debug APK assembly: passed.
- Lint follow-up: 0 errors, 293 warnings and 20 hints. Fixed both notification
  permission findings by handling SecurityException after the existing permission
  checks; rejected task notifications are not registered as active. QuickLog
  uses LocalResources so strings update on configuration changes. No suppressions
  or lint policy changes were added. Android unit tests were rerun: 967 passed.
- Installed the Debug APK on an Android 16 device: versionCode 13,
  versionName 0.6.2.1, minSdk 29, targetSdk 36. Startup, existing history display,
  in-app back navigation and opening the system file picker were observed.
  File selection round-trip, gesture animation quality, floating windows,
  terminal interaction and large-screen rotation still need manual acceptance.
- The APK/device observations above precede the lint follow-up; that follow-up
  reran Android compilation, unit tests and lint.
- Release R8 assembly, release signing and publishing were not performed.

## Official references

- [AGP 9.3 compatibility and fixes](https://developer.android.com/build/releases/agp-9-3-0-release-notes)
- [Flutter built-in Kotlin / add-to-app migration](https://docs.flutter.dev/release/breaking-changes/migrate-to-built-in-kotlin/for-app-developers)
- [File Picker changelog](https://pub.dev/packages/file_picker/changelog)
- [WebView Android migration](https://pub.dev/packages/webview_flutter_android/changelog)
- [Riverpod 3 migration](https://riverpod.dev/docs/3.0_migration)
- [ListTile material ancestry](https://api.flutter.dev/flutter/material/ListTile-class.html)
- [Android 16 target-specific changes](https://developer.android.com/about/versions/16/behavior-changes-16)
