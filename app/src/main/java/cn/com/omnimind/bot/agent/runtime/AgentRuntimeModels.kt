package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.DeepSeekProvider
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.ProviderCustomHeaderUtils
import java.io.File

data class AgentWorkspaceDescriptor(
    val id: String,
    val rootPath: String,
    val androidRootPath: String,
    val uriRoot: String,
    val currentCwd: String,
    val androidCurrentCwd: String,
    val shellRootPath: String,
    val retentionPolicy: String
)

data class AgentModelOverride(
    val providerProfileId: String,
    val providerProfileName: String? = null,
    val modelId: String,
    val apiBase: String,
    val apiKey: String,
    val customHeaders: Map<String, String> = emptyMap(),
    val protocolType: String = "openai_compatible",
    val wireApi: String = "chat_completions",
    val contextLimit: Int? = null
) {
    companion object {
        /**
         * Creates the only supported Agent-side projection of a shared
         * Provider profile. Callers must not copy Provider fields manually:
         * the resulting override is consumed by HttpController for chat,
         * Responses, Anthropic, vision, and compaction requests alike.
         */
        fun fromProviderProfile(
            profile: ModelProviderProfile,
            modelId: String,
            contextLimit: Int? = null,
        ): AgentModelOverride? {
            val normalizedModel = modelId.trim().takeIf { it.isNotEmpty() } ?: return null
            if (!profile.isConfigured()) return null
            return AgentModelOverride(
                providerProfileId = profile.id.trim(),
                providerProfileName = profile.name.trim().takeIf { it.isNotEmpty() },
                modelId = normalizedModel,
                apiBase = profile.baseUrl,
                apiKey = profile.apiKey,
                customHeaders = profile.customHeaders,
                protocolType = profile.protocolType,
                wireApi = profile.wireApi,
                contextLimit = contextLimit?.takeIf { it > 0 },
            ).normalizedOrNull()
        }
    }
}

/**
 * Normalizes an Agent override at the boundary before it is handed to the
 * HTTP route resolver. This keeps legacy callers safe while preserving the
 * Provider store's direct-request URL marker and endpoint semantics.
 */
fun AgentModelOverride.normalizedOrNull(): AgentModelOverride? {
    val normalizedBase = ModelProviderConfigStore.normalizeBaseUrl(apiBase) ?: return null
    val normalizedProviderId = providerProfileId.trim()
    if (normalizedProviderId.isEmpty()) return null
    val normalizedModel = modelId.trim()
    if (normalizedModel.isEmpty()) return null
    return copy(
        providerProfileId = normalizedProviderId,
        providerProfileName = providerProfileName?.trim()?.takeIf { it.isNotEmpty() },
        modelId = normalizedModel,
        apiBase = normalizedBase,
        apiKey = apiKey.trim(),
        customHeaders = ProviderCustomHeaderUtils
            .sanitizeCustomHeaders(customHeaders)
            .mapValues { (_, value) -> value.trim() },
        protocolType = DeepSeekProvider.normalizeProtocolType(protocolType),
        wireApi = OpenAiWireApi.normalize(wireApi),
        contextLimit = contextLimit?.takeIf { it > 0 },
    )
}

fun AgentModelOverride.normalized(): AgentModelOverride = normalizedOrNull()
    ?: throw IllegalArgumentException("Agent Provider configuration is invalid.")

data class ArtifactAction(
    val type: String,
    val label: String,
    val target: String? = null,
    val payload: Map<String, Any?> = emptyMap()
) {
    fun toPayload(): Map<String, Any?> = mapOf(
        "type" to type,
        "label" to label,
        "target" to target,
        "payload" to payload
    )
}

data class ArtifactRef(
    val id: String,
    val uri: String,
    val title: String,
    val mimeType: String,
    val size: Long,
    val sourceTool: String,
    val workspacePath: String,
    val androidPath: String,
    val previewKind: String,
    val actions: List<ArtifactAction> = emptyList()
) {
    fun fileName(): String = File(androidPath).name

    val embedKind: String
        get() = when (previewKind) {
            "image" -> "image"
            "audio" -> "audio"
            "video" -> "video"
            "pdf" -> "pdf"
            "html" -> "html"
            "office_word", "office_sheet", "office_slide" -> "office"
            else -> "link"
        }

    val inlineRenderable: Boolean
        get() = embedKind != "link"

    val renderMarkdown: String
        get() {
            val safeTitle = title
                .replace("\\", "\\\\")
                .replace("[", "\\[")
                .replace("]", "\\]")
            return if (embedKind == "image") {
                "![${safeTitle}]($uri)"
            } else {
                "[${safeTitle}]($uri)"
            }
        }

    fun toPayload(): Map<String, Any?> = mapOf(
        "id" to id,
        "uri" to uri,
        "title" to title,
        "fileName" to fileName(),
        "mimeType" to mimeType,
        "size" to size,
        "sourceTool" to sourceTool,
        "workspacePath" to workspacePath,
        "androidPath" to androidPath,
        "previewKind" to previewKind,
        "embedKind" to embedKind,
        "inlineRenderable" to inlineRenderable,
        "renderMarkdown" to renderMarkdown,
        "actions" to actions.map { it.toPayload() }
    )
}

data class SkillIndexEntry(
    val id: String,
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val rootPath: String,
    val shellRootPath: String,
    val skillFilePath: String,
    val shellSkillFilePath: String,
    val hasScripts: Boolean,
    val hasReferences: Boolean,
    val hasAssets: Boolean,
    val hasEvals: Boolean,
    val enabled: Boolean = true,
    val source: String = "user",
    val installed: Boolean = true
)

data class ResolvedSkillContext(
    val skillId: String,
    val frontmatter: Map<String, String>,
    val metadata: Map<String, String> = emptyMap(),
    val bodyMarkdown: String,
    val loadedReferences: List<String> = emptyList(),
    val scriptsDir: String? = null,
    val assetsDir: String? = null,
    val triggerReason: String
) {
    fun promptSummary(maxChars: Int = 1800): String {
        val skillName = frontmatter["name"]?.ifBlank { skillId } ?: skillId
        val lines = bodyMarkdown.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .dropWhile { it.startsWith("---") }
            .dropWhile { it.startsWith("#") }
            .take(16)
        val base = buildString {
            appendLine("Skill: $skillName")
            appendLine("Trigger: $triggerReason")
            scriptsDir?.takeIf { it.isNotBlank() }?.let { appendLine("Scripts: $it") }
            assetsDir?.takeIf { it.isNotBlank() }?.let { appendLine("Assets: $it") }
            if (loadedReferences.isNotEmpty()) {
                appendLine("References: ${loadedReferences.joinToString(", ")}")
            }
            appendLine(lines.joinToString("\n"))
        }.trim()
        return if (base.length <= maxChars) base else base.take(maxChars) + "\n..."
    }

    fun stepGuidance(maxChars: Int = 900): String {
        val lines = bodyMarkdown.lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("---") &&
                    !line.startsWith("#") &&
                    !line.startsWith("```")
            }
            .take(10)
        val base = lines.joinToString("\n")
        return if (base.length <= maxChars) base else base.take(maxChars) + "\n..."
    }
}

data class SkillMatchResult(
    val entry: SkillIndexEntry,
    val confidence: Double,
    val triggerReason: String
)
