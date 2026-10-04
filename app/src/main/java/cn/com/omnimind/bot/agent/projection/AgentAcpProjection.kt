package cn.com.omnimind.bot.agent.projection

/*
 * Port of the top-level (non-class) part of `agent_event_reducer.dart`:
 * ACP session/update projection, tool call projection, content / media /
 * artifact helpers, usage / commands / config helpers, the legacy event
 * adapter and the envelope helpers. Dart private names drop the underscore.
 */

internal class AgentQuestion(
    val id: String,
    val title: String,
    val detail: String,
)

internal val renderableAcpIncrementTypes: Set<String> = linkedSetOf(
    "tool_call_content_chunk",
    "terminal_output_chunk",
    "terminal_update",
)

internal fun isRenderableAcpRawUpdate(update: Map<String, Any?>): Boolean {
    val sessionUpdate = acpString(update["sessionUpdate"])
    if (renderableAcpIncrementTypes.contains(sessionUpdate)) {
        return true
    }
    val raw = copyStringMap(update["rawUpdate"])
    val rawType = acpString(
        raw?.get("sessionUpdate") ?: raw?.get("type") ?: raw?.get("kind") ?: raw?.get("updateType"),
    )
    return renderableAcpIncrementTypes.contains(rawType)
}

internal fun renderableAcpParams(params: Map<String, Any?>): JsonMap {
    val update = copyStringMap(params["update"])
    if (update == null || !isRenderableAcpRawUpdate(update)) {
        return sameOrStringMap(params)!!
    }
    val raw = copyStringMap(update["rawUpdate"]) ?: return sameOrStringMap(params)!!
    val rawType = acpString(
        raw["sessionUpdate"] ?: raw["type"] ?: raw["kind"] ?: raw["updateType"],
    )
    val updateType = acpString(update["sessionUpdate"])
    val effectiveType = if (renderableAcpIncrementTypes.contains(updateType)) updateType else rawType
    return LinkedHashMap(params).apply {
        put(
            "update",
            LinkedHashMap<String, Any?>(raw).apply {
                putAll(update)
                if (effectiveType != null) put("sessionUpdate", effectiveType)
                put("rawUpdate", update["rawUpdate"])
            },
        )
    }
}

private fun acpPlanEntriesText(entries: List<JsonMap>?): String? =
    entries?.joinToString("\n") { entry ->
        "- [${entry["status"] ?: "pending"}] ${entry["content"] ?: ""}"
    }

private fun acpPlanEntries(value: Any?): List<JsonMap>? =
    (value as List<*>?)
        ?.filterIsInstance<Map<*, *>>()
        ?.map { entry -> copyStringMap(entry)!! }

