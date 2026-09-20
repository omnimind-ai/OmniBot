package cn.com.omnimind.bot.agent.runtime

/** ACP v2 config selections require a value discriminator absent in v1. */
internal fun remoteAcpRequestParams(method: String, params: Any?, protocolVersion: Int): Any? {
    if (protocolVersion != 2 || method != "session/set_config_option") return params
    val values = params as? Map<*, *> ?: return params
    if (values.containsKey("type")) return params
    val type = when (values["value"]) {
        is String -> "id"
        is Boolean -> "boolean"
        else -> return params
    }
    return values.entries.associate { it.key.toString() to it.value } + ("type" to type)
}

/** Version conversion only. Session and turn ownership stay in AgentRuntimeManager. */
internal fun normalizeRemoteAcpNotification(
    message: Map<String, Any?>,
    protocolVersion: Int,
): Map<String, Any?> {
    if (protocolVersion != 2 || message["method"] != "session/update") return message
    val params = message["params"] as? Map<*, *> ?: return message
    val update = params["update"] as? Map<*, *> ?: return message
    // Codex-specific metadata is interpreted at its transport adapter boundary.
    // This is the backend's actual identity, never a generated turn or text match.
    val meta = update["_meta"] as? Map<*, *>
    val codex = meta?.get("codex") as? Map<*, *>
    val turnId = (codex?.get("turnId") as? String)?.takeIf { it.isNotBlank() }
    val normalized = params.entries.associate { it.key.toString() to it.value }.toMutableMap()
    turnId?.let { normalized["turnId"] = it }
    return message + mapOf("protocolVersion" to 2, "params" to normalized)
}

internal fun isAcpV2State(message: Map<String, Any?>, state: String): Boolean {
    if ((message["protocolVersion"] as? Number)?.toInt() != 2) return false
    val params = message["params"] as? Map<*, *> ?: return false
    val update = params["update"] as? Map<*, *> ?: return false
    return message["method"] == "session/update" &&
        update["sessionUpdate"] == "state_update" && update["state"] == state
}
