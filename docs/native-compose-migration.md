# Flutter → Kotlin / Compose migration

## Target and current slice

Preserve OmniBot's existing appearance and behavior while moving its UI to Kotlin
and Jetpack Compose. Miuix provides navigation, gesture handling, popup hosting,
and suitable controls; its default visual style does not replace OmniBot's design.
Keep static appearance aligned with Flutter; use the library's native motion and
gesture behavior rather than reproducing Flutter transition machinery.

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
      │   ├─ ConversationDomainService + Room ConversationDao observation
      │   ├─ existing FlutterSharedPreferences (read compatibility)
      │   ├─ WorkspaceScheduledTaskScheduler (sidebar task relationships)
      │   └─ existing McpServerManager
      ├─ NativeWebActionRepository → OmniPluginHost (metadata-driven actions)
      ├─ NativeAboutViewModel → NativeAboutRepository → AppUpdateManager
      ├─ NativePermissionsViewModel → NativePermissionsRepository → AppPermissionAccess
      ├─ :native-ui / NativeHomeApp
      │   └─ one saved miuix-nav stack: Home → Settings / Archive / About / Permissions
      └─ LegacyHomeNavigator → MainActivity → existing Flutter page
```

- Composables accept immutable snapshots and callbacks. They do not locate
  services, perform I/O, own Activities, or send Agent prompts.
- The Activity collects state with `collectAsStateWithLifecycle`; its ViewModel
  survives configuration changes. Room observes the existing history table.
  Preference observation unregisters its listener when collection ends.
- Miuix owns page transitions, saved route state, and predictive-back commit/cancel.
  `NavDisplay` uses its default transition and dimming; `rememberNavSystemCornerRadius`
  supplies the device corner radius. There is no custom animation, detached event
  dispatcher, or Activity back gate in the native home.
- Miuix 0.9.4 does not provide a side drawer. The existing AndroidX
  `ModalDrawerSheet(drawerState = ...)` overload owns its own predictive-back
  handling; do not add a second `BackHandler` around it. At the root, Android owns
  back-to-home. Drawer state remains scoped to the home entry.
- The legacy `flutter.predictive_back_enabled` preference continues to apply to
  Flutter and the terminal during coexistence. Native home consistently uses
  library/system back behavior, as requested for this migration. Do not delete or
  rewrite the stored preference; retire its UI when its remaining consumers move.
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
- Drawer supports the live list, scheduled-parent/child groups, pinned/date/mode
  sections, persisted expansion, title/last-message search, selection with the
  resolved Harness identity, archive/restore and Agent Web quick actions. Full
  message-content search, image previews, rename/delete/copy menus and remaining
  visual differences still need migration/acceptance.
- Settings overview, MCP toggle, local-service detail sheet, About/update and
  permissions are native. Other detail pages still use the existing feature pages. Workspace-memory
  status currently uses the same persisted initial-render cache as Flutter.
- Native home must gain the launch/foreground behaviors currently owned by
  MainActivity (startup conversation preference, terminal auto-start, task/notification
  hooks, account refresh and app update checks) before becoming the default.
- Validate tablet/foldable layouts, rotation, accessibility text scaling, TalkBack,
  theme/language changes on return, process recreation and predictive-back
  commit/cancel. Then migrate feature pages and finally the single chat projection
  owner. Remove Flutter bootstrap/channels/build tooling only after the last owner
  has moved.

## Next migration batches

Finish complete user flows and remove their compatibility mappings as they move.
The order below follows the actual owners in this repository, not page size alone.

| Order | Scope | Existing owner / prerequisite | Completion boundary |
| --- | --- | --- | --- |
| 1 | Core Home → Drawer → Settings and local-service sheet — source implemented | `ConversationDomainService`, scheduler storage, `OmniPluginHost`, `McpServerManager` | See the batch 1 checkpoint; runtime/visual acceptance remains manual. |
| 2 | About/update and permission pages — source implemented | `AppUpdateManager`, `AppPermissionAccess` and existing platform helpers | See batch 2 below; logs and the guide remain explicit compatibility destinations. |
| 3 | Appearance, miscellaneous, and home preferences | `AppThemeController`, `AppLocaleController`, `HomeGreetingSettingsService`, `AppBackgroundService`, `TaskRuntimeSettings` | Establish one preferences owner with a temporary Flutter reader before enabling Kotlin writes. Migrate theme/language, home prompts, backgrounds and system settings together with their effects. |
| 4 | Storage management, providers, scene models, MCP/plugin settings, Agent configuration | Storage analysis/cleanup currently lives inside `StorageUsageChannel`; provider/model resolution and plugin runtime already have native owners. | Extract storage operations into a reusable repository/service, leaving the channel as an adapter. Reuse configured provider resolution and plugin capabilities; do not duplicate them in page ViewModels. |
| 5 | Chat, composer, tool/approval rendering and conversation runtime | The canonical ACP lifecycle and the single reducer/coordinator described above | Move projection ownership with history/identity/reconnect behavior intact. Do not retain a Dart reducer and add a second Kotlin reducer for the same session. |
| 6 | Remove Flutter | All feature pages and lifecycle owners have migrated | Delete obsolete routes/channels, engine initialization and Flutter build dependencies. Enable the native entry by default only after the remaining launch behavior and visual checks are complete. |

The bounded checkpoints below implement batches 1 and 2. Later rows are a
roadmap, not authorization to continue after a Goal's stopping condition.

Before expanding the home surface, correct its provisional action wiring:
`HomeScreen` currently opens a new chat for the pet button, opens Agent settings
for the agent selector, and maps Workspace to `/home/chat`. These are navigation
placeholders, not migrated feature behavior. Connect each to its real owner or
keep the feature inside the compatibility screen until it is migrated.

Preference migration needs special care: Flutter's `SharedPreferences` instance
and Riverpod controllers retain in-memory state. Writing the same keys from
Kotlin alone will not update that state. Make the compatibility entry refresh
those existing readers from the chosen owner; do not add a general event bus or
another settings store. Preserve values such as `language_option = zhHans`.

Use Miuix controls before writing interactive components. Preserve OmniBot's
palette, typography, assets and page spacing through parameters or small visual
wrappers. Check geometry before replacing the remaining custom `CompactSwitch`:
its Flutter reference is 32 × 18.67dp, while Miuix 0.9.4's switch is 49 × 28dp.
Do not hide a visual mismatch behind a scaled gesture target, or copy Miuix's
animation/gesture internals just to change dimensions.

## Batch 1 checkpoint (completed)

This Goal ended after the following source implementation and hand-off. About
and permissions were subsequently authorized as a separate bounded Goal.

- Drawer grouping projects existing Conversation identities; scheduled groups
  use the scheduler's existing Flutter-compatible storage reader. Expansion keys
  remain compatible with Flutter. Archive/restore has a native route, library swipe
  handling and a long-press Miuix sheet; the legacy archive-page mapping is removed.
- History archiving uses `ConversationDomainService.setConversationArchived`.
  Its update now changes only archival metadata, preserving concurrent message
  counts/checkpoints. **Native archiving changes history visibility; it does not
  close or cancel ACP sessions.** The old Dart helper's best-effort legacy session
  archival/selection cleanup is not reimplemented as another native lifecycle.
- Web actions are discovered by placement metadata. Status and stop IDs are read
  from each plugin definition. There is no polling loop, automatic start, automatic
  stop or manufactured ACP turn. Missing runtime/provider/model results return to
  the existing focused configuration page; URLs/tokens stay in the plugin host.
- Local-service details use `OverlayBottomSheet` under Miuix `Scaffold`. The
  manager owns enable/disable and token rotation. Token values remain in ephemeral
  view state, are redacted from its string representation, and clipboard copies
  are marked sensitive on supported Android versions. The old mapping back into
  Flutter's settings overview is removed.
- Only source/resource checks were performed in this Goal. Regression fixtures
  were updated, but no build, test suite, emulator or device action was run. Full
  functionality and visual 1:1 acceptance remain for the maintainer.

Manual hand-off checklist:

1. With scheduled parents/children, pinned threads and pure-chat records, verify
   ordering, counts, folding, rotation/relaunch restoration and no duplicate rows.
   Delete a schedule through the existing task page and verify its history remains
   discoverable in the ordinary date groups.
2. Archive/restore via swipe (both habitual-hand settings), long press and
   accessibility actions. Check the native archive page, search, the seven-day
   preference, and an actively running conversation. Archiving must not replay,
   cancel or overwrite the running turn. Reopening must select the stored Harness.
3. Verify Web actions only appear for enabled plugin capabilities. Check stopped,
   starting/running, explicit long-press stop, failure feedback and the missing
   runtime/provider/model configuration destinations. Returning from the browser
   should refresh actual status.
4. Enable the local service; open details, copy address/token, refresh the token,
   and confirm the displayed/copied value against the service. Exercise busy and
   failure states, sheet drag/back cancellation, rotation and light/dark themes.
5. Compare the same fixtures with Flutter for typography, spacing, clipping,
   scrolling, brand icons and system insets. This checkpoint does not certify
   pixel equality or migrate unrelated home/composer controls.

The batch 1 Goal was marked complete after this hand-off.

## Batch 2: About/update and permissions (2026-09-23)

Scope is limited to these two pages and their necessary shared adapters:

- `AboutScreen` preserves the original logo asset, compact layout threshold,
  version display, update hint, update confirmation, request/runtime logs, guide,
  beta subscription and CNB/GitHub download-source selection. The native entry
  reads cached status; explicit check/beta actions use `AppUpdateManager`. The
  beta change retains the original follow-up check with cached-state fallback.
- `UpdateConfirmation` displays current/latest version, publication date and
  release notes in a Miuix dialog. Confirmation either opens the release page or
  delegates to the existing notification-permission and APK-installer flow.
  Notification denial does not block the download. Unknown-app-install access
  remains owned by `ExternalApkInstaller`; the user must confirm again after
  granting it. An installer-launch result is not presented as installation success.
- `PermissionsScreen` keeps the four core grants (background, accessibility,
  overlay, installed apps), optional all-files/Shizuku access, overview count and
  application notification preference. `notification_enabled` remains the same
  MMKV boolean; it is not Android's `POST_NOTIFICATIONS` grant.
- `AppPermissionAccess` centralizes the platform operations previously wrapped
  by `SpecialPermissionManager`. The Flutter wrapper now delegates to it as well.
  It uses the existing OEM settings helpers, storage-access helper, accessibility
  environment and Shizuku manager. Opening Settings never marks a grant successful.
  Accessibility uses the environment's existing bounded readiness wait (4 seconds)
  after return. Shizuku distinguishes installation, Binder/running state and grant.
- Page ViewModels are separate from the home ViewModel and are Activity-scoped.
  This preserves in-flight installation state across page navigation and configuration
  changes; it does not create another downloader. UI collection and status refresh
  follow the Miuix entry lifecycle using AndroidX lifecycle APIs. A process restart
  reads persisted state and does not automatically repeat an installation or request.
- `HomeRoute.About` and `HomeRoute.Permissions` replace their legacy destination
  mappings. Logs still open the existing Flutter pages. The fixed
  `/my/about/user-guide` route is shared by Flutter and native About, preserves
  the localized documentation URL/back behavior, and avoids arbitrary URL extras.
- Miuix owns component press states, switches, radio selection, progress indicators,
  dialogs/sheets and predictive back. `PreferenceRow` is only a typography/spacing
  wrapper over `BasicComponent`. Switches use Miuix's 49 × 28dp geometry with
  OmniBot colors; the old Flutter switch dimensions are not reproduced with scaling
  or another gesture implementation. Pixel equality still needs visual acceptance.

Manual acceptance for this batch:

1. Open both pages from native Settings, rotate, navigate back and repeat with
   light/dark themes and large text. Check the compact About layout, long English
   permission labels, source picker, modal dismissal and predictive back.
2. Check cached/empty update state, explicit check, no update, new update and
   network failure. Toggle beta, return from other pages, and verify the existing
   stored preference/download source remains authoritative in both UI implementations.
3. Confirm an update with and without a direct APK URL. Check notification denial,
   install-source permission denial/grant, download failure, installer cancellation
   and returning to About while an operation is running. Do not interpret opening
   the installer as a completed installation.
4. Verify request logs, runtime logs and both localized guide URLs; their back
   action must return to native About through the existing compatibility host.
5. Toggle the application notification preference independently of system permission.
   For each core/optional permission, enter Settings, return both without granting
   and after granting, and verify the displayed state/count comes from the platform.
6. Exercise accessibility disabled/connecting/ready, denied settings navigation,
   and cancel during the readiness check. Exercise Shizuku not installed, not
   running, denied, adb/root granted and Binder loss, including a running Sui
   backend without a Shizuku launcher. No shell health probe is part of page refresh.

Stopping condition: finish this two-page source migration, inspect API/resource/
route wiring, update this checklist and report unverified behavior; then complete
the Goal and stop. Do not automatically proceed to appearance, miscellaneous,
storage, model configuration or chat. No build, test, emulator/device interaction,
actual update check/download/install, commit or push was run for this Goal.

## Verification

Historical baseline (2026-09-22): the five focused Flutter router tests passed.
Those results do not validate the subsequent migration batches.
Full host compilation, native unit/instrumentation tests, and screenshot comparison
have **not completed**. Build/test processes and the emulator were stopped at the
maintainer's request because of machine load; subsequent verification is manual.
The commands below are provided for the maintainer, not a record of passed checks.
The subsequent navigation simplification was inspected in source only; no build,
test or emulator was started. Manual checks should cover drawer back cancel/commit,
Settings back cancel/commit, toolbar back, root back-to-home, saved route restoration,
and devices with both rounded and square screens.

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