internal fun projectAcpSessionUpdate(
    event: Map<String, Any?>,
    params: Map<String, Any?>,
): JsonMap? {
    val update = copyStringMap(params["update"]) ?: return null
    val message = copyStringMap(event["message"])
    val sessionId = acpFirstString(
        event["sessionId"],
        event["session_id"],
        message?.get("sessionId"),
        message?.get("session_id"),
        message?.get("threadId"),
        message?.get("thread_id"),
        params["sessionId"],
        params["session_id"],
        event["threadId"],
        event["thread_id"],
        params["threadId"],
        params["thread_id"],
        update["sessionId"],
        update["session_id"],
    )
    val turnId = acpFirstString(
        event["turnId"],
        event["turn_id"],
        message?.get("turnId"),
        message?.get("turn_id"),
        message?.get("taskId"),
        message?.get("task_id"),
        message?.get("runId"),
        message?.get("run_id"),
        params["turnId"],
        params["turn_id"],
        update["turnId"],
        update["turn_id"],
        update["taskId"],
        update["task_id"],
    )
    val sessionUpdate = acpString(update["sessionUpdate"])
    if (sessionUpdate == null || sessionUpdate.isEmpty()) return null

    // ACP messageId identifies a message within an ACP session, not an entry
    // in the host conversation. Scope projected entries by the host-owned
    // turn while keeping all chunks from the same turn together.
    fun turnScopedEntryId(rawId: Any?): String? {
        val id = acpString(rawId)?.trim()
        if (id == null || id.isEmpty()) return null
        val owner = turnId?.trim()
        if (owner == null || owner.isEmpty()) return id
        return "$owner-$id"
    }

    val messageIdentity = acpFirstString(
        update["messageId"],
        update["message_id"],
        update["itemId"],
        update["item_id"],
        update["contentId"],
        update["content_id"],
    )
    val scopedMessageId = turnScopedEntryId(messageIdentity) ?: turnId
    val scopedEntryId = turnScopedEntryId(
        update["entryId"] ?: update["entry_id"],
    )
    val presentation = acpPresentationMeta(update)

    // Preserve the reasoning segment identity exposed in _meta so the
    // timeline remains reasoning -> tool -> reasoning even when the upstream
    // messageId is reused.
    val reasoningSegmentIndex = acpReasoningSegmentIndex(presentation)
    val scopedReasoningMessageId =
        if (reasoningSegmentIndex == null || scopedMessageId == null) {
            scopedMessageId
        } else {
            "$scopedMessageId-reasoning-$reasoningSegmentIndex"
        }
    val scopedReasoningEntryId =
        if (reasoningSegmentIndex == null || scopedEntryId == null) {
            scopedEntryId
        } else {
            "$scopedEntryId-reasoning-$reasoningSegmentIndex"
        }

    fun projectedParams(values: Map<String, Any?>): JsonMap {
        return LinkedHashMap(values).apply {
            if (sessionId != null) put("sessionId", sessionId)
            if (sessionId != null) put("threadId", sessionId)
            if (turnId != null) put("turnId", turnId)
            if (acpEventAllowsImplicitTurnAdmission(event)) {
                put("allowImplicitTurnAdmission", true)
            }
        }
    }

    fun terminalItemId(): String? = acpFirstString(
        update["toolCallId"],
        update["tool_call_id"],
        update["callId"],
        update["call_id"],
        update["terminalId"],
        update["terminal_id"],
    )

    when (sessionUpdate) {
        "agent_message_chunk" -> {
            val presentationMedia = acpPresentationMedia(presentation)
            val presentationArtifacts = acpPresentationArtifacts(presentation)
            return jsonMapOf(
                "method" to "item/agentMessage/delta",
                "params" to projectedParams(
                    LinkedHashMap<String, Any?>().apply {
                        // DSH may omit messageId, and when present it is only
                        // session scoped. Both forms need a turn-scoped id.
                        put("itemId", scopedMessageId)
                        if (scopedEntryId != null) put("entryId", scopedEntryId)
                        // Streaming text must preserve every leading/trailing
                        // space and newline across chunk boundaries.
                        put("delta", acpExtractStreamingText(update["content"]) ?: "")
                        if (mergeAcpMedia(
                                acpAssistantMedia(update["content"]),
                                presentationMedia,
                            ).isNotEmpty()
                        ) {
                            put(
                                "acpAssistantMedia",
                                mergeAcpMedia(
                                    acpAssistantMedia(update["content"]),
                                    presentationMedia,
                                ),
                            )
                        }
                        if (mergeAcpArtifacts(
                                acpAssistantArtifacts(update["content"]),
                                presentationArtifacts,
                            ).isNotEmpty()
                        ) {
                            put(
                                "acpAssistantArtifacts",
                                mergeAcpArtifacts(
                                    acpAssistantArtifacts(update["content"]),
                                    presentationArtifacts,
                                ),
                            )
                        }
                        if (presentation != null) put("acpPresentation", presentation)
                    },
                ),
            )
        }
        "agent_thought_chunk" -> {
            return jsonMapOf(
                "method" to "item/reasoning/delta",
                "params" to projectedParams(
                    LinkedHashMap<String, Any?>().apply {
                        put("itemId", scopedReasoningMessageId)
                        if (scopedReasoningEntryId != null) put("entryId", scopedReasoningEntryId)
                        put("delta", acpReasoningText(update, presentation))
                        if (presentation != null) put("acpPresentation", presentation)
                    },
                ),
            )
        }
        "user_message_chunk" -> {
            // ACP session/load replays and live turn echoes share one
            // projection seam. A live echo is safe only with an official turn
            // identity.
            val isReplay =
                update["replay"] == true ||
                    params["replay"] == true ||
                    event["replay"] == true
            if (!isReplay && turnId == null) return null
            return jsonMapOf(
                "method" to "item/userMessage/delta",
                "params" to projectedParams(
                    LinkedHashMap<String, Any?>().apply {
                        put("itemId", scopedMessageId)
                        if (scopedEntryId != null) put("entryId", scopedEntryId)
                        put("delta", acpExtractStreamingText(update["content"]) ?: "")
                        put("replay", isReplay)
                    },
                ),
            )
        }
        "tool_call" -> {
            return jsonMapOf(
                "method" to "item/started",
                "params" to projectedParams(
                    jsonMapOf(
                        "item" to projectAcpToolCall(
                            update,
                            sessionId = sessionId,
                            turnId = turnId,
                        ),
                    ),
                ),
            )
        }
        "tool_call_update" -> {
            val item = projectAcpToolCall(
                update,
                sessionId = sessionId,
                turnId = turnId,
            )
            val status = acpString(item["status"])?.lowercase()
            return jsonMapOf(
                "method" to if (isTerminalAcpToolStatus(status)) "item/completed" else "item/updated",
                "params" to projectedParams(jsonMapOf("item" to item)),
            )
        }
        "tool_call_content_chunk" -> {
            return jsonMapOf(
                "method" to "item/tool/contentDelta",
                "params" to projectedParams(
                    jsonMapOf(
                        "toolCallId" to acpFirstString(
                            update["toolCallId"],
                            update["tool_call_id"],
                            update["callId"],
                            update["call_id"],
                        ),
                        "content" to (update["content"] ?: update["chunk"] ?: update["data"]),
                        "rawUpdate" to update,
                    ),
                ),
            )
        }
        "terminal_output_chunk" -> {
            return jsonMapOf(
                "method" to "item/commandExecution/outputDelta",
                "params" to projectedParams(
                    jsonMapOf(
                        "itemId" to terminalItemId(),
                        "terminalId" to (update["terminalId"] ?: update["terminal_id"]),
                        "terminalSessionId" to (update["terminalId"] ?: update["terminal_id"]),
                        "delta" to acpTerminalOutputDelta(update),
                        "rawUpdate" to update,
                    ),
                ),
            )
        }
        "terminal_update" -> {
            val terminalDelta = acpTerminalOutputDelta(update)
            val terminalStatus = acpString(
                update["status"] ?: update["state"] ?: update["exitStatus"],
            )
            if (terminalDelta.isNotEmpty()) {
                return jsonMapOf(
                    "method" to "item/commandExecution/outputDelta",
                    "params" to projectedParams(
                        jsonMapOf(
                            "itemId" to terminalItemId(),
                            "terminalId" to (update["terminalId"] ?: update["terminal_id"]),
                            "terminalSessionId" to (update["terminalId"] ?: update["terminal_id"]),
                            "delta" to terminalDelta,
                            "rawUpdate" to update,
                        ),
                    ),
                )
            }
            return jsonMapOf(
                "method" to "item/updated",
                "params" to projectedParams(
                    jsonMapOf(
                        "item" to LinkedHashMap<String, Any?>().apply {
                            put("id", terminalItemId())
                            put("type", "commandExecution")
                            if (terminalStatus != null) put("status", terminalStatus)
                            if (update["terminalId"] != null) {
                                put("terminalSessionId", update["terminalId"])
                            }
                            put("rawUpdate", update)
                        },
                    ),
                ),
            )
        }
        "plan" -> {
            val entries = acpPlanEntries(update["entries"])
            return jsonMapOf(
                "method" to "turn/plan/updated",
                "params" to projectedParams(
                    jsonMapOf(
                        "entries" to (entries ?: emptyList<JsonMap>()),
                        "plan" to (acpPlanEntriesText(entries) ?: ""),
                    ),
                ),
            )
        }
        "plan_update" -> {
            val plan = copyStringMap(update["plan"])
            val planType = acpString(plan?.get("type"))?.lowercase()
            val entries = acpPlanEntries(plan?.get("entries"))
            val planText = when (planType) {
                "markdown" -> acpExtractStreamingText(plan?.get("content")) ?: ""
                "file" -> acpString(plan?.get("uri")) ?: ""
                else -> acpPlanEntriesText(entries) ?: ""
            }
            return jsonMapOf(
                "method" to "turn/plan/updated",
                "params" to projectedParams(
                    LinkedHashMap<String, Any?>().apply {
                        if (acpString(plan?.get("id")) != null) put("itemId", plan!!["id"])
                        if (acpString(plan?.get("id")) != null) put("planId", plan!!["id"])
                        put("entries", entries ?: emptyList<JsonMap>())
                        put("plan", planText)
                        put("planData", plan)
                    },
                ),
            )
        }
        "plan_removed" -> {
            return jsonMapOf(
                "method" to "turn/plan/removed",
                "params" to projectedParams(
                    LinkedHashMap<String, Any?>().apply {
                        if (acpString(update["id"]) != null) put("itemId", update["id"])
                        if (acpString(update["id"]) != null) put("planId", update["id"])
                    },
                ),
            )
        }
        "current_mode_update" -> {
            return jsonMapOf(
                "method" to "thread/settings/updated",
                "params" to projectedParams(
                    jsonMapOf("collaborationMode" to update["currentModeId"]),
                ),
            )
        }
        "config_option_update" -> {
            // Configuration metadata is consumed directly by the ACP-facing
            // settings UI. It is not converted into an app-owned event name.
            return null
        }
        "session_info_update" -> {
            return jsonMapOf(
                "method" to "thread/name/updated",
                "params" to projectedParams(jsonMapOf("name" to update["title"])),
            )
        }
        else -> {
            // Usage, commands, and future ACP update kinds do not affect the
            // chat cards yet.
            return null
        }
    }
}

internal fun isTerminalAcpToolStatus(status: String?): Boolean {
    return when (status?.trim()?.lowercase()) {
        "completed",
        "complete",
        "success",
        "succeeded",
        "failed",
        "error",
        "cancelled",
        "canceled",
        "interrupted",
        "aborted",
        "timeout",
        "timed_out",
        -> true
        else -> false
    }
}

internal fun isTerminalAgentEventMethod(method: String): Boolean {
    return method == "turn/completed" ||
        method == "turn/failed" ||
        method == "thread/closed" ||
        method == "error"
}

