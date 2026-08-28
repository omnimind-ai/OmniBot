@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import com.agentclientprotocol.model.Annotations
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanVariant
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigSelectOptions
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus

/**
 * The official ACP notification envelope forwarded to the host UI.
 *
 * This type intentionally contains no app-specific method names. ACP session
 * updates are carried by the standard `session/update` notification, and the
 * Flutter side consumes the official `sessionUpdate` discriminator inside the
 * update payload.
 */
internal data class AcpSessionNotification(
    val sessionId: String,
    val update: Map<String, Any?>
)

/**
 * Projects the standard per-prompt ACP usage response into the same
 * presentation metadata consumed by every assistant footer.
 *
 * `usage_update` is session-scoped context occupancy; it cannot describe the
 * input/output/cache split for one completed turn. Harnesses such as Claude
 * Code provide that split on [PromptResponse.usage], so the host forwards it
 * as an empty official message chunk for the last assistant message instead
 * of introducing a Harness-specific UI event.
 */
internal fun PromptResponse.toAcpTurnUsageUpdate(
    messageId: MessageId? = null,
): Map<String, Any?>? {
    val promptUsage = usage ?: return null
    val cacheReadTokens = promptUsage.cachedReadTokens ?: 0L
    val cacheWriteTokens = promptUsage.cachedWriteTokens ?: 0L
    val totalInputTokens = promptUsage.inputTokens + cacheReadTokens + cacheWriteTokens
    val turnUsage = linkedMapOf<String, Any?>(
        "ctx" to totalInputTokens,
        "in" to totalInputTokens,
        "out" to promptUsage.outputTokens,
        "cache" to cacheReadTokens,
        "totalInputTokens" to totalInputTokens,
        "uncachedInputTokens" to promptUsage.inputTokens,
        "cacheReadTokens" to cacheReadTokens,
        "cacheWriteTokens" to cacheWriteTokens,
        "promptTokens" to totalInputTokens,
        "completionTokens" to promptUsage.outputTokens,
        "totalTokens" to promptUsage.totalTokens,
    )
    return linkedMapOf<String, Any?>(
        "sessionUpdate" to "agent_message_chunk",
        "content" to mapOf("type" to "text", "text" to ""),
        "_meta" to mapOf(
            "cn.com.omnimind.agent" to mapOf(
                "usage" to mapOf("turnUsage" to turnUsage)
            )
        ),
    ).apply {
        messageId?.value?.let { put("messageId", it) }
    }
}

/**
 * Maps one ACP session update to the official ACP `session/update` payload, or
 * `null` when the update carries nothing the timeline renders.
 *
 * [threadId] scopes the notification to its ACP session. Optional ACP fields
 * are preserved as optional; the presentation layer may create local fallback
 * ids when it needs to render a card, but the protocol bridge does not invent
 * them.
 */
