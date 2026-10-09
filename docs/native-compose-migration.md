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
      ├─ NativePluginMarketViewModel / NativePluginDetailViewModel → NativePluginRepository → OmniPluginHost
      ├─ NativeMemoryCenterViewModel → NativeMemoryCenterRepository → WorkspaceMemoryService
      ├─ NativeTerminalSettingsViewModel → NativeTerminalSettingsRepository → EmbeddedTerminalSetupManager / EmbeddedTerminalInitCoordinator / EmbeddedTerminalAutoStartManager / WorkspaceMountManager
      ├─ NativeUsageStatisticsViewModel → NativeUsageStatisticsRepository → ConversationDomainService / TokenUsageRecordDao (read-only)
      ├─ :native-ui / NativeHomeApp
      │   └─ one saved miuix-nav stack: Home → Settings / Archive / About / Permissions / Appearance / Background / Pet / HomePreferences / Miscellaneous / AlarmSettings / OpenWith / Storage / RequestLogs / RuntimeLogs / WorkspaceMemory / SceneModels / ModelProviders / McpTools / Agents / AgentConfig(agentId) / RemoteBridge / ScheduledTasks / ExecutionHistory / Skills / Plugins / PluginDetail(pluginId) / Memory / Terminal(focusPackageId)
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
| `session/update` projection and merge | `AgentEventReducer` (app module since 5a) | One native reducer; Flutter and Compose surfaces consume its snapshots. Do not add a second reducer on any side. |
| Active conversation coordination | `ChatConversationRuntimeCoordinator` (app module since 5a, hosted by `ChatRuntimeHost`) | The Dart class of the same name is only a snapshot mirror and command forwarder. |
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
  (batch 4j); the plugin market and detail pages are native (batch 4k); the
  memory center is native (batch 4l-1); the drawer shortcut row no longer opens
  any Flutter compatibility page. The terminal settings page is native
  (batch 4m), including the distribution switch, environment inventory,
  boot tasks and workspace mounts. The OmniFlow execution center
  (`/task/omniflow`, entered from tool-summary cards and manual recording) and
  the remote workspace browser remain Flutter compatibility destinations.
- Native home gained MainActivity's launch/foreground behaviors in 5e-7e
  (terminal auto-start, account refresh, app update checks, orientation);
  deep-link routing still goes through MainActivity. The generic native chat entry now delegates
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

## Batch 5 plan: chat migration split (2026-10-04; 5a-0 through 5c-4 source complete)

The chat domain is ~53.5k Dart lines (`chat/` 36.7k + `command_overlay/` 16.9k)
and cannot move as one Goal. The architecture survey behind this split:

- The ACP runtime (`AgentRuntimeManager`/`LocalAcpRuntime`) is already native.
  What still lives in Dart is the projection (`AgentEventReducer`, ~7k lines,
  pure) and the runtime owner (`ChatConversationRuntimeCoordinator` + 9 part
  files, ~3.5k lines, singleton ChangeNotifier keyed by
  `(conversationId, mode)`), plus all UI.
- Event chain: `LocalAcpRuntime.emitAcpNotification` (host envelope adds
  `eventId=$sessionId:$seq`, `hostTurnId`, `replay`) →
  `AgentRuntimeManager.onMessage` normalization (turn attribution via host
  prompt reservation only; Room `syncMessage`; public envelope with
  conversationId/turnId/agentId) → EventChannel
  `cn.com.omnimind.bot/AgentRuntimeEvents` (buffered when no listener) →
  Dart `AgentRuntimeService.events` → ChatPage attribution →
  coordinator admission (session identity + `acceptsAcpEvent`) →
  `AgentEventReducer.reduce` → ChatPage chrome-signature/mutation-revision
  gated `setState`. `session/prompt` MethodChannel responses also enter the
  same reducer (`reducePromptResponse`); nothing synthesizes private
  `turn/*` events.
- `ChatPage` is one State + 2 mixins + 12 `part of` files sharing the State,
  not independent components; sending lives in
  `chat_page_conversation_flow.dart` (`_sendMessage` →
  `_dispatchUserMessage` → `_sendAgentMessage`: turn pre-admission
  `beginAcpTurn`, session reservation, then channel `session/prompt`).
- History double-writes converge on Room: native `syncMessage` plus the
  coordinator's debounced snapshot via `replaceConversationMessages`.
  Approval/user-input/elicitation cards are reducer projections of
  `session/request_permission` / `elicitation/create` /
  `item/tool/requestUserInput`, answered via `respondToServerRequest`.

Split order (each row is one bounded Goal; 5a/5b are atomic). 5a-0 was added
after the first 5a attempt found that ChatPage writes the runtime directly
(29 message-list mutations, 21 field writes, ~100 synchronous coordinator
calls) and attributes events from page-local state, so a read-only snapshot
adapter could not absorb it without a second writer:

| Slice | Scope | Completion boundary |
| --- | --- | --- |
| 5a-0 | Dart-only seam, behavior-preserving: pages/sheet/drawer read a runtime only through a read-only `ChatRuntimeView` and write only through coordinator commands; ChatPage/ChatBotSheet event attribution moves into the coordinator (`attachEventHost` + declarative `ChatRuntimeRoutingContext`). | No page code mutates `ChatConversationRuntimeState` or a runtime message list; the coordinator is the only `AgentRuntimeService.events` projector; existing Dart tests pass. Source complete — see the 5a-0 checkpoint. |
| 5a | Move `AgentEventReducer` + `ChatConversationRuntimeCoordinator`(+parts) + `ChatConversationRuntimeState` + event helpers (`agent_message_kinds`, tool/diff parsers, identity, stream-meta, acp extension registry) into app-module Kotlin behind the 5a-0 command/view/routing API, emitting immutable UI snapshots; the Dart coordinator becomes a thin adapter implementing `ChatRuntimeView` from snapshots, forwarding commands over a channel and pushing the routing context. TTS side effects and the persistence tail chain move with the owner. Parsers still imported by Dart cards stay in Dart for rendering only until 5c. | Dart has no second reducer/coordinator; the existing Dart reducer/coordinator tests are ported to Kotlin unit tests and pass. Commands that return values to the page (`bindAcpSession`, `isTaskActive`, `applyAcpPromptResponse`, …) become async; their call ordering in send/cancel must be reviewed with 5b. Atomic — a partial move creates the forbidden second reducer. Highest-risk slice and prerequisite for everything below. |
| 5b | Prompt admission: `_sendAgentMessage`/`_sendPureChatMessage`/`_prepareAcpSessionForTurn`/harness-switch barrier/cancel become a native `ChatPromptDispatcher`; the composer emits intents only. | `session/prompt` admission, `respondToServerRequest` and `$/cancel_request` have a single native entry; idle/busy states come from the 5a snapshot. Atomic for the same reason. |
| 5c | Message rendering in Compose: run timeline, MessageBubble, card family (tool summary/transcript/diff/request cards/deep thinking/plan), message list, run groups, tool activity strip. | Same fixture renders identically in Flutter and Compose; approval buttons call the 5b response entry. Card kinds may be split further for pixel comparison. |
| 5d | (Preceded by 5d-0, see below.) Composer in Compose: ChatInputArea family + state machine + attachments + agent menus + context-usage ring; send button calls the 5b intent. | Keyboard/popup/expand animations aligned; slash-command panel works. Manual recording and omniflow tooling may stay Flutter behind compatibility entries. |
| 5e | Page shell: ChatPage lifecycle/bootstrap/target resolution, app bar (agent switching uses the existing native `agent/select`), drawer embedding, browser overlay, HD tablet layout, remote workspace panel. | The native home agent selector placeholder connects to the real switcher; the `/home/chat` compatibility route retires. |
| 5f | Retirement: delete the Flutter chat routes/part files and obsolete channel methods; decide CommandOverlay/ChatBotSheet ownership. | No chat functionality left in the Flutter engine; feeds batch 6. |

May stay in Flutter longer (they hold no lifecycle): CommandOverlay/ChatBotSheet
hosts, the OpenClaw legacy surface, manual recording, and the embedded remote
workspace browser panel — they interact through intents/routes only.

## Batch 5d-0 plan: native turn launcher (2026-10-06; 5d-0a, 5d-0b and 5d-0c source complete)

The 5b dispatcher takes ready-made `session/new` / `session/prompt`
arguments. Building them is still Dart page code: three dispatch paths
(`_sendAgentMessage`, `_sendPureChatMessage`, `_tryAgentFlow`) each freeze
the target, admit the run, create the conversation, assemble the arguments
and apply the session pointers afterwards, and `/review` bypasses the
dispatcher entirely (`review/start`). A native composer has nothing to call
until that moves, so 5d-0 precedes the Compose composer:

| Slice | Scope |
| --- | --- |
| 5d-0a | Pure argument logic in Kotlin (`projection/ChatTurnArguments.kt`), unwired. |
| 5d-0b | `ChatTurnLauncher`: one native `launchTurn` for the three paths (user row, admission, conversation creation, persistence, prepare/submit/release, error application); returns session/thread pointers. Atomic: no second send orchestration. Settings stay caller-frozen (Dart today, a native reader in 5d-1). Native conversation creation notifies Dart to adopt the id and refresh the drawer. |
| 5d-0c | Pre-send guards (Harness switch barrier, per-target lock), retry / edited resend and `/review` through the launcher. |

Legacy defects fixed in the move (each covered by a Kotlin test):

1. Retry and edited resend skipped the Harness barrier and the submit lock
   (could reach the old Agent or send twice). 5d-0c.
2. `handleAgentError` applied errors to the *visible* runtime instead of the
   dispatch target. 5d-0b.
3. Session pointers were written before submit without a target check, so
   a conversation switch could inherit the old session. 5d-0b.
4. Pure chat and the task flow re-read "the newest user message" instead of
   sending the submission, and that text already described every
   attachment, so Xiaowan's adapter (which builds the same hint from the
   attachments it receives) showed each file to the model twice, once by
   name and once by path. The launcher sends the submitted text; only
   excluded files get a path hint (`buildUserPromptText`). Verified by
   running `buildXiaowanPromptParts` on both inputs. 5d-0a/b.
5. The task-flow path did not filter `sendToModel: false` attachments; the
   ACP adapter turns any readable path into a resource link, so excluded
   files still reached the model. `modelAttachments` filters every path.
   Verified in `LocalAcpRuntime` prompt block building. 5d-0a.
6. Cleanups: the model configuration was checked up to three times per
   send; the task-flow `handleAgentError` after a failed submit cannot fire
   (the dispatcher already ended the run).
7. `/review` called `review/start` beside the launcher and dropped its
   result. Only a PromptResponse ends a run (the reducer completes nothing
   else), so after every review the page stayed "responding" until it was
   reopened. `review/start` is not an ACP method: locally it was only
   `startTurn(text = "/review")`, and `/review` is offered only when the
   Agent advertises it, so it is now an ordinary advertised prompt. 5d-0c.

### 5d-0a checkpoint (source complete, unwired)

- `ChatTurnArguments.kt`: `newSessionArguments` / `promptSessionArguments`
  (same keys, order and trimming as Dart; permission expands to the policy
  triple), `AgentPermissionMode` (policy mapping + stored preference values
  and legacy spellings), `agentModelSourceKey`, `selectAgentRequestModel`,
  `ChatTurnIds` (`<ms>-user` / `<ms>-ai`, run id = request id),
  `buildUserPromptText` and `modelAttachments`. The prompt text reuses the
  adapter's `AgentAttachmentPromptSupport` (equivalent to Dart, plus UTF-16
  sanitizing and `data:image/` detection) instead of a second copy; terminal
  variables reuse `OmnibotTerminalEnvironment.loadUserVariables`.