internal fun projectAcpToolCall(
    update: Map<String, Any?>,
    sessionId: String? = null,
    turnId: String? = null,
): JsonMap {
    val permissionCard = acpPermissionCard(update["rawOutput"])
    val standardContent = acpStandardToolContent(update["content"])
    val presentation = acpPresentationMeta(update)
    val presentationMedia = acpPresentationMedia(presentation)
    val presentationArtifacts = acpPresentationArtifacts(presentation)
    val standardMedia = acpAssistantMedia(standardContent)
    val allMedia = mergeAcpMedia(standardMedia, presentationMedia)
    val standardPresentation = acpStandardToolPresentation(standardContent)
    val kind = acpString(update["kind"])
    val officialToolType = acpOfficialToolType(kind)
    // Keep a generic item type on sparse updates so the shared reducer still
    // recognizes the lifecycle event as a tool.
    val projectedType: String? = acpToolUiType(kind)
    val rawOutput = update["rawOutput"]
    val structuredOutput = copyStringMap(
        if (rawOutput is String) decodeAcpJsonValue(rawOutput) else rawOutput,
    )
    // Some ACP bridges carry progress metadata in rawInput on
    // `tool_call_update`. Promote the shared presentation fields too.
    val rawInput = update["rawInput"]
    val structuredInput = copyStringMap(
        if (rawInput is String) decodeAcpJsonValue(rawInput) else rawInput,
    )
    val plainRawOutput =
        if (rawOutput is String && structuredOutput == null) rawOutput.trim() else ""
    val structuredArtifacts = asMapList(structuredOutput?.get("artifacts"))
    val standardArtifacts = acpAssistantArtifacts(
        standardContent,
        includeEmbeddedResources = true,
    )
    val artifacts = mergeAcpArtifacts(structuredArtifacts, standardArtifacts)
    val allArtifacts = mergeAcpArtifacts(artifacts, presentationArtifacts)
    val firstMedia = allMedia.firstOrNull()
    val standardToolType = acpString(standardPresentation["toolType"])
    return LinkedHashMap<String, Any?>().apply {
        put("id", update["toolCallId"])
        put("toolCallId", update["toolCallId"])
        if (sessionId != null) put("sessionId", sessionId)
        if (turnId != null) put("turnId", turnId)
        if (projectedType != null) put("type", projectedType)
        put("title", update["title"])
        if (update["title"] != null) put("toolTitle", update["title"])
        put("status", update["status"])
        put("content", update["content"])
        put("locations", update["locations"])
        put("rawInput", update["rawInput"])
        put("rawOutput", update["rawOutput"])
        if (plainRawOutput.isNotEmpty()) {
            put("summary", plainRawOutput)
            put("progress", plainRawOutput)
        }
        putAll(acpStructuredToolOutput(structuredInput))
        putAll(acpStructuredToolOutput(structuredOutput))
        if (allArtifacts.isNotEmpty()) put("artifacts", allArtifacts)
        if (standardContent.isNotEmpty()) put("contentItems", standardContent)
        if (allMedia.isNotEmpty()) put("media", allMedia)
        if (firstMedia?.get("imageDataUrl") != null) put("imageDataUrl", firstMedia["imageDataUrl"])
        if (firstMedia?.get("imageUrl") != null) put("imageUrl", firstMedia["imageUrl"])
        if (firstMedia?.get("audioDataUrl") != null) put("audioDataUrl", firstMedia["audioDataUrl"])
        if (firstMedia?.get("audioUrl") != null) put("audioUrl", firstMedia["audioUrl"])
        if (firstMedia?.get("mimeType") != null) put("mimeType", firstMedia["mimeType"])
        // Standard content is a concrete capability signal and takes
        // precedence over generic adapter envelopes; an official ToolKind is
        // the fallback.
        putAll(standardPresentation)
        if (standardToolType == null && officialToolType != null) {
            put("toolType", officialToolType)
        }
        if (permissionCard != null) put("permissionCard", permissionCard)
    }
}

internal fun acpStandardToolContent(value: Any?): MutableList<JsonMap> {
    return acpContentItems(value)
}

internal fun acpContentItems(value: Any?): MutableList<JsonMap> {
    if (value is List<*>) {
        return value
            .filterIsInstance<Map<*, *>>()
            .mapTo(ArrayList()) { item -> copyStringMap(item)!! }
    }
    val item = copyStringMap(value)
    return if (item == null) mutableListOf() else mutableListOf(item)
}

internal fun acpTerminalOutputDelta(update: Map<String, Any?>): String {
    val encoding = acpString(
        update["encoding"] ?: update["dataEncoding"],
    )?.lowercase()
    if (encoding == "base64") {
        return acpDecodeBase64Output(update["data"] ?: update["output"]) ?: ""
    }
    val byteList = acpDecodeByteListOutput(update["bytes"])
    if (byteList != null) {
        return byteList
    }
    return acpExtractStreamingText(
        update["output"]
            ?: update["text"]
            ?: update["delta"]
            ?: update["data"]
            ?: update["content"],
    ) ?: ""
}

/**
 * Converts ACP assistant image/resource blocks into the image location shape
 * used by the shared tool card.
 */
internal fun acpAssistantMedia(value: Any?): MutableList<JsonMap> {
    val media = ArrayList<JsonMap>()

    fun visit(candidate: Any?) {
        if (candidate is List<*>) {
            for (item in candidate) {
                visit(item)
            }
            return
        }
        val block = copyStringMap(candidate) ?: return
        val type = acpString(block["type"])?.lowercase()
        if (type == "content") {
            visit(block["content"])
            return
        }
        if (type == "resource") {
            visit(block["resource"])
            return
        }
        // ACP extensions often put the already-normalized media location in
        // `_meta` rather than repeating an official ContentBlock.
        val normalizedMediaType = acpString(block["mediaType"])?.lowercase()
        val normalizedImage = acpString(block["imageDataUrl"]) ?: acpString(block["imageUrl"])
        val normalizedAudio = acpString(block["audioDataUrl"]) ?: acpString(block["audioUrl"])
        if (normalizedImage != null && normalizedImage.trim().isNotEmpty()) {
            media.add(
                LinkedHashMap<String, Any?>().apply {
                    put("mediaType", "image")
                    if (normalizedImage.startsWith("data:")) {
                        put("imageDataUrl", normalizedImage)
                    } else {
                        put("imageUrl", normalizedImage)
                    }
                    put("mimeType", acpString(block["mimeType"]) ?: "image/png")
                    put("title", acpString(block["title"]) ?: acpString(block["name"]) ?: "图片")
                },
            )
            return
        }
        if (normalizedAudio != null && normalizedAudio.trim().isNotEmpty()) {
            media.add(
                LinkedHashMap<String, Any?>().apply {
                    put("mediaType", "audio")
                    if (normalizedAudio.startsWith("data:")) {
                        put("audioDataUrl", normalizedAudio)
                    } else {
                        put("audioUrl", normalizedAudio)
                    }
                    put("mimeType", acpString(block["mimeType"]) ?: "audio/mpeg")
                    put("title", acpString(block["title"]) ?: acpString(block["name"]) ?: "音频")
                },
            )
            return
        }
        val mimeType = acpString(block["mimeType"]) ?: acpString(block["mime_type"])
        val isImage =
            type == "image" ||
                normalizedMediaType == "image" ||
                mimeType?.lowercase()?.startsWith("image/") == true
        val isAudio =
            type == "audio" ||
                normalizedMediaType == "audio" ||
                mimeType?.lowercase()?.startsWith("audio/") == true
        if (!isImage && !isAudio) return
        val data = acpString(block["data"]) ?: acpString(block["blob"])
        val uri = acpString(block["uri"]) ?: acpString(block["url"])
        val mediaType = if (isAudio) "audio" else "image"
        val location = if (data == null || data.isEmpty()) {
            uri
        } else if (data.startsWith("data:")) {
            data
        } else {
            "data:${mimeType ?: (if (isAudio) "audio/mpeg" else "image/png")};base64,$data"
        }
        if (location == null || location.trim().isEmpty()) return
        media.add(
            LinkedHashMap<String, Any?>().apply {
                put("mediaType", mediaType)
                if (isImage && location.startsWith("data:")) put("imageDataUrl", location)
                if (isImage && !location.startsWith("data:")) put("imageUrl", location)
                if (isAudio && location.startsWith("data:")) put("audioDataUrl", location)
                if (isAudio && !location.startsWith("data:")) put("audioUrl", location)
                put("mimeType", mimeType ?: (if (isAudio) "audio/mpeg" else "image/png"))
                put(
                    "title",
                    acpString(block["title"])
                        ?: acpString(block["name"])
                        ?: (if (isAudio) "音频" else "图片"),
                )
            },
        )
    }

    visit(value)
    return media
}

/** Dart `Uri.encodeComponent`: UTF-8 percent-encoding, unreserved `A-Za-z0-9-_.!~*'()`. */
private fun dartUriEncodeComponent(value: String): String {
    val out = StringBuilder()
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        if (code < 0x80 &&
            (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-_.!~*'()")
        ) {
            out.append(char)
        } else {
            out.append('%')
            out.append("0123456789ABCDEF"[code shr 4])
            out.append("0123456789ABCDEF"[code and 0x0F])
        }
    }
    return out.toString()
}

