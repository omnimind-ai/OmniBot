# Kotlin context-maintenance ports

These are source ports with Android host adaptations, not direct TypeScript package dependencies.

- Pi / Mario Zechner, MIT: `badlogic/pi-mono@7d8ab31a477ecc07b36f56ffcae58c79307a68be`, `packages/coding-agent/src/core/compaction/compaction.ts`: last reported usage plus trailing estimates, backward recent-budget cut selection, cuts at complete user/assistant boundaries rather than tool results, split-turn summary/checkpoint behavior, summary output budget. License: `pi-compaction-LICENSE.txt`.
- Google LLC, Apache-2.0: `google-gemini/gemini-cli@85aca163f6c73ac6ce380b5447359146b8adcae4`, `packages/core/src/context/chatCompressionService.ts`: reverse traversal of tool results, retain a recent output budget, preserve full over-budget output in a file and substitute a retrievable reference, validate compressed size. `packages/core/src/utils/tokenCalculation.ts`: ASCII/4 and non-ASCII*1.5 text estimate. License: `gemini-compaction-LICENSE.txt`.

Host adaptations: ChatCompletionMessage data types; leading system messages and the exact latest user request remain in the model context; complete final tool groups can be summarized before the next request; image_url is counted as image rather than Base64 text; multilingual estimation is retained for large strings instead of reverting to ASCII/4; tools schema overhead is accounted separately. Existing workspace offloads provide durable readable paths. Offloaded older results use a retrievable reference without a tail excerpt: per-result excerpts accumulated past the shared budget when restoring 120 large results. Recent results retain the upstream shared budget. Existing ACP and Conversation owners persist only a proven canonical boundary. A lagging or ambiguous journal never advances the durable cutoff.

The original Agent loop and retry controllers are not imported. No automatic replay is introduced. Kotlin regression tests cover the relevant behavior; the upstream TS test suite has not been executed within this Android project.

2026-09-08: `AgentContextOverflow` also ports the error and non-overflow patterns from
the same pinned Pi revision's `packages/ai/src/utils/overflow.ts` (MIT). Host adaptations
gate HTTP auth/rate-limit/server errors and add the two captured regressions (`Prompt
exceeds max length`, numeric `Input length ... exceeds the maximum length ...`). Silent
truncation detection and Pi's separate Agent loop are not imported. Pi's
`AgentSession._checkCompaction` distinguishes context recovery from transport retries:
one compact-and-continue attempt, preserving completed tools. OpenOmniBot keeps that
operation inside the existing orchestrator/controller and rejects recovery once the
failed request has started output. A named `force` input replaces fictitious token
usage. The summarizer uses known model capacity separately from the user's trigger;
unknown model capacity retains the conservative effective budget. This is a Kotlin
host adaptation, not a claim that a TypeScript package is running on Android.

History restoration also applies the existing Gemini 50k shared tool budget while
reading database pages, before JSON replay construction can exhaust Android's heap.
This host projection counts the serialized tool record conservatively, streams past
oversized result fields, and stores the entire original record as a readable file.
It preserves canonical assistant/tool identity and does not write the projection back
to Room. Historical image payloads remain retrievable from that complete record;
current-turn image delivery is unchanged. It is not a second summarizer or Agent loop.

The Android ACP projection is asynchronous. A successful summary now awaits Room's
commit notification for its exact completed tool group before advancing the existing
checkpoint. Only lightweight headers and matching records are loaded for this check;
running placeholders are not committed results. The wait is bounded to 30 seconds and
fails without claiming a durable summary on timeout. It is a journal barrier, not a
second Agent retry or a timing-based cutoff.

2026-09-09: Android tool-result projection additionally retains bounded small metadata
for the newest result when its body is offloaded. This is a host adaptation, not
an upstream summarization algorithm: parse the existing AgentEventAdapter JSON
envelope, keep exact scalar values such as provider-owned cursors, and omit long
strings/arrays. It is independent of tool names and file extensions. Input parsing
is capped at 512 Ki characters, structural depth at 3, fields at 64 per object,
and metadata at 4096 characters; oversized cursor strings are omitted, never
truncated into a different cursor. The metadata must fit the same remaining
recent-output token budget. Original output storage and ACP identities are unchanged.
Other and oversized/unstructured outputs retain the complete-file reference.
This fixes the reproduced loss of inline nextOffset after a 64 KiB file page
was replaced by a generic reference. Device and real-provider acceptance is
recorded separately in the conversation regression index.
