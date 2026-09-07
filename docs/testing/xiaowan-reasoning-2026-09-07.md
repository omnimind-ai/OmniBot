# Xiaowan reasoning visibility acceptance (2026-09-07)

Scope: emulator-5556, Xiaowan, GLM-5.1, real configured LLMTHU API through the existing local observer. This is not acceptance of the OmniBot official gateway or the user's current phone conversation.

## Observed before repair

- Selected reasoning through the model settings UI; actual `/v1/chat/completions` request recorded `effort: max` (observer request 17).
- Sent synthetic `Reply XIAOWAN_THINK_VERIFY` through the composer. ACP session suffix `46032abd`, turn suffix `f52e4ab7` completed with official `end_turn` after 69.147 seconds.
- During execution, expanding `Thought for 0s` displayed nonempty reasoning text. The provider and ACP-to-Flutter projection did not discard all reasoning in this reproduction.
- After completion, the process section folded. Android accessibility merged the clickable `Processed 48s` header with the final answer and footer into one node at `[32,328][688,552]`. Tapping that node's centre landed outside the visual header and did not expand it.
- The zero-second reasoning label is a separate unresolved timing observation; the empty initial ACP thought chunk means `first_reasoning` timing alone does not prove nonempty reasoning arrival.

## Focused repair

Give the existing AgentRunHeader its own semantic container and button identity; exclude duplicate visual label semantics. Reuse its existing InkWell and callback. No protocol, lifecycle, timer, folding policy or visual redesign is introduced.

Regression: `agent_run_header_test.dart` verifies that the completed header is a separate semantic control from the adjacent answer and that tapping it invokes expansion. All 15 focused header tests pass.

Device build and post-install verification are recorded below when completed. Persisted `none` reasoning selections must not be bulk rewritten: some phone sessions explicitly have reasoning disabled, but this does not identify the user's reported session.

## Post-install result

Build succeeded (35s), installed with `adb install -r` on emulator-5556; APK SHA-256 `9a6ec999724668c59f8733389b9957d05889907984805b708f39258216ce52df`.

After package restart the same completed conversation survived. The fresh semantic `Processed 48s` click now expanded the process section. Clicking `Thought for 0s` then exposed the persisted nonempty reasoning. A combined assertion requiring the answer to remain in the same viewport failed because expanded reasoning moved it outside the viewport; the answer was separately visible before expansion. No claim is made that the user's particular phone session is fixed. Screenshot: `/tmp/oob-thinking-restored-pass.png` (local ephemeral evidence).
