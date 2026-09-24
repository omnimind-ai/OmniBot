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
      ├─ NativePreferencesViewModel → UiPreferencesStore → existing FlutterSharedPreferences
      ├─ NativeMiscSettingsViewModel → MiscPreferencesRepository → existing keys / platform owners
      ├─ NativeBackgroundViewModel → AppBackgroundRepository + BackgroundPreviewLoader
      ├─ NativePetSettingsViewModel → PetAppearanceRepository → PetPackageInstaller + PetPreviewRenderer
      ├─ :native-ui / NativeHomeApp
      │   └─ one saved miuix-nav stack: Home → Settings / Archive / About / Permissions / Appearance / Background / Pet / HomePreferences / Miscellaneous
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
  pet action playback controls, greeting placement/rotation, prompt icons and background pixel
  comparison still need
  their owning feature migrated and compared.
- Drawer supports the live list, scheduled-parent/child groups, pinned/date/mode
  sections, persisted expansion, title/last-message search, selection with the
  resolved Harness identity, archive/restore and Agent Web quick actions. Full
  message-content search, image previews, rename/delete/copy menus and remaining
  visual differences still need migration/acceptance.
- Settings overview, MCP toggle, local-service detail sheet, About/update and
  permissions, theme/language, home preferences, miscellaneous and background
  image settings and pet appearance are native. Alarm, open-with, quick-start
  and other detail pages still use the existing feature pages. Workspace-memory
  status currently uses the same persisted initial-render cache as Flutter.
- Native home must gain the launch/foreground behaviors currently owned by
  MainActivity (terminal auto-start, account refresh and app update checks)
  before becoming the default. The generic native chat entry now delegates
  startup conversation selection to the existing Flutter chat owner; the
  launcher itself still presents native Home first. NativeHomeActivity
  now attaches to the existing task wake-lock/notification foreground owner.
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
| 3a | Theme/language and home preferences — source implemented | `UiPreferencesStore`, existing `AppLocaleManager` and Flutter controllers/cache | One writer for the existing three keys, native controls, compatibility refresh; see batch 3a. |
| 3b-1 | Miscellaneous settings overview — source implemented | `MiscPreferencesRepository`, `TaskRuntimeSettings`, MMKV, existing Flutter preferences and platform helpers | One native page and shared writes/refresh; see batch 3b-1. |
| 3b-2a | Background image settings — source implemented | `AppBackgroundRepository`, the existing `app_background_config_v1` key and `filesDir/backgrounds` | One writer for native and Flutter settings; see batch 3b-2a. |
| 3b-2b | Pet appearance — source implemented | `PetAppearanceRepository`, existing overlay runtime, workspace pet roots, ZIP validator and preview renderer | Native selection/import/discovery and Flutter compatibility share one owner; see batch 3b-2b. |
| 4 | Storage management, providers, scene models, MCP/plugin settings, Agent configuration | Storage analysis/cleanup currently lives inside `StorageUsageChannel`; provider/model resolution and plugin runtime already have native owners. | Extract storage operations into a reusable repository/service, leaving the channel as an adapter. Reuse configured provider resolution and plugin capabilities; do not duplicate them in page ViewModels. |
| 5 | Chat, composer, tool/approval rendering and conversation runtime | The canonical ACP lifecycle and the single reducer/coordinator described above | Move projection ownership with history/identity/reconnect behavior intact. Do not retain a Dart reducer and add a second Kotlin reducer for the same session. |
| 6 | Remove Flutter | All feature pages and lifecycle owners have migrated | Delete obsolete routes/channels, engine initialization and Flutter build dependencies. Enable the native entry by default only after the remaining launch behavior and visual checks are complete. |

The bounded checkpoints below implement batches 1, 2, 3a, 3b-1, 3b-2a and 3b-2b. Later rows are a
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

## Batch 3a checkpoint: theme, language and home preferences

The source implementation adds Settings → Appearance and Settings → Home Settings
to the same saved Miuix navigation stack. Appearance contains theme and language
selectors plus **Background & Pet**, which opens the existing Flutter page with
`section=background`. That entry hides its duplicate basic selectors. The original
Flutter appearance route still shows them for the default Flutter launcher.
Background images, pet selection and the rest of miscellaneous settings are not
migrated in this batch.

