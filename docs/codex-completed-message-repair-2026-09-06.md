# Codex completes without a visible answer

## Evidence

On the real phone, the 23:35:23, 23:35:48 and 23:39:11 Codex prompts
received reasoning and an official `end_turn`, but no ACP assistant text.
For session suffix `0ac36e37`, the official Codex rollout contains an
assistant `output_text` of 49 characters and a `task_complete` with the same
answer length. No private prompt/answer text is copied here.

The installed Codex ACP 1.1.7 handler ignores `agentMessage` in
`completeItemEvent`; only `item/agentMessage/delta` becomes assistant text.
Official 1.10.0 has the same behavior. A deterministic Responses server
returning text in `response.output_item.done` without text deltas reproduces
the loss with the real official CLI: initialized, request served, end_turn,
but empty ACP assistant text. This is an adapter projection defect; it is
not evidence that Flutter dropped a received answer or the model produced none.

Official source: https://github.com/agentclientprotocol/codex-acp/blob/main/src/CodexEventHandler.ts

## Change

The existing catalog `managedInstallCommandAsset` installs official 1.10.0
and applies a narrow, version-checked patch inside its `CodexEventHandler`.
It tracks emitted text by official item ID. On item completion it projects
only the un-emitted suffix of authoritative text. Fully streamed messages
are not duplicated; inconsistent final text fails instead of guessing.
Unexpected package versions/source layouts fail installation explicitly.
The patch is idempotent and should be removed when an upstream release
provides equivalent completed-item projection.

The host continues to consume only ACP session updates and official prompt
completion. It does not read rollout files for recovery, synthesize terminal
states, re-prompt, or render reasoning as the answer.

## Verification

`scripts/verify-codex-completed-messages.mjs CLI_DIRECTORY FIXTURE_DIRECTORY`
uses a disposable CLI installation and the exact shipped patch. It runs
real Codex/ACP against deterministic HTTP fixtures for completed-only text,
partial deltas, normal streaming, and typed request failure. Both Provider
wire configurations are covered; all eight cases passed. Success requires
exactly `OK` in ACP assistant text, not merely HTTP success and end_turn.

Final APK installed on phone `b49f281b`, preserving application data:
`82906b398ae2990d46326db8854a7e37622f933041a0bdc83830f8174fa8712c`.
Final native tests: 54 adapter + 2 catalog tests passed; build succeeded.
The eight CLI regressions were rerun with the final native-generated catalog
and passed (not just the earlier manually prepared fixture).

Real phone, Codex ACP 1.10.0, GLM-5.1, session suffix `59fae071`:
- 00:01:12: visible `CODEX_VISIBLE_OK`, official end_turn, UI 12.3s.
- 00:02:13: same-session follow-up correctly returned `blue-orchid-63`,
  official end_turn, UI 18.6s. No session reconnect between the two prompts.

Screenshots and CLI results: `verification/codex-completed-message-20260907/`.
This verifies two real text turns; long terminal tasks and all other Harnesses
are not claimed as accepted by this report. Historical failed test turns
remain visible. Active user conversations were not interrupted.

The first phone build exposed a separate regression in the reasoning-catalog
change: enabling Gson `serializeNulls()` made absent enum defaults explicit
nulls. Real `session/new` failed to parse the catalog (`invalid type: null,
expected string or map`). Removed that serializer change; optional reasoning
defaults may be absent as well as null. The failed phone sends are retained,
and that first APK is not an accepted build. The adapter itself was updated
through Settings → Agent mode → Codex → reinstall; installed version 1.10.0
and exactly one completion-patch marker were confirmed.
