# ACP steering design reference

Reviewed on 2026-11-07. The implementation is written for OmniBot's existing ACP runtime; no upstream implementation code was copied.

- Reference: [agentclientprotocol/codex-acp, `docs/steering.md`](https://github.com/agentclientprotocol/codex-acp/blob/356abaf0e7b5194489b62304011a37e5b02f1184/docs/steering.md), commit `356abaf0e7b5194489b62304011a37e5b02f1184` (2026-10-28).
- Wire compatibility: negotiate `initialize._meta.steering.supported`; submit `_session/steering` with standard prompt content blocks and `_meta.steering.idleBehavior = "promptRequired"`; consume `outcome = "injected"` or `"promptRequired"`.
- Lifecycle choice: the active `session/prompt` owns completion, usage, cancellation and errors. A late steering request must not spawn a detached turn. Pending new prompts remain client-owned drafts until submitted via the normal `session/prompt` lifecycle.
- Local additions: the host's existing prompt reservation supplies an expected-turn fence; a request id identifies a user item and makes steering retries idempotent. A per-prompt inbox admits input atomically with final-response sealing and consumes it between complete model/tool rounds. Text steering is supported; attachments use ordinary queued prompts.
- History: accepted steering emits the official `user_message_chunk` with a stable message id through the existing ACP projection. Cancellation discards only unconsumed execution input, retaining the committed user item.