| Existing key in FlutterSharedPreferences | Write owner | Presentation readers |
| --- | --- | --- |
| `flutter.theme_option` | `UiPreferencesStore.setTheme` | Native home/preferences and `AppThemeController` |
| `flutter.language_option` | `UiPreferencesStore.setLanguage` → `AppLocaleManager`, workspace default refresh, widget refresh | Native localized Activity and `AppLocaleController`/`SystemLocaleController` |
| `flutter.home_greeting_settings` | `UiPreferencesStore` semantic mutations under one mutex | Native home/preferences and `HomeGreetingSettingsService.notifier` |

The existing `app_state` method channel adapts get/update operations to this owner.
Flutter no longer writes full cached home snapshots or these theme/language keys
itself. Each home mutation rereads current data and preserves unrelated root fields
and prompt metadata. Reset restores the five built-ins, clears pins and preserves
the greeting setting. Empty prompt lists remain empty; deleted or duplicate pins
are filtered and at most two are retained. Built-ins can be removed but not edited.
New/custom prompts require nonblank names/text and limits of 12/160 graphemes.

`UiPreferencesSync` reloads the existing SharedPreferences cache, theme/language
controllers and home notifier on Flutter startup/resume and before compatibility
navigation. It does not repeat writes or language side effects. Device language
comes from `AppLocaleManager.systemLocale()` through the same channel, rather than
application resources that may already contain an explicit language override.
Native language changes recreate the localized host; Miuix saves the route stack.
The visible native host reuses `StartupThemeResolver` to apply the saved night mode.
Flutter theme writes retain the existing policy of avoiding runtime application
night-mode changes while its Activity is visible.

Miuix `TabRowWithContour`, `Switch`, `TextField`, `IconButton`, `OverlayBottomSheet`
and `OverlayDialog` own interactions. Existing palette, toolbar and list spacing
are reused. Editor text is saveable; a successful save closes it, while a failed
save retains the draft and shows an error. This is source alignment, not a claim
of accepted pixel parity: library control geometry and prompt labels/icons still
need comparison against the Flutter reference on a device.

Manual acceptance for this batch:

1. From native Settings, open Appearance and Home Settings. Check toolbar/system
   back, predictive-back cancel/commit, sheet/dialog dismissal, rotation and
   restoration with an editor draft. Repeat with Chinese/English, light/dark themes,
   a narrow display, large text and the keyboard visible.
2. Select light, dark and system themes; restart the app and enter/return from a
   cached Flutter compatibility page. Change device theme while system mode is
   selected. Check both content and system bars, including Android 10/11 and 12+.
3. Select 简体中文, English and system language. Start with a device language that
   differs from the explicit app language, then return to system. Check native
   resources, Flutter controllers and tool/widget language. Change device language
   while backgrounded and return; unsupported device languages fall back to English.
4. Disable/re-enable the greeting. Add a custom prompt, edit it, and check that the
   existing composer receives the saved text without sending it automatically.
   Try empty/whitespace fields, 12/13 and 160/161 graphemes, and composed emoji.
   Built-ins must have no edit action. Cancel an editor and repeat after rotation.
5. Pin two prompts, try a third, unpin and pin another. Delete a pinned prompt and
   verify pin cleanup. Delete every prompt and restart: the empty list must remain
   empty. Restore defaults and confirm that only prompts/pins reset, not greeting.
6. Alternate edits between the native page and Flutter Home Settings (accessible
   through the remaining Miscellaneous page). Return to an already-created Flutter
   host and check theme, language, greeting, custom text and pinned order. Confirm
   that a newer edit is not overwritten by an older cached snapshot.
7. Check failed saves: the draft must remain available, no success is displayed,
   and retry must work. Open Background & Pet, verify its existing controls still
   work, and return to the native appearance page without duplicate basic selectors.

Source-only verification includes resource/JSON consistency, route and owner
inspection, Dart formatting/parsing and `git diff --check`. Existing test fixtures
were adapted to the shared channel, and `ui_preferences_sync_test.dart` adds a
regression case for restoring an already-cached host. These tests have **not run**.
No compilation, emulator/device interaction, real preference mutation, commit or
push was performed. The finite stopping condition is this source implementation,
lightweight inspection and manual checklist; later batch 3b-1/3b-2 and other migration
work require a new Goal.

