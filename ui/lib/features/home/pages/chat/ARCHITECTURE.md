# Chat architecture

The chat feature is intentionally split around ownership rather than around
individual product modes. Normal chat, OpenClaw, and Agent share the same page
shell, but each mode owns one `ChatPageModeState` instance and reads one
optional runtime through a read-only `ChatRuntimeView`.

## Dependency direction

1. `chat_page.dart` is the composition root. It owns Flutter controllers,
   selects the active mode, and combines the focused page mixins.
2. `state/chat_page_mode_state.dart` owns mode-local presentation state and its
   reset contract. Do not add parallel `Map<ChatPageMode, ...>` fields to the
   page.
3. `services/chat_conversation_runtime_coordinator.dart` is the Flutter
   adapter of the native runtime owner (batch 5a). The ACP projection — one
   `AgentEventReducer`, one `ChatConversationRuntimeCoordinator`, history
   persistence and reply voice autoplay — lives in the app module
   (`cn.com.omnimind.bot.agent.projection`, hosted by `ChatRuntimeHost`).
   This file only mirrors the immutable snapshots the owner publishes on
   `cn.com.omnimind.bot/ChatRuntimeEvents` (`chat_runtime_mirror.dart`) and
   forwards every command on `cn.com.omnimind.bot/ChatRuntime`. Pages, the
   command-overlay sheet and the drawer read a runtime only through
   `ChatRuntimeView` (`chat_runtime_view.dart`) and change it only through
   coordinator commands. Plain field/list writes are applied to the mirror at
   once and confirmed by the next snapshot; commands whose result the caller
   uses (`applyAcpPromptResponse`, `bindAcpSession`, `unregisterTask`, …)
   return futures that complete after the mirror includes their change.
   Surfaces attach with `attachEventHost(context:, onOutcome:)`, publish a
   declarative `ChatRuntimeRoutingContext` (sent natively whenever it
   changes), and receive one `ChatRuntimeEventOutcome` per applied event.
   Never add event reduction or runtime state on the Dart side.
   Prompt admission (batch 5b) goes through `ChatPromptDispatcher`
   (`chat_prompt_dispatcher.dart`, native owner of the same name): pages
   reserve the session with `prepareTurnSession`, check their own navigation
   target, then `submitTurnPrompt`. Pages never call `session/new` or
   `session/prompt` themselves.
4. `adapters/` converts remote Agent/Codex payloads into app models. Raw
   protocol traversal and compatibility aliases belong there, not in widgets
   or page lifecycle code.
5. `widgets/chat_widgets.dart` is a compatibility library. Concrete widgets
   are split by responsibility into AppBar, mode slider, message list, and
   input wrapper parts.

Data access remains behind the existing services and repositories. Widgets
must not call persistence or platform channels directly.

## Runtime invariants

- `ObservableChatMessageList` remains the source for row-level notifications;
  streaming content changes must not force a full page rebuild. Widgets accept
  any `ObservableChatMessageSource`, which the read-only view implements.
  The mirror keeps row listenables across snapshots: a streamed chunk
  replaces only the changed rows (snapshots carry only changed messages).
- Snapshots carry a coordinator-wide revision. Late batches never roll a
  runtime back, and a page snapshot sent with `replaceConversationSnapshot`
  carries the revision it was built from so the owner treats it as a live
  refresh when the runtime moved on meanwhile.
- A runtime list obtained from `runtimeFor` throws on writes. Page helpers
  (`_insertVisibleMessage`, `_replaceVisibleMessage`, …) route a write to the
  coordinator when the visible list is runtime-owned and to the page-local
  fallback list otherwise.
- Runtime text caches and active turn IDs are different identity spaces. Never
  infer active turns from `currentAiMessages` keys.
- Polling snapshots must preserve reducer-owned in-flight state when
  `preserveLiveStreamingState` is true.
- Mode state reset clears conversation data while retaining view preferences
  such as expanded run groups.
- Existing public import paths (`chat_page.dart`, `chat_widgets.dart`, and
  `chat_conversation_runtime_coordinator.dart`) are compatibility contracts.

## Adding behavior

- Add rendering-only behavior to the focused widget file.
- Add mode-local UI values to `ChatPageModeState` and update its reset test.
- Add runtime transformations to the narrowest `chat_runtime_*_support.dart`
  extension; keep mutable runtime ownership in the coordinator/state classes.
- Add raw remote payload compatibility to `adapters/` with mapper tests.
- Create a new service/repository when platform, persistence, or network access
  is required instead of importing it into a widget.