- Verification: `ChatTurnArgumentsTest` 10 (ports the argument / source /
  model cases of `agent_runtime_service_test` and the prompt text rules,
  plus fix 5); full `:app` unit suite 1491, 0 failures. Nothing calls the
  new code yet; no Dart edits.

### 5d-0b checkpoint (source complete; device acceptance pending)

- **Native**: `ChatTurnLauncher.launchTurn(request, isTargetCurrent)`
  admits the run, inserts the user row by id, persists the admission
  snapshot (non-ephemeral runtimes), reserves the session, re-checks the
  target, prompts with `ChatTurnArguments`, and returns `ChatTurnOutcome`
  (completed / failed / rejected + pointers only while the target is
  current). `ChatRuntimeHost` exposes it as the `launchTurn` command and
  holds a per-surface navigation generation (`setSurfaceGeneration`): the
  page bumps it on every target change and on dispose, so a stale turn
  stops between awaits without calling back into Dart. The step commands
  (`prepareTurnSession` / `submitTurnPrompt` / `releaseTurnSession`) are no
  longer on the channel; `ChatPromptDispatcher` keeps them as the
  launcher's internals.
- **Dart**: `_sendAgentMessage` keeps its status probe, remote/conversation
  resolution and post-send adoption, and sends through `launchTurn`;
  `_sendPureChatMessage` and `_handleExecutableTaskFlow` share one
  `_launchNormalTurn` (they differed only in model selection, agent id and
  thinking cleanup); the command overlay sheet does the same with its own
  surface generation, fenced on close, cancel and dispose. Removed:
  `_prepareAcpSessionForTurn`, `_tryAgentFlow` and its three caller-less
  override parameters, `_latestUserAttachments`,
  `_latestUserAgentAttachments`, `_buildPromptRequestId`, the overlay's
  copy of the attachment hint helpers, the private permission-mapping
  duplicate (now `AgentPermissionMode.preferenceValue`), and the
  unreachable "统一 Agent 启动失败" fallback.
- **Fixes landed here**: 2 (errors go to the run's own runtime), 3
  (pointers only from a current outcome), 4 (submission, no double hint),
  5 (every path filters `sendToModel: false`), 6 (dead fallback).
  Remaining for 5d-0c (done there): 1 (retry / edited resend barrier and
  lock), `/review`, and the triple model-configuration check.
- **Verification**: `ChatTurnLauncherTest` 11 (admission, reuse, submission
  text, attachment filtering, empty, stale before reservation, stale during
  `session/new` closes the session, no pointers after a move, persistence
  failure stays on its runtime, `session/new` and prompt failures);
  `ChatTurnArgumentsTest` 10; `:app` unit suite 1502, native-ui 53, 0
  failures; androidTest compile and release resource merge succeeded.
  `flutter test` 965 passed, 4 failed (the same 4 settings/background
  tests fail without this change); `flutter analyze` 0 errors, no new
  warnings in the chat/overlay libraries. The fake native runtime in
  `ui/test/helpers` mirrors the launcher; the architecture test asserts
  every chat surface sends through `launchTurn` and runs none of its steps.
  No device run.

### 5d-0c checkpoint (source complete; device acceptance pending)

- **Submit gate**: `ChatSubmitGate` (`chat_page_models.dart`) is the one
  admission path of a page submit: Harness switch barrier, then the
  per-target lock, then the conversation bootstrap. `_sendMessage` and the
  three retry entries (`_retryUserMessage`, `_saveAndResendEditedUserMessage`,
  `_retryFailedAgentTurn`, via `_runRetrySubmit`) go through it; a retry
  passes `requireSameTarget` and is dropped when the page moved to another
  target while it waited (fix 1). `_sendMessageInFlightTargetIds` is gone.
- **`/review`**: the slash card submits `/review` like typing it, so an
  advertised command reaches the launcher as an ordinary prompt (fix 7).
  Removed `AgentRuntimeService.reviewSession` / `startReview` and their
  test. The native `review/start` handlers stay for now (no Dart caller);
  retired with the channel in 5f.
- **Model check**: one `_ensureNormalChatModelConfigurationForSend` per send,
  in `_dispatchUserMessage` after slash routing; a retry checks before
  clearing the old round and passes `modelConfigurationChecked` (fix 6).
- **Verification**: `ChatSubmitGate` behavior tests 6 (queued retry runs
  after the switch, dropped after a target move, refused after a failed
  switch, retry + composer send once, other targets not blocked, move
  during bootstrap); `ChatTurnLauncherTest` +2 (advertised `/review` ends
  its run, retry keeps its row with a new run id); architecture test for
  the shared gate and the single model check. `:app` unit suite 1504,
  native-ui 53, 0 failures; androidTest compile and release resource merge
  succeeded. `flutter test` 970 passed, 4 failed (the same 4 baseline
  settings/background tests); `flutter analyze` 0 errors. No device run.

Manual acceptance checklist:

1. Agent, normal and pure chat each send and stream as before; a fresh
   conversation is created on first send and appears in the drawer.
2. Send, then switch conversation before the reply starts: the new
   conversation shows no spinner and the old one finishes in history.
3. Attach a file marked "add to workspace" (`sendToModel: false`) in
   normal chat: the model reads it by path and the transcript does not
   list it twice.
4. Command overlay: send, close the sheet during "connecting": no prompt
   is sent; stop during a reply ends it.
5. Tap retry (or save an edited message) while switching Harness: it
   sends once to the new Agent, or is dropped if the conversation changed.
6. Run `/review` from the slash panel and by typing it: the reply streams
   and the composer returns to idle when it ends.

## Fix: Flutter home drawer drops frames on open and close (2026-10-08)

The shipped home drawer is the Flutter `HomeDrawer` in `ChatPage`'s
Scaffold (native Home is opt-in, `omnibot.nativeHome=false`). No device was
attached, so the causes were found by reading the code; each is pinned by a
test that fails when the fix is reverted.

1. **Rebuild per streamed token.** The drawer listened to the runtime
   coordinator and called `setState` on every notification, which fires on
   every native snapshot of every runtime. While a reply streamed, the whole
   list rebuilt dozens of times a second, including during the slide. It now
   rebuilds only when the set of running conversations changes (the only
   runtime fact it renders). Test: 30 streamed snapshots, 0 rebuilds.
2. **Reload on the first frame of the open.** A Scaffold drawer is
   unmounted when closed, so its `initState` (`_loadConversations` with two
   `setState`s, plus the image-preview pass that reads and decodes whole
   conversation histories on the UI isolate) ran on the first frame of every
   open, and `_handleHomeDrawerChanged` started a second reload on top. With
   a snapshot cached the drawer now opens on it and refreshes once the slide
   has settled (`deferInitialLoad`, `reloadAfterSettle`, cancelled on close).
   Image-preview batches also wait while the drawer slides. Test: no
   `getConversations` during the slide, exactly one after it.
3. **Chat page rebuilt during the close.** Tapping a conversation popped the
   drawer and switched `/home/chat` in the same frame, so the chat page's
   history load and full rebuild ran in the frames of the close slide. The
   switch now runs after the close settles.

Verification: `flutter test` 974 passed, 4 failed (the same 4 baseline
settings/background tests); `flutter analyze` 0 errors. Device check: open
and close the drawer while a reply streams, and tap a conversation; the
slide should stay smooth in both directions.

## Batch 5e plan: chat page shell (2026-10-08)

Split from the 5e row of the batch 5 table; each slice keeps the Flutter
chat working beside it.

| Slice | Scope |
| --- | --- |
| 5e-1 | New conversation on the native page: the drawer's `+` opens it, the first send creates the conversation (title, Harness binding, permission) and launches through the 5d-0 launcher. |
| 5e-2 | History paging, restore after process death, the preview entry becomes "Open in native chat". The drawer's primary tap stays on Flutter until the native page has message actions and the Harness switcher (5e-5), so switching it does not drop features. |
| 5e-3 | App bar: title, Harness switcher (native `agent/select` + the 5d-0c switch barrier), new-conversation action, ACP config panel. |
| 5e-4 | Empty-state greeting and quick prompts; Home's composer entry opens the native page. |
| 5e-5 | User message actions (copy, edit, retry) and link previews on the native page. |
| 5e-6 | Remote Codex, OpenClaw, browser overlay, workspace panel and manual recording entries (or explicit Flutter hand-offs). |
| 5e-7 | Lifecycle: startup conversation preference, shared-open drafts, voice, pet overlay, tablet layout; retire `/home/chat`. |

### 5e-1 checkpoint (source complete; device acceptance pending)

- `HomeRoute.NativeNewChat` opens the native chat page with no
  conversation; the drawer's `+` (which only closed the drawer before) now
  opens it. Home's composer entry and quick prompts still open the Flutter
  chat until 5e-4.
- `NativeChatTranscriptViewModel` takes a nullable conversation id. A new
  page resolves its target from the selected Harness
  (`AcpAgentProfileStore.selected()`; remote Codex keeps the Flutter hint).
  The first send creates the conversation through
  `ConversationDomainService.createConversation` (title = first user text
  cut at 20 characters, as in Dart `persistConversationSnapshot`; the
  Harness bound by the service), stores the permission choice for the new
  id, seeds the runtime and launches. The native drawer refreshes from the
  Room flow and the Flutter list from `FlutterChatSyncBridge`.
- The page's navigation fence now follows the route (`attach` / `detach`
  from a `DisposableEffect`, kept across rotation). Before, the fence was
  closed only in `onCleared`, which for these activity-scoped ViewModels
  runs when the activity finishes, so leaving a page never stopped a turn
  still preparing.
- Not yet: plan mode and `/effort` need an existing conversation (they are
  session or conversation settings) and are ignored before the first send.
- Verification: `NativeChatComposerTargetTest` +1 (title rule); `:app`
  1521, native-ui 65, 0 failures; androidTest compile and release resource
  merge succeeded. No device run.

Manual acceptance (5e-1): drawer `+` → native page shows the composer
with the selected Harness's permission; first send creates a conversation
that appears in both drawers with the first text as title and streams the
reply; leave during "connecting" on a second new page: nothing is sent and
no empty conversation is left behind except the one created by the send;
with remote Codex selected the page shows the "open in chat" hint.

### 5e-2 checkpoint (source complete; device acceptance pending)

- Stored history loads 50 rows at a time and the next page loads when the
  list nears its top (Dart `loadMoreMessages`; the offset advances by what
  was received). A live runtime never pages: it is seeded with the complete
  history before its first send.
- The route supplies the ViewModel key and the created conversation id is
  kept in `SavedStateHandle`, so a new page whose first send created a
  conversation reopens that conversation after process death instead of a
  blank new page (which would create a second conversation on the next
  send). Before, the key was a fresh `nanoTime` per composition.
- Labels: the drawer menu entry is "Open in native chat"; the page subtitle
  says native chat instead of preview; an untitled page says "New
  conversation".
- Verification: `:app` 1521, native-ui 65, 0 failures; androidTest compile
  and release resource merge succeeded. No new unit tests (paging and saved
  state are framework wiring); covered by the acceptance steps. No device run.

