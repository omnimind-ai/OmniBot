# Chat image audit — 2026-09-17

Request: inspect chat image uploads for remaining bugs.

Source findings (not physical-device reproductions):
1. LocalAcpRuntime.materializePromptAttachments copies to workspace only in
   prompt-local args. Chat retry reads attachments from message.content;
   AgentConversationHistoryRepository snapshot storage serializes incoming UI
   message maps without merging durable attachment metadata from existing entries.
   A path-only picker message can therefore retain the cache path. Cache eviction
   followed by history retry is a pending end-to-end regression, not yet validated.
2. AgentWorkspaceAttachmentSupport.prepareSingleAttachment returns immediately
   for promptPath/workspacePath without checking readability. LocalAcpRuntime
   buildPromptBlocks falls back to ResourceLink when the local image file is
   unavailable, even when the Agent supports image input. This can omit native
   image bytes rather than report an unreadable image. Workspaces may be remote;
   the fix must distinguish remote references from locally owned image files.

Executed: 10 JVM attachment tests, 6 Flutter picker/payload tests passed.
Entrypoints:
- :app:testDevelopStandardDebugUnitTest --tests '*AgentImageAttachmentSupportTest' --tests '*AgentWorkspaceAttachmentSupportTest'
- cd ui && flutter test --no-pub test/utils/picked_attachment_metadata_test.dart test/features/home/pages/chat/utils/agent_runtime_attachment_payload_test.dart

The above suites do not cover snapshot overwrite + cache eviction + retry or
expired workspace images through the full ACP boundary. Those new end-to-end
regressions are **not implemented/not run**, and neither finding is claimed fixed.
Only emulator-5580 connected; **待真机验证**. No image source changes this audit.
