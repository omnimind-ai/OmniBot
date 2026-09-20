# File transfer persistence repair — 2026-09-17

Goal: fix chat file/image transfer, including cache eviction, history snapshots,
reload, retry and missing local attachment handling without a second protocol.

Implemented in existing owners:
- History repository materializes user attachments through the existing workspace
  copier before persisting their references. Snapshot updates retain durable
  paths by attachment ID within the same message/conversation.
- Canonical prompt dispatch resolves stale references against that message's
  history using conversationId and dev.omnimind/clientMessageId. This covers
  retries while the UI still holds its original picker path.
- Owned workspace files are checked before accepting pre-materialized metadata.
  Deleted owned images/files produce an attachment error. Remote Harness paths
  remain that Harness's responsibility.
- Missing/unreadable ordinary local files now fail preparation, as images do,
  instead of silently passing a dead path. Failed preparation during history
  persistence preserves the user's original message for error display/recovery.

Executable regressions: ConversationAttachmentPersistenceTest tests a real
synthetic file copy, cache deletion, serialized metadata reload, stale snapshot
merge, subsequent byte read, attachment deletion, distinct IDs with identical
names, and missing owned versus remote workspace paths. Materialization is
injected in these JVM tests; Android URI copy and Room transaction integration
are not proven by them.

Entry: :app:testDevelopStandardDebugUnitTest --tests '*ConversationAttachmentPersistenceTest' --tests '*AgentImageAttachmentSupportTest' --tests '*AgentWorkspaceAttachmentSupportTest'
Initial result: 13 tests passed. Final build/test result is recorded below.

Not yet accepted end-to-end: actual picker -> Room -> cache eviction -> same-page
retry/restart -> model file/image receipt. Only emulator-5580 is connected.
**待真机验证**. No new Release APK produced. The goal remains active.

Final validation: focused 13 JVM tests and assembleDevelopStandardDebug succeeded
(55s); git diff --check passed. This verifies compiled wiring and helper behavior,
not the unexecuted full Android/Room/model acceptance listed above.

## Android/Room integration — follow-up

Added debug-only DebugAttachmentPersistenceReceiver and executable
`scripts/verify-attachment-persistence.py`. It uses the actual history repository,
Room database and workspace materializer with a valid synthetic PNG and a text
file. It removes only its own picker cache, submits stale UI snapshots, repeats
native message upserts, resolves prompt references, compares bytes, then repeats
after force-stop/relaunch. Its synthetic archived conversation and files are
cleaned afterwards. No model request is made by this test.

Result: emulator-5580 / sdk_gphone64_arm64 / Android 13 / version 0.6.3 (16),
seed passed, process-reload passed, cleanup passed. Evidence:
`artifacts/file-transfer-20260917/android-room.json`.

This exposed/fixed duplicate upsertUserMessage dropping durable references when
incoming picker cache is gone. Build and installation passed.

Actual chat picker/model acceptance deferred: UI hierarchy did not become idle;
screenshot showed a different pending test draft. It was not overwritten.
No physical device connected. **待真机验证** remains; goal not complete.

## Missing-file recovery and package identity

Shared ACP buildPromptBlocks now validates owned Android workspace files even
when the selected Harness owns materialization, preventing silent ResourceLink
fallback for a missing owned image/file. Remote paths remain delegated.

Final focused run: 9 JVM tests passed; debug build passed (50s).
Android integration additionally passed missing-and-recover for PNG and text:
delete durable file -> expected preparation error; deleted picker source ->
expected error; restore bytes -> same attachment readable again. Cleanup passed.
Installed APK SHA-256 checked before/after and unchanged. Evidence:
artifacts/file-transfer-20260917/android-room-recovery.json.

Updated execution entry requires --apk PATH to pin the tested artifact.
Physical-device requirement remains unmet across three consecutive goal turns;
only emulator-5580 is connected. Actual chat picker/model receipt is also pending
because the shared emulator chat contains another test draft. Await an available
acceptance device; do not treat these integration checks as full completion.
