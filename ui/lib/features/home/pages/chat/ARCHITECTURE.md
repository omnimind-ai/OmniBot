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
3. `services/chat_conversation_runtime_coordinator.dart` is the runtime facade.
   Its public commands and state ownership stay in that file; implementation
   details live in the private `chat_runtime_*_support.dart` extensions.
   Pages, the command-overlay sheet and the drawer read a runtime only through
   `ChatRuntimeView` (`chat_runtime_view.dart`) and change it only through
   coordinator commands (`insertRuntimeMessage`, `updateRuntimePresentation`,
   …). The coordinator is the only subscriber that projects
   `AgentRuntimeService.events`: surfaces attach with
   `attachEventHost(context:, onOutcome:)`, publish a declarative
   `ChatRuntimeRoutingContext`, and receive one `ChatRuntimeEventOutcome` per
   applied event (`chat_runtime_event_routing.dart`).
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