Manual acceptance (5e-2): open a conversation with more than 50 messages
from the drawer menu and scroll up: older pages load without gaps or
duplicates; send from a new page, put the app in the background with
"Don't keep activities" on, return: the same conversation is shown and the
next send goes to it.

### 5e-3 checkpoint (source complete; device acceptance pending)

- **App bar** (`chat/ChatPageBar.kt`): Harness chip with brand icon and a
  Miuix list popup of enabled Harnesses, the ACP config button, and a
  new-conversation button that replaces the current page with
  `NativeNewChat`.
- **Harness switch** (`planHarnessSwitch`): a conversation keeps the
  Harness it was created with, so choosing another on a page that has one
  selects it (`agent/select`, configuration only, running sessions keep
  their Harness) and opens a new conversation, as Flutter's
  `buildHarnessSwitchTarget` does; on a new page it only retargets the first
  send. Refused while any turn runs (the Flutter switch barrier exists for
  the same reason). Remote Codex is not offered; it keeps its Flutter flow.
- **ACP config panel** (`AcpConfigModels.kt`, `AcpConfigPanel.kt`): every
  declared option (`session/load` without history), selects open their
  choices, booleans toggle, unknown types are shown read-only; labels port
  `acpConfigLabel` and the effort value names. Writes use
  `session/set_config_option` and the full response replaces the list.
  Read-only while this conversation's turn runs; a new page asks for a first
  send instead of creating an empty conversation to read settings.
- **Verification**: `AcpConfigModelsTest` 2, `NativeChatComposerTargetTest`
  +1 (switch plan); `:app` 1522, native-ui 67, 0 failures; androidTest
  compile and release resource merge succeeded. No device run.

Manual acceptance (5e-3): switch Harness on a new page, send: the turn runs
on the chosen Harness; switch on a page with a conversation: a new page
opens on that Harness and the old conversation keeps its own; switching
while any reply streams shows the busy notice; the config panel lists the
same options as the Flutter slider panel and a change applies to the next
reply.

### 5e-4 checkpoint (source complete; device acceptance pending)

- **Greeting** (`chat/ChatEmptyGreeting.kt`): an empty native page shows
  the Flutter greeting: headline with the Harness name, the keyword rotating
  every 1.8 s (never repeating the current word), and up to two quick
  prompts (`selectGreetingPrompts`: pinned first, else a random pair,
  untitled prompts never offered). Off when the Home greeting setting is
  off. A prompt only fills the composer (`InjectedDraft`, adopted once);
  nothing is sent until the user taps send, like Dart `_applyHomeQuickPrompt`.
- **Home entries now open the native page**: the composer entry and its
  `+` / mic buttons resolve the startup target like the Flutter page's
  bootstrap (`resolveChatStartupTarget`: "new conversation" preference, else
  the last visible conversation if it still exists and is not archived,
  OpenClaw or remote; otherwise a new page). Home quick prompts open a new
  native page with the prompt as its draft (kept in `SavedStateHandle`, so
  recreation does not re-fill it).
- **Verification**: `ChatEmptyGreetingTest` 3, `NativeChatComposerTargetTest`
  +1 (startup target); `:app` 1523, native-ui 70, 0 failures; androidTest
  compile and release resource merge succeeded. No device run.
- Still Flutter from native Home: the drawer's primary tap on a
  conversation (5e-5) and the workspace entry.

Manual acceptance (5e-4): with "resume last" on, tap Home's composer: the
last conversation opens natively; with "new conversation": an empty page
with the greeting; tap a quick prompt: the composer is filled and nothing
is sent; turn the greeting off: an empty page shows no greeting.

### 5e-5 checkpoint (source complete; device acceptance pending)

- **Message actions**: a long press on a user bubble opens a Miuix list
  popup. `userMessageActions` ports `_canEditUserMessage` /
  `_canRetryUserMessage`: edit and retry only on the latest user message,
  never while a reply runs or on a page that cannot send; copy needs text;
  an attachment-only message can be retried. Bubbles list their attachment
  names.
- **Copy** uses the system clipboard (Android 13+ shows its own
  confirmation). **Edit** moves the text into the composer with an editing
  banner and a cancel button; send removes the round from the edited message
  on (runtime and history, `allowHistoryRemoval`) and sends the edited text
  with the original attachments. **Retry** keeps the user row (same id,
  `ChatTurnIds.forRetry`), removes the reply after it, and runs the same
  submission again. Both go through `send`, so they share the submit lock,
  the runtime seeding and the launcher; the round is removed only after the
  runtime holds the complete history (`beforeLaunch`).
- **Drawer and archive taps now open the native page** for every
  conversation it can send to (`opensNatively`: Agent and pure chat on a
  local Harness); OpenClaw, scheduled Sub Agent runs and remote Codex keep
  their Flutter pages.
- **Verification**: `UserMessageActionsTest` 4, `OpensNativelyTest` 2,
  `ChatTurnLauncherTest` +1 (retry round removal); `:app` 1524, native-ui
  76, 0 failures; androidTest compile and release resource merge succeeded.
  No device run.
- Not ported yet: link previews under user messages, the Agent user-input
  answer from the composer (`_respondToPendingAgentUserInput`), and the
  context-threshold sheet on long press of the ring.

Manual acceptance (5e-5): long press the latest user message: edit, copy,
retry; long press an older one: copy only; retry: the reply is replaced and
the user message stays once; edit and send: the old round disappears in
both the native page and Flutter; tap a drawer conversation: it opens
natively, an OpenClaw one opens in Flutter.

### 5e-6 checkpoint (source complete; device acceptance pending)

- **Fix of a 5e-5 regression**: since drawer taps open the native page, a
  conversation whose Agent asked a question (`user_input` / elicitation) had
  no way to answer it. The composer now takes its text as the answer while
  a request is pending (`pendingUserInputCard`, `userInputResponseArgs`, a
  port of `_respondToPendingAgentUserInput` and
  `_singleComposerElicitationContent`: the single schema field is typed as
  integer, number, boolean or array). The send goes through the 5b
  `respondToServerRequest` entry and the card is marked submitted with the
  answer; on failure the text returns to the composer. While a question is
  pending the composer is not "processing", as on the Flutter page.
- **`/compact`** on pure chat: listed in the panel with `/record`, runs the
  native compactor (`ConversationDomainService.compactConversationContext`)
  with the conversation's model override, shows the compaction marker and
  maps the result like Dart (`compactionStatus`). Only this conversation's
  running turn blocks it.
- **Flutter hand-offs instead of dead ends**: OpenClaw, Sub Agent runs and
  remote Codex pages show "Open in chat"; `/record` and `/openclaw` open the
  Flutter chat on this conversation with the typed text as its draft
  (`LegacyDestination.Conversation.draft` → `nativeDraft`, never sent by
  itself). The hint no longer flashes while a target is still resolving.
- **Verification**: `AgentUserInputAnswerTest` 3, `ChatSlashCommandsTest`
  updated, `NativeChatComposerTargetTest` +1 (compaction status); `:app`
  1528, native-ui 76, 0 failures; androidTest compile and release resource
  merge succeeded. No Dart changes. No device run.
- **Compression threshold**: a long press on the context ring opens a
  Miuix sheet (Dart `_ContextThresholdSheet`: presets 32K to 1M and a typed
  positive integer, `parseContextThreshold`). It saves on confirm through
  `ConversationDomainService.updateConversationPromptTokenThreshold`; the
  Dart sheet autosaved 320 ms after each keystroke, so a half-typed number
  could reach the store. `ContextThresholdTest` 2.
- **Link previews** (`ui/chat/LinkPreviewService.kt`, a port of
  `services/link_preview_service.dart`): the same URL extraction (explicit
  and bare domains with the common-suffix list, Markdown punctuation
  trimmed, `omnibot://` resources and image links skipped, at most three),
  Open Graph > Twitter Card > page title order, an 8 s OkHttp fetch with one
  request per URL and a process cache. A native send stores loading
  placeholders in `content.linkPreviews` (the Flutter shape) and fills each
  one when its fetch returns, then persists. Cards render under user and
  assistant messages; a tap opens the link. `LinkPreviewServiceTest` 10
  (the five Dart cases, punctuation and dedup, stored previews kept, HTML
  parsing, a MockWebServer fetch with caching, the write-back).
- Still Flutter: the browser overlay, the workspace panel, the remote Codex
  runtime and OpenClaw themselves (reached through the hand-off).

Manual acceptance (5e-6): an Agent that asks a question (Codex plan
questions, an MCP elicitation): type the answer and send, the card shows
the answer and the turn continues; pure chat `/compact`: marker card, then
"Context compressed"; `/record`: the Flutter chat opens on the same
conversation; an OpenClaw conversation from the archive: "Open in chat".

### 5e-7a checkpoint: shared drafts (source complete; device acceptance pending)

- A share from another app (`McpFileReceiverActivity`) opens the native
  chat page when native Home is enabled: it starts `NativeHomeActivity`
  with the draft's request key (`EXTRA_SHARED_DRAFT_KEY`, read on create
  and on new intents), Home routes to `NativeNewChat(sharedDraftKey)`, and
  the page adopts the pending `SharedOpenDraftStore` draft once (text into
  the composer, files as attachments via `sharedDraftAttachments`,
  `sendToModel: false` kept), then clears it, as Dart
  `_applyStagedSharedDraftIfNeeded` does. A draft replaced by a newer share
  is left alone. With native Home off the Flutter route is unchanged.
- Verification: `NativeChatComposerTargetTest` +1; `:app` 1539, native-ui
  78, 0 failures; androidTest compile and release resource merge
  succeeded. No device run.

Manual acceptance (5e-7a), with `-Pomnibot.nativeHome=true`: share text and
an image from the gallery to Omnibot: a native new page opens with the text
and image in the composer and nothing sent; share again while the app is
open: a second page opens with the new draft.

### 5e-7b checkpoint: voice autoplay without Flutter (source complete; device acceptance pending)

- Assistant-reply autoplay is decided natively (`ChatRuntimeVoiceAutoplay`),
  but its speaker (`ChatRuntimeHost.voiceSpeaker`) was bound only by the
  Flutter `VoicePlaybackChannel`. With native Home running and no Flutter
  engine attached, replies on the native page were never spoken even with
  autoplay on. `ChatRuntimeHost.speak` now uses the Flutter-bound speaker
  when present and otherwise a host-owned `SceneVoicePlaybackManager`, so
  each reply is spoken by exactly one player.
- Verification: `:app` 1539, native-ui 78, 0 failures. Playback itself is
  device-only. No device run.

Manual acceptance (5e-7b), with `-Pomnibot.nativeHome=true` and voice
autoplay on: send from the native page without ever opening the Flutter
chat; the reply is spoken once. Then open the Flutter chat and send there:
still spoken once.

### 5e-7c checkpoint: visible conversation (source complete; device acceptance pending)

- The task runtime suppresses completion notifications for the
  conversation on screen and clears stale ones when it opens
  (`TaskRuntimeSettings.setVisibleConversation`). Only the Flutter page
  reported it (`_syncVisibleChatConversation`), so a reply finishing on the
  native page also posted a notification for it. The native page now
  reports itself on attach, on the first send that creates its
  conversation, and clears it on detach (kept across rotation).
