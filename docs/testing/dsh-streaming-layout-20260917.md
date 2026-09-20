# DSH streaming blank-area investigation — 2026-09-17

User report: DSH chat streaming leaves large blank areas. Follow-up: simulate DSH operations directly rather than requesting a screenshot.

## Reproduction and scope

Inspected the installed official `@deepseek-ai/dsh-acp/lib/index.js` on PJE110: `assistantUpdates` sends committed reasoning/text blocks in order as `agent_thought_chunk` / `agent_message_chunk`, sharing the assistant message id, then the host receives PromptResponse. The fixture uses that shape through the existing AgentEventReducer and ChatMessageList; it does not implement another Agent lifecycle.

Two executable reproductions failed before the fix:

- A committed plain-text answer followed by official completion still lacks its full rendered text 400 ms later.
- Repeated four-character chunks, separated by idle periods, cease to be fully visible within 240 ms starting with the second chunk.

The second case is caused by retaining the previous Ticker elapsed timestamp after restart, although Ticker restarts its clock at zero. The first is caused by the plain-text rendering path ignoring `isFinal` when an animation already exists. Fixes are confined to the presentation widget: restart its clock and reveal completed content immediately. ACP state and stored content remain unchanged.

These reproduce missing/delayed displayed text. They do **not** establish that every reported large blank area has this cause. A third fixture checks long reasoning, multiple Markdown paragraphs, completion/collapse and JSON history reload, including rendered height and viewport position; no residual large blank area was reproduced in that scenario.

## Executable regressions

From `ui`:

```sh
flutter test --no-pub test/features/home/pages/chat/widgets/dsh_streaming_layout_test.dart test/widgets/streaming_text_test.dart test/features/home/pages/chat/widgets/chat_message_list_test.dart test/features/home/pages/command_overlay/widgets/deep_thinking_card_test.dart
flutter run --no-pub -d b49f281b -t test/device/dsh_streaming_layout_live.dart
```

The physical replay entry uses Flutter's LiveTestWidgetsFlutterBinding and the same test suite, production reducer and chat widgets, in the separate generated module host `com.example.ui.host`. It does not replace the main app or count as a live DSH provider request.

- Desktop widget regressions: 83 passed; pre-fix and post-fix logs under `artifacts/dsh-streaming-layout-20260917/`.
- Physical replay: **3/3 passed twice**, including a Dart VM hot restart between runs, on OnePlus PJE110 / Android 16 / serial `b49f281b`. Module host `com.example.ui.host` version 1.0/code 1; final test code delivered by Flutter hot restart. See `physical-replay.json` and `physical-replay.log`. This is synthetic ACP replay on real hardware, not a successful live model request.
- Host setup: the generated module host and Flutter library required temporary `compileSdk = 37` because of `permission_handler_android` (main app configuration untouched; generated files restored afterward). Initial device attempts failed on viewport readiness and missing native voice APIs; the test entry now waits for viewport metrics and explicitly disables voice configuration/events in this text-only fixture. These failures are not counted as passing runs.
- Focused Dart analysis: no issues.
- Original-app end-to-end acceptance: **待真机验证**. Main app PJE110 (`b49f281b`) is 0.6.3 / code 16. A real UI send with synthetic marker `OOB_DSH_STREAM_BLANK_0917` was admitted; no assistant journal record was available during inspection, and another interaction switched the phone away. This is not a successful generation or visual acceptance. No request replay was performed.