/**
 * Projects non-image ACP resources into the existing artifact card. Image
 * resources remain on the image-card route handled by [acpAssistantMedia].
 */
internal fun acpAssistantArtifacts(
    value: Any?,
    includeEmbeddedResources: Boolean = false,
): MutableList<JsonMap> {
    val artifacts = ArrayList<JsonMap>()

    fun visit(candidate: Any?) {
        if (candidate is List<*>) {
            for (item in candidate) {
                visit(item)
            }
            return
        }
        val block = copyStringMap(candidate) ?: return
        val type = acpString(block["type"])?.lowercase()
        if (type == "content") {
            visit(block["content"])
            return
        }
        if (type == "resource") {
            if (!includeEmbeddedResources) return
            val resource = copyStringMap(block["resource"]) ?: return
            val resourceMimeType =
                acpString(resource["mimeType"]) ?: acpString(resource["mime_type"])
            if (resourceMimeType?.lowercase()?.startsWith("image/") == true) {
                return
            }
            val uri = acpString(resource["uri"])?.trim()
            // A raw ACP blob without a durable URI cannot safely be routed to
            // the artifact card yet.
            if (uri == null || uri.isEmpty()) return
            val text = acpString(resource["text"])
            val blob = acpString(resource["blob"])
            val title =
                acpString(block["title"])
                    ?: acpString(block["name"])
                    ?: acpString(resource["name"])
                    ?: uri
            artifacts.add(
                LinkedHashMap<String, Any?>().apply {
                    put("id", "resource-${dartUriEncodeComponent(uri)}")
                    put("title", title)
                    if (acpString(block["name"]) != null) put("fileName", block["name"])
                    put("uri", uri)
                    if (resourceMimeType != null) put("mimeType", resourceMimeType)
                    if (resource["size"] != null) put("size", resource["size"])
                    if (text != null) put("text", text)
                    if (blob != null) put("blob", blob)
                    if (resourceMimeType?.lowercase()?.startsWith("text/") == true) {
                        put("previewKind", "text")
                    }
                },
            )
            return
        }
        val uri = acpString(block["uri"])?.trim()
        // `_meta` presentations commonly use a compact `{uri, title,
        // mimeType}` artifact object instead of a `resource_link` block.
        if (type == null || type == "artifact" || type == "file") {
            if (uri == null || uri.isEmpty()) return
        } else if (type != "resource_link") {
            return
        }
        if (uri == null || uri.isEmpty()) return
        val mimeType = acpString(block["mimeType"]) ?: acpString(block["mime_type"])
        if (mimeType?.lowercase()?.startsWith("image/") == true) return
        val title =
            acpString(block["title"])
                ?: acpString(block["name"])
                ?: acpString(block["description"])
                ?: uri
        artifacts.add(
            LinkedHashMap<String, Any?>().apply {
                put("id", "resource-${dartUriEncodeComponent(uri)}")
                put("title", title)
                if (acpString(block["name"]) != null) put("fileName", block["name"])
                put("uri", uri)
                if (mimeType != null) put("mimeType", mimeType)
                if (block["size"] != null) put("size", block["size"])
                if (mimeType?.lowercase()?.startsWith("text/") == true) {
                    put("previewKind", "text")
                }
            },
        )
    }

    visit(value)
    return artifacts
}

internal fun acpPresentationMedia(presentation: Map<String, Any?>?): MutableList<JsonMap> {
    if (presentation == null) return mutableListOf()
    return acpAssistantMedia(
        presentation["media"] ?: presentation["images"] ?: presentation["audio"],
    )
}

internal fun acpPresentationArtifacts(presentation: Map<String, Any?>?): MutableList<JsonMap> {
    if (presentation == null) return mutableListOf()
    return acpAssistantArtifacts(
        presentation["artifacts"]
            ?: presentation["artifact"]
            ?: presentation["resources"],
    )
}

internal fun mergeAcpMedia(
    first: List<JsonMap>,
    second: List<JsonMap>,
): MutableList<JsonMap> {
    val merged = ArrayList<JsonMap>(first)
    for (candidate in second) {
        val candidateLocation = acpFirstString(
            candidate["imageDataUrl"],
            candidate["imageUrl"],
            candidate["audioDataUrl"],
            candidate["audioUrl"],
        )
        val duplicate =
            candidateLocation != null &&
                merged.any { existing ->
                    acpFirstString(
                        existing["imageDataUrl"],
                        existing["imageUrl"],
                        existing["audioDataUrl"],
                        existing["audioUrl"],
                    ) == candidateLocation
                }
        if (!duplicate) merged.add(candidate)
    }
    return merged
}

internal fun mergeAcpArtifacts(
    first: List<JsonMap>,
    second: List<JsonMap>,
): MutableList<JsonMap> {
    val merged = ArrayList<JsonMap>(first)
    for (candidate in second) {
        val candidateId = acpString(candidate["id"])
        val candidateUri = acpString(candidate["uri"])
        val duplicate = merged.any { existing ->
            val existingId = acpString(existing["id"])
            val existingUri = acpString(existing["uri"])
            (candidateId != null && candidateId == existingId) ||
                (candidateUri != null && candidateUri == existingUri)
        }
        if (!duplicate) merged.add(candidate)
    }
    return merged
}

/**
 * Derives visual hints from standard ACP tool content once, before all
 * Harnesses enter the existing shared card router.
 */
internal fun acpStandardToolPresentation(contentItems: List<Map<String, Any?>>): JsonMap {
    var imageDataUrl: String? = null
    var audioDataUrl: String? = null
    var audioUrl: String? = null
    var audioMimeType: String? = null
    var terminalSessionId: String? = null
    var diffPath: String? = null
    var hasDiff = false
    for (media in acpAssistantMedia(contentItems)) {
        val mediaType = acpString(media["mediaType"])?.lowercase()
        if (mediaType == "image") {
            imageDataUrl = imageDataUrl ?: acpFirstString(media["imageDataUrl"], media["imageUrl"])
        } else if (mediaType == "audio") {
            audioDataUrl = audioDataUrl ?: acpString(media["audioDataUrl"])
            audioUrl = audioUrl ?: acpString(media["audioUrl"])
            audioMimeType = audioMimeType ?: acpString(media["mimeType"])
        }
    }
    for (item in contentItems) {
        val itemType = acpString(item["type"])?.lowercase()
        if (itemType == "diff") {
            hasDiff = true
            diffPath = diffPath ?: acpFirstString(
                item["path"],
                item["filePath"],
                item["file_path"],
            )
            continue
        }
        if (itemType == "terminal") {
            terminalSessionId = terminalSessionId ?: acpString(item["terminalId"])
            continue
        }
        if (itemType != "content") continue
        val block = copyStringMap(item["content"])
        val blockType = acpString(block?.get("type"))?.lowercase()
        if (blockType == "audio") {
            val data = acpString(block?.get("data"))
            val mimeType = acpString(block?.get("mimeType")) ?: "audio/mpeg"
            val uri = acpString(block?.get("uri")) ?: acpString(block?.get("url"))
            if (data != null && data.isNotEmpty()) {
                audioDataUrl = audioDataUrl
                    ?: if (data.startsWith("data:")) data else "data:$mimeType;base64,$data"
            } else {
                audioUrl = audioUrl ?: uri
            }
            audioMimeType = audioMimeType ?: mimeType
            continue
        }
        if (blockType != "image") continue
        val data = acpString(block?.get("data"))
        val mimeType = acpString(block?.get("mimeType")) ?: "image/png"
        imageDataUrl = imageDataUrl
            ?: if (data == null || data.isEmpty()) acpString(block?.get("uri")) else "data:$mimeType;base64,$data"
    }
    return LinkedHashMap<String, Any?>().apply {
        if (hasDiff) put("toolType", "file")
        if (diffPath != null) put("filePath", diffPath)
        if (imageDataUrl != null && !hasDiff) put("toolType", "image")
        if (imageDataUrl != null) put("imageDataUrl", imageDataUrl)
        if (audioDataUrl != null || audioUrl != null) put("toolType", "audio")
        if (audioDataUrl != null) put("audioDataUrl", audioDataUrl)
        if (audioUrl != null) put("audioUrl", audioUrl)
        if (audioMimeType != null) put("mimeType", audioMimeType)
        if (terminalSessionId != null) put("terminalSessionId", terminalSessionId)
    }
}