internal fun SessionUpdate.toAcpSessionNotification(
    threadId: String
): AcpSessionNotification? = when (this) {
    is SessionUpdate.AgentMessageChunk -> AcpSessionNotification(
        sessionId = threadId,
        update = linkedMapOf<String, Any?>(
            "sessionUpdate" to "agent_message_chunk",
            "content" to content.acpPayload()
        ).apply {
            messageId?.value?.let { put("messageId", it) }
            putAcpMeta(_meta)
        }
    )

    is SessionUpdate.AgentThoughtChunk -> AcpSessionNotification(
        sessionId = threadId,
        update = linkedMapOf<String, Any?>(
            "sessionUpdate" to "agent_thought_chunk",
            "content" to content.acpPayload()
        ).apply {
            messageId?.value?.let { put("messageId", it) }
            putAcpMeta(_meta)
        }
    )

    is SessionUpdate.ToolCall -> AcpSessionNotification(
        sessionId = threadId,
        update = toolPayload(this) + ("sessionUpdate" to "tool_call")
    )

    is SessionUpdate.ToolCallUpdate -> AcpSessionNotification(
        sessionId = threadId,
        update = toolPayload(this) + ("sessionUpdate" to "tool_call_update")
    )

    is SessionUpdate.PlanUpdate -> AcpSessionNotification(
        sessionId = threadId,
        update = linkedMapOf<String, Any?>(
            "sessionUpdate" to "plan",
            "entries" to entries.map(PlanEntry::acpPayload),
        ).apply { putAcpMeta(_meta) }
    )

    is SessionUpdate.PlanUpdateV2 -> AcpSessionNotification(
        sessionId = threadId,
        update = linkedMapOf<String, Any?>(
            "sessionUpdate" to "plan_update",
            "plan" to plan.acpPayload(),
        ).apply { putAcpMeta(_meta) }
    )

    is SessionUpdate.PlanRemoved -> AcpSessionNotification(
        sessionId = threadId,
        update = linkedMapOf<String, Any?>(
            "sessionUpdate" to "plan_removed",
            "id" to id,
        ).apply { putAcpMeta(_meta) }
    )

    is SessionUpdate.CurrentModeUpdate -> AcpSessionNotification(
        sessionId = threadId,
        update = mapOf(
            "sessionUpdate" to "current_mode_update",
            "currentModeId" to currentModeId.value
        )
    )

    is SessionUpdate.ConfigOptionUpdate -> AcpSessionNotification(
        sessionId = threadId,
        update = linkedMapOf<String, Any?>(
            "sessionUpdate" to "config_option_update",
            "configOptions" to configOptions.map(::acpConfigOptionPayload)
        ).apply { putAcpMeta(_meta) }
    )

    is SessionUpdate.SessionInfoUpdate -> linkedMapOf<String, Any?>().apply {
        put("sessionUpdate", "session_info_update")
        title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
        updatedAt?.let { put("updatedAt", it) }
        putAcpMeta(_meta)
    }.takeIf { it.size > 1 }?.let {
        AcpSessionNotification(sessionId = threadId, update = it)
    }

    is SessionUpdate.UsageUpdate -> AcpSessionNotification(
        sessionId = threadId,
        update = linkedMapOf<String, Any?>(
            "sessionUpdate" to "usage_update",
            "used" to used,
            "size" to size,
            "cost" to cost?.let { mapOf("amount" to it.amount, "currency" to it.currency) }
        ).apply { putAcpMeta(_meta) }
    )

    is SessionUpdate.AvailableCommandsUpdate -> AcpSessionNotification(
        sessionId = threadId,
        update = mapOf(
            "sessionUpdate" to "available_commands_update",
            "availableCommands" to availableCommands.map {
                mapOf("name" to it.name, "description" to it.description)
            }
        )
    )

    // Keep an extension inside the official session/update envelope. This is
    // deliberately not converted to an app-owned event name: the shared UI
    // can retain/inspect the original discriminator and raw JSON, while a
    // future ACP-aware renderer can add a projection without changing any
    // Harness adapter.
    is SessionUpdate.UnknownSessionUpdate -> AcpSessionNotification(
        sessionId = threadId,
        update = linkedMapOf<String, Any?>(
            "sessionUpdate" to sessionUpdateType,
            "rawUpdate" to rawJson.toAcpValue(),
        ).apply { putAcpMeta(_meta) }
    )

    // ACP agents may echo the submitted prompt as a user_message_chunk. Keep
    // the official update intact: the host Conversation reducer decides
    // whether it is a replay or a live echo and merges it idempotently with
    // the locally committed user message. Dropping it here makes the DSH
    // prompt disappear before it can reach the shared ACP projection.
    is SessionUpdate.UserMessageChunk -> AcpSessionNotification(
        sessionId = threadId,
        update = linkedMapOf<String, Any?>(
            "sessionUpdate" to "user_message_chunk",
            "content" to content.acpPayload(),
        ).apply {
            messageId?.value?.let { put("messageId", it) }
            putAcpMeta(_meta)
        }
    )
}

