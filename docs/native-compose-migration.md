# Flutter → Kotlin / Compose migration

## Target and current slice

Preserve OmniBot's existing appearance and behavior while moving its UI to Kotlin
and Jetpack Compose. Miuix provides navigation, gesture handling, popup hosting,
and suitable controls; its default visual style does not replace OmniBot's design.

The first slice is **Home → Drawer → Settings**. `:native-ui` contains its UI and
serializable `miuix-nav` routes. `app/ui/nativehome` adapts existing repositories
and owns the temporary hand-off to Flutter feature pages.

This is an **opt-in migration surface**, not a claim of completed visual or
functional parity. The default launcher remains unchanged. Enable the slice with:

```sh
./gradlew :app:assembleDevelopStandardDebug \
  -Ptarget=lib/main_standard.dart -Pomnibot.nativeHome=true
```

Launch the installed app normally. Routed notification/deep-link intents retain
their existing owner. Omit `omnibot.nativeHome` to build the existing entry point.
No local Downloads path, included third-party source checkout, signing change,
database migration, or new preference namespace is required.

## Module and state ownership

```text
LauncherActivity
  └─ NativeHomeActivity
      ├─ NativeHomeViewModel → NativeHomeRepository
      │   ├─ existing Room ConversationDao
      │   ├─ existing FlutterSharedPreferences (read compatibility)
      │   └─ existing McpServerManager
      ├─ :native-ui / NativeHomeApp
      │   └─ one saved miuix-nav stack: Home → Settings
      └─ LegacyHomeNavigator → MainActivity → existing Flutter page
```

- Composables accept immutable snapshots and callbacks. They do not locate
  services, perform I/O, own Activities, or send Agent prompts.
- The Activity collects state with `collectAsStateWithLifecycle`; its ViewModel
  survives configuration changes. Room observes the existing history table.
  Preference observation unregisters its listener when collection ends.
- Miuix owns page transition state, saved route state, and predictive back. The
  existing predictive-back preference controls progress delivery as well as
  transition choice. Drawer state is scoped to the home entry.
- Kotlin/JVM 21 is required for Miuix's public inline navigation DSL. Versions are
  centralized in the existing catalog; Miuix UI and nav are pinned to 0.9.4.
- Android resources carry Chinese/English text and licensed Lucide vectors.
  Keep migrated text in sync with `ui/lib/l10n/app_{en,zh}.arb` while both UIs exist.
- `native_home_default_prompts.json` mirrors the five defaults in
  `HomeGreetingSettingsService`; saved custom prompts take precedence.

The structure follows Android's [state holder guidance](https://developer.android.com/topic/architecture/ui-layer/stateholders).
Keep abstractions concrete: add a new module only when it has an independent
dependency/ownership boundary. Do not add a generic screen framework, service
locator, or an interface for every class.

## ACP lifecycle remains canonical

| Identity / transition | Existing owner | Migration rule |
| --- | --- | --- |
| Conversation and persisted items | Room + existing history services | Observe existing rows; never recreate a conversation for a drawer click. |
| ACP session selection and connection | `AgentRuntimeManager` / `LocalAcpRuntime` | Reuse the selected Conversation/session binding. |
| Prompt admission, turn and cancellation | Canonical ACP runtime prompt reservation | UI navigation must not start, replay, complete, or cancel a turn. |
| `session/update` projection and merge | `AgentEventReducer` | Move the owner as one feature; do not implement a Compose-specific reducer beside it. |
| Active conversation coordination | `ChatConversationRuntimeCoordinator` | Keep this owner until the chat feature migrates completely. |
| Tool calls / approval / terminal response | Existing ACP request lifecycle | Preserve IDs, ordering, late-event handling, and official completion. |

`openLegacyPage` is strictly a temporary **page navigation** request. It creates a
blank Flutter navigation root and pushes one existing feature page. Popping that
page completes the navigation request and closes its Flutter Activity. It neither
transports Agent updates nor changes the business lifecycle.