internal fun acpPresentationMeta(update: Map<String, Any?>): JsonMap? {
    val projection = AcpExtensionRegistry.shared.project(update)
    return if (projection.presentation.isEmpty()) null else projection.presentation
}

/**
 * Reads the shared presentation metadata from an ACP session update,
 * regardless of which supported bridge envelope contains it.
 */
internal fun acpEventPresentation(event: Map<String, Any?>): JsonMap? {
    for (envelope in agentEnvelopeMaps(event)) {
        val update = copyStringMap(envelope["update"]) ?: continue
        val presentation = acpPresentationMeta(update)
        if (presentation != null) return presentation
    }
    return null
}

/**
 * A Harness may report exact turn usage in a final empty message chunk after
 * `turn/completed`; the conversation fence may safely admit this one shape.
 */
internal fun acpEventCarriesFinalTurnUsage(event: Map<String, Any?>): Boolean {
    for (envelope in agentEnvelopeMaps(event)) {
        val update = copyStringMap(envelope["update"])
        if (update == null ||
            acpString(update["sessionUpdate"]) != "agent_message_chunk"
        ) {
            continue
        }
        val presentation = acpPresentationMeta(update)
        val usage = copyStringMap(presentation?.get("usage"))
        if (copyStringMap(usage?.get("turnUsage")) == null) continue
        if ((acpExtractStreamingText(update["content"]) ?: "").isEmpty()) return true
    }
    return false
}

/** ACP's standard usage update, translated once at the reducer boundary. */
internal fun acpStandardUsage(update: Map<String, Any?>): JsonMap {
    return LinkedHashMap<String, Any?>().apply {
        if (update["used"] != null) put("latestPromptTokens", update["used"])
        if (update["size"] != null) put("promptTokenThreshold", update["size"])
    }
}

internal fun acpAvailableCommands(value: Any?): MutableList<JsonMap> {
    if (value !is List<*>) return mutableListOf()
    val commands = ArrayList<JsonMap>()
    val seen = HashSet<String>()
    for (candidate in value) {
        val item = copyStringMap(candidate) ?: continue
        val name = acpString(item["name"] ?: item["command"])?.trim()
        if (name == null || name.isEmpty()) continue
        val normalized = if (name.startsWith("/")) name.substring(1) else name
        if (normalized.isEmpty() || !seen.add(normalized.lowercase())) continue
        commands.add(
            jsonMapOf(
                "name" to normalized,
                "description" to (acpString(item["description"]) ?: ""),
            ),
        )
    }
    return commands
}

internal fun acpConfigOptions(value: Any?): MutableList<JsonMap> {
    if (value !is List<*>) return mutableListOf()
    return value
        .mapNotNull { copyStringMap(it) }
        .map { option -> LinkedHashMap(option) as JsonMap }
        .filterTo(ArrayList()) { option ->
            val id = acpString(option["id"] ?: option["configId"])
            id != null && id.isNotEmpty()
        }
}

internal fun rememberAcpExtensionUpdate(
    runtime: ChatConversationRuntimeState,
    update: Map<String, Any?>,
) {
    runtime.acpExtensionUpdates.add(LinkedHashMap(update))
}

/**
 * Retain extension namespaces even when they do not have a Card projector
 * yet, so the original namespace remains inspectable for replay/debugging.
 */
internal fun rememberAcpExtensionMetadata(
    runtime: ChatConversationRuntimeState,
    update: Map<String, Any?>,
) {
    val projection = AcpExtensionRegistry.shared.project(update)
    if (projection.extensions.isEmpty()) return
    rememberAcpExtensionUpdate(
        runtime,
        jsonMapOf(
            "sessionUpdate" to update["sessionUpdate"],
            "extensions" to projection.extensions,
        ),
    )
}

internal fun acpReasoningCardData(presentation: Map<String, Any?>?): JsonMap {
    val reasoning = copyStringMap(presentation?.get("reasoning")) ?: return LinkedHashMap()
    val taskTitle = acpString(reasoning["taskTitle"] ?: reasoning["task_title"])
    val reasoningSummary = acpExtractText(
        reasoning["summary"] ?: reasoning["reasoningSummary"],
    )?.trim()
    val preparation = acpString(reasoning["preparation"])
    val subTasks = acpStringList(
        reasoning["subTasks"] ?: reasoning["sub_tasks"],
    )
    val memoryActions = acpStringList(
        reasoning["memoryActions"] ?: reasoning["memory_actions"],
    )
    val stage = acpThinkingStage(reasoning["stage"] ?: reasoning["phase"])
    return LinkedHashMap<String, Any?>().apply {
        if (taskTitle != null) put("taskTitle", taskTitle)
        if (subTasks.isNotEmpty()) put("subTasks", subTasks)
        if (preparation != null) put("preparation", preparation)
        if (memoryActions.isNotEmpty()) put("memoryActions", memoryActions)
        if (reasoningSummary != null && reasoningSummary.isNotEmpty()) {
            put("reasoningSummary", reasoningSummary)
        }
        if (stage != null) put("stage", stage)
    }
}

internal fun acpThinkingStage(value: Any?): Int? {
    if (value is Number) {
        val stage = value.toInt()
        return if (stage >= 1 && stage <= 5) stage else null
    }
    val normalized = value?.toString()?.trim()?.lowercase()
    return when (normalized) {
        "thinking", "analysis", "analyzing", "planning" -> 1
        "tool", "tool_call", "tool-call", "calling_tool" -> 2
        "executing", "execution", "running" -> 3
        "complete", "completed", "done", "finished" -> 4
        "cancelled", "canceled", "aborted" -> 5
        else -> null
    }
}

internal fun acpReasoningSegmentIndex(presentation: Map<String, Any?>?): String? {
    if (presentation == null || presentation.isEmpty()) return null
    val reasoning = copyStringMap(presentation["reasoning"])
    val value =
        reasoning?.get("segmentIndex")
            ?: reasoning?.get("segment_index")
            ?: presentation["reasoningSegmentIndex"]
            ?: presentation["reasoning_segment_index"]
            ?: presentation["segmentIndex"]
            ?: presentation["segment_index"]
    return if (value?.toString()?.trim()?.isEmpty() == true) null else value?.toString()?.trim()
}

internal fun preservedAcpReasoningCardData(cardData: Map<String, Any?>): JsonMap {
    return LinkedHashMap<String, Any?>().apply {
        if (acpString(cardData["taskTitle"]) != null) put("taskTitle", cardData["taskTitle"])
        if (cardData["subTasks"] is List<*>) put("subTasks", cardData["subTasks"])
        if (acpString(cardData["preparation"]) != null) put("preparation", cardData["preparation"])
        if (cardData["memoryActions"] is List<*>) put("memoryActions", cardData["memoryActions"])
    }
}

internal fun acpStringList(value: Any?): MutableList<String> {
    if (value is List<*>) {
        return value
            .mapNotNull { acpExtractText(it) }
            .map { item -> item.trim() }
            .filterTo(ArrayList()) { item -> item.isNotEmpty() }
    }
    val text = acpExtractText(value)?.trim()
    return if (text == null || text.isEmpty()) mutableListOf() else mutableListOf(text)
}

