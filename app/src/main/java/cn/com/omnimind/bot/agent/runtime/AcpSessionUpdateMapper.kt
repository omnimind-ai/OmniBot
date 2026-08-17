@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PlanVariant
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
        }
    )

    is SessionUpdate.AgentThoughtChunk -> AcpSessionNotification(
        sessionId = threadId,
        update = linkedMapOf<String, Any?>(
            "sessionUpdate" to "agent_thought_chunk",
            "content" to content.acpPayload()
        ).apply {
            messageId?.value?.let { put("messageId", it) }
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
        update = mapOf(
            "sessionUpdate" to "plan",
            "entries" to entries.map {
                mapOf(
                    "content" to it.content,
                    "priority" to it.priority.name.lowercase(),
                    "status" to it.status.name.lowercase()
                )
            }
        )
    )

    is SessionUpdate.PlanUpdateV2 -> AcpSessionNotification(
        sessionId = threadId,
        update = mapOf(
            "sessionUpdate" to "plan",
            "entries" to when (val variant = plan) {
                is PlanVariant.Items -> variant.entries.map {
                    mapOf(
                        "content" to it.content,
                        "priority" to it.priority.name.lowercase(),
                        "status" to it.status.name.lowercase()
                    )
                }
                is PlanVariant.Markdown -> listOf(
                    mapOf(
                        "content" to variant.content,
                        "priority" to "medium",
                        "status" to "in_progress"
                    )
                )
                is PlanVariant.File -> listOf(
                    mapOf(
                        "content" to variant.uri,
                        "priority" to "medium",
                        "status" to "in_progress"
                    )
                )
            }
        )
    )

    is SessionUpdate.PlanRemoved -> AcpSessionNotification(
        sessionId = threadId,
        update = mapOf(
            "sessionUpdate" to "plan",
            "entries" to emptyList<Map<String, Any?>>()
        )
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
        update = mapOf(
            "sessionUpdate" to "config_option_update",
            "configOptions" to configOptions.map(::acpConfigOptionPayload)
        )
    )

    is SessionUpdate.SessionInfoUpdate -> title
        ?.takeIf { it.isNotBlank() }
        ?.let {
            AcpSessionNotification(
                sessionId = threadId,
                update = mapOf(
                    "sessionUpdate" to "session_info_update",
                    "title" to it
                )
            )
        }

    is SessionUpdate.UsageUpdate -> AcpSessionNotification(
        sessionId = threadId,
        update = mapOf(
            "sessionUpdate" to "usage_update",
            "used" to used,
            "size" to size,
            "cost" to cost?.let { mapOf("amount" to it.amount, "currency" to it.currency) }
        )
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

    // ACP clients must not invent a second event type for unknown updates.
    // The official SDK already preserves the raw update at the protocol seam;
    // unsupported updates are intentionally ignored by this UI projection.
    is SessionUpdate.UnknownSessionUpdate -> null

    // The client is the author of user messages, so a replayed echo of one adds
    // nothing to the timeline.
    is SessionUpdate.UserMessageChunk -> null
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
    is SessionUpdate.PlanRemoved -> true
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

private fun ContentBlock.textPayload(): String = when (this) {
    is ContentBlock.Text -> text
    is ContentBlock.ResourceLink -> title ?: name
    is ContentBlock.Image -> uri ?: ""
    is ContentBlock.Audio -> ""
    is ContentBlock.Resource -> resource.toString()
}

private fun ContentBlock.acpPayload(): Map<String, Any?> = mapOf(
    "type" to "text",
    "text" to textPayload()
)

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
    )

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
    )

private fun List<ToolCallContent>.toolContentPayload(): List<Map<String, Any?>> = map {
    when (it) {
        is ToolCallContent.Content -> mapOf(
            "type" to "content",
            "content" to it.content.acpPayload()
        )
        is ToolCallContent.Diff -> mapOf(
            "type" to "diff",
            "path" to it.path,
            "oldText" to it.oldText,
            "newText" to it.newText
        )
        is ToolCallContent.Terminal -> mapOf(
            "type" to "terminal",
            "terminalId" to it.terminalId
        )
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