- Pet overlay toggling already lives in the native Home top bar; the chat
  page has no pet control of its own to port.
- Verification: `:app` 1539, native-ui 78, 0 failures. Notification
  behavior is device-only. No device run.

Manual acceptance (5e-7c): send from the native page and keep it open
until the reply ends: no completion notification; leave the page before it
ends: the notification appears.

### 5e-7d checkpoint: tablet landscape layout (source complete; device acceptance pending)

- In a landscape window at least 960 x 600 dp (Dart
  `isHdPadLandscapeViewport`), Home and the native chat pages show the
  drawer as a permanent left pane; chat pages add the workspace browser
  (5e-8a) as a right pane. Other pages (settings, workspace, file preview)
  take the full width. `TabletShell` keeps the page stack in the same slot
  whatever panes show, so resizing a multi-window split or rotating keeps
  the page state.
- Widths follow `HdPadPaneLayoutResolver` (`TabletPanes.resolve`: left
  220–360, right 240–420, center at least 320). Dragging a divider resizes;
  dragging past the Dart collapse threshold collapses the pane. Widths are
  saved under the Flutter keys (`chat_hd_pad_left_pane_width` /
  `..._right_...`, in the `shared_preferences` double encoding), so both
  chats open at the same widths. Collapse state is per session, as in
  Flutter.
- Changes from Flutter: a collapsed pane keeps its divider as a rail that
  reopens it on tap (Flutter needed the menu button and its own workspace
  chip). Dividers are TalkBack buttons with a state description and a
  collapse/expand action. Home's menu button toggles the left pane instead of opening the
  modal drawer, whose swipe gesture is off on tablets.
- A conversation picked in the left pane replaces the chat beside it
  instead of stacking another page (Flutter switched the embedded thread).
- The embedded workspace pane leaves system back to the chat page beside it.
- Verification: `TabletPaneLayoutTest` (6, the Dart
  `chat_hd_pad_layout_test.dart` cases plus the collapse threshold) and
  `TabletPanePreferencesTest` (2). `:app` 1556, native-ui 96, 0 failures.
  No device run; layout, drag feel and the 280 ms pane animation are
  device-only.

Manual acceptance (5e-7d): on a tablet or a resizable emulator in
landscape, Home and a chat show the left pane; a chat shows the workspace
pane. Drag each divider to resize and past the threshold to collapse; tap a
rail to reopen; reopen the Flutter chat and confirm it uses the same widths.
Pick conversations in the left pane (no page stacking). Rotate to portrait
and resize a split window across 960 dp: the chat keeps its draft and
scroll position. With TalkBack, focus a divider and use its action.

### 5e-7e checkpoint: launch behaviors in native Home (source complete; device acceptance pending)

- `AppEntryBehaviors` now owns what only `MainActivity` did on launch and
  resume, and both entry activities call it:
  - responsive orientation (phones portrait, tablets free);
  - terminal auto-start tasks on a fresh launch;
  - the silent update check and the best-effort account session refresh on
    every resume.
- Legacy bugs fixed:
  1. **Native Home skipped all of them.** With `omnibot.nativeHome=true`,
     enabled terminal auto-start tasks never ran, the account session was
     not refreshed after app switching, no update check ran, and phones
     rotated Home to landscape. Covered by `AppEntryBehaviorsTest` (the
     orientation rule); the other three are launch/IO behaviors and
     device-only.
  2. **Every compatibility page re-ran terminal auto-start.** Each page
     native Home hands to Flutter starts `MainActivity`, which ran the
     auto-start tasks again. Sessions that were running reported
     `alreadyRunning`, but a task that had exited was restarted just by
     opening a Flutter page. `MainActivity` now skips auto-start for
     native-destination intents and on recreation.
- Unchanged on purpose: `AppUpdateManager.requestSilentCheckIfDue` passes
  `force = true` (since `9372e0b64`), so each resume fetches the release
  feed despite the 6-hour interval. That predates the migration and may be
  intended for the cloud-service policy, so it is noted rather than changed.
- Still `MainActivity`-only: deep-link routing (`SchemeUtil.pushRoute`) and
  the quick-log widget router; `LauncherActivity` sends any intent with data
  or extras to `MainActivity`.
- Verification: `:app` 1557, native-ui 96, 0 failures. No device run.

Manual acceptance (5e-7e), with `-Pomnibot.nativeHome=true`: enable a
terminal auto-start task, kill the app and launch: the task starts once; open
a Flutter compatibility page (e.g. Account) after stopping the task: it does
not restart. Rotate a phone on Home: stays portrait. Background the app past
the token lifetime and return: still signed in.

### Installing for acceptance

The variants carry an edition dimension, so the install task is
`./gradlew :app:installDevelopStandardDebug -Pomnibot.nativeHome=true`
(`installDevelopDebug` is ambiguous). Earlier hand-offs named the wrong
task.

### 5e-9 checkpoint: Sub Agent conversations open natively (source complete; turn not device-verified)

- Scheduled Sub Agent runs (`mode = subagent`, with a parent and a task id)
  now open on the native page from the drawer, the archive and their
  completion notifications (5e-7f), instead of handing off to Flutter.
- A follow-up message continues the run the way the Flutter page did
  (`_dispatchUserMessage` on the normal page → `_handleExecutableTaskFlow`):
  the normal runtime, the Xiaowan Harness, the dispatch scene model (never a
  pure-chat override), no permission menu, and `conversationMode` stays
  `subagent`. Thinking is cleared on failure only for pure chat, as in Dart.
- Still Flutter: OpenClaw and remote Codex.
- Verification: `NativeChatComposerTargetTest` and `OpensNativelyTest`
  updated; `:app` 1561, native-ui 97, 0 failures. On the emulator a seeded
  Sub Agent conversation opens natively with the composer and no permission
  button; no model provider was configured, so no turn was sent.

Manual acceptance (5e-9): let a scheduled Sub Agent task run, open it from
the drawer and from its notification: native page, history shown; send a
follow-up: it runs with Xiaowan on the scene model and the conversation stays
in the Sub Agent section.

### First emulator pass (2026-10-10)

The first run of native Home on a device: Pixel 10 Pro emulator, API 37.1,
`-Pomnibot.nativeHome=true`; tablet checks with `wm size 1600x2560`,
`wm density 320` in landscape. No crash on launch.

Verified working: cold launch into native Home; workspace browser
(listing, two-level expansion, descend at depth 2, breadcrumbs, back climbs
a folder); Markdown preview; edit, rotate with an unsaved draft (kept),
save (written atomically, no temp file left); a 1.1 MB multi-line log
(truncated, read-only); tablet panes (drawer pane, workspace pane on chat,
drag resize clamped to 360 dp and saved in the Flutter double encoding,
drag past the threshold collapses, the rail and the menu button reopen);
a chat draft survives landscape → portrait → landscape; phones stay
portrait.

Defects found and fixed (each now named here):

1. **Overlays outside the page Scaffold never showed** (5e-8a). The file
   preview's leave/discard confirmations and every browser sheet and dialog
   (actions, rename, delete, move) were declared after `OmniPage`; Miuix
   overlays render in a Scaffold's popup host, so they composed but never
   appeared and back went straight to the previous page. Moved inside the
   page content, like every other native screen.
2. **Back on the leave confirmation also left the page.** With the dialog
   open, a back press closed the dialog and fell through to the page stack,
   losing the draft. The page's back handler now stays enabled while its
   dialogs are open and only closes them.
3. **The drawer raised the keyboard on every open** (native Home since
   batch 1). `ModalNavigationDrawer` requests focus on its content when it
   opens; with no focusable container the focus reached the search field,
   and the keyboard stayed up on the page the drawer opened next. The
   drawer column is now focusable, so it takes that focus; tapping search
   still opens the keyboard.
4. **A 1 MB single-line file exhausted memory** (5e-8a). Laying out one
   very long line grew native heap past 1.2 GB within seconds and took
   down the app and system processes across the emulator. The read-only
   preview cuts lines over 4,000 characters and marks the file read-only
   (`previewCutsOnlyOverlongLines`); the 1 MB read limit alone was not
   enough. Re-measured: native heap stays at 18 MB.
5. **Tablet: the page stack drew over the drawer pane** (5e-7d). The
   outgoing page of a transition stayed composed and offset outside the
   center slot. The center slot is now clipped when the panes show.

Not covered yet on the emulator: notifications, voice, share-in,
TalkBack, predictive back gestures (only key presses were sent), process
death.

### 5e-7f checkpoint: notifications open native chats (source complete; device acceptance pending)

- Task-completion and scheduled Sub Agent notifications build
  `/home/chat?conversationId=…&mode=…` and start `MainActivity`. With native
  Home on, `MainActivity` now clears the notification as before, then
  forwards those routes to native Home (`NativeEntryRoutes`) and finishes
  without starting the Flutter engine's page. Producers are unchanged.
- Native Home waits for its conversation list, then opens the conversation
  on the native page when `opensNatively` allows (not if the same chat is
  already on top) and otherwise hands it to the Flutter chat. Notification
  modes (`normal`) and stored modes (`agent`) are compared through
  `conversationModeKey`.
- Unchanged: other routes (memory center from the quick-log widget, an
  untargeted `/home/chat`) still open Flutter pages, and with native Home
  off nothing changes.
- The first-use spotlight tour stays in Flutter: it is only reached from
  the Flutter onboarding (`ChatPage(showFirstUseTour: true)`), which is not
  migrated yet; it moves with onboarding.
- Verification: `NativeEntryRoutesTest` (3, using the producers'
  `TaskCompletionNavigator.buildChatRoute`); `:app` 1560, native-ui 96,
  0 failures. No device run.

Manual acceptance (5e-7f), with `-Pomnibot.nativeHome=true`: leave a native
chat before its reply ends, tap the completion notification: the native
page opens on that conversation and the notification is gone; tap it again
while that chat is open: no second page. A scheduled Sub Agent notification
opens the Flutter chat (Sub Agent runs are Flutter-only). Kill the app and
tap a notification: native Home starts and opens the conversation.

### 5e-8a checkpoint: workspace browser and file preview (source complete; device acceptance pending)

The tablet layout (5e-7d) needs a workspace pane, so the workspace browser
moves first. Native Home's Workspace button now opens a native page instead
of the Flutter chat.

- **Browser** (`WorkspaceBrowserScreen`, `NativeWorkspaceBrowserViewModel`,
  `WorkspaceFileRepository`): breadcrumbs from `/workspace`, folders first,
  inline expansion two levels deep and then descend, mounts marked and
  unmount-only, long-press sheet (edit, rename, move, delete), multi-select
  with folder include/child exclude and bulk delete. Back leaves selection
  mode, then climbs a folder, and only leaves the page at the root. A
  refresh is fenced by a generation so a slow listing of a folder the user
  left never replaces the current one. Pure rules
  (`WorkspaceBrowserModels.kt`) are ported from
  `omnibot_workspace_browser.dart`.
- **Move** uses a folder picker instead of drag-and-drop (the Dart drag only
  reached folders on screen and was not reachable with TalkBack). The same
  checks refuse a move into itself or a descendant, into the current parent,
  of a mount, or onto a taken name.