internal fun acpReasoningText(
    update: Map<String, Any?>,
    presentation: Map<String, Any?>?,
): String {
    val fallback = acpExtractStreamingText(update["content"]) ?: ""
    if (fallback.isNotEmpty()) {
        return fallback
    }
    val reasoning = copyStringMap(presentation?.get("reasoning")) ?: return fallback
    val metadataText = acpExtractText(
        reasoning["text"]
            ?: reasoning["content"]
            ?: reasoning["message"]
            ?: reasoning["summary"],
    )?.trim()
    val taskDescription = acpExtractText(
        reasoning["taskDescription"] ?: reasoning["task_description"],
    )?.trim()
    val taskTitle = acpExtractText(
        reasoning["taskTitle"] ?: reasoning["task_title"],
    )?.trim()
    val preparation = acpExtractText(reasoning["preparation"])?.trim()
    val subTasks = reasoning["subTasks"] ?: reasoning["sub_tasks"]
    val memoryActions = reasoning["memoryActions"] ?: reasoning["memory_actions"]
    val lines = ArrayList<String>()
    if (taskTitle != null && taskTitle.isNotEmpty()) {
        lines.add(taskTitle)
    }
    if (taskDescription != null && taskDescription.isNotEmpty()) {
        lines.add(taskDescription)
    }
    if (subTasks is List<*>) {
        val items = subTasks
            .mapNotNull { acpExtractText(it) }
            .map { item -> item.trim() }
            .filter { item -> item.isNotEmpty() }
        if (items.isNotEmpty()) {
            lines.add(items.joinToString("\n") { item -> "- $item" })
        }
    }
    if (preparation != null && preparation.isNotEmpty()) {
        lines.add(preparation)
    }
    val memoryItems = acpStringList(memoryActions)
    if (memoryItems.isNotEmpty()) {
        lines.add("记忆：${memoryItems.joinToString("、")}")
    }
    return if (lines.isEmpty()) {
        if (metadataText?.isNotEmpty() == true) metadataText else fallback
    } else {
        lines.joinToString("\n\n")
    }
}

private val acpStructuredToolOutputKeys: List<String> = listOf(
    "toolName",
    "displayName",
    "serverName",
    "summary",
    "message",
    "question",
    "missingFields",
    "missing_fields",
    "missing",
    "progress",
    "terminalOutput",
    "terminalSessionId",
    "terminalStreamState",
    "workspaceId",
    "interruptedBy",
    "interruptionReason",
    "artifacts",
    "actions",
    "success",
    "exitCode",
    "error",
    "timedOut",
    "timed_out",
    "imageDataUrl",
    "dataUrl",
    "imageUrl",
    "audioDataUrl",
    "audioUrl",
    "mimeType",
    "previewJson",
    "rawResultJson",
    "outputTruncated",
    "originalChars",
    "headTail",
    "fullOutputArtifact",
    "subagentStatusText",
    "subagentEvents",
    "subagentEvent",
    "taskId",
    "runId",
    "run_id",
    "contextType",
)

private val acpNestedResultFactKeys: List<String> = listOf(
    "terminalOutput",
    "terminalSessionId",
    "terminalStreamState",
    "imageDataUrl",
    "dataUrl",
    "imageUrl",
    "audioDataUrl",
    "audioUrl",
    "mimeType",
    "artifacts",
    "actions",
    "workspaceId",
    "success",
    "exitCode",
    "error",
    "timedOut",
    "timed_out",
    "outputTruncated",
    "originalChars",
    "headTail",
    "fullOutputArtifact",
    "previewJson",
    "rawResultJson",
)

private val acpNestedResultRequestKeys: List<String> = listOf(
    "question",
    "missingFields",
    "missing_fields",
    "missing",
    "message",
    "subagentStatusText",
    "subagentEvents",
    "subagentEvent",
)

/**
 * ACP reserves rawOutput for adapter-specific tool results. Preserve the
 * common result vocabulary at the shared card seam.
 */
internal fun acpStructuredToolOutput(output: Map<String, Any?>?): JsonMap {
    if (output == null || output.isEmpty()) {
        return LinkedHashMap()
    }
    val result =
        output["result"]
            ?: output["resultPreview"]
            ?: output["preview"]
            ?: output["previewJson"]
    val projected = LinkedHashMap<String, Any?>().apply {
        if (output["toolType"] != null) put("toolType", output["toolType"])
        for (key in acpStructuredToolOutputKeys) {
            if (output[key] != null) put(key, output[key])
        }
        if (result != null) put("result", result)
    }

    // Make nested `result` facts available to the shared card parser.
    val resultMap =
        copyStringMap(result)
            ?: (if (result is String) copyStringMap(decodeAcpJsonValue(result)) else null)
    if (resultMap != null) {
        val nestedToolType = acpString(resultMap["toolType"])
        if ((projected["toolType"] == null ||
                projected["toolType"].toString().trim().lowercase() == "context") &&
            nestedToolType != null &&
            nestedToolType.trim().isNotEmpty() &&
            nestedToolType.trim().lowercase() != "context"
        ) {
            projected["toolType"] = nestedToolType
        }
        for (key in acpNestedResultFactKeys) {
            if (projected[key] == null && resultMap[key] != null) {
                projected[key] = resultMap[key]
            }
        }
        for (key in acpNestedResultRequestKeys) {
            if (projected[key] == null && resultMap[key] != null) {
                projected[key] = resultMap[key]
            }
        }
    }
    if (projected["previewJson"] == null && result != null) {
        projected["previewJson"] = result
    }
    return projected
}

internal fun permissionCardFromAcpItem(item: Map<String, Any?>): JsonMap? {
    val explicit = copyStringMap(item["permissionCard"])
    if (explicit != null) return explicit
    return acpPermissionCard(item["rawOutput"])
}

internal fun acpPermissionCard(rawOutput: Any?): JsonMap? {
    val decoded = if (rawOutput is String) decodeAcpJsonValue(rawOutput) else rawOutput
    val map = copyStringMap(decoded)
    if (map == null || map["type"] != "permission_section") return null
    return map
}

internal fun decodeAcpJsonValue(text: String): Any? {
    return try {
        DartJson.decode(text)
    } catch (_: Exception) {
        text
    }
}

internal fun acpToolUiType(kind: String?): String {
    return when (kind?.lowercase()) {
        "execute" -> "commandExecution"
        "edit", "delete", "move" -> "fileChange"
        "search", "fetch" -> "webSearch"
        "think" -> "plan"
        else -> "tool"
    }
}

/**
 * Official ACP ToolKind is the portable capability signal shared by all
 * Harnesses.
 */
internal fun acpOfficialToolType(kind: String?): String? {
    return when (kind?.trim()?.lowercase()) {
        "read" -> "workspace"
        "edit", "delete", "move" -> "file"
        "search" -> "search"
        "execute" -> "terminal"
        "fetch" -> "browser"
        "think" -> "plan"
        else -> null
    }
}

internal fun isReasoningMethod(method: String): Boolean {
    return method == "item/reasoning/delta" ||
        method == "item/reasoning/summaryPartAdded" ||
        method == "item/reasoning/summaryTextDelta" ||
        method == "item/reasoning/textDelta"
}

private val acpTurnScopedSessionUpdates: Set<String> = setOf(
    "agent_message_chunk",
    "agent_thought_chunk",
    "tool_call",
    "tool_call_update",
    "plan",
    "plan_update",
    "plan_removed",
    "terminal_output_chunk",
    "terminal_update",
)