## Batch 3b-1 checkpoint: miscellaneous settings

Settings → Miscellaneous now uses the same saved Miuix navigation stack. Its list
keeps the Flutter order, titles, summaries, 18dp icon family and 18dp/24dp page
spacing. Miuix `BasicComponent`, `Switch`, `RadioButton` and `OverlayDialog` own
click, selection and dismissal behavior. The startup and dominant-hand choices use
Miuix selection dialogs. The library switch is 49 × 28dp versus the old 32 ×
18.67dp Flutter switch; this visible difference needs device review, not a scaled
custom gesture implementation.

The nine existing settings retain their actual owners:

| Settings | Existing storage and effect owner |
| --- | --- |
| Startup behavior, recent-only, hide from Recents, independent send, predictive back, dominant hand | Their existing `flutter.*` keys in FlutterSharedPreferences; `MiscPreferencesRepository` serializes this page's writes. |
| Vibration | MMKV `app_vibrate`, as consumed by `VibrationUtil` and other Flutter pages. |
| Prevent sleep and completion notification | `TaskRuntimeSettings` in `OmnibotSettings`; its writes mirror the old FlutterSharedPreferences keys for Flutter cache refresh. The native Activity attaches to its existing foreground/wake-lock lifecycle. |
| Hide from Recents | `RecentTasksVisibility` applies `ActivityManager.appTasks` and commits `flutter.hide_from_recents`. The old dedicated Flutter channel was removed; both Activities apply the saved preference at startup. |
| Completion-notification permission | Existing `AppPermissionAccess` requests Android notification permission before enabling. A denial leaves the setting unchanged. |

The existing `app_state` channel adapts reads and semantic updates. Flutter's
`StorageService` and its Miscellaneous page use that shared entry. It no longer
writes the task key once in Flutter and again through AssistsCore. Returning to a
cached Flutter host reloads preferences, restores the dominant-hand/predictive-back
controllers and notifies the existing conversation sidebar listener if recent-only
changed. The native home observes that key and reruns the existing archive policy.

The native launcher continues to show native Home. Its untargeted composer/voice
entry opens the existing Flutter chat route without a conversation target, so
`ChatPage` applies the stored resume-last/new-conversation choice. Selecting a
specific conversation or quick prompt remains explicit and bypasses that choice.
The native label explains that the preference takes effect when entering chat.

Miscellaneous → Home Settings now opens the native page. Alarm Settings, Open
with Omnibot and Quick Start are explicit typed compatibility destinations;
their existing implementations remain responsible for those workflows. The
Flutter Miscellaneous page is still used by the default Flutter launcher and
refreshes its local values when the app resumes.

Manual acceptance for this batch:

1. Open Settings → Miscellaneous on the native launcher. Check all rows and
   icons in Chinese/English, light/dark, narrow width, large text and rotation.
   Check Miuix selection/dialog/back dismissal and return to Settings.
2. Choose both startup options and enter chat from the native home composer
   and voice entry. Confirm resume-last selects the existing thread and
   new-conversation starts a new draft. Then open an explicit saved conversation
   and quick prompt; neither should be redirected by the startup choice.
3. Toggle recent-only and check that conversations older than seven days move to
   Archive promptly; turn it off and confirm archived conversations are not
   silently unarchived. Enter a cached Flutter sidebar and verify its policy.
4. Toggle hide from Recents, inspect the system task list, restart and verify the
   saved preference applies to both Activity entry points. Check failure leaves
   the displayed switch at its last committed value.
5. Toggle vibration, independent send and dominant hand. Verify MMKV-backed
   vibration elsewhere, the chat keyboard Enter/send behavior and history swipe
   direction after moving between native and Flutter pages.
6. Toggle predictive back. Verify Flutter and terminal behavior when enabled and
   disabled; native Miuix navigation continues to use its system gesture. Exercise
   interrupted/cancelled back gestures on Settings and Miscellaneous.
7. While a task runs, toggle prevent-sleep and check wake-lock/window flag updates
   when native home or Flutter is foreground. Deny/grant Android notifications,
   toggle completion notifications and verify the saved preference and actual
   completion alert behavior.