- **File preview** (`WorkspaceFileScreen`, `NativeWorkspaceFileViewModel`):
  text, Markdown (`ChatMarkdownText`) and code with an editor; images
  decoded with sampling; a draft survives rotation and process death
  (`SavedStateHandle`), and leaving with unsaved edits asks first.
  Open-with, open-in-browser (HTML), share and save-to-device (system
  "create document" picker) are in the toolbar. PDF, HTML, Office and media
  files show their details and hand off to the system: the Flutter page's
  embedded WebView/PDF/media players are not ported yet.
- **File hand-offs** (`SharedFileIntents`): the FileProvider staging, mime
  fallback and per-resolver grants moved out of `FileSaveChannel`, which now
  calls the same code, so Flutter and native stage files identically.
- **Links and tool cards**: on the native chat page, `omnibot://` links and
  the workspace/preview/open/save tool actions open these pages
  (`WorkspaceResourcePaths`, a port of the resource service's uri and shell
  path mapping) instead of the "open from the chat page" toast. Public
  storage still goes through the Flutter flow that requests the all-files
  permission.

- **Links are never followed when deleting**: a mount is unmounted by
  removing the link, and a symlink nested in a deleted folder is removed as a
  link, so host files are never touched
  (`aNestedLinkIsDeletedAsALink`,
  `deletingAMountUnmountsWithoutTouchingTheHostFiles`). This is a
  safeguard, not a reproduced Flutter bug.

Legacy bugs fixed (each covered by a Kotlin test):

1. **Saving wrote in place.** An interrupted save left a truncated file.
   Saves now write a sibling temp file and rename it atomically, writing
   through a symlinked file to its target (`writeReplacesContentAndLeavesNoTempFile`,
   `writingThroughALinkKeepsTheLink`).
2. **Large or non-UTF-8 files broke the preview.** Dart read the whole file
   with a strict decoder, so a binary-ish log failed to open and a huge one
   could exhaust memory. The preview reads at most 1 MB, replaces malformed
   bytes and opens a truncated file read-only
   (`readTextTruncatesLargeFilesAndReplacesMalformedBytes`).
3. **Native Home sent some native destinations to Flutter.** The host handed
   every pending destination except ModelProviders to Flutter while
   `NativeHomeApp` also pushed native pages for terminal focus and shared
   drafts. Both effects read the same composition's value, so both ran: the
   Flutter page opened over the native one. For shared drafts this was a
   5e-7a regression (the Flutter chat also adopted the draft). `LegacyDestination.opensNativePage` now names
   every destination the native app owns
   (`destinationsNativeHomeOpensAreNotHandedToFlutter`).

- Still Flutter: the in-chat workspace panel and the Flutter `/home/omnibot_workspace`
  and `/home/omnibot_artifact_preview` routes (used by the Flutter chat and
  ChatBotSheet), the remote Codex workspace browser, and embedded
  PDF/HTML/Office/media preview.
- Verification: new `WorkspaceBrowserModelsTest` (12),
  `WorkspaceFileRepositoryTest` (12, real temp directories and symlinks) and
  `WorkspaceResourcePathsTest` (3). No device run.

Manual acceptance (5e-8a):
1. Home → Workspace: the root lists folders first, mounts are marked;
   expand two levels, the third opens as the current folder; breadcrumbs
   and back climb up.
2. Rename, move (picker) and delete a file and a folder; try the refused
   moves (into itself, onto a taken name, a mount); unmount a mount and
   confirm the host folder is untouched.
3. Multi-select a folder, exclude one child, delete: only the child stays.
4. Open a `.md`, edit, rotate, then back: the draft survives and leaving
   asks first; save and reopen. Open a large log: truncated, read-only.
5. Open an image, a PDF and an HTML file: image renders; PDF/HTML offer
   open-with/browser/share; save to device writes a copy.
6. In the native chat, tap an `omnibot://workspace/...` link and a tool
   card's preview action: the native pages open. Settings → Terminal from a
   focus link opens only the native page (no Flutter page behind it).

## Batch 5d-1 plan: Compose composer (2026-10-07; 5d-1a, 5d-1b and 5d-1c source complete)

| Slice | Scope |
| --- | --- |
| 5d-1a | Native reader/writer of the Agent command preferences (`AgentCommandPreferences`), on the Flutter keys. |
| 5d-1b | Composer state machine and the Compose composer (text field, attachments, primary action, context ring, permission menu), hosted on the native transcript page; sends through `ChatTurnLauncher` with settings from 5d-1a. |
| 5d-1c | Slash command panel (built-in + advertised ACP commands, `/model`, `/plan`, `/init`, `/effort`). The free-form ACP config panel moves with the page shell (5e). |

Manual recording, OmniFlow tooling and the command overlay sheet keep the
Flutter composer behind their entries until 5f.

### 5d-1a checkpoint (source complete, unwired)

- `projection/AgentCommandPreferences.kt`: read (conversation value, else
  global), write (global plus conversation scope), clear, and
  `turnSettings` (effort normalized, permission defaults to full access);
  `normalizeAgentReasoningEffort` ports the Dart normalizer. Keys are the
  Flutter `chat_agent_command_preference.*` keys under `flutter.`, so both
  composers share values while both exist; the model key carries its model
  source.
- Not ported: the legacy `chat_codex_command_preference` fallback. Dart
  called `getString(key, defaultValue: '') ?? getString(legacyKey)`, so the
  fallback never ran; reading it natively would revive values the page had
  cleared (verified against `StorageService.getString`).
- Verification: `AgentCommandPreferencesTest` 7. Nothing calls it yet.

### 5d-1b checkpoint (source complete; device acceptance pending)

- **Compose**: `chat/ChatComposer.kt` (Miuix `TextField`, three lines then
  scroll; attachment chips; `+` picker; permission menu on Miuix
  `OverlayListPopup` + `DropdownImpl`; context ring; send/stop button) and
  `ChatComposerModels.kt` (primary action, attachment payload, ring
  thresholds 85% / 100%, permission choices). `ChatTranscriptScreen` hosts
  it as the bottom bar with IME and navigation-bar insets.
- **ViewModel** (`NativeChatTranscriptViewModel`): resolves the send target
  (`NativeChatComposerTarget`: Agent conversations through their own
  Harness on the Agent runtime, pure chat with no Harness on the normal
  runtime; a live runtime keeps its mode; OpenClaw, Sub Agent and remote
  Codex stay Flutter-only and show a hint instead). Settings come from
  `AgentCommandPreferences` (Agent) or the conversation override and effort
  (pure chat), terminal variables from `OmnibotTerminalEnvironment`.
  Sending inserts the user row and launches through the new
  `ChatRuntimeHost.launchTurnDetached`, which runs in the host scope so
  leaving the page never cancels a turn between admission and its
  PromptResponse; the page's fence (`surfaceOpen`) stops a turn that has
  not prompted yet. Stop goes through `dispatcher.cancelTurn`. Permission
  choices are written on the Flutter keys. Attachments come from the system
  document picker as `content://` uris, which the runtime already copies
  into the workspace.
- **Guarded here**: the preview loads only 200 history rows, and admission
  persists the runtime's messages as the conversation; a runtime created
  from that page would have replaced older history. `seedRuntime` builds it
  from every stored message first (as the Flutter page's
  `onConversationLoaded` does). Covered by a launcher test that admission
  appends to seeded history.
- **Found by the new test**: `ChatComposerAttachment.toMap` first used
  `linkedMapOf().apply { size?.let { put("size", it) } }`, where `size` is
  the map's own entry count, so every attachment reported a size of 3.
- **Verification**: `ChatComposerModelsTest` 5, `NativeChatComposerTargetTest`
  6, `ChatTurnLauncherTest` +1; `:app` unit suite 1518, native-ui 58, 0
  failures; androidTest compile and release resource merge succeeded. No
  Dart changes. No device run.
- Not verified: the stored Agent model is sent as-is (the Flutter page
  validates it against the loaded catalog first); a stale stored model id
  would reach the Harness. The model picker and slash panel are 5d-1c.

Manual acceptance checklist (5d-1b):

1. Open an Agent conversation from the drawer's native preview: send a
   message, watch it stream, stop it; the Flutter chat shows the same turn.
2. Open a long conversation (over 200 messages), send once, reopen it in
   Flutter: older messages are still there.
3. Pure-chat conversation: sends with its model override and no
   permission menu; OpenClaw and remote Codex show the "open in chat" hint.
4. Change permission natively, then open the conversation in Flutter: the
   same choice is selected.
5. Attach an image and a file; send; leave the page mid-reply: the reply
   finishes and the spinner clears in Flutter.

### 5d-1c checkpoint (source complete; device acceptance pending)

- **Pure rules** (`chat/ChatSlashCommands.kt`): `resolveSubmit` ports
  `resolveAgentSlashSubmitIntent` and `_tryHandleSlashCommand`; `entries`
  ports the panel cards (built-ins, then advertised commands without
  duplicates, prefix filter; `/model` route by substring with the selected
  model first; pure-chat `/effort` stops). `ChatSlashPanel` renders the rows
  above the composer; a row fills the draft or submits through the same path
  as typing it. Manual recording, `/compact` and `/openclaw` stay Flutter
  flows and show an "open in chat" notice.
