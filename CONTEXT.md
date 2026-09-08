# OmniBot domain vocabulary

## Conversation

A user-visible, durable history identified by `conversationId` and its
canonical conversation mode. It survives a Flutter rebuild and is the source
for restoring the chat surface.

## ACP session

The Agent-side execution context identified by `sessionId`. A session may be
reused for multiple prompts and may be restored independently of the local
conversation record.

## Turn

One prompt execution inside an ACP session, identified by `turnId`. A turn is
temporary execution state, not a durable conversation and not a replacement
for a session.

## Runtime snapshot

The current in-memory projection of a conversation while the UI is active. It
may contain streaming text, thinking, tool state, and an in-flight turn. It is
never allowed to replace a durable conversation with an empty or partial
snapshot merely because a page is rebuilding.

## Conversation history

The durable message projection read through the compatibility reader. Native
ACP history is preferred, while older local snapshots and storage aliases are
read and normalized before optional forward migration.

## Lifecycle transition

A change of conversation target or mode. It invalidates asynchronous work from
the previous target; late loads, updates, and persistence completions must not
mutate the new target.

## Model request context

A bounded, derived view of conversation history prepared by Xiaowan before each
model call. Compaction changes this view and its durable checkpoint; it does not
replace the user's complete history or create a new ACP turn. Complete files and
large tool outputs remain retrievable through the existing workspace artifacts.
UI pages are partial projections: routine persistence merges message identities.
Only explicit user history edits may remove records missing from a submitted view.

## Context budget ownership

The Provider owns model capacity. The Conversation owns the user's selected
context budget. Usage reports are observations, not configuration writes.
Refreshing model metadata or receiving usage must not overwrite the user's
setting. Request preparation respects the smaller applicable capacity/budget and
reserves output space; both ordinary and summary requests obey this boundary.
A missing, interrupted, truncated or over-budget summary never advances a durable
checkpoint. A UI repaint, partial page, or empty runtime is never a clear-history
instruction.