8. Open native Home Settings, Alarm Settings, Open with Omnibot and Quick Start
   from Miscellaneous; verify their own state, return path and no duplicate Agent
   request/turn.
9. Open the old Flutter Miscellaneous page, change a setting, return to native,
   change it again and return to the cached Flutter host. Confirm the same value
   appears and that a failed save does not show a changed value.

Source-only checks cover Dart formatter parsing, XML/resource names, route and
key ownership, and `git diff --check`. The Flutter cache regression and native
navigation tests were updated in source but **not run**. No build, emulator/device
interaction, real task/notification/Recents setting change, commit or push was
performed for this Goal. Stop after this bounded page; background belongs to
batch 3b-2a and pet appearance to 3b-2b. Storage, model and chat work belongs
to later Goals.

## Batch 3b-2a checkpoint: background image settings

Appearance → Background Image is now a saved Miuix page. It handles the old
`AppBackgroundConfig` fields: enable/source, local image or HTTP(S) URL,
chat/workspace preview, focal point and scale, blur, overlay strength/brightness,
chat text size and automatic/custom color. Miuix owns switches, source buttons,
tabs, sliders, text fields, the color picker and dialogs. Compose's
`transformable` handles preview pan/zoom; page navigation and predictive back
remain owned by miuix-nav. The native Home uses the same saved background image
and mask. The preview chrome follows the Flutter geometry and mask/luminance
formulas, pending side-by-side device comparison.

`AppBackgroundRepository` is the only writer to the existing
`flutter.app_background_config_v1` key. It accepts the same 13 JSON fields as
Flutter's `AppBackgroundConfig.toJson`; no database or preference namespace is
added. Flutter `AppBackgroundService` keeps its notifier and luminance analysis,
but its saves, resets, imports and managed-file deletion call the existing
`app_state` channel. Startup may read the same key locally when the channel has
not attached yet. Entering or resuming a Flutter compatibility page reloads the
stored config; an older pending Flutter draft is cancelled when a newer native
value arrives. The default Flutter appearance route remains complete, while
`section=pet` was the temporary pet-only handoff; batch 3b-2b now opens a
native Pet page. The default Flutter Appearance route remains complete.

The image picker uses Android's photo picker. Imports copy into the same
`filesDir/backgrounds` folder used by Flutter's Android path provider; the
source implementation was checked locally. The repository caps imports at
50 MB, checks PNG/JPEG/WebP/GIF content, stages and atomically moves the file,
and only deletes a direct managed child after a successful config commit or
when an unreferenced import is discarded. Old paths outside that directory
remain untouched. Preview loading is downsampled and runs on IO, with bounded
HTTP(S) redirects, response size and timeouts. Native GIF preview uses its
first frame; Flutter's existing image renderer remains the final display owner
for the compatibility chat/workspace pages. EXIF orientation and any pixel
differences require device review.

Manual acceptance for this batch:

1. Start with each existing Flutter background state: none, local and remote.
   Open native Appearance → Background Image, confirm field values, preview and
   saved route restoration in Chinese/English and light/dark themes.
2. Enable/disable the background, choose a local image with the system picker,
   cancel a picker, replace a selected image, and restart. Verify the new file
   loads in native Home and Flutter Chat/Workspace. Test PNG, JPEG, WebP and GIF;
   observe orientation for portrait camera images.
3. Try an invalid URL, an HTTP URL, an HTTPS URL, a failed response and an image
   exceeding the preview loader's limit. Invalid drafts must not replace the
   committed background; valid saves must appear in both implementations.
4. Drag/pinch both preview tabs and adjust blur, strength, brightness and text
   size. Compare mask, focal point, zoom and text contrast with the same Flutter
   fixture at equal density/font scale. Check swatches, custom hex and the Miuix
   color picker, including invalid hex input.
5. Navigate away during a pending auto-save or import; return and restart.
   Verify the newest committed config persists, failed imports leave no partial
   image, old managed images are cleaned only after replacement, and outside
   files are never deleted.
6. Open the cached Flutter appearance page after editing natively. Its controls
   and chat/workspace visuals must show the latest value, and its older draft
   must not write itself back. Edit there, return to native, and repeat.
