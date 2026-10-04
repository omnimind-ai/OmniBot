package cn.com.omnimind.bot.agent.projection

/**
 * Shared ACP extension projection.
 *
 * Standard ACP events stay separate from presentation metadata. Agents may
 * put provider-specific information in `_meta`; this registry translates
 * common shapes into the presentation vocabulary the reducer consumes while
 * retaining every namespace for diagnostics and future adapters.
 */
typealias AcpExtensionProjector = (Map<String, Any?>) -> Map<String, Any?>?

class AcpExtensionProjection(
    val presentation: JsonMap,
    /**
     * Original namespace payloads, including scalar/list extensions. ACP
     * metadata is forward-compatible, so unknown extensions are kept.
     */
    val extensions: JsonMap,
) {
    val isEmpty: Boolean get() = presentation.isEmpty() && extensions.isEmpty()
}

class AcpExtensionRegistry(
    projectors: Map<String, AcpExtensionProjector> = emptyMap(),
) {
    private val projectors = LinkedHashMap(projectors)

    fun register(namespace: String, projector: AcpExtensionProjector) {
        val key = namespace.trim()
        require(key.isNotEmpty()) { "namespace must not be empty" }
        projectors[key] = projector
    }

    fun unregister(namespace: String) {
        projectors.remove(namespace.trim())
    }

    fun project(update: Map<String, Any?>): AcpExtensionProjection {
        val meta = sameOrStringMap(update["_meta"]) ?: sameOrStringMap(update["meta"])
        if (meta == null || meta.isEmpty()) {
            return AcpExtensionProjection(linkedMapOf(), linkedMapOf())
        }
        val presentation: JsonMap = linkedMapOf()
        val extensions: JsonMap = linkedMapOf()
        for ((rawNamespace, value) in meta) {
            val namespace = rawNamespace.trim()
            val payload = sameOrStringMap(value)
            if (namespace.isEmpty()) continue
            extensions[namespace] = value
            // Typed projection is opt-in for object payloads.
            if (payload == null) continue

            projectors[namespace]?.let { mergeIfAbsent(presentation, it(payload)) }

            // Shared namespace kept for the Xiaowan ACP bridge.
            if (isSharedPresentationNamespace(namespace)) {
                mergeIfAbsent(presentation, payload)
            }
            // Generic clients use a `presentation` object in their namespace.
            mergeIfAbsent(presentation, sameOrStringMap(payload["presentation"]))
            projectCommonAliases(payload, presentation)
        }
        return AcpExtensionProjection(presentation, extensions)
    }

    companion object {
        /** Process-wide registry used by the ACP reducer. */
        val shared = AcpExtensionRegistry()

        private val nonAlphanumeric = Regex("[^a-z0-9]")

        private val aliases = mapOf(
            "usage" to "usage",
            "reasoning" to "reasoning",
            "thinking" to "reasoning",
            "deepthinking" to "reasoning",
            "deepthought" to "reasoning",
            "thought" to "reasoning",
            "tool" to "tool",
            "toolcall" to "tool",
            "artifact" to "artifacts",
            "artifacts" to "artifacts",
            "compaction" to "compaction",
            "contextcompaction" to "compaction",
            "retry" to "retry",
            "media" to "media",
            "clarify" to "clarification",
            "clarification" to "clarification",
            "recovery" to "recovery",
            "permission" to "permission",
            "permissions" to "permission",
            "plan" to "plan",
            "task" to "task",
            "subtask" to "task",
            "memory" to "memory",
        )

        private val reasoningFields = mapOf(
            "taskdescription" to "taskDescription",
            "tasktitle" to "taskTitle",
            "subtasks" to "subTasks",
            "preparation" to "preparation",
            "memoryactions" to "memoryActions",
        )

        private fun isSharedPresentationNamespace(namespace: String): Boolean {
            val normalized = namespace.lowercase().replace(nonAlphanumeric, "")
            return normalized == "cncomomnimindagent" ||
                normalized == "omnimindagent" ||
                normalized == "acppresentation"
        }

        private fun projectCommonAliases(payload: Map<String, Any?>, presentation: JsonMap) {
            for ((key, value) in payload) {
                val normalized = key.lowercase().replace(nonAlphanumeric, "")
                val reasoningField = reasoningFields[normalized]
                if (reasoningField != null) {
                    val reasoning = sameOrStringMap(presentation["reasoning"]) ?: linkedMapOf()
                    if (!reasoning.containsKey(reasoningField)) reasoning[reasoningField] = value
                    presentation["reasoning"] = reasoning
                    continue
                }
                val canonical = aliases[normalized] ?: continue
                if (canonical == "reasoning") {
                    val existingValue = presentation["reasoning"]
                    val existing = sameOrStringMap(existingValue) ?: linkedMapOf()
                    if (existingValue != null && existing.isEmpty()) {
                        existing["text"] = existingValue
                    }
                    val incoming = sameOrStringMap(value)
                    if (incoming != null) {
                        mergeIfAbsent(existing, incoming)
                    } else if (!existing.containsKey("text")) {
                        existing["text"] = value
                    }
                    presentation["reasoning"] = existing
                    continue
                }
                if (presentation.containsKey(canonical)) continue
                presentation[canonical] = value
            }
        }

        private fun mergeIfAbsent(target: JsonMap, source: Map<String, Any?>?) {
            if (source == null) return
            for ((key, value) in source) {
                if (!target.containsKey(key)) target[key] = value
            }
        }
    }
}
