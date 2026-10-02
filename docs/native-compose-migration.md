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
      ├─ NativeStorageUsageViewModel → StorageUsageRepository ← Flutter StorageUsageChannel
      ├─ NativeLogsViewModel → existing AiRequestLogStore / RuntimeLogStore
      ├─ NativeWorkspaceMemoryViewModel → WorkspaceMemoryService + WorkspaceMemoryRollupScheduler
      ├─ NativeSceneModelsViewModel → SceneModelSettingsRepository + ProviderModelCatalogService
      ├─ NativeModelProviderViewModel → ProviderEditorRepository + ModelProviderConfigStore
      ├─ NativeRemoteMcpViewModel → RemoteMcpConfigService → RemoteMcpConfigStore / RemoteMcpDiscoveryRegistry
      ├─ NativeAgentsViewModel → NativeAgentsRepository → AgentRuntimeManager method boundary
      ├─ NativeAgentConfigViewModel → NativeAgentsRepository + SceneModelSettingsRepository
      ├─ NativeAlarmSettingsViewModel → NativeAlarmSettingsRepository → AgentAlarmToolService (MMKV)
      ├─ NativeOpenWithViewModel → SharedOpenPreferenceStore
      ├─ NativeRemoteBridgeViewModel → NativeRemoteBridgeRepository → AgentRuntimeManager config/remote/*
      ├─ NativeScheduledTasksViewModel → NativeScheduledTasksRepository → WorkspaceScheduledTaskScheduler / AgentAlarmToolService
      ├─ NativeSkillStoreViewModel → SkillIndexService (registry + workspace skill dirs)
      ├─ NativeUsageStatisticsViewModel → NativeUsageStatisticsRepository → ConversationDomainService / TokenUsageRecordDao (read-only)
      ├─ :native-ui / NativeHomeApp
      │   └─ one saved miuix-nav stack: Home → Settings / Archive / About / Permissions / Appearance / Background / Pet / HomePreferences / Miscellaneous / AlarmSettings / OpenWith / Storage / RequestLogs / RuntimeLogs / WorkspaceMemory / SceneModels / ModelProviders / McpTools / Agents / AgentConfig(agentId) / RemoteBridge / ScheduledTasks / ExecutionHistory / Skills
      └─ LegacyHomeNavigator → MainActivity → existing Flutter page
```

- Shared UI lives in `native-ui/.../components/` and wraps Miuix controls with
  their default sizing and haptics: `OmniPage` (Scaffold, SmallTopAppBar,
  one-shot snackbar notice), `OmniIconButton`, `OmniSwitch`, `OmniTabRow`,
  `OmniChoiceRow`, `OmniConfirmDialog` / `OmniNoticeDialog` /
  `OmniDialogActions`. New pages use these instead of hand-drawn rows,
  per-screen color overrides or Flutter pixel replicas.
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
  permissions, theme/language, home preferences, miscellaneous, background
  image settings, pet appearance, storage management, the two log pages and
  workspace-memory settings, scene-model bindings/voice autoplay, the
  Provider editor, remote MCP tool settings and the Agent mode list are native.
  The per-Agent configuration editors (Codex, Claude Code, OpenCode, DeepSeek
  Harness and custom launch profiles) are native as well; the remote PC Bridge
  settings page is native (its QR scan entry temporarily hands off to the
  Flutter page, see batch 4h-1); the scene page's Agent-avatar editor remains a
  compatibility destination; native avatar previews read the existing keys and
  packaged Flutter preset assets until the avatar feature moves.
  Alarm and open-with are native; quick-start
  and other detail pages still use the existing feature pages. Workspace-memory
  status currently uses the same persisted initial-render cache as Flutter.
  The chat header's inline Agent quick-switcher still belongs to the chat
  migration; the home agent button opens the native Agents page meanwhile.
  The scheduled tasks page (list, edit sheet, exact-alarm tab) is native; its
  drawer entry opens the native route. The drawer's 轨迹 (usage statistics)
  page is native as well (batch 4i-2). The skill store page is native
  (batch 4j); of the drawer shortcut row, only the memory center still opens a
  Flutter compatibility page. The OmniFlow execution center
  (`/task/omniflow`, entered from tool-summary cards and manual recording) and
  the remote workspace browser remain Flutter compatibility destinations.
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
| 2 | About/update and permission pages — source implemented | `AppUpdateManager`, `AppPermissionAccess` and existing platform helpers | See batch 2 below; the guide remains a compatibility destination and logs moved in batch 4a. |
| 3a | Theme/language and home preferences — source implemented | `UiPreferencesStore`, existing `AppLocaleManager` and Flutter controllers/cache | One writer for the existing three keys, native controls, compatibility refresh; see batch 3a. |
| 3b-1 | Miscellaneous settings overview — source implemented | `MiscPreferencesRepository`, `TaskRuntimeSettings`, MMKV, existing Flutter preferences and platform helpers | One native page and shared writes/refresh; see batch 3b-1. |
| 3b-2a | Background image settings — source implemented | `AppBackgroundRepository`, the existing `app_background_config_v1` key and `filesDir/backgrounds` | One writer for native and Flutter settings; see batch 3b-2a. |
| 3b-2b | Pet appearance — source implemented | `PetAppearanceRepository`, existing overlay runtime, workspace pet roots, ZIP validator and preview renderer | Native selection/import/discovery and Flutter compatibility share one owner; see batch 3b-2b. |
| 4 | Storage management, workspace memory, providers, scene models, MCP/plugin settings, Agent configuration | Storage analysis/cleanup now lives in `StorageUsageRepository`; the channel is a Flutter adapter. Workspace memory has its own service and scheduler. Provider/model resolution and plugin runtime already have native owners. | Storage/logs, workspace memory, scene bindings, Provider editor and remote MCP tools are bounded slices below. Reuse configured provider resolution and plugin capabilities in later slices; do not duplicate them in page ViewModels. |
| 5 | Chat, composer, tool/approval rendering and conversation runtime | The canonical ACP lifecycle and the single reducer/coordinator described above | Move projection ownership with history/identity/reconnect behavior intact. Do not retain a Dart reducer and add a second Kotlin reducer for the same session. |
| 6 | Remove Flutter | All feature pages and lifecycle owners have migrated | Delete obsolete routes/channels, engine initialization and Flutter build dependencies. Enable the native entry by default only after the remaining launch behavior and visual checks are complete. |

The bounded checkpoints below implement batches 1, 2, 3a, 3b-1, 3b-2a, 3b-2b and the bounded slices of 4 through 4i-2. Later rows are a
roadmap, not authorization to continue after a Goal's stopping condition.

## Batch 4j checkpoint: skill store (source complete; device acceptance pending)

- The drawer's Skills entry now opens the saved native `Skills` route. The page
  keeps the Flutter structure: search field (name/description), skill rows with
  the built-in/official badge, status summary labels, removed-built-in note,
  installed path + delete action, enable switch / install action with busy
  indicators, the official-repository sync button in the toolbar with a busy
  spinner, and the empty/search-empty states.
- **Owner conclusion**: the `agentSkill*` channel handlers in
  `AssistsCoreManager` construct `SkillIndexService(context,
  AgentWorkspaceManager(context))` per call. That service owns the
  `.skill_registry.json` registry and the workspace skill directories
  (including the built-in seed/prune and the official repository git sync via
  the embedded terminal runtime). The native ViewModel calls the same owner
  directly with the same constructor shape — no second store, no channel
  change. `WorkspaceStorageAccess.isGranted` is currently always true; the
  permission-error branch of the handlers is preserved for the Flutter page.
- Sorting matches the owner's `listSkillsForManagement` and the Flutter page:
  installed first, then built-in/official/other, then name. Toggle replaces
  the row in place; install replaces and re-sorts; delete reloads; sync
  replaces the list with the sorted result and shows the count. The
  `agentSkillInstall` (sourcePath) method has no caller in this page and was
  not needed.
- `Page.Skills`, `Page.Storage`, `Page.RequestLogs`, `Page.RuntimeLogs`,
  `Page.WorkspaceMemory` and `Page.McpTools` had no remaining callers (grep
  verified) and were removed with their `LegacyHomeNavigator` mappings.
  `Page.ModelProviders` (pendingDestination) and `Page.SceneModels` (avatar
  hand-off) stay. The drawer shortcut row now leaves only the memory center on
  a compatibility page.
- The page uses the shared Omni components (`OmniPage`, `OmniSwitch`,
  `OmniConfirmDialog`). Compilation (`:app:compileDevelopStandardDebugKotlin`,
  `:native-ui:testDebugUnitTest`,
  `:native-ui:compileDebugAndroidTestKotlin`) and `git diff --check` are the
  verification boundary. No device interaction, install, delete or sync was
  run. Visual parity and runtime acceptance remain pending.

Manual acceptance checklist:

1. Compare both themes and languages: toolbar sync button, search field, row
   typography, badges, status labels, switch/install trailing, dividers, empty
   and search-empty states, insets and predictive back.
2. Toggle a skill off/on; install a removed built-in skill; delete a
   user-installed skill with confirmation (and cancel). Confirm the Flutter
   page (default launcher) shows the same states afterwards, and the Agent
   runtime's enabled-skill list matches.
3. Sync the official repository against a healthy and a failing network/bridge
   (or with the terminal runtime missing); confirm the busy spinner, the count
   toast and the failure notice, and that the list updates or stays intact
   accordingly.
4. Alternate toggle/delete/install operations between the native and Flutter
   pages; both lists and sort order must converge.
5. Rotate and recreate the process while a sync is running and with a delete
   dialog open; no duplicate sync starts and no phantom deletion.

## Batch 4i-2 checkpoint: execution history (source complete; device acceptance pending)

- Scope correction: the drawer's history entry (`omni_history`, 轨迹/Activity)
  mapped to Flutter `/task/execution_history`, which renders
  `UsageStatisticsPage` (conversation heatmap plus weekly token bars), not the
  OmniFlow execution center. With the maintainer's confirmation this batch
  migrates 轨迹. The OmniFlow execution center (`/task/omniflow`: Function
  list, run logs, detail sheet, replay/enhance/delete through
  `OmniFlowToolChannel`) has no native entry point and stays in Flutter.
- The drawer entry now opens the saved native `ExecutionHistory` route.
  `Page.ExecutionHistory` had no remaining callers (`grep` confirmed only the
  drawer and `LegacyHomeNavigator`) and was removed with its mapping. The
  Flutter route itself is unchanged and still serves the default launcher.
- **Ownership**: read-only. Conversations come from
  `ConversationDomainService.listConversationPayloads(includeArchived = true)`,
  the same path as the `getConversations` channel without `archiveBefore`, so
  opening the page never archives anything. Hidden Agent conversations are
  filtered with the same preference keys as Flutter `ConversationService` and
  `NativeHomeRepository`. Token usage comes from
  `DatabaseHelper.getTokenUsageRecordsSince` (the `getTokenUsageRecords`
  channel owner). No store, channel, polling loop or write was added; the page
  refreshes on entry resume.
- `UsageStatisticsAggregation` (native-ui, pure, JVM unit-tested) ports the
  card's rules exactly: 16-week window, Monday-aligned grid, streak back from
  today, `reasoning + text` tokens with the `completion` fallback,
  `normalizeModelId`, model order (tokens desc, then id), per-week segment
  order, `x.xK/x.xM` formatting, intensity buckets and the Dart code-unit hash
  for model colors. The ViewModel runs it on `Dispatchers.IO`; state holds
  counts and model ids only.
- UI keeps the Flutter layout and colors (stats pills, heatmap/legend/bar
  palettes, 11sp tooltips on `#2D3032`/`#353E53`, skeleton, 600ms fade, 300ms
  tab cross-fade, empty-token text). The custom sliding segmented control is
  replaced by Miuix `TabRowWithContour` (Omni segment colors), and Flutter tap
  tooltips by Miuix `TooltipBox` shown on tap. The 18dp page margin replaces
  the card's 20dp padding. Lucide message-circle, flame, zap, network and
  refresh-ccw were added for the pills. Native English uses "cached" where the
  Flutter localizer left 缓存 untranslated.
- Compilation (`:app:compileDevelopStandardDebugKotlin`,
  `:native-ui:testDebugUnitTest`, `:native-ui:compileDebugAndroidTestKotlin`)
  and `git diff --check` are the verification boundary; the new aggregation
  tests and the drawer → 轨迹 → back navigation test compile (the former also
  ran on the JVM). No device interaction or database access was run. Visual
  parity and runtime acceptance remain pending.

Manual acceptance checklist:

1. Compare both themes and both languages against the Flutter page with the
   same data: skeleton, stats pills (streak color at ≥3 days, models/cached
   pills appearing only when non-empty), tab switch, heatmap month/day labels
   and cell colors, legend strip scrolling, stacked bars, empty-token state.
2. Tap heatmap cells and bars: tooltip text (count/date, week range, up to six
   models, `+N`, cached line, 无消耗/No usage) and placement near screen edges.
3. Alternate with the Flutter page: create/archive/hide an Agent conversation
   and run a model call, then confirm both pages show the same totals after
   returning (resume refresh); confirm opening the native page never archives.
4. Check a day boundary and a timezone change: today's cell, the streak and the
   week bucket of late-night records must match Flutter.
5. Rotate and recreate the process on the Token tab; the page must reload
   without flicker loops, and predictive back (commit and cancel) must return
   to Home with the drawer closed.
6. Narrow/wide widths and large font scale: 720dp content cap, cell/bar
   clamping (4–14dp) and the pill row wrapping.

## Batch 4i-1 checkpoint: scheduled tasks (source complete; device acceptance pending)

- The drawer's Scheduled entry now opens the saved native `ScheduledTasks`
  route. The page keeps the Flutter structure: the 定时任务/闹钟列表 tab switch,
  task rows (SubAgent badge, notifications-off badge, underlined schedule text
  opening the editor, daily chip, relative next-run text, expired dimming,
  delete with confirmation), the exact-alarm rows with delete confirmation, and
  both empty states. `Page.ScheduledTasks` had no remaining callers and was
  removed with its mapping; execution history stays a compatibility
  destination.
- **Ownership**: `WorkspaceScheduledTaskScheduler` remains the only
  scheduling/storage owner. The page lists `listTasks()` and edits through
  `upsertTask` with the same full-field payload shape as the Flutter page's
  `task.toJson()`; the owner's own `resolveNextExecutionAt` re-validates fixed
  times (next-day roll) and keeps fresh countdown values, and its
  upsert/delete already mirror into the Flutter preference store, so both
  launchers see the same list. Exact alarms read/delete through
  `AgentAlarmToolService` (`listExactReminders`/`deleteExactReminder`). No
  AlarmManager calls, timers, or a second store were added.
- The edit sheet keeps its Flutter scope: it edits the schedule of an existing
  task only (fixed time via two Miuix NumberPickers, countdown stepper with a
  1–1440 numeric dialog, daily-repeat switch on the fixed tab); there is no
  create-new flow because the Flutter sheet has none. The confirm payload keeps
  every untouched field and `isEnabled = true`, exactly like the Flutter sheet.
- Sheet draft state is sheet-local; the ViewModel computes the next execution
  time with the Dart formula (the owner recomputes fixed times itself) and
  shows the updated notice with the display text. Relative/day roll formatting
  uses the app locale via the existing native-locale resolver.
- Compilation (`:app:compileDevelopStandardDebugKotlin`,
  `:native-ui:testDebugUnitTest`, `:native-ui:compileDebugAndroidTestKotlin`)
  and `git diff --check` are the verification boundary. No device interaction,
  alarm creation or scheduling change was run. Visual parity and runtime
  acceptance remain pending.

Manual acceptance checklist:

1. Compare both themes and languages: tab switch, task rows (badges, underlined
   schedule text, daily chip, next-run text, expired dimming), alarm rows, empty
   states, editor sheet, insets and predictive back.
2. Edit a fixed-time task across midnight and a timezone/DST boundary; edit a
   countdown task; toggle daily repeat. Confirm the owner's computed
   `nextExecutionTime` in both UIs and that the alarm still fires once (and
   reschedules when daily).
3. Delete a task and an alarm with confirmation; cancel both dialogs. Confirm
   the Flutter page (default launcher) shows the same list afterwards.
4. Let a one-shot task fire and verify it disappears from both pages; let a
   daily task fire and verify it reschedules. Repeat after process death and
   reboot (boot receiver reschedules).
5. Alternate edits between the native page and the Flutter page; both lists and
   the drawer scheduled groups must converge.
6. Rotate and recreate the process with the editor open and mid-countdown
   input; drafts and unsaved edits must not write themselves.

## Batch 4h-1 checkpoint: remote PC Bridge settings (source complete; device acceptance pending)

- The Agents page's remote PC Bridge row now opens the saved native
  `RemoteBridge` route. The page keeps the enable switch, Bridge URL, masked
  Token with reveal toggle, remote cwd, the four-state autosave status line
  (required-fields / pending / saving / saved), the test-connection action and
  the remote directory picker as an `OverlayBottomSheet` with home/parent/
  reload navigation, per-directory rows, error retry and empty state.
- **Autosave semantics**: edits cancel only the 700 ms debounce job; the
  in-flight write job is never cancelled by further edits and re-arms a
  trailing debounce when the form changed during the write (the Flutter page
  could leave such an edit pending until the next keystroke). The saved
  signature is the server's trimmed response; programmatic refills never pass
  through the edit actions, so no `_syncing` guard is needed. The ViewModel is
  Activity-scoped, so leaving the page cannot discard a committed write; a
  process death drops the pending debounce like the Flutter page's dispose.
- **Ownership**: `NativeRemoteBridgeRepository` adapts `config/remote/read`,
  `config/remote/write`, `config/remote/test` and `config/remote/fs/list`; the
  store and the runtime's remote-session teardown after a config write are
  unchanged. A successful write also refreshes the existing
  `flutter.remote_bridge_enabled` first-frame cache read by the Agents page.
  The token is masked by default and stays out of state/log string forms.
- **QR scan temporary wiring**: the native page's Scan QR button opens the
  Flutter compatibility page (`Page.RemoteBridge`, retained for exactly this
  hand-off), whose scanner autosaves through the same store; returning re-reads
  `config/remote/read` on resume, unless the native form holds an unsaved draft
  (the draft then wins). A native scanner waits for the camera/scan dependency
  decision (CameraX+ML Kit or ZXing, targeting non-GMS devices) in batch 4h-2.
- Compilation (`:app:compileDevelopStandardDebugKotlin`,
  `:native-ui:testDebugUnitTest`, `:native-ui:compileDebugAndroidTestKotlin`)
  and `git diff --check` are the verification boundary. No device interaction,
  bridge connection or real network request was run. Visual parity and runtime
  acceptance remain pending.

Manual acceptance checklist:

1. Compare both themes and languages: enable row, three fields, token mask
   toggle, outlined scan/test buttons, status line colors, insets and
   predictive back. Repeat on narrow windows and with large text.
2. Type into URL/token/cwd and toggle the switch: confirm the status moves
   pending → saving → saved roughly 700 ms after the last keystroke, and that
   typing during a write still results in one final save (no lost trailing
   edit). Toggle enable with missing URL/cwd and check the incomplete hint.
3. Test connection with valid and invalid bridges, with the form saved and
   unsaved (the probe uses the current form values).
4. Open the directory picker with and without a stored cwd; navigate into
   child directories, home and parent; reload; select the current directory
   and confirm the cwd field adopts it and autosaves. Cover bridge offline,
   HTTP error and empty directory with retry.
5. Scan a QR code through the Flutter hand-off page and return: the native
   form must show the scanned values and autosave state. Repeat with an
   unsaved draft open on the native page and confirm the draft is kept.
6. Alternate edits with the Flutter page under the default launcher and
   confirm the Agents row's enabled state and both editors agree. Rotate and
   recreate the process with an unsaved draft and with the picker open.

## Batch 4g checkpoint: alarm and open-with settings (source complete; device acceptance pending)

- Miscellaneous → Alarm Settings and Miscellaneous → Open with Omnibot now open
  saved native routes. `Page.Alarm`/`Page.OpenWith` had no remaining callers, so
  both enum entries and their legacy mappings were removed; Quick Start stays a
  compatibility destination.
- **Alarm page ownership**: the MMKV record (`agent_alarm_sound_settings_v1`)
  and its validation stay in `AgentAlarmToolService`; the page's repository only
  adapts it. **URI decision**: the consumer (`AgentAlarmRingingService`) plays
  the selection through `MediaPlayer.setDataSource(context, uri)`, which accepts
  content URIs and falls back to the default alarm sound on failure. The native
  picker therefore stores the SAF document's `content://` URI with
  `takePersistableUriPermission` instead of copying bytes (the Flutter page's
  file_picker path was already an app-cache copy, and a path copy would add a
  second storage owner). A provider that rejects the persistable grant still
  leaves the pick usable for the process lifetime; playback then falls back to
  the default ringtone. The row displays the document's display name for
  content URIs and the raw stored value for legacy file paths written by the
  Flutter page. The READ_MEDIA_AUDIO/READ_EXTERNAL_STORAGE request before
  picking matches the Flutter flow and is centralized in
  `AppPermissionAccess.requestAudioReadPermission`, even though SAF itself
  needs no permission.
- **Open-with ownership**: `SharedOpenPreferenceStore` remains the single
  owner; the page calls it directly (no channel). Selection is optimistic, the
  store's normalized return value is authoritative, and a rejected mode rolls
  back with the save-failed notice, as on the Flutter page. The mode dropdown
  uses the Miuix `OverlayDialog` choice pattern from Miscellaneous/batch 4f-2;
  the loading placeholder matches the Flutter spinner position.
- Compilation (`:app:compileDevelopStandardDebugKotlin`,
  `:native-ui:testDebugUnitTest`, `:native-ui:compileDebugAndroidTestKotlin`)
  and `git diff --check` are the verification boundary. No emulator/device
  interaction, no real file pick and no permission request was run. Visual
  parity and runtime acceptance remain pending.

Manual acceptance checklist (alarm):

1. Compare both themes and languages: three source rows with radio + selected
   check, dividers, local file section (display name, 3-line ellipsis, outlined
   pick button), remote URL field, save button, insets and predictive back.
2. Pick an MP3 with the system picker after granting and after denying the
   audio permission; cancel the picker; confirm the local section shows the
   document name and the source switches to Local MP3.
3. Save each source; trigger an exact alarm (or let one fire) and confirm the
   chosen sound plays — including after process restart and reboot (persisted
   grant), with fallback to the default alarm sound when the document becomes
   unavailable.
4. Save with no local file selected and with a non-HTTP(S) URL: validation
   notice, no write. Save a valid remote URL and confirm playback streams.
5. Set a local path from the Flutter page (default launcher), then open the
   native page: the stored path displays and remains intact through a native
   save. Rotate and recreate the process with an unsaved draft.

Manual acceptance checklist (open-with):

1. Compare both themes and languages: section header, image/file rows, dropdown
   labels per target, subtitles per mode, loading spinner position, insets and
   predictive back.
2. Switch each target between default and workspace; confirm the store value
   changes, the subtitle follows, and the Flutter share-in flow (chat input
   attach vs LAN link vs workspace path) behaves accordingly.
3. Alternate edits with the Flutter page under the default launcher and confirm
   both surfaces show the same mode.
4. Rotate and recreate the process while the choice dialog is open; confirm the
   dialog dismisses or restores without a stray write.

## Batch 4f-2 checkpoint: agent config editors (source complete; device acceptance pending)

- The Agents page's `配置 >` entry now opens the saved native
  `AgentConfig(agentId)` route instead of the Flutter compatibility page. The
  editor keeps the five adapter-owned kinds: `codex` (shared Provider/model
  selector + official config/auth path note), `json` (Claude settings.json with
  a pre-save JSON-object check), `jsonc` (OpenCode, no JSON check),
  `deepseek-harness` (selector + reasoning-effort and permission-mode
  selection dialogs), and `profile` (command / per-line arguments / per-line
  `KEY=VALUE` environment / enable switch), plus the custom-Agent delete
  confirmation. Xiaowan and Kimi Code resolve to the `profile` kind through
  the existing adapter fallback, exactly as on the Flutter page.
- `NativeAgentsRepository` now also adapts `agent/config/read` and
  `agent/config/write`. The runtime's `expectedRevision` optimistic lock is
  passed through unchanged; its "Agent config changed concurrently" rejection
  is recognized by message and surfaces a conflict notice while keeping the
  draft. The read payload's `apiKey`/`baseUrl`/`model` are dropped at the
  repository boundary because no native editor renders them; `content` and
  launch environment stay out of state/log string forms.
- The shared Provider/model selector reads `scene.dispatch.model` through
  `SceneModelSettingsRepository` (new read-only `dispatchBinding()` plus the
  existing `saveBinding`, which retains its provider-change invalidation side
  effect). Like the Flutter page, a successful binding save then calls the
  runtime's existing `AgentRuntimeManager.disconnect()` — no new teardown
  path. Page load reads persisted catalogs only; opening the picker refreshes
  configured Providers live with the catalog service's revision check, and a
  failed fetch keeps the persisted/manual list with a retry row, mirroring
  the Flutter selector's per-provider fallback.
- List refresh after native config edits relies on the navigation entry
  lifecycle: returning to Agents re-fires `ON_RESUME`, which re-reads the
  cached catalog. The Flutter page's `PopScope` changed-flag exists only for
  the compatibility route and is not replicated.
- `LegacyDestination.AgentConfig` and its `/home/agent_config/{id}` mapping
  had no remaining callers and were removed. The default Flutter launcher and
  its Dart pages are unchanged; `remote_codex_setting` stays a compatibility
  destination.
- Compilation (`:app:compileDevelopStandardDebugKotlin`,
  `:native-ui:testDebugUnitTest`) and `git diff --check` are the verification
  boundary. No emulator/device interaction or network request was run. The
  Codex kind's save button intentionally keeps the Flutter behavior of calling
  `agent/config/write` with only `expectedRevision`; whether that write is
  meaningful is owned by the runtime adapter, not by either page.

Manual acceptance checklist:

1. Open each built-in editor (Codex, Claude Code, OpenCode, DeepSeek Harness)
   and a custom Agent in both themes and languages. Compare titles, subtitles,
   selector, fields, dropdowns, save button, insets and predictive back;
   repeat after rotation and process recreation (drafts restore from the
   saved state, not from a stale snapshot).
2. Edit and save the Claude settings.json (valid and invalid JSON), the
   OpenCode JSONC and the DSH reasoning/permission options. Confirm the saved
   values reappear on the Flutter page and vice versa, and that a failed save
   keeps the draft.
3. Change the same Agent's config in Flutter while the native editor is open,
   then save natively: the revision conflict notice must appear and the draft
   must survive; reopening the page must show the newer content.
4. Change the shared Provider/model from the native editor, confirm the
   Agents page summary and the Flutter scene/chat owners see the new binding,
   and the running ACP process is torn down through the existing disconnect.
   Repeat with a failing Provider catalog fetch (persisted list + retry).
5. Edit a custom Agent's command/arguments/environment and enable switch;
   verify the Agents list reflects them on return. Delete a custom Agent with
   confirmation and confirm the list no longer shows it; cancel the dialog
   and confirm nothing changes. Environment values must not appear in logs.
6. Verify the Codex save behavior matches the Flutter page exactly (same
   outcome for the same runtime state), and that xiaowan/Kimi Code open the
   launch-profile editor with the delete action hidden.

## Batch 4f-1 checkpoint: agent list (source complete; device acceptance pending)

- Settings → Agent Mode and the home agent button now open the saved native
  `Agents` route. The page mirrors the Flutter Agent list: toolbar refresh probe
  and add-custom editor (name / command / per-line arguments / per-line
  `KEY=VALUE` environment / enable switch), the read-only shared dispatch-model
  summary, local name/description/command search, the all/available/unavailable
  segmented filter with counts, built-in and custom sections with status
  dot/label, plugin-capability or description or monospace command subtitles,
  classified error lines, install/recheck actions with Miuix bottom-sheet
  results, `配置 >` entries, the `agent_settings` Web-action section with
  open/stop and running/starting status, and the remote PC Bridge row.
- `NativeAgentsRepository` only adapts `AgentRuntimeManager.handleMethod`
  payloads (`agent/list`, `agent/refresh`, `agent/save`, `agent/delete`,
  `agent/test`, `agent/prepare`, `config/remote/read`). No channel or runtime
  internals changed. Commands, arguments and environment values stay out of
  list state and logs; `config/remote/read` contributes only its enabled flag,
  cached under the existing `flutter.remote_bridge_enabled` key for the first
  frame, exactly as the Flutter page does.
- **prepare in-flight ownership**: the runtime's `ManagedAcpPreparationGate`
  already serializes managed installs and reports `harness_preparation_in_progress`.
  The native page therefore keeps no second retry/poll/owner: the ViewModel
  marks a per-agent busy flag in view state, triggers `agent/prepare` once, and
  re-reads `agent/list` when it finishes. The Dart-side
  `prepareAgentInBackground` registry remains the Flutter page's owner and is
  untouched. A process death mid-install is recovered by the next cached
  `agent/list` health read, not by replaying the install.
- Error text reuses `AgentRuntimeErrorSupport.failureKind` classification (for
  Throwables directly; for stored `lastCheckError`/result strings by wrapping
  the raw text) and maps the kind to bilingual resources. No parallel error
  mapping was added; unknown kinds render the same fallback text as Flutter.
- The unified-model summary reads `scene.dispatch.model` through the migrated
  `SceneModelSettingsRepository`/`SceneModelCatalogResolver` path; no new read
  path was created. The Xiaowan row icon reuses the shared avatar preview
  reader (existing Flutter keys and packaged presets), now shared with the
  scene page.
- `NativeWebActionRepository` gained an `agent_settings` placement listing
  (same status/stop actions as the drawer quick actions, long label and
  description); its `invoke` re-resolution accepts both known placements.
  Provider/model/runtime-missing results keep the existing owner: a notice plus
  the native Model Providers route or the terminal settings compatibility page.
- The remote Bridge row remains a typed compatibility destination
  (`Page.RemoteBridge` → `/home/remote_codex_setting`). The `配置 >` row moved to
  the native `AgentConfig` route in batch 4f-2; `LegacyDestination.AgentConfig`
  and `Page.Agents` had no remaining callers and were removed.
- Compilation (`:app:compileDevelopStandardDebugKotlin`,
  `:native-ui:testDebugUnitTest`) and `git diff --check` are the verification
  boundary for this batch. No emulator/device interaction, network request or
  real install/probe was run. Visual parity and runtime acceptance remain
  pending.

Manual acceptance checklist:

1. Compare the Flutter and native Agent pages in both themes and both
   languages: toolbar actions, managed-model summary, search field, filter
   counts, brand icons, status dots/labels, subtitles, error lines, dividers,
   insets and predictive back; repeat on narrow and large windows and after
   process recreation.
2. Add a custom Agent (including multiline arguments and `KEY=VALUE`
   environment), edit the same Agent from the Flutter compatibility page, and
   confirm both surfaces converge. Check blank name/command validation and that
   a failed save keeps the draft. Confirm arguments/environment never appear in
   logs or navigation state.
3. Run Install/Reinstall on a managed Adapter and Check again on a custom
   Agent. Confirm busy markers, the result sheet wording, the
   `harness_preparation_in_progress` busy message when a second install is
   requested, and that the list refreshes from `agent/list` afterwards.
4. Open and stop a local Web interface action; check running/starting badges,
   busy exclusivity, and the runtime-missing/provider-required destinations.
5. Toggle the remote PC Bridge on the compatibility page and return; the native
   row must reflect the enabled state (cached first frame, then the
   `config/remote/read` refresh).
6. Alternate edits between this page and the Flutter Agent page under the
   default launcher; both must show the same catalog, health and remote state.
7. Rotate and recreate the process with the editor open, a sheet visible and a
   prepare in flight; verify saved route state, draft loss behavior matches the
   other native editors, and no duplicate install starts.

## Batch 4e checkpoint: remote MCP tools (source complete; device acceptance pending)

- Settings → MCP Tools now opens a saved native route. The native page lists
  remote services, their enabled and discovery state, tool count and last error;
  it supports add/edit/delete, enable/disable, manual list refresh and forced
  tool discovery. The existing Flutter page remains for compatibility entry
  points under the default launcher.
- `RemoteMcpConfigService` is the shared mutation boundary for the native page
  and Flutter channel. It retains the existing MMKV store, discovery registry
  and `AgentRuntimeManager.invalidateMcpConfiguration()` side effect. The
  native ViewModel keeps credentials out of list state, masks the editor token
  and checks the saved configuration before overwriting a draft changed
  elsewhere. A failed save leaves the editor open.
- Source/route/resource inspection and diff checks are the verification
  boundary. No build, test suite, emulator/device interaction or MCP network
  request was run at the user's request. Visual parity and runtime acceptance
  remain pending.

Manual acceptance checklist:

1. Compare Flutter and native pages in both themes and languages, including
   empty/loading/error states, narrow and large windows, keyboard insets,
   delete confirmation and predictive back.
2. Add, edit, enable, disable and delete a service. Confirm both UIs see the
   same store values, the token is masked in the native editor and absent from
   incidental logs/navigation state, and Agent MCP sessions use the existing
   invalidation path.
3. Refresh tools on healthy and failing endpoints. Check persisted health,
   tool count, last error and forced discovery; repeat after process recreation.
4. Change the same service in Flutter while the native editor is open. Confirm
   the native save reports a conflict and leaves the unsaved draft available.

## Batch 4d checkpoint: Provider editor (source complete; device acceptance pending)

- Settings → Model Providers and Scene Models → configure Provider now use one
  saved native route. The home Agent Web action's Provider-required result also
  opens that route; the existing Flutter route remains for the default Flutter
  launcher and compatibility entry points.
- The native page edits and switches existing Provider profiles, offers the
  same built-in endpoints/protocols and OpenAI wire formats, masks/reveals the
  current API key, edits custom headers, and supports add/delete with
  confirmation. Draft fields save after focus leaves or when explicitly saved;
  switching profiles first saves a valid draft. Invalid URLs/headers and a
  profile revision changed elsewhere leave the draft intact and report failure.
- `ProviderEditorRepository` calls `ModelProviderConfigStore` for credentials,
  endpoint normalization, revisions and existing Agent runtime invalidation.
  It also owns manual model IDs and chat visibility in the existing Flutter
  preference keys. Flutter's service now reaches those lists through one native
  channel, while its Provider editor stays available during coexistence.
  Existing Flutter test channel fixtures were updated to match the contract.
- Native model discovery uses the shared `ProviderModelCatalogService` and its
  before/after revision check. The page can show a persisted catalog, refresh
  against the Provider, add/remove manual IDs, hide/show chat models and hide
  all fetched models. As in Flutter, removing a fetched-only model affects the
  current list; an explicit fresh fetch can return it. Saving a changed Provider
  revision clears the editor's old fetched list while retaining manual IDs.
  Metadata supplied by
  the Provider is shown when available; the Flutter-only models.dev visual
  enrichment and exact model-group visuals still need device comparison.
- Source/API, XML, route, focused Dart analysis and diff inspection are the
  verification boundary. No build, test suite, emulator/device interaction or
  app Provider requests were run at the user's request. Visual parity and runtime
  acceptance remain pending.

Manual acceptance checklist:

1. Compare native and Flutter at the same theme, locale, density and font scale:
   header actions, fields, focus/keyboard, dialogs, model list and metadata,
   insets and predictive back. Test narrow, landscape and large windows.
2. Edit URL, API key and custom headers; check a valid save and a failed save.
   Confirm the secure secret store and Flutter editor see the same values;
   inspect logs and saved navigation state for absent credentials/header values.
3. Switch, add and delete Providers; verify the selected ID, revision, Agent
   runtime invalidation, built-in endpoint preset and OpenAI Responses choice.
   Change a Provider concurrently in Flutter and check stale native drafts do
   not overwrite it without an explicit refresh.
4. Fetch a model list, add/remove manual IDs and toggle chat visibility,
   including hide-all and a refresh that returns a removed remote model.
   Compare with Flutter chat selection and scene binding; repeat offline,
   with an empty result and after process recreation.
5. Follow the scene page's Provider link and the home Agent Web prerequisite
   action; return to the previous native page and confirm refreshed settings.

## Batch 4c checkpoint: scene-model configuration (source complete; device acceptance pending)

- Settings and Workspace Memory now open Scene Models on the saved native
  `miuix-nav` stack. The page presents the existing catalog order, saved Provider
  bindings, default/unbound/missing-Provider labels, scene descriptions and
  voice autoplay/status/voice/style. The Agent avatar keeps its existing read
  keys and preview; editing hands off to the Flutter scene page's avatar picker.
  Provider editing moved to the native route in batch 4d.
- The Miuix `OverlayListPopup` owns placement, dismissal and keyboard bounds.
  Its application content supports model-ID search, Provider expansion, selected
  model markers, restore-default, loading/empty/failure states and retry.
  Foundation rows retain the existing 14sp scene / 13sp selector hierarchy;
  the shared page host and Omni palette remain the owners of insets and theme.
- `SceneModelSettingsRepository` is shared with the existing Flutter channel for
  binding mutations and their established GUI/Agent side effects. A same-Provider
  model change retains the existing ACP session; changing Provider or restoring
  the Agent default retains the existing shared-runtime invalidation rule.
  No new prompt/session lifecycle or conversation projection is added.
- `ProviderModelCatalogService` extracts the channel's existing model discovery
  contract, including supplied credentials/headers, official capabilities,
  before/after Provider revision checks and successful discovery persistence.
  Native reads the Provider editor's existing manual-ID keys, including its
  legacy list encoding, without adding a manual-ID writer or a network cache.
  Saved bindings remain visible while catalogs load or fail. Explicit refresh
  forces official discovery; obsolete page refresh jobs cannot project results.
- Voice autoplay updates the current `SceneVoiceConfigStore` config without
  replacing its secured custom curl command. The existing voice catalog entry
  was missing from `SceneModelBindingStore`'s allowed IDs; `scene.voice` is now
  accepted by that shared store for both settings surfaces. Flutter's existing
  application resume callback refreshes an already initialized
  `VoicePlaybackCoordinator`, so returning chat consumes native settings changes
  without creating a second playback/configuration owner.
- Source/API, resource, route, compatibility and diff inspection are the
  verification boundary. No build, test suite, emulator/device interaction or
  network calls were run. Visual parity and runtime acceptance remain pending.

Manual acceptance checklist:

1. Compare both themes and locales at matching density/font scale: scene rows,
   selector popup, long model IDs/tooltips, search/keyboard bounds, voice settings,
   insets, predictive back and restored navigation state.
2. Bind and restore each catalog scene, including Voice and GUI. Compare Flutter
   and native persisted bindings and runtime resolution. Check that same-Provider
   model changes retain the ACP session; Provider changes use its existing owner.
3. Check configured, unconfigured and removed Providers, manual-only models,
   saved models absent from discovery, text/embedding official catalogs, failed
   discovery and explicit retry. Change a Provider while a query is pending and
   verify its older response cannot overwrite the newer Provider catalog.
4. Toggle voice autoplay with a bound model and with a configured custom curl
   command. Confirm voice/style/mode/secured command are unchanged; return to
   Flutter chat and check the existing playback owner's refreshed configuration.
5. Open Provider/avatar compatibility editors and return. Confirm native binding,
   catalog and avatar refresh; return to Workspace Memory and verify capability
   status refresh leaves all unsaved memory drafts intact.

## Batch 4b checkpoint: workspace memory (source complete; device acceptance pending)

- Settings → Workspace Memory now opens a native Miuix page on the saved
  `miuix-nav` stack. Its scene-model link now opens the native binding page
  described in batch 4c.
- Soul, chat-only prompt and `MEMORY.md` retain explicit Save actions. Drafts
  stay in the Activity-scoped ViewModel and are not persisted on keystroke or
  back gesture. Reads/writes call the existing `WorkspaceMemoryService` methods;
  neither long-term memory storage format nor ACP lifecycle changes.
- Embedding status/toggle use the existing workspace service. Nightly-rollup
  status/toggle use `WorkspaceMemoryRollupScheduler`; manual Rollup is shared by
  Flutter's channel and the native page through its `runNow()` operation.
- Only source, resource, route and contract inspection was performed. No build,
  test suite, emulator or device was run at the user's request. Visual 1:1 and
  behavior acceptance remain manual.

Manual acceptance checklist:

1. Compare the native and Flutter pages at matching theme, locale, density and
   font scale: section headers, switch geometry, multiline editors, Save buttons,
   feedback snackbar, insets and predictive back.
2. Load nonempty Soul, chat prompt and `MEMORY.md`; edit each independently.
   Verify back without Save leaves files unchanged, while Save updates the
   existing Flutter page. Check read/write failure feedback without exposing
   prompt or memory contents in logs.
3. Toggle embedding retrieval with and without a configured embedding model.
   Follow the scene-model link and return; confirm status refresh leaves
   unsaved text drafts untouched.
4. Toggle nightly rollup, compare next-run time, run a manual rollup on suitable
   sample memory, and compare the result/last-run state in Flutter. Confirm
   rotation and Activity recreation retain expected state.

## Batch 4a checkpoint: storage and logs (source complete; device acceptance pending)

- Settings → Storage and About → Request logs / Runtime logs now enter the same
  native `miuix-nav` back stack. Existing Flutter routes remain available when
  the default Flutter launcher is used.
- `StorageUsageRepository` contains the unchanged analysis, category cleanup,
  strategy and metrics-history logic formerly embedded in `StorageUsageChannel`.
  The channel retains its names and payload shape. The native ViewModel adapts
  the same summary/result maps; Compose owns only presentation and confirmation.
- Request logs use `AiRequestLogStore.listRecent(10)` and expose per-entry
  request/response copy. Runtime logs use `RuntimeLogStore.listRecent(200)`,
  per-entry stack copy, full-text clipboard export, and confirmed clear. These
  operations do not add a log store, ACP state, or automatic clipboard transfer.
- Static source/resource/route checks are the validation boundary for this
  batch. No Gradle build, test suite, emulator or device verification was run
  at the user's request. Visual 1:1 acceptance remains a manual device task.

Manual acceptance checklist:

1. In both themes and Chinese/English, compare native Storage against Flutter
   at the same density and font scale: title, overview, trend, distribution,
   strategy rows, category details, dialog dimensions and system insets.
2. Refresh Storage and compare totals, category order/breakdown, metrics source,
   history and trend with Flutter. Confirm a safe category with all/7/30-day
   scopes and verify released bytes and retained recent files. Check a
   dangerous category's confirmation and a strategy before using real data.
3. Populate a successful and failed AI request and a runtime crash. Compare
   order, totals, expanded content and copy behavior; check that clipboard
   contents change only after tapping Copy or Copy all. Confirm runtime Clear
   can be canceled, then verify both native and Flutter see the cleared store.
4. Verify system back and predictive gesture from each page and its dialogs,
   then rotate/relaunch. Check the default Flutter entry still opens its pages.

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

Miscellaneous → Home Settings now opens the native page. Alarm Settings and
Open with Omnibot moved to native routes in batch 4g. Quick Start remains an
explicit typed compatibility destination; its existing implementation stays
responsible for that workflow. The
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