7. Open Pet Appearance from native Background Image. Confirm the dedicated
   native page returns correctly; the default Flutter Appearance route still
   contains its existing pet controls through the shared native owner.

Source-only checks cover the 13-field JSON mapping, XML and bilingual resources,
channel method names, compatibility routes, Dart formatting/parsing and
`git diff --check`. No build, test, emulator/device interaction, real settings
change, commit or push was run for this Goal. Stop after this source batch;
pet packages and scanning were assigned to batch 3b-2b below.

## Batch 3b-2b checkpoint: pet appearance

Native Home's pet button and Background Image → Pet Appearance now open a
saved Miuix page. It lists the built-in pet and discovered custom pets with
58dp previews, shows the selected pet, and provides refresh, selection and
system document picking for `.codex-pet.zip`. Miuix owns rows, buttons,
dialog dismissal and navigation; thumbnails load only as their rows appear.
This moves appearance selection without adding an Agent lifecycle or a second
pet state machine.

`PetAppearanceRepository` is the single owner for discovery and selected
appearance. It reads the existing `OmnibotSettings` keys, falls back to the
existing Flutter keys for older installs, and mirrors a successful selection
back to FlutterSharedPreferences. It then asks the existing
`DraggableBallInstance` to refresh. `OverlayChannel` remains a thin adapter for
the Flutter compatibility page and retains show/hide/action operations.
The old Dart directory scanner and Dart ZIP installer were removed; the default
Flutter Appearance page still renders its pet section from the native option
snapshot. Returning to it refreshes that snapshot.

Discovery covers `workspace/.omnibot/pets` and the legacy `workspace/pets`,
package subdirectories, current images, metadata JSON/Markdown and existing
selected images. It keeps PNG, JPEG, WebP and GIF previews, rasterizes atlas
first frames and SVG, and can use image references or an inline SVG from HTML.
Generated previews reuse the former `.omnibot-preview.png` suffix. Atlas pets
still pass their source atlas to the overlay; SVG/HTML-based pets pass a
renderable PNG, including when upgrading an old selected SVG path. Pet identity
stays the same. Preview generation is
bounded and confined to the workspace root. The built-in preview is copied
from the existing Flutter asset.

The native installer checks ZIP size (32 MB), extracted bytes (64 MB), entry
count (64), safe relative paths, exactly one `pet.json`, the pet ID, PNG/WebP
header, and the existing 1536×1872 v1 / 1536×2288 v2 atlas contract.
It stages the selected manifest and spritesheet under the existing pets root,
then swaps a replacement directory with rollback on failure. The option is
selected only after installation succeeds. This is a source implementation;
package compatibility and overlay playback require device verification.

Manual acceptance for this batch:

1. On the native launcher, open Pet Appearance from both Home and Background
   Image. Check the built-in pet, selected label, list spacing, back gestures,
   rotation, Chinese/English, light/dark and large text.
2. Populate both workspace pet roots with supported loose files and package
   directories. Include metadata names/descriptions, preferred `current` files,
   atlas, SVG and HTML reference/inline SVG previews. Refresh and compare the
   option set, order, names and 58dp previews with the existing Flutter page.
3. Select built-in and custom pets, including a v1 and v2 sprite atlas. Check
   that the live overlay refreshes, selection survives restart, and the Flutter
   page returns with the same option selected. Repeat the reverse direction.
4. Import a valid `.codex-pet.zip` with the Android picker, update the same ID,
   cancel the picker, and test invalid ZIP, missing/duplicate manifest,
   traversal paths, oversized entries, invalid image header and atlas/version
   mismatch. Failures must leave the previous selection and package usable.
5. Open the default Flutter Appearance page and import/select there. Verify
   the same native option appears and the overlay reacts without a second
   preference write. Check a cached Flutter page after native changes.
6. Check an old selected path and an old package with generated previews. No
   migration should erase files or silently switch the selected pet. Check
   package replace/rollback and missing-file behavior on device.

Source-only verification includes ZIP/metadata/preview contract inspection,
bilingual resources, bridge methods, Dart parsing and `git diff --check`.
No build, test, emulator/device interaction, real overlay selection/import,
commit or push was performed for this Goal. Stop here; storage/model work and
the chat runtime belong to later Goals.

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