internal fun requiresAcpTurnIdentity(method: String, params: Map<String, Any?>): Boolean {
    if (method == "item/tool/requestUserInput" ||
        method.endsWith("requestApproval") ||
        (method == "item/userMessage/delta" && params["replay"] == true)
    ) {
        return false
    }
    if (method == "turn/started" ||
        method == "turn/plan/updated" ||
        method == "turn/plan/removed" ||
        method == "turn/diff/updated" ||
        method == "rawResponseItem/completed" ||
        method.startsWith("item/")
    ) {
        val item = copyStringMap(params["item"])
        val itemType = canonicalAgentItemType(acpString(item?.get("type")))
        if (itemType == "requestApproval" ||
            itemType == "requestUserInput" ||
            itemType == "elicitation"
        ) {
            return false
        }
        return true
    }
    if (method != "session/update") {
        return false
    }
    val update = copyStringMap(params["update"])
    val sessionUpdate = acpString(update?.get("sessionUpdate"))
    return sessionUpdate != null && acpTurnScopedSessionUpdates.contains(sessionUpdate)
}

internal class AcpLegacyEventAdapter {
    fun isLegacy(event: Map<String, Any?>): Boolean {
        return event["legacyCompatibility"] == true ||
            event.containsKey("taskId") ||
            event.containsKey("task_id") ||
            event.containsKey("streamKind") ||
            event.containsKey("eventKind") ||
            (event.containsKey("kind") &&
                resolveAgentEventMethod(event = event, message = event).isEmpty())
    }

    /**
     * Converts the removed `AgentStreamEvent` data shape into official ACP
     * item notifications. Returns [source] itself (when mutable) if it is not
     * a legacy shape.
     */
    fun normalize(source: Map<String, Any?>): JsonMap {
        if (resolveAgentEventMethod(event = source, message = source).isNotEmpty()) {
            return sameOrStringMap(source)!!
        }
        val kind = acpString(
            source["kind"] ?: source["streamKind"] ?: source["eventKind"],
        )?.lowercase()
        if (kind == null || kind.isEmpty()) {
            return sameOrStringMap(source)!!
        }

        val taskId = acpFirstString(
            source["taskId"],
            source["task_id"],
            source["turnId"],
            source["turn_id"],
            source["runId"],
            source["run_id"],
        )
        val entryId = acpFirstString(
            source["entryId"],
            source["entry_id"],
            source["messageId"],
            source["message_id"],
            source["itemId"],
            source["item_id"],
            source["callId"],
            source["call_id"],
        )
        val sessionId = acpFirstString(
            source["sessionId"],
            source["session_id"],
            source["threadId"],
            source["thread_id"],
        )
        val requestId =
            source["requestId"]
                ?: source["request_id"]
                ?: source["requestID"]
                ?: source["id"]
        val sequence = acpFirstString(source["seq"], source["sequence"])
        val eventId =
            acpFirstString(source["eventId"], source["hostEventId"])
                ?: (if (taskId != null && sequence != null) "legacy:$taskId:$sequence" else null)
        val common = LinkedHashMap<String, Any?>().apply {
            if (sessionId != null) put("sessionId", sessionId)
            if (taskId != null) put("turnId", taskId)
            if (requestId is String && requestId.trim().isNotEmpty()) {
                put("requestId", requestId.trim())
            }
            if (requestId is Number) put("requestId", requestId)
            if (eventId != null) put("eventId", eventId)
            put("legacyCompatibility", true)
        }
        val text = source["text"] ?: source["content"]
        val thinking = source["thinking"] ?: source["reasoning"]
        val toolName = acpFirstString(
            source["toolName"],
            source["tool_name"],
            source["displayName"],
        )
        val item = LinkedHashMap<String, Any?>().apply {
            if (entryId != null) put("id", entryId)
            if (entryId != null) put("itemId", entryId)
            if (toolName != null) put("toolName", toolName)
            if (source["toolType"] != null) put("toolType", source["toolType"])
            if (source["status"] != null) put("status", source["status"])
            if (source["summary"] != null) put("summary", source["summary"])
            if (source["error"] != null) put("error", source["error"])
            if (source["rawOutput"] != null) put("rawOutput", source["rawOutput"])
            if (source["result"] != null) put("rawOutput", source["result"])
        }

        fun eventWith(method: String, params: Map<String, Any?>): JsonMap {
            return LinkedHashMap<String, Any?>(common).apply {
                put("method", method)
                put(
                    "params",
                    LinkedHashMap<String, Any?>(common).apply {
                        putAll(params)
                        put(
                            "_compatibility",
                            jsonMapOf(
                                "source" to "legacy_agent_stream",
                                "kind" to kind,
                            ),
                        )
                    },
                )
            }
        }

        when (kind) {
            "thinking_started", "thinking_snapshot", "thinking" ->
                return eventWith(
                    "item/reasoning/delta",
                    LinkedHashMap<String, Any?>().apply {
                        if (entryId != null) put("itemId", entryId)
                        put("delta", thinking ?: text ?: "")
                    },
                )
            "text_snapshot", "assistant_message", "message", "text" ->
                return eventWith(
                    "item/agentMessage/delta",
                    LinkedHashMap<String, Any?>().apply {
                        if (entryId != null) put("itemId", entryId)
                        put("delta", text ?: "")
                    },
                )
            "tool_started" ->
                return eventWith(
                    "item/started",
                    jsonMapOf(
                        "item" to LinkedHashMap<String, Any?>(item).apply {
                            put("type", source["itemType"] ?: "dynamicToolCall")
                            put("status", source["status"] ?: "in_progress")
                        },
                    ),
                )
            "tool_progress" ->
                return eventWith(
                    "item/updated",
                    jsonMapOf(
                        "item" to LinkedHashMap<String, Any?>(item).apply {
                            put("type", "dynamicToolCall")
                        },
                    ),
                )
            "tool_completed" ->
                return eventWith(
                    "item/completed",
                    jsonMapOf(
                        "item" to LinkedHashMap<String, Any?>(item).apply {
                            put("type", source["itemType"] ?: "dynamicToolCall")
                            put("status", source["status"] ?: "completed")
                        },
                    ),
                )
            "permission_required" ->
                return eventWith(
                    "item/started",
                    jsonMapOf(
                        "item" to LinkedHashMap<String, Any?>(item).apply {
                            put("id", entryId ?: "legacy-permission")
                            put("type", "requestApproval")
                        },
                    ),
                )
            "clarify_required" ->
                return eventWith(
                    "item/started",
                    jsonMapOf(
                        "item" to LinkedHashMap<String, Any?>(item).apply {
                            put("id", entryId ?: "legacy-clarification")
                            put("type", "requestUserInput")
                            put("question", source["question"] ?: text ?: "")
                            put(
                                "missingFields",
                                source["missingFields"]
                                    ?: source["missing"]
                                    ?: emptyList<Any?>(),
                            )
                        },
                    ),
                )
            "retrying" ->
                return eventWith(
                    "item/agentMessage/delta",
                    LinkedHashMap<String, Any?>().apply {
                        if (entryId != null) put("itemId", entryId)
                        put("delta", "")
                        put(
                            "acpPresentation",
                            jsonMapOf(
                                "retry" to LinkedHashMap<String, Any?>().apply {
                                    if (source["retryCount"] != null) put("count", source["retryCount"])
                                    if (source["maxRetries"] != null) put("maxRetries", source["maxRetries"])
                                    put("message", source["message"] ?: "正在重试…")
                                    if (source["reason"] != null) put("reason", source["reason"])
                                },
                            ),
                        )
                    },
                )
            else ->
                // Unknown legacy kinds are retained for diagnostics instead of
                // being guessed into a visible card.
                return LinkedHashMap(source).apply { put("legacyCompatibility", true) }
        }
    }
}

internal val acpLegacyEventAdapter = AcpLegacyEventAdapter()

internal fun canUseHostTurnReservation(
    runtime: ChatConversationRuntimeState,
    event: Map<String, Any?>,
): Boolean {
    // Only the host's active prompt reservation can attribute a
    // session-scoped update with no wire turn id.
    return acpEventAllowsImplicitTurnAdmission(event) &&
        runtime.isAiResponding &&
        runtime.activeAcpTurnId == null &&
        runtime.currentDispatchTurnId?.trim()?.isNotEmpty() == true
}