- **ViewModel**: `submit` routes the draft. `/model` rebinds
  `scene.dispatch.model` through `SceneModelSettingsRepository` (catalog in
  `NativeChatModelCatalog`: bound Provider's discovered + manual models) and
  moves the live session with `session/set_config_option`; `/plan` sets the
  advertised `collaboration_mode` value and stores it on the Flutter key;
  `/init` sends the AGENTS.md prompt shown as `/init`; pure-chat `/effort`
  writes the conversation's effort map. Agent turns now send the bound
  dispatch model instead of a stored per-Harness id, which closes the 5d-1b
  "stale model id" gap.
- **Fix 8** (Flutter and native): `/model` on a shared-Provider Agent called
  `AgentRuntimeService.disconnect()` with no running-turn check, and
  disconnect cancels every in-flight turn in every conversation. Both
  composers now refuse configuration changes while any turn runs
  (`configLocked`, Dart `hasAnyInFlightTask`). Verified from
  `AgentRuntimeManager.disconnect` / `LocalAcpRuntime.disconnectLocked`.
- **Verification**: `ChatSlashCommandsTest` 7, `NativeChatModelCatalogTest`
  2, Dart architecture test for the guard. `:app` 1520, native-ui 65, 0
  failures; androidTest compile and release resource merge succeeded.
  `flutter test` 972 passed, 4 failed (the same baseline tests); `flutter
  analyze` 0 errors. No device run.

Manual acceptance (5d-1c): type `/` in a native Agent conversation (rows
match the Flutter panel, advertised commands appear); `/model` lists the
Provider's models and switching changes the next reply's model in both
composers; `/model` while another conversation is replying shows the busy
notice and that reply continues; `/plan` toggles and `/plan <prompt>` sends
in plan mode; `/init` shows as `/init`; pure chat `/effort high` applies.

## Batch 5c-4 checkpoint: run groups, tool activity strip and message anchors (source complete; device acceptance pending)

- **Content** (all native-ui `chat/`; no app-side presenter needed):
  - `AgentRunGroupBlock` ports `AgentRunGroupMessage` (ACP presentation):
    one `AgentRunHeader` per turn (brand avatar, shimmering "正在处理 Ns" or
    "<live tool> · Ns" while running, "已处理 / 执行失败 / 已取消  1m 5s"
    after, fold chevron only when there is history), arrival-order segments,
    a 320 ms fold for process cards and non-final prose, plans and failures
    kept out of the fold, the first thinking card's avatar dropped, and
    consecutive tool cards collapsed into `_AgentToolCallGroup` rows (live
    title or "已处理", count, chevron). Finished runs start folded; running
    runs are always open.
  - `ChatActivityModels`: `resolveAgentToolActivitySnapshot`,
    `shouldShowAgentToolActivitySnapshot`, `resolveActiveAgentToolMessage`
    and `buildChatMessageAnchors` ported from `tool_activity_utils.dart` /
    `chat_message_anchor_bar.dart`.
  - `ChatToolActivityStrip`: active row (status dot, title, type label,
    status tag) with a stop button while running, the run's other tools in a
    drawer above (≤264dp, newest next to the active row); rows open the tool
    detail sheet. `ChatMessageAnchorBar`: round button above the list that
    opens a popup list of avatars + first lines; tapping scrolls the list to
    the entry.
  - The shared `components/AgentBrandIcon` moved out of `AgentsScreen`
    (same mapping, adds size/tint and `hasKnownAgentBrand`).
  - The strip follows the run the user expanded last
    (`expandedAgentRunTaskOrder`), like Flutter.
- **Owner decisions**:
  - Stop goes through the 5b `dispatcher.cancelTurn` (`session/cancel`)
    with the snapshot's `activeAcpSessionId` / `activeAcpTurnId` and the
    card's `runId`; ACP has no per-tool cancel. The button stays disabled
    until the card leaves `running`; failures show
    "停止工具调用失败，请稍后重试". The preview page's runtime writes are now
    approval answers and this stop.
  - Not ported (Miuix-first simplification or still owned elsewhere): the
    strip's browser/terminal preview thumbnails and glass cutout, the
    slash-command strip (`ChatCommandActivityStrip`, composer-owned, 5d),
    the anchor fan layout, long-press magnifier and system-bar spotlight
    (replaced by a plain popup list), and the non-ACP Xiaowan run header
    (Flutter now uses ACP presentation for every mode).
- **Verification boundary**: native-ui tests 53 (adds `ChatActivityModelsTest`
  10, porting the snapshot cases of `chat_tool_activity_strip_test` plus
  anchors and elapsed labels); full `:app` unit suite 1481, 0 failures.
  Gradle compile / androidTest compile / release resource merge succeeded;
  no Dart edits; `git diff --check` clean. No device run.

Manual acceptance checklist:

1. During a run the header shimmers "正在处理 Ns" and switches to the live
   tool's title; when it ends it folds to "已处理 …" and only the final reply
   stays; tapping the header unfolds it smoothly.
2. Several consecutive tools show one row with a count; tapping expands them.
3. While a tool runs the strip shows it with the stop button; stopping ends
   the turn and the Flutter chat page shows the same cancellation.
4. After the run, expanding its header shows its tools in the strip;
   folding hides the strip.
5. The anchor button lists the conversation's messages; tapping one scrolls
   to it.

## Batch 5c-3 checkpoint: request, thinking and plan cards (source complete; device acceptance pending)

- **Content**:
  - App (`ui/chat/`): `AgentChatCardPresenter` ports `AgentRequestNotice`
    presentation (`_compactRequestPresentation` schema-field title/detail and
    `可选：` choices, `_cardStatus`, unavailable/session-ended rules) and the
    `CardWidgetFactory` `deep_thinking` branch (stage/isLoading defaults,
    64-bit timestamps, primary `<taskId>-thinking` avatar rule, English
    line-by-line localization). `presentPlanEntries` ports `_PlanEntriesBlock`
    data (`planEntries`/`entries`, content/title/text, `任务 n` fallback,
    completed / in-progress / pending). `ToolCardCache` became
    `ChatCardCache` (tool, request and thinking cards, still keyed by the
    content map instance).
  - native-ui (`chat/`): `AgentRequestNotice` (Miuix `TextButton` deny /
    primary allow, outcome labels, composer hint for user input),
    `DeepThinkingCard` (avatar + status row with shimmer while thinking,
    "思考完成 (用时n秒)" once both boundaries exist, chevron fold, 210dp
    window that follows the newest line until the user scrolls up, bottom
    fade, cancelled footer), plan rows under the plan capsule,
    `ContextCompactionMarker` and `HistoryOmittedCard`. The shimmer brush is
    shared with the tool title (`rememberShimmerBrush`). 4 Lucide icons.
  - Preview page: thinking cards show the existing avatar
    (`loadAgentAvatarPreview`); the first thinking card of a run group drops
    it because the group header names the Agent.
- **Owner decisions**:
  - Approval answers are the preview page's only runtime write. They go
    through `ChatRuntimeHost.dispatcher.respondToServerRequest` (the 5b
    entry) with the Dart `respondToApproval` payload, require `{ok: true}`,
    and only then set `status`/`submittedAnswers` through
    `coordinator.replaceRuntimeMessage` + `publishDirtySnapshots` +
    `schedulePersistRuntimeConversation(persistMessages)`, so the Flutter
    mirror and Room history see the same card. Answers are offered only for
    live snapshots; stored history rows show "该请求当前无法操作".
  - The Dart notice also wrote `agent_request_response.*` SharedPreferences
    keys; only the full `AgentRequestCard` (not used by the timeline) reads
    them, so the native path does not write them.
  - Flutter's paced character reveal and the parent-scroll hand-off are not
    ported: Compose nested scrolling hands overscroll to the list.
  - Still placeholders (kept for 5c-4 or later owners): the legacy Xiaowan
    `isExecutable` "准备执行任务…/取消任务" footer, `stage_hint`,
    `permission_section` (needs the Flutter authorize flow),
    `openclaw_attachment`, `artifact_card` (resource service), `acp_audio`
    and audio/image/VLM/subagent content inside tool cards.
- **Verification boundary**: app `ui.chat` tests 51 (adds
  `AgentChatCardPresenterTest` 11, porting the presentation decisions of
  `agent_request_card_test` and `deep_thinking_card_test`, plus request /
  thinking mapping); full `:app` unit suite 1481, 0 failures; native-ui unit
  tests 43, 0 failures. Gradle compile / androidTest compile / release
  resource merge succeeded; no Dart edits; `git diff --check` clean. No
  device run.

Manual acceptance checklist:

1. Start a Claude Code / Codex run with approval mode on, open "原生消息预览"
   from the drawer: the request card shows allow/deny; tapping allow
   continues the run, the card reads "已允许" in both the preview and the
   Flutter chat page, and survives reopening the conversation.
2. Turn off the network or kill the agent before answering: the toast
   "回复未送达，可以重试" appears and the buttons stay usable.
3. While thinking streams, the status shimmers and the text follows the
   newest line; scrolling up inside it stops following. When finished it
   folds to "思考完成 (用时n秒)"; tapping expands it.
4. A plan update shows the plan capsule with completed / in-progress /
   pending rows; context compaction shows the centered chip.
5. Dark/light themes and English locale.

## Batch 5c-2 checkpoint: tool summary, transcript and diff cards (source complete; device acceptance pending)

- **Content**:
  - App (`ui/chat/`): `presentAgentToolCard` ports the display logic of
    `AgentToolSummaryCard` / `tool_activity_utils` / `agent_tool_transcript` /
    `terminal_output_utils` and `AgentAcpCardNormalizer` method by method
    (titles, progress title, type/status labels, inline vs capsule style,
    transcript prompt/output, copy text, actions, diff extraction) into the
    immutable `AgentToolCardUi`. It reuses the 5a projection parsers
    (`AgentDiffParser`, `AgentToolCallParser`, `DartJson`); nothing is
    duplicated. `LegacyTextLocalizer` is ported in full (423 exact entries plus
    the regex rewriters) and takes the locale as a parameter.
    `ToolCardCache` re-presents a card only when its content map changes.
  - native-ui (`chat/`): `AgentToolCard` (status capsule with spinner/icon,
    shimmer title while running, diff-stat chip; flat inline row for file and
    agent-native tools whose diff expands in place), `AgentDiffView` (lazy,
    wrapping unified diff with gutters and the GitHub-like palette),
    `AgentToolDetailSheet` (Miuix `OverlayBottomSheet`: type/status chips,
    copy, ANSI-colored terminal transcript or diff, action buttons),
    `ansiAnnotatedString` (port of `AnsiTextSpanBuilder`), 14 Lucide icons.
- **Owner decisions**:
  - Detail actions: app routes (`route` with an in-app path) hand off to the
    Flutter page like plugin routes; workspace/file preview/save actions need
    the Flutter resource service and show a hint in the preview until 5e.
  - Deferred to 5c-3: subagent timeline, plan entries, VLM result, image
    preview and audio inside tool cards (they render as a normal capsule).
  - App JVM unit tests now run on a Java 21 launcher so they can load
    native-ui classes (native-ui compiles to class file 65).
- **Verification boundary** (at 5c-2): app `ui.chat` tests 39 (ported
  `agent_tool_transcript_test`, `terminal_output_utils_test`,
  `agent_acp_card_normalizer_test`, the label/style decisions of
  `agent_tool_summary_card_test`, localizer, snapshot mapping) and projection
  tests 336, 0 failures; native-ui chat tests 35 (adds `AnsiTextTest`).
  Skipped Dart cases: VLM, image, subagent, appearance color, ANSI widget test
  (ported natively). The full `:app` unit suite now passes (1469 tests, 0 failures): the 16 classes that failed at the 5a baseline pass on the Java 21 launcher.
  `flutter test` / `flutter analyze` unchanged (no Dart edits). Gradle
  compile/native-ui tests/androidTest compile/release resource merge
  succeeded; `git diff --check` clean. No device run.

Manual acceptance checklist:

1. In the preview, terminal/search/MCP tools show capsules; running ones spin
   and shimmer, finished ones show the status badge and colors.
2. A file edit row shows the file name highlighted and `+n -m`; tapping
   expands the diff; long diffs scroll.
3. Tapping a capsule opens the detail sheet; copy works; ANSI colors render;
   a schedule tool's "查看定时任务" opens the Flutter page.
4. English locale: labels switch to English.

## Batch 5c-1 checkpoint: run timeline, Markdown and read-only transcript preview (source complete; device acceptance pending)

- **Slices of 5c** (owner decision: Miuix-first, matching structure rather
  than pixel parity; each slice renders live snapshots on the preview page):
  - 5c-1: message model, run timeline, Markdown/LaTeX text, message list and
    the read-only preview page (this checkpoint).
  - 5c-2: tool summary / transcript / diff cards.
  - 5c-3: request/approval cards (actions go through the 5b
    `respondToServerRequest` entry), deep thinking, plan and small markers.
  - 5c-4: run groups, tool activity strip, anchor bar (source complete).
- **Content**:
  - native-ui `chat/`: `ChatMessageUi` (field-for-field mirror of the runtime
    `ChatMessage`, identity getters runId/sessionId/turnId/toolCallId),
    `AgentRunTimeline` (method-by-method port of `agent_run_timeline.dart`,
    Dart names kept except `startedAtMillis`/`finishedAtMillis`), the
    timeline-only subset of `chat_message_kinds`, `ChatMarkdownText`
    (Markwon 4.6.2 + tables/strikethrough/linkify + JLatexMath; inline `$…$`
    normalized to `$$…$$` outside code), `ChatMessageList` /
    `ChatTranscriptScreen` (user bubble, assistant Markdown, run group header
    with folded process messages; cards render as a labelled placeholder until
    5c-2/5c-3).
  - Navigation: `HomeRoute.ChatTranscriptPreview`, opened from the drawer and
    archive long-press sheet ("原生消息预览"), only in the native home.
  - App: `NativeChatTranscriptViewModel` listens to the native coordinator
    and mirrors the newest snapshot of that conversation; without a live
    runtime it reads stored history (200 rows) from
    `ConversationDomainService`. It never sends commands; the Flutter chat
    page stays the only interactive surface until 5e. Links open only for
    http(s).
- **Verification boundary**: native-ui `AgentRunTimelineTest` 29 (ported from
  Dart) and `ChatMarkdownMathTest` 3 pass; projection Kotlin tests 336, 0
  failures. App JVM tests cannot load native-ui classes (native-ui compiles to
  class file 65, app tests run on JDK 17), so the snapshot → `ChatMessageUi`
  mapping is checked by compilation only. `flutter test` 967 passed with the
  same 4 pre-existing failures; `flutter analyze` exit 0. Gradle compile /
  native-ui tests / androidTest compile / release resource merge succeeded;
  `git diff --check` clean. No device run.

Manual acceptance checklist:

1. Long-press a conversation in the native drawer → "原生消息预览": history
   renders newest at the bottom; user bubbles right-aligned; Markdown tables,
   code, links and `$x$` / `$$x$$` math render.
2. Start an Agent run in Flutter, open the preview: the header says live
   runtime, the run group shows running and updates as tools/thinking arrive;
   tapping the header folds the process messages.
3. Dark and light themes; back returns to the drawer.

## Batch 5b checkpoint: native prompt admission (source complete; device acceptance pending)

- **Content**: `ChatPromptDispatcher` (app module, owned by `ChatRuntimeHost`)
  is the single native entry for chat prompt admission. It reserves the ACP
  session (`session/new` + coordinator `bindAcpSession`, closing a session the
  run no longer owns), sends `session/prompt`, and applies the official
  PromptResponse or the transport error through the one coordinator.
  `session/cancel`, `$/cancel_request` and `respondToServerRequest` from
  Flutter land on the same entry (`AgentRuntimeChannel` routes them), as does
  the runtime-less scheduled Sub Agent prompt. Chat pages and the
  command-overlay sheet call `ChatPromptDispatcher.instance`
  (`prepareTurnSession` / `submitTurnPrompt` / `releaseTurnSession`); no chat
  surface calls the prompt transport (guarded by `chat_architecture_test`).
- **Owner decisions**:
  - Error text for transport failures is produced natively by
    `AgentUserErrorText` (the full port of `formatAgentRuntimeErrorForUser`,
    now shared with the reducer), from the same `userFacingMessage` /
    `failureKind` the channel used to report.
  - Page navigation stays with the page: it checks its own target before
    reserving and again after the reservation returns, and releases the
    reservation when it moved on. The Harness switch send barrier also stays
    page-side: it sequences the page's own target installation, while native
    admission is protected by task ownership (`isTaskActive`).
  - Busy/idle comes from the 5a snapshot (`isAiResponding`, `boundTaskIds`).
- **Behavior differences**: the command-overlay sheet now binds its ACP
  session like the main page, and shows the formatted failure text instead
  of the raw exception string. In the Agent page, remote-thread adoption runs
  after the PromptResponse is applied instead of just before.
- **Verification boundary**: projection Kotlin tests 336, 0 failures
  (adds `ChatPromptDispatcherTest`: reserve/reuse/abandon-close/failure as
  PromptResponse/release/submit/classified errors/single entry); other `:app`
  failures unchanged from baseline. `flutter test` 967 passed with the same 4
  pre-existing failures; `flutter analyze` 0 errors. Gradle compile/native-ui
  tests/androidTest compile/release resource merge succeeded; `git diff
  --check` clean. No device run.

Manual acceptance checklist:

1. Send in Agent, Xiaowan and pure-chat modes; stop during status, session
   creation and streaming; no leaked sessions, no stray error bubbles.
2. Switch Harness or conversation while a send is preparing: no prompt
   reaches the old target and its session is closed.
3. Provider errors (quota, auth, timeout, disconnect) show the same short
   text as before in the failure card.
4. Approve/decline requests, answer user-input and elicitation requests,
   cancel requests; scheduled Sub Agent tasks still run.
5. Command-overlay sheet: send, stop, close-retry, and a failed send.

## Batch 5a checkpoint: ACP projection owner moved to Kotlin (source complete; device acceptance pending)

- **Content**: `AgentEventReducer`, `ChatConversationRuntimeCoordinator`,
  `ChatConversationRuntimeState` and the event helpers (identity, stream
  meta, message kinds, ACP extension registry, tool-call and diff parsers)
  are ported method by method to `app/.../agent/projection`. The Dart
  reducer, the coordinator's part files and the runtime state class are
  deleted; `ChatConversationRuntimeCoordinator` in Dart keeps its public API
  as a snapshot mirror plus command forwarder (`chat_runtime_mirror.dart`).
  The tool/diff parsers and message-kind helpers stay in Dart for card
  rendering only (5c).
- **Owner decisions**:
  - `ChatRuntimeHost` (process singleton, main thread) owns the coordinator,
    takes `AgentRuntimeManager`'s primary event listener (which keeps
    buffering until the first surface attaches, as it did for Flutter) and
    publishes snapshots on `ChatRuntimeEvents`; commands arrive on
    `ChatRuntime`. The `AgentRuntimeEvents` EventChannel and
    `AgentRuntimeService.events` are removed: no Dart code sees raw ACP
    events.
  - Event attribution is the 5a-0 routing ported natively; surfaces publish
    their `ChatRuntimeRoutingContext` whenever it changes (checked after each
    frame and before turn admission).
  - Snapshots carry a coordinator-wide revision and only the messages the
    UI does not yet hold; the mirror keeps row listenables, ignores late
    batches and requests a resync when it misses a message. Text caches
    cross only as keys. `replaceConversationSnapshot` carries the revision
    it was built from; a newer runtime treats it as a live refresh.
  - Commands that return values (`applyAcpPromptResponse`, `bindAcpSession`,
    `finishTaskFromAuthoritativeSnapshot`, `unregisterTask`, persistence)
    are async and resolve after the mirror includes their change; call sites
    in send/cancel now `await` them. `isTaskActive` reads `boundTaskIds` from
    the snapshot.
  - Persistence: `NativeChatRuntimeHistoryStore` calls
    `ConversationDomainService` directly and keeps the Dart history-service
    semantics (per-conversation write order, digest dedupe, failure throws,
    legacy snapshot cleanup, latest-metadata merge). Summary generation is
    shared through `ConversationSummaryGenerator`.
  - Reply voice autoplay moved with the owner (`ChatRuntimeVoiceAutoplay`,
    speaking through `SceneVoicePlaybackManager`); Flutter
    `VoicePlaybackCoordinator` keeps manual play/pause/replay only.
  - IM/external user messages (`FlutterChatSyncBridge`) go straight into
    the native runtime instead of round-tripping through Flutter.
  - Dart code with no caller (streaming text batches, link-preview
    resolution, an old tool-card helper and related thinking helpers) was
    not ported.