Quick prompts fill the existing composer after its conversation bootstrap. They
do not send automatically. The established send handler remains the only entry
to prompt admission.

## Visual reference contract

The checked-in Flutter implementation is the reference, not the Miuix demo app:

| Surface | Source | Required measurements |
| --- | --- | --- |
| Palette | `ui/lib/theme/omni_theme_palette.dart` | Preserve every light/dark token. The light drawer uses `AppColors.background` (`#F5F5F5`). |
| Page title | `ui/lib/widgets/common_app_bar.dart` | 44dp toolbar, 56dp leading slot, centered 17sp/600 title. |
| Settings | `ui/lib/features/home/pages/settings/settings_page.dart` | 18dp horizontal margin, 24dp group gaps, 18dp icons, 14sp title, 11sp summary, 1dp inset separators; no cards. |
| Drawer | `ui/lib/features/home/widgets/home_drawer*.dart` | 80% width; 16dp side padding; 36dp search field; 44dp footer capsule. |
| Greeting | `chat/widgets/chat_empty_greeting.dart` | 19sp text, 6dp line gap, 14dp quick-prompt gap, existing animation and placement. |
| Composer/header | `chat/widgets/chat_app_bar.dart`, `command_overlay/widgets/chat_input_*` | Preserve original SVGs, sizing, interactions, and background treatment when the owning feature migrates. |

Do not mark a surface **1:1 accepted** from matching color constants or a successful
build. Compare the same fixture, locale, density, font scale, theme, system insets,
and animation state on the same emulator/device. Keep transient status bars and
random greeting selection out of pixel comparisons.

## Remaining work before default enablement

- Home currently has a navigation entry into the existing composer, not a migrated
  chat input. Full input/voice/attachments, agent selection, workspace switching,
  pet actions, backgrounds, greeting placement/rotation and prompt icons still need
  their owning feature migrated and compared.
- Drawer currently supports the live list, pin ordering, title/last-message search,
  selecting a conversation, archive entry and six footer destinations. Date/mode
  sections, scheduled children expansion, message-content search, swipe actions,
  image previews and Agent Web quick actions remain to be migrated.
- Settings overview and MCP toggle are native. Detail pages, including the local
  service detail sheet, still use the existing feature pages. Workspace-memory
  status currently uses the same persisted initial-render cache as Flutter.
- Native home must gain the launch/foreground behaviors currently owned by
  MainActivity (startup conversation preference, terminal auto-start, task/notification
  hooks, account refresh and app update checks) before becoming the default.
- Validate tablet/foldable layouts, rotation, accessibility text scaling, TalkBack,
  theme/language changes on return, process recreation and predictive-back
  commit/cancel. Then migrate feature pages and finally the single chat projection
  owner. Remove Flutter bootstrap/channels/build tooling only after the last owner
  has moved.

## Verification

Current checkpoint (2026-09-22): the five focused Flutter router tests passed.
Full host compilation, native unit/instrumentation tests, and screenshot comparison
have **not completed**. Build/test processes and the emulator were stopped at the
maintainer's request because of machine load; subsequent verification is manual.
The commands below are provided for the maintainer, not a record of passed checks.

```sh
./gradlew :native-ui:testDebugUnitTest
ANDROID_SERIAL=emulator-5554 ./gradlew :native-ui:connectedDebugAndroidTest
./gradlew :app:assembleDevelopStandardDebug \
  -Ptarget=lib/main_standard.dart -Pomnibot.nativeHome=true
cd ui
flutter test test/core/router/native_home_compatibility_test.dart test/core/router/go_router_config_test.dart
```

The native navigation test exercises Home → Drawer → Settings, saved-state
restoration and returning home, plus conversation-identity hand-off. It writes
light/dark screenshots under the test application's external files directory
(`native-home-verification`) for visual review. Flutter tests check that the
compatibility navigation completes only after its page is popped.