/**
 * Whether an ACP session update belongs to a specific prompt turn.
 *
 * Timeline updates (messages, reasoning, tool calls, plans) render inside a
 * turn and are meaningless without one. Session-scoped updates (title, mode,
 * config, usage, available commands) apply to the thread and are still worth
 * forwarding between turns.
 */
internal fun SessionUpdate.isTurnScoped(): Boolean = when (this) {
    is SessionUpdate.AgentMessageChunk,
    is SessionUpdate.AgentThoughtChunk,
    is SessionUpdate.ToolCall,
    is SessionUpdate.ToolCallUpdate,
    is SessionUpdate.PlanUpdate,
    is SessionUpdate.PlanUpdateV2,
    is SessionUpdate.PlanRemoved,
    is SessionUpdate.UserMessageChunk -> true
    else -> false
}

internal fun acpConfigOptionPayload(option: SessionConfigOption): Map<String, Any?> {
    val base = linkedMapOf<String, Any?>(
        "id" to option.id.value,
        "name" to option.name,
        "description" to option.description,
        "category" to option.category?.value,
        "currentValue" to option.acpCurrentValuePayload()
    )
    when (option) {
        is SessionConfigOption.Select -> {
            base["type"] = "select"
            base["options"] = option.acpFlatOptions().map {
                mapOf(
                    "value" to it.value.value,
                    "name" to it.name,
                    "description" to it.description
                )
            }
        }
        is SessionConfigOption.BooleanOption -> {
            base["type"] = "boolean"
        }
    }
    return base
}

private fun SessionConfigOption.Select.acpFlatOptions() = when (val value = options) {
    is SessionConfigSelectOptions.Flat -> value.options
    is SessionConfigSelectOptions.Grouped -> value.groups.flatMap { it.options }
}

private fun SessionConfigOption.acpCurrentValuePayload(): Any? = when (this) {
    is SessionConfigOption.Select -> currentValue.value
    is SessionConfigOption.BooleanOption -> currentValue
}

/** Preserve the official ACP content-block union at the platform boundary. */
private fun ContentBlock.acpPayload(): Map<String, Any?> = when (this) {
    is ContentBlock.Text -> linkedMapOf<String, Any?>(
        "type" to "text",
        "text" to text,
    )
    is ContentBlock.Image -> linkedMapOf<String, Any?>(
        "type" to "image",
        "data" to data,
        "mimeType" to mimeType,
    ).apply { uri?.let { put("uri", it) } }
    is ContentBlock.Audio -> linkedMapOf<String, Any?>(
        "type" to "audio",
        "data" to data,
        "mimeType" to mimeType,
    )
    is ContentBlock.ResourceLink -> linkedMapOf<String, Any?>(
        "type" to "resource_link",
        "name" to name,
        "uri" to uri,
    ).apply {
        description?.let { put("description", it) }
        mimeType?.let { put("mimeType", it) }
        put("size", size)
        title?.let { put("title", it) }
    }
    is ContentBlock.Resource -> linkedMapOf<String, Any?>(
        "type" to "resource",
        "resource" to resource.acpPayload(),
    )
}.apply {
    annotations?.let { put("annotations", it.acpPayload()) }
    putAcpMeta(_meta)
}

private fun Annotations.acpPayload(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    audience?.let { roles -> put("audience", roles.map { it.name.lowercase() }) }
    priority?.let { put("priority", it) }
    lastModified?.let { put("lastModified", it) }
    putAcpMeta(_meta)
}

private fun EmbeddedResourceResource.acpPayload(): Map<String, Any?> = when (this) {
    is EmbeddedResourceResource.TextResourceContents -> linkedMapOf<String, Any?>(
        "text" to text,
        "uri" to uri,
    ).apply {
        mimeType?.let { put("mimeType", it) }
        putAcpMeta(_meta)
    }
    is EmbeddedResourceResource.BlobResourceContents -> linkedMapOf<String, Any?>(
        "blob" to blob,
        "uri" to uri,
    ).apply {
        mimeType?.let { put("mimeType", it) }
        putAcpMeta(_meta)
    }
}