- **Known differences**: the native metadata merge can read hidden Agent
  conversations that the Dart lookup skipped (it then preserves their stored
  fields). Page commands that used to mutate state synchronously are applied
  optimistically to the mirror and confirmed by the owner.
- **Verification boundary**: Kotlin `:app` unit tests — projection package
  324 tests, 0 failures (ported reducer/coordinator/routing/voice suites plus
  snapshot semantics); the other 45 failures in 16 `:app` test classes match
  the pre-change baseline. `flutter test`: 968 passed, the same 4
  pre-existing failures; the 12 remote-Codex snapshot-mapper cases moved to
  `remote_codex_snapshot_mapper_test.dart`; overlay/drawer widget tests use
  `FakeNativeChatRuntime`. `flutter analyze` 0 errors, no new warnings.
  `:app:compileDevelopStandardDebugKotlin :native-ui:testDebugUnitTest
  :native-ui:compileDebugAndroidTestKotlin
  :app:mergeProductionStandardReleaseResources` succeeded; `git diff --check`
  clean. No device run.

Manual acceptance checklist:

1. Agent, Xiaowan and pure-chat turns stream text, reasoning, tool and
   approval cards without flicker; row-level updates only (no full list
   rebuild per chunk).
2. Stop during status/connect/session-new/prompt; late events never reach
   the next turn; retry and edit of the latest message.
3. Kill and reopen the app during a turn: buffered events replay into the
   right conversation; history after restart matches what was shown.
4. Background Sub Agent/scheduled conversation streams while another is
   visible; the drawer running dot follows it.
5. Command-overlay sheet with ChatPage mounted: one projection, correct
   conversation.
6. Remote Agent (PC Bridge): thread promotion, history hydration, switching
   threads.
7. Voice scene with autoplay on/off: sentences spoken once while streaming,
   tail on completion; manual replay still works.
8. IM/WeChat external user messages appear immediately in an open chat.
9. Context compaction marker, link previews, OpenClaw waiting card.

## Batch 5a-0 checkpoint: chat runtime read/write seam (source complete; device acceptance pending)

- **Content**: Dart-only and behavior-preserving; no Kotlin, channel or
  persistence change. `ChatConversationRuntimeCoordinator.runtimeFor` /
  `ensureRuntime` / `ensureEphemeralRuntime` now return a cached read-only
  `ChatRuntimeView` (`services/chat_runtime_view.dart`) whose message list
  (`ChatRuntimeMessageListView`) throws on any write while keeping row
  listenables and mutation revisions. Message-list widgets accept the new
  `ObservableChatMessageSource` interface. The coordinator's internal code
  uses private `_runtimeStateFor` / `_ensureRuntimeState`.
