# Assistant selection before model initialization

## Reproduction

On physical device `b49f281b`, selecting Claude Code failed at launch preparation because the selected Provider endpoint used an OpenAI-compatible protocol. The runtime then restored the previous assistant. This prevented the user from selecting Claude Code before choosing a compatible model connection.

## Change

Assistant selection now persists the selected profile without initializing an ACP process or rolling back on model validation. Existing session admission owns initialization. Flutter no longer requires a configured model before selection or automatically connects immediately after selection. A failed configuration panel can open the existing model selector through “Choose model”. Session and turn ownership remain unchanged.

## Verification

- 19 focused Flutter tests passed, including the recovery action and harness switch barrier tests.
- Android adapter and runtime configuration tests passed; debug APK build succeeded.
- Installed the APK on `emulator-5556`. With an incompatible current connection, selecting Claude Code succeeds and the configuration recovery action opens the existing Provider/model card.
- Force-stopped and relaunched the emulator app. Claude Code remains selected, evidenced by `emulator-selection-after-restart.png`.
- Physical-device installation was attempted but failed because USB debugging disconnected. The new APK has **not** been verified on the phone.
- This verifies assistant selection and access to model configuration, not successful Claude Code prompts with an incompatible Provider or live model catalog reload across every harness.

Build snapshot: `/tmp/oob-select-assistant-first-20260906.apk`.

## Physical-device follow-up

After the phone reconnected, `adb -s b49f281b install -r` succeeded for that build. App data was retained. Agent-operated UI verification successfully selected Claude Code and then Codex; see `phone-cc-selected.png` and `phone-codex-selected.png`.

The existing Codex conversation showed a Provider HTTP 401 for GLM-4.5-Air at `/v1/responses`. Direct host-side requests using the device's generated Codex credential and endpoint reproduced this independently of the app:

| Model | `/v1/chat/completions` | `/v1/responses` |
| --- | --- | --- |
| GLM-4.5-Air | 200 | 401, upstream authentication failure |
| DeepSeek-V3.1 | 200 | 500, upstream knowledge-base capability error |

These were small real API requests, not mocked tests. Credentials were read in memory and not printed or written to this report. This establishes a current Provider Responses-route failure for these models; successful model discovery or Chat Completions does not establish Codex compatibility. No credential replacement, protocol proxy, or automatic retry was introduced. Codex prompts remain blocked on this Provider route until it is repaired or a working Responses connection is selected.

## Conversation acceptance follow-up

Further testing found that GLM-5.1 on the same Provider returns HTTP 200 on Responses. This narrows the earlier failure to specific model routes; the Provider is not universally incompatible with Codex.

On the phone, selected GLM-5.1 through the model selector. The first send after changing models failed before an upstream prompt. Inspection found both the Flutter selector and native `saveSceneModelBinding` disconnected the runtime after a model change, leaving the page with a session created by the settings panel. On relaunch, official loading of that empty Codex session failed. A subsequent user send created a session and completed successfully:

- Agent: Codex ACP 1.1.7, model GLM-5.1.
- Input: `Reply OOB_CODEX_CHAT_OK only. Do not use tools.`
- Actual assistant reply: `OOB_CODEX_CHAT_OK`.
- Official PromptResponse: `end_turn` at 23:01:42; displayed elapsed time 17.5 seconds.
- Evidence: `phone-codex-real-reply.png`.

This is a real short chat acceptance result, not evidence of tool execution, long tasks, all models, or all Harnesses.

The follow-up implementation uses `session/set_config_option` for a model change on the same Provider, avoids native runtime invalidation for that model-only binding change, and updates the page's selected request model. Provider/credential changes retain their existing invalidation path. Final device verification of these follow-up changes is pending the coordinated build/install.

Repeatable acceptance scenarios:

1. New Codex conversation → open model settings → select a different compatible model on the same connection → send a new prompt. It must complete without restarting the app.
2. Continue that conversation and verify previous content is available.
3. Change the model in the same conversation, send again, and verify both the official selected value and request completion.
4. Restart the app, return to the conversation, and continue without losing history or the selected model.
5. With an incompatible connection or an upstream failure, open the existing model card and configure another connection; an error must not trap the user behind a stale single-model list.

## Subsequent findings: model catalog and reasoning metadata

The 23:18 multi-step Codex run retained session `...6f45510c` and reached actual tool calls, but model switching was **not** yet proven: later inspection showed the official selected model and scene binding were still GLM-5.1, and the generated Codex catalog contained one model. The selector had displayed GLM-5.2 from the live Provider list, but the official session could not select a value it had never loaded. Do not attribute this run to GLM-5.2.

Found the catalog lifetime conflict: Flutter `_migrateLegacyStorageIfNeeded` deleted the current `cached_provider_models_with_base_v2` document during preference reads. Native `rememberModels` / `cachedModels` use this same document to prepare the official Harness launch catalog. Removed deletion of the current document; retained cleanup of the truly legacy key. Live Provider selection still fetches the network and does not display stored launch metadata as live results. Added a read/reinitialization regression and updated the live-discovery test to assert fresh results while preserving native launch metadata.

Found a separate adapter limitation: `buildCodexModelCatalogJson` advertised only `medium` for every model. The [official Codex unknown-model implementation](https://github.com/openai/codex/blob/main/codex-rs/models-manager/src/model_info.rs) uses an absent default and empty supported levels. The adapter now follows this fallback and preserves `supported_reasoning_levels` / `default_reasoning_level` only when explicitly present in Provider metadata. The UI displays a noninteractive “Model default” when the official session declares no effort setting. No fixed low/medium/high list was substituted. Official local CLI fixture checks with unknown metadata completed and emitted `none`; actual GLM-5.1 Responses requests accepted `none`, `low`, and `high` with HTTP 200, which establishes request acceptance for this route only, not universal effort capabilities or different cognitive behavior.

The multi-step tool task had early terminal-command failures with no detail, followed by some later tool progress. It was cancelled normally at 23:22:15 to release the phone's settings for the user. Official stop reason was `cancelled`. It did not complete the requested tests and is **not** a passing long-task result. Terminal diagnostics and post-fix full device acceptance remain in progress.