private fun PlanEntry.acpPayload(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "content" to content,
    "priority" to priority.name.lowercase(),
    "status" to status.name.lowercase(),
).apply { putAcpMeta(_meta) }

private fun PlanVariant.acpPayload(): Map<String, Any?> = when (this) {
    is PlanVariant.Items -> linkedMapOf<String, Any?>(
        "type" to "items",
        "id" to id,
        "entries" to entries.map(PlanEntry::acpPayload),
    ).apply { putAcpMeta(_meta) }
    is PlanVariant.Markdown -> linkedMapOf<String, Any?>(
        "type" to "markdown",
        "id" to id,
        "content" to content,
    ).apply { putAcpMeta(_meta) }
    is PlanVariant.File -> linkedMapOf<String, Any?>(
        "type" to "file",
        "id" to id,
        "uri" to uri,
    ).apply { putAcpMeta(_meta) }
}

private fun toolPayload(update: SessionUpdate.ToolCall): Map<String, Any?> =
    linkedMapOf(
        "toolCallId" to update.toolCallId.value,
        "kind" to (update.kind?.name?.lowercase() ?: "other"),
        "title" to update.title,
        "status" to update.status?.name?.lowercase(),
        "content" to update.content.toolContentPayload(),
        "locations" to update.locations.map {
            mapOf("path" to it.path, "line" to it.line?.toLong())
        },
        "rawInput" to update.rawInput?.toAcpValue(),
        "rawOutput" to update.rawOutput?.toAcpValue()
    ).apply {
        putAcpMeta(update._meta)
    }

private fun toolPayload(update: SessionUpdate.ToolCallUpdate): Map<String, Any?> =
    linkedMapOf(
        "toolCallId" to update.toolCallId.value,
        "kind" to update.kind?.name?.lowercase(),
        "title" to update.title,
        "status" to update.status?.name?.lowercase(),
        "content" to update.content?.toolContentPayload(),
        "locations" to update.locations?.map {
            mapOf("path" to it.path, "line" to it.line?.toLong())
        },
        "rawInput" to update.rawInput?.toAcpValue(),
        "rawOutput" to update.rawOutput?.toAcpValue()
    ).apply {
        putAcpMeta(update._meta)
    }

private fun MutableMap<String, Any?>.putAcpMeta(
    meta: kotlinx.serialization.json.JsonElement?
) {
    if (meta != null && meta !is kotlinx.serialization.json.JsonNull) {
        put("_meta", meta.toAcpValue())
    }
}

private fun List<ToolCallContent>.toolContentPayload(): List<Map<String, Any?>> = map {
    when (it) {
        is ToolCallContent.Content -> mapOf(
            "type" to "content",
            "content" to it.content.acpPayload()
        )
        is ToolCallContent.Diff -> linkedMapOf<String, Any?>(
            "type" to "diff",
            "path" to it.path,
            "oldText" to it.oldText,
            "newText" to it.newText
        ).apply { putAcpMeta(it._meta) }
        is ToolCallContent.Terminal -> linkedMapOf<String, Any?>(
            "type" to "terminal",
            "terminalId" to it.terminalId
        ).apply { putAcpMeta(it._meta) }
    }
}

private fun kotlinx.serialization.json.JsonElement.toAcpValue(): Any? = when (this) {
    is kotlinx.serialization.json.JsonObject -> entries.associate { (key, value) ->
        key to value.toAcpValue()
    }
    is kotlinx.serialization.json.JsonArray -> map { it.toAcpValue() }
    is kotlinx.serialization.json.JsonPrimitive -> {
        if (isString) content
        else when (content) {
            "true" -> true
            "false" -> false
            "null" -> null
            else -> content.toLongOrNull() ?: content.toDoubleOrNull() ?: content
        }
    }
}