- **Write path**: every former page write is a coordinator command —
  `updateRuntimePresentation`, `setRuntimeDispatchTurnId`,
  `setRuntimeConversation`, `setRuntimeLastAgentToolType`,
  `setRuntimeBrowserSessionSnapshot`, and the id-based message commands
  `insertRuntimeMessage` (keeps the list's upsert-by-id), `replaceRuntimeMessage`,
  `removeRuntimeMessages`, `removeLeadingRuntimeMessages`,
  `appendRuntimeMessages`, `replaceRuntimeMessages`. Like the direct writes they
  replace, they do not notify coordinator listeners. ChatPage routes writes
  through `_insertVisibleMessage` etc., which fall back to the page-local list
  before a runtime exists; the `ChatDispatchSupport` / `ConversationManager`
  mixins gained `insertVisibleMessage` / `clearVisibleMessages` /
  `appendVisibleMessages` hooks. Remote Agent thread runtimes are created and
  promoted by `ensureRemoteThreadRuntime` / `activateRemoteThreadRuntime`;
  the page keeps only its own pointers (`_adoptRemoteCodexThread`).
- **Event attribution (owner decision)**: the coordinator is now the only
  `AgentRuntimeService.events` projector. It subscribes while at least one
  surface is attached (`attachEventHost`), matching the former page-scoped
  subscriptions, and pulls a declarative `ChatRuntimeRoutingContext` (visible
  mode, per-mode conversation ids, remote thread facts) at event time. The
  ChatPage attribution order is ported unchanged (explicit conversation id →
  remote thread → session/turn identity → legacy process owner → visible Agent
  conversation for identity-less `error`/process events). The command-overlay
  sheet attaches as a `dispatchScoped` surface that claims only its explicit
  conversation while a prompt is in flight. Each event is applied once and the
  `ChatRuntimeEventOutcome` goes to every surface for presentation follow-up
  (toasts, session/turn pointers, collaboration mode, `setState`).
  **Intentional difference**: with ChatPage and the sheet both mounted, an
  event for the sheet's conversation was previously applied by both
  handlers (deduped only when it carried a host `eventId`); it is now applied
  once, to the identity owner if one exists, otherwise to the sheet's runtime.
  Remote-thread promotion now updates the page's thread pointers right after
  the event is projected (same synchronous call) instead of right before.
- In 5a the routing context becomes a pushed value and the commands become
  channel calls; nothing in page code needs to change for that move.
- **Verification boundary**: `flutter test` (baseline before the change: 1263
  passed, 4 pre-existing failures in background/misc settings and
  `app_background_service_test`, unrelated to chat; after: 1273 passed, the
  same 4 failures), `flutter analyze --no-fatal-warnings --no-fatal-infos`
  (0 errors; no new warnings/infos against HEAD), the new
  `chat_runtime_view_and_routing_test.dart` (read-only view, command write
  path, attach/detach, single application, identity/background routing,
  sheet claims, remote promotion), and `git diff --check`. No device run.

Manual acceptance checklist:

1. Agent and Xiaowan turns stream text, reasoning and tool cards exactly as
   before; Stop cancels and the late events do not reappear in the next turn.
2. Open the command-overlay sheet while ChatPage is mounted; send from the
   sheet and confirm its messages stream once and ChatPage shows no stray
   runtime for that conversation.
3. Background Sub Agent / scheduled conversation streams while another
   conversation is visible; the drawer running indicator follows it.
4. Remote Agent (PC Bridge): first event of a new thread promotes the pending
   messages into the thread view; switching threads keeps each history.
5. History paging, retry/edit of the latest user message, OpenClaw waiting
   card, manual-recording result card and link previews still update in
   place.

## Batch 4m checkpoint: terminal settings (source complete; device acceptance pending)

- The Settings row and the home composer terminal icon now open the saved native
  `Terminal(focusPackageId)` route; the `TerminalPackage` deep link keeps its
  focus behavior through the native route (the Agents page's runtime-missing
  hand-off and the home Web-action path both land there). `Page.Terminal` had
  no remaining callers and was removed with its mapping; the navigator's
  `TerminalPackage` mapping stays as a defensive fallback that native home never
  dispatches. The terminal process, environment installation and launch paths
  are untouched: the page's setup/terminal buttons still call
  `EmbeddedTerminalLaunchHelper.launch`.
- **Owner conclusion**: the environment inventory stays with
  `EmbeddedTerminalSetupManager.getPackageInventory()`; the distribution switch
  reuses the channel handler's exact sequence
  (`EmbeddedTerminalInitCoordinator.prepareDistribution` →
  `TerminalManager.closeAllSessions` → the ReTerminal settings write), with
  progress observed through the coordinator's existing listener registry and
  cancel through `cancelCurrent()`. Boot tasks stay with
  `EmbeddedTerminalAutoStartManager` (list/save/delete/runTaskNow).
  **Workspace mounts**: the Flutter `WorkspaceMountService` was a Dart-only
  owner over symlinks under the workspace root; the new
  `WorkspaceMountManager` ports the same symlink format, alias validation and
  unique-alias suggestion to Kotlin, rooted at the existing
  `AgentWorkspaceManager.rootDirectory`. Both UIs operate on the same symlinks;
  no second store or migration exists.
- The mount picker uses the system document-tree picker; primary-volume and
  Documents-home tree URIs map to host paths, other volumes report the
  invalid-directory notice (the Flutter page used file_picker's real-path
  conversion). Alias validation errors map to localized resources.
- Compilation (`:app:compileDevelopStandardDebugKotlin`,
  `:native-ui:testDebugUnitTest`,
  `:native-ui:compileDebugAndroidTestKotlin`) and `git diff --check` are the
  verification boundary. No device interaction, download, install or mount was
  run. Visual parity and runtime acceptance remain pending.

Manual acceptance checklist:

1. Compare both themes and languages: distribution segmented control, intro
   line, grouped environment rows (checkbox vs ready check, status tag, version
   text), setup button states, boot-task rows and editor, mount rows, error
   cards, insets and predictive back.
2. Switch Alpine/Ubuntu with a real download: progress bar, stage text, cancel,
   and failure revert (selection returns to the previous distribution). Repeat
   with an interrupted network. Confirm the Flutter page and the terminal agree
   afterwards.
3. Detect a fresh environment; the missing items are preselected and the focus
   deep link (`TerminalPackage` from the Agents Web action) selects its
   package. Start configuration and confirm the terminal's setup session runs.
4. Add, edit, toggle, run and delete boot tasks; confirm the terminal session
   bridge state follows and the Flutter page shows the same list.
5. Mount a primary-storage directory and a Documents-home directory; confirm
   the symlink appears in `/workspace`, the chat/terminal see it, and unmount
   removes only the link. Check a broken mount badge after deleting the source
   directory, and alias validation errors.
6. Rotate and recreate the process during a distribution switch and with the
   boot-task editor open; confirm no duplicate preparation starts.

## Batch 4l-1 checkpoint: memory center (source complete; device acceptance pending)

- The drawer's Memory entry now opens the saved native `Memory` route; the
  drawer shortcut row no longer opens any Flutter compatibility page.
  `Page.Memory` had no remaining callers and was removed with its mapping. The
  page keeps the static gradient greeting, the local/long-term tab switch, the
  short-memory cards (26-grapheme title truncation, full text below when
  truncated, icon + localized time label), long-press selection mode with
  select-all/count/cancel and the bottom delete bar, content blur while
  selecting, the MEMORY.md long-term list with add/refresh actions, the detail
  sheet, and the editor sheet. Both tab empty states, the full-page empty state
  and the loading skeleton remain.
- **Owner conclusion**: short-memory entries and the MEMORY.md file stay with
  `WorkspaceMemoryService` (`listShortMemoryEntries` /
  `deleteShortMemoryEntries` with its unchanged-snapshot validation /
  `readLongTermMemory` / `writeLongTermMemory`). `NativeMemoryCenterRepository`
  only adds the page's bullet-line projection, including the Flutter
  `base64url(index|memory)` id scheme and the append/replace/delete line
  edits, so both UIs read and write the same file format. A stale selection
  re-reads instead of deleting against a shifted snapshot, as on the Flutter
  page.
- **Dead code conclusion**: `ConversationHeatmap` and `memory_detail/` have no
  Flutter-side references and were not migrated. The tag section renders only
  the single「全部」chip upstream (the tag list is reset to that one entry on
  every load), so no tag filter UI was migrated. The LLM greeting flow is
  disabled upstream (the Flutter `_loadMemorySuggestion` clears the cached
  keys and returns before generating), so the native page renders the static
  greeting and never calls `generateMemoryGreeting`.
- **mem0 boundary**: the "cloud" tab is the workspace MEMORY.md bullet list,
  not a network service. The editor (add/edit with the 300-grapheme counter and
  the optional tags field, whose values the bullet format does not persist) and
  delete confirmation are included in this batch, merging the planned 4l-2.
  Long-term time pills show the load-time "just now", matching the Flutter
  parse-time timestamps.
- Compilation (`:app:compileDevelopStandardDebugKotlin`,
  `:native-ui:testDebugUnitTest`,
  `:native-ui:compileDebugAndroidTestKotlin`) and `git diff --check` are the
  verification boundary. No device interaction or memory write was run. Visual
  parity and runtime acceptance remain pending.

Manual acceptance checklist:

1. Compare both themes and languages: greeting (gradient in light, plain in
   dark), tabs, short-memory cards (truncation, quick-log prefix normalization
   in Chinese only), selection mode (count title, select-all, blur, bottom
   delete bar), long-term list/header/skeleton/empty states, insets and
   predictive back.
2. Long-press a card, toggle selection, select all, delete with confirmation
   and cancel. Confirm the workspace files and the Flutter page converge, and
   a stale selection refreshes instead of deleting the wrong entries.
3. Add a long-term memory (empty and over-300-grapheme validation), edit one,
   delete one with confirmation; confirm MEMORY.md stays bullet-formatted and
   the Flutter page shows the same content.
4. Rotate and recreate the process in selection mode and with the editor open;
   confirm no phantom delete and the editor draft does not self-save.
5. Alternate short-memory deletes between the native page and the Flutter page;
   both lists must converge.

## Batch 4k checkpoint: plugin market (source complete; device acceptance pending)

- The drawer's Plugins entry now opens the saved native `Plugins` route, and
  rows open `PluginDetail(pluginId)`. The market keeps the search field
  (name/description/publisher), plugin rows with icon, description,
  publisher/kind/size line, status label and dividers, plus empty and
  search-empty states. The detail page keeps the header, localized description,
  capability list, usage items, the ready guide with VLM readiness messaging
  and plugin-declared actions, the information expander, and the bottom
  install / enable / update / uninstall actions with an uninstall
  confirmation.
- **Owner conclusion**: `OmniPluginHost` remains the only catalog,
  install/update download and enable-state owner; install/update are plain
  suspend calls with no separate download manager, and the page only triggers
  and shows busy/result. `NativePluginRepository` adds typed access and the
  app-locale resolution of plugin-declared `{zh, en}` texts. The GUI-scene VLM
  readiness projection moved from `PluginPlatformChannel` into
  `PluginVlmReadiness.kt`, shared by the channel (unchanged wire output) and
  the native page.
- Plugin-declared routes (`/home/chat`, `/task/omniflow`, plugin detail
  self-links) hand off through a new `LegacyDestination.PluginRoute`, validated
  to app-internal paths; the Flutter OmniFlow center's own detail link is
  untouched. `Page.Plugins` had no remaining native callers and was removed
  with its mapping.
- Compilation (`:app:compileDevelopStandardDebugKotlin`,
  `:native-ui:testDebugUnitTest`,
  `:native-ui:compileDebugAndroidTestKotlin`) and `git diff --check` are the
  verification boundary. No device interaction, real install/update/uninstall,
  sync or network request was run. Visual parity and runtime acceptance remain
  pending.

Manual acceptance checklist:

1. Compare both themes and languages: market list rows, dividers, empty and
   search-empty states, detail header, capabilities, usage, information
   expander, bottom bar, insets and predictive back.
2. Install a plugin (busy indicator), toggle enable for a non-required plugin,
   update it, and uninstall with confirmation (and cancel). Confirm the Flutter
   pages show the same state afterwards and the Agent tool surface follows.
3. With the GUI scene unconfigured, open the OmniFlow detail page: the ready
   guide shows the configure message and the readiness-gated action is
   disabled. Configure the Provider, return, and confirm the guide flips to
   ready and the action opens chat. Repeat on a debug build (prepared message).
4. Follow the guide's `/task/omniflow` action and the chat action; return and
   confirm the detail state is unchanged. Open the same plugin from the
   Flutter OmniFlow center link and confirm consistency.
5. Rotate and recreate the process while an install is running and with the
   uninstall dialog open; confirm no duplicate install starts and state
   re-reads from the host.

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
