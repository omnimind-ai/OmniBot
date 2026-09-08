# Short-term memory retention and deletion

Verification on 2026-09-06: 14 focused Flutter tests and 9 native deletion tests
passed; `assembleDevelopStandardDebug` succeeded. The initial native build hit a
local incremental compiler cache-version error; retrying with
`-Pkotlin.incremental=false` succeeded without deleting repository files.

Emulator acceptance (2026-09-06, emulator-5556, installed 0.6.1 debug):

- Started with the memory center showing no short-term entries.
- Selected Xiaowan and sent one normal UI prompt requesting only a test daily
  memory, `OOB_MEMORY_SMOKE_20260906 test-only memory`. The real model invoked
  `memory_write_daily`; the tool and final answer reported success.
- Opened Memory Center and verified the test entry was visible. Long-pressed it,
  selected Delete, checked the confirmation's exact marker, then confirmed.
- Verified the empty UI and that the daily source file no longer contained the
  marker. Force-stopped/relaunched the app and reopened Memory Center: still empty.
- The originating conversation and its tool/result history survived restart.
  Only the explicitly created test memory was deleted. No existing user memory
  was removed. This verifies single deletion on an Android emulator, not a physical
  phone or multi-day filesystem failure recovery.

Context-continuation probe in the same isolated conversation:

- Xiaowan acknowledged the conversation-only project code `CEDAR_614` and pending
  task `verify blue export`, then acknowledged a later `NEXT` prompt. No memory
  file write was requested for these markers.
- Submitted `/compact` once through the normal composer. No context summary was
  committed (test conversation 5: null summary/cutoff, update timestamp 0).
- Source trace explains the result: `_tryHandleSlashCommand` delegates Agent-mode
  input to `_tryHandleAgentSlashCommand` before the ordinary-chat `/compact`
  branch. Xiaowan does not advertise that command; the Agent dispatcher rejects
  it as unsupported. This is a manual-command capability/entry gap, not evidence
  of automatic compaction failing or a provider request hanging.
- Manual compression continuity and actual long-conversation automatic
  compaction remain unverified. Do not replace either gate with this short test.
  Any follow-up should reuse the existing compactor through the owning runtime,
  not add a second page-specific Agent lifecycle.

## Ownership and behavior

The existing `WorkspaceMemoryService` owns daily short-memory Markdown files.
There is no automatic expiry: daily rollup extracts summaries/long-term facts
without deleting the original entries. The memory center lists all persisted
entries by default, not a retention-limited window.

Long-press a short-memory card to select it, optionally select additional cards,
then choose Delete and confirm. Cancellation makes no deletion request.
Only the selected short-memory source blocks and affected derived index chunks
are removed. Chat history, original quick logs, extracted long-term memory,
rollup summaries, and already loaded conversation context are not cleared.
Editing an original quick log can create its short-memory entry again.

The existing Flutter/native channel invokes one service batch operation. Native
IDs plus date/time/content snapshots are validated for every target before any
day is rewritten. Missing, edited, or ambiguous entries fail rather than fall
back to text matching. Batch positions refer to the original parsed file.
Flutter card integers are presentation-only, never hashes used as native IDs.
Deletion failures trigger a list reload. Disk failure across several day files
can leave a partially completed batch; it is not a cross-file transaction.

Deletion uses the existing workspace memory write lock. An in-flight embedding
refresh rechecks current source chunks under that same lock before saving,
preventing a stale refresh from writing removed chunks back into the index.
No new memory store, expiry policy, ACP lifecycle, or model configuration is added.

## Regression checks

Flutter tests:

```sh
cd ui
flutter test test/services/workspace_memory_service_test.dart test/services/workspace_memory_deletion_test.dart test/features/memory/memory_center_deletion_test.dart test/features/memory/services/mem0_memory_service_test.dart test/features/home/pages/settings/workspace_memory_setting_page_test.dart
```

Native tests and APK:

```sh
./gradlew --no-daemon --no-parallel :app:testDevelopStandardDebugUnitTest --tests cn.com.omnimind.bot.agent.ShortMemoryDeletionTest :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart -Pkotlin.incremental=false
```

Coverage includes single and multi-selection confirmation, cancellation, failed
native deletion, page remount with the backing fixture retained, exact channel
payloads, stale/ambiguous target rejection, original batch indexes, duplicate
content, multiline blocks, and metadata preservation. Widget/channel tests are
simulated; they do not establish real-device filesystem acceptance. Test fixtures
are isolated and must not be replaced with deletions of the user's memories.