internal fun resolveAgentEventMethod(
    event: Map<String, Any?>,
    message: Map<String, Any?>,
): String {
    for (envelope in agentEnvelopeMaps(message)) {
        val normalized = normalizeAgentEventMethod(acpString(envelope["method"]))
        if (normalized.isNotEmpty()) {
            return normalized
        }
    }
    if (event !== message) {
        for (envelope in agentEnvelopeMaps(event)) {
            val normalized = normalizeAgentEventMethod(
                acpString(envelope["method"]),
            )
            if (normalized.isNotEmpty()) {
                return normalized
            }
        }
    }
    for (envelope in agentEnvelopeMaps(message)) {
        val rawType = acpString(envelope["type"])
        if (!agentTypeLooksLikeEventMethod(rawType)) {
            continue
        }
        val normalized = normalizeAgentEventMethod(rawType)
        if (normalized.isNotEmpty()) {
            return normalized
        }
    }
    if (event !== message) {
        for (envelope in agentEnvelopeMaps(event)) {
            val rawType = acpString(envelope["type"])
            if (!agentTypeLooksLikeEventMethod(rawType)) {
                continue
            }
            val normalized = normalizeAgentEventMethod(rawType)
            if (normalized.isNotEmpty()) {
                return normalized
            }
        }
    }
    return ""
}

internal fun agentTypeLooksLikeEventMethod(rawType: String?): Boolean {
    val value = rawType?.trim() ?: ""
    if (value.isEmpty()) {
        return false
    }
    val normalized = normalizeAgentEventMethod(value)
    return normalized.contains("/") ||
        normalized == "error" ||
        looksLikeStandaloneAgentItemType(value)
}

private val dottedAgentEventMethods: Map<String, String> = mapOf(
    "thread.started" to "thread/started",
    "turn.started" to "turn/started",
    "turn.completed" to "turn/completed",
    "turn.failed" to "turn/failed",
    "item.started" to "item/started",
    "item.updated" to "item/updated",
    "item.completed" to "item/completed",
)

internal fun normalizeAgentEventMethod(rawMethod: String?): String {
    val value = rawMethod?.trim() ?: ""
    if (value.isEmpty()) {
        return ""
    }
    val dotted = dottedAgentEventMethods[value]
    if (dotted != null) {
        return dotted
    }
    if (looksLikeStandaloneAgentItemType(value)) {
        return "item/completed"
    }
    return value
        .replace("/agent_message/", "/agentMessage/")
        .replace("/command_execution/", "/commandExecution/")
        .replace("/file_change/", "/fileChange/")
        .replace("/mcp_tool_call/", "/mcpToolCall/")
}

internal fun eventParams(
    event: Map<String, Any?>,
    message: Map<String, Any?>,
    method: String,
): JsonMap {
    val messageParams = firstNestedParamsMap(message)
    if (messageParams != null && messageParams.isNotEmpty()) {
        return messageParams
    }
    val eventParams = firstNestedParamsMap(event)
    if (eventParams != null && eventParams.isNotEmpty()) {
        return eventParams
    }
    if (isItemLifecycleMethod(method)) {
        val item = firstNestedItemMap(message) ?: firstNestedItemMap(event)
        if (item != null) {
            return payloadWithoutEnvelope(message).apply { put("item", item) }
        }
        val directItem =
            standaloneAgentItemPayload(message)
                ?: standaloneAgentItemPayload(event)
        if (directItem != null) {
            return LinkedHashMap<String, Any?>().apply {
                putAll(topLevelAgentIds(message))
                putAll(topLevelAgentIds(event))
                put("item", directItem)
            }
        }
    }
    val messagePayload = payloadWithoutEnvelope(message)
    if (messagePayload.isNotEmpty()) {
        return messagePayload
    }
    return payloadWithoutEnvelope(event)
}

internal val agentEnvelopeKeys: List<String> = listOf(
    "message",
    "payload",
    "data",
    "event",
    "notification",
    "result",
)

internal fun agentEnvelopeMaps(
    root: Map<String, Any?>,
    depth: Int = 0,
): Sequence<Map<String, Any?>> = sequence {
    if (depth > 6) {
        return@sequence
    }
    yield(root)
    val params = copyStringMap(root["params"])
    if (params != null) {
        yieldAll(agentEnvelopeMaps(params, depth = depth + 1))
    }
    for (key in agentEnvelopeKeys) {
        val nested = copyStringMap(root[key]) ?: continue
        yieldAll(agentEnvelopeMaps(nested, depth = depth + 1))
    }
}

internal fun firstNestedParamsMap(
    root: Map<String, Any?>,
    depth: Int = 0,
): JsonMap? {
    if (depth > 6) {
        return null
    }
    val direct = copyStringMap(root["params"])
    if (direct != null) {
        val nested = firstNestedParamsMap(direct, depth = depth + 1)
        if (nested != null && nested.isNotEmpty()) {
            return topLevelAgentIds(root).apply { putAll(nested) }
        }
        if (direct.isNotEmpty()) {
            return topLevelAgentIds(root).apply { putAll(direct) }
        }
    }
    for (key in agentEnvelopeKeys) {
        val nested = copyStringMap(root[key]) ?: continue
        val nestedParams = firstNestedParamsMap(nested, depth = depth + 1)
        if (nestedParams != null && nestedParams.isNotEmpty()) {
            return topLevelAgentIds(root).apply { putAll(nestedParams) }
        }
    }
    return null
}

private val nestedItemKeys: List<String> = listOf("item", "rawItem", "responseItem")

internal fun firstNestedItemMap(
    root: Map<String, Any?>,
    depth: Int = 0,
): JsonMap? {
    if (depth > 6) {
        return null
    }
    for (key in nestedItemKeys) {
        val item = copyStringMap(root[key])
        if (item != null) {
            return item
        }
    }
    val params = copyStringMap(root["params"])
    if (params != null) {
        val item = firstNestedItemMap(params, depth = depth + 1)
        if (item != null) {
            return item
        }
    }
    for (key in agentEnvelopeKeys) {
        val nested = copyStringMap(root[key]) ?: continue
        val item = firstNestedItemMap(nested, depth = depth + 1)
        if (item != null) {
            return item
        }
    }
    return null
}

internal fun isItemLifecycleMethod(method: String): Boolean {
    return method == "item/started" ||
        method == "item/updated" ||
        method == "item/completed"
}

internal fun payloadWithoutEnvelope(value: Map<String, Any?>): JsonMap {
    val payload = LinkedHashMap<String, Any?>()
    for ((key, entryValue) in value) {
        if (key == "method" ||
            key == "type" ||
            key == "params" ||
            agentEnvelopeKeys.contains(key)
        ) {
            continue
        }
        payload[key] = entryValue
    }
    return payload
}

internal fun standaloneAgentItemPayload(value: Map<String, Any?>): JsonMap? {
    val type = acpString(value["type"])
    if (!looksLikeStandaloneAgentItemType(type)) {
        return null
    }
    return sameOrStringMap(value)
}

internal fun looksLikeStandaloneAgentItemType(itemType: String?): Boolean {
    val canonicalItemType = canonicalAgentItemType(itemType)
    return canonicalItemType == "agentMessage" ||
        canonicalItemType == "reasoning" ||
        isAgentToolItemType(canonicalItemType)
}

private val topLevelAgentIdKeys: List<String> = listOf(
    "threadId",
    "thread_id",
    "turnId",
    "turn_id",
    "itemId",
    "item_id",
)

internal fun topLevelAgentIds(value: Map<String, Any?>): JsonMap {
    val ids = LinkedHashMap<String, Any?>()
    val meta = copyStringMap(value["_meta"])
    if (meta != null) {
        for (key in listOf("threadId", "thread_id")) {
            if (meta.containsKey(key)) {
                ids[key] = meta[key]
            }
        }
    }
    for (key in topLevelAgentIdKeys) {
        if (value.containsKey(key)) {
            ids[key] = value[key]
        }
    }
    return ids
}
