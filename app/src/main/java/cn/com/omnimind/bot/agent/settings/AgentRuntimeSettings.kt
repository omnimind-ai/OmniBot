package cn.com.omnimind.bot.agent

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV

data class AgentRuntimeSettings(
    val maxModelRounds: Int? = null,
    val maxCompletionTokens: Int? = null,
    val streamIdleTimeoutMs: Long? = null,
    val maxTransientStreamRetries: Int? = null,
    val transientStreamRetryDelayMs: Long? = null,
    val maxLengthContinuationRounds: Int? = null,
    val maxMissingToolCallRecoveryRounds: Int? = null,
    val maxContextOverflowRecoveryRounds: Int? = null,
    val maxToolResultChars: Int? = null,
    val maxVlmSteps: Int? = null,
    val maxRejectedActionRetries: Int? = null,
    val terminalTimeoutSeconds: Int? = null,
    val browserNavigationTimeoutMs: Long? = null,
    val browserWaitTimeoutMs: Long? = null,
    val browserMaxTabs: Int? = null,
    val browserMaxScrollCount: Int? = null,
    val browserMaxDepth: Int? = null,
    val browserActionTimeoutMs: Long? = null,
    val contextQueryLimit: Int? = null,
    val fileReadMaxChars: Int? = null,
    val fileListLimit: Int? = null,
    val fileListMaxDepth: Int? = null,
    val fileSearchLimit: Int? = null,
    val terminalSessionReadMaxChars: Int? = null,
    val skillsListLimit: Int? = null,
    val skillReadMaxChars: Int? = null,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "maxModelRounds" to maxModelRounds,
        "maxCompletionTokens" to maxCompletionTokens,
        "streamIdleTimeoutMs" to streamIdleTimeoutMs,
        "maxTransientStreamRetries" to maxTransientStreamRetries,
        "transientStreamRetryDelayMs" to transientStreamRetryDelayMs,
        "maxLengthContinuationRounds" to maxLengthContinuationRounds,
        "maxMissingToolCallRecoveryRounds" to maxMissingToolCallRecoveryRounds,
        "maxContextOverflowRecoveryRounds" to maxContextOverflowRecoveryRounds,
        "maxToolResultChars" to maxToolResultChars,
        "maxVlmSteps" to maxVlmSteps,
        "maxRejectedActionRetries" to maxRejectedActionRetries,
        "terminalTimeoutSeconds" to terminalTimeoutSeconds,
        "browserNavigationTimeoutMs" to browserNavigationTimeoutMs,
        "browserWaitTimeoutMs" to browserWaitTimeoutMs,
        "browserMaxTabs" to browserMaxTabs,
        "browserMaxScrollCount" to browserMaxScrollCount,
        "browserMaxDepth" to browserMaxDepth,
        "browserActionTimeoutMs" to browserActionTimeoutMs,
        "contextQueryLimit" to contextQueryLimit,
        "fileReadMaxChars" to fileReadMaxChars,
        "fileListLimit" to fileListLimit,
        "fileListMaxDepth" to fileListMaxDepth,
        "fileSearchLimit" to fileSearchLimit,
        "terminalSessionReadMaxChars" to terminalSessionReadMaxChars,
        "skillsListLimit" to skillsListLimit,
        "skillReadMaxChars" to skillReadMaxChars,
    )

    companion object {
        fun fromMap(value: Map<*, *>?): AgentRuntimeSettings {
            val source = value ?: emptyMap<Any?, Any?>()
            return AgentRuntimeSettings(
                maxModelRounds = source.int("maxModelRounds"),
                maxCompletionTokens = source.int("maxCompletionTokens"),
                streamIdleTimeoutMs = source.long("streamIdleTimeoutMs"),
                maxTransientStreamRetries = source.int("maxTransientStreamRetries"),
                transientStreamRetryDelayMs = source.long("transientStreamRetryDelayMs"),
                maxLengthContinuationRounds = source.int("maxLengthContinuationRounds"),
                maxMissingToolCallRecoveryRounds = source.int("maxMissingToolCallRecoveryRounds"),
                maxContextOverflowRecoveryRounds = source.int("maxContextOverflowRecoveryRounds"),
                maxToolResultChars = source.int("maxToolResultChars"),
                maxVlmSteps = source.int("maxVlmSteps"),
                maxRejectedActionRetries = source.int("maxRejectedActionRetries"),
                terminalTimeoutSeconds = source.int("terminalTimeoutSeconds"),
                browserNavigationTimeoutMs = source.long("browserNavigationTimeoutMs"),
                browserWaitTimeoutMs = source.long("browserWaitTimeoutMs"),
                browserMaxTabs = source.int("browserMaxTabs"),
                browserMaxScrollCount = source.int("browserMaxScrollCount"),
                browserMaxDepth = source.int("browserMaxDepth"),
                browserActionTimeoutMs = source.long("browserActionTimeoutMs"),
                contextQueryLimit = source.int("contextQueryLimit"),
                fileReadMaxChars = source.int("fileReadMaxChars"),
                fileListLimit = source.int("fileListLimit"),
                fileListMaxDepth = source.int("fileListMaxDepth"),
                fileSearchLimit = source.int("fileSearchLimit"),
                terminalSessionReadMaxChars = source.int("terminalSessionReadMaxChars"),
                skillsListLimit = source.int("skillsListLimit"),
                skillReadMaxChars = source.int("skillReadMaxChars"),
            ).validated()
        }

        fun fromJson(json: String): AgentRuntimeSettings {
            val element = JsonParser.parseString(json)
            require(element.isJsonObject) { "runtimeSettings must be a JSON object" }
            val objectValue = element.asJsonObject
            return fromMap(objectValue.entrySet().associate { it.key to it.value.toRuntimeValue() })
        }
    }

    fun validated(): AgentRuntimeSettings {
        fun positiveOrNull(value: Int?): Int? = value?.takeIf { it > 0 }
        fun positiveLongOrNull(value: Long?): Long? = value?.takeIf { it > 0L }
        return copy(
            maxModelRounds = positiveOrNull(maxModelRounds),
            maxCompletionTokens = positiveOrNull(maxCompletionTokens),
            streamIdleTimeoutMs = positiveLongOrNull(streamIdleTimeoutMs),
            maxTransientStreamRetries = positiveOrNull(maxTransientStreamRetries),
            transientStreamRetryDelayMs = positiveLongOrNull(transientStreamRetryDelayMs),
            maxLengthContinuationRounds = positiveOrNull(maxLengthContinuationRounds),
            maxMissingToolCallRecoveryRounds = positiveOrNull(maxMissingToolCallRecoveryRounds),
            maxContextOverflowRecoveryRounds = positiveOrNull(maxContextOverflowRecoveryRounds),
            maxToolResultChars = positiveOrNull(maxToolResultChars),
            maxVlmSteps = positiveOrNull(maxVlmSteps),
            maxRejectedActionRetries = positiveOrNull(maxRejectedActionRetries),
            terminalTimeoutSeconds = positiveOrNull(terminalTimeoutSeconds),
            browserNavigationTimeoutMs = positiveLongOrNull(browserNavigationTimeoutMs),
            browserWaitTimeoutMs = positiveLongOrNull(browserWaitTimeoutMs),
            browserMaxTabs = positiveOrNull(browserMaxTabs),
            browserMaxScrollCount = positiveOrNull(browserMaxScrollCount),
            browserMaxDepth = positiveOrNull(browserMaxDepth),
            browserActionTimeoutMs = positiveLongOrNull(browserActionTimeoutMs),
            contextQueryLimit = positiveOrNull(contextQueryLimit),
            fileReadMaxChars = positiveOrNull(fileReadMaxChars),
            fileListLimit = positiveOrNull(fileListLimit),
            fileListMaxDepth = positiveOrNull(fileListMaxDepth),
            fileSearchLimit = positiveOrNull(fileSearchLimit),
            terminalSessionReadMaxChars = positiveOrNull(terminalSessionReadMaxChars),
            skillsListLimit = positiveOrNull(skillsListLimit),
            skillReadMaxChars = positiveOrNull(skillReadMaxChars),
        )
    }
}
object AgentRuntimeSettingsStore {
    private const val KEY_PREFIX = "agent_runtime_settings_v1:"
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()

    @Synchronized
    fun read(context: Context, agentId: String): AgentRuntimeSettings {
        val raw = runCatching { MMKV.defaultMMKV().decodeString(key(agentId)) }
            .getOrNull()
            ?: return AgentRuntimeSettings()
        return runCatching { AgentRuntimeSettings.fromJson(raw) }
            .getOrElse { AgentRuntimeSettings() }
    }

    @Synchronized
    fun write(context: Context, agentId: String, settings: AgentRuntimeSettings): AgentRuntimeSettings {
        val normalized = settings.validated()
        check(MMKV.defaultMMKV().encode(key(agentId), gson.toJson(normalized.toMap()))) {
            "failed to persist Agent runtime settings"
        }
        return normalized
    }

    fun toJson(settings: AgentRuntimeSettings): String = gson.toJson(settings.toMap())

    private fun key(agentId: String): String = KEY_PREFIX + agentId.trim()
}

private fun Map<*, *>.int(key: String): Int? = get(key).toNumber()?.toInt()

private fun Map<*, *>.long(key: String): Long? = get(key).toNumber()?.toLong()

private fun Any?.toNumber(): Number? = when (this) {
    is Number -> this
    is String -> this.trim().toLongOrNull() ?: this.trim().toDoubleOrNull()
    else -> null
}

private fun com.google.gson.JsonElement.toRuntimeValue(): Any? = when {
    this is JsonObject -> asJsonObject.entrySet().associate { it.key to it.value.toRuntimeValue() }
    this is com.google.gson.JsonArray -> asJsonArray.map { it.toRuntimeValue() }
    this is com.google.gson.JsonNull -> null
    asJsonPrimitive.isBoolean -> asBoolean
    asJsonPrimitive.isNumber -> asNumber
    else -> asString
}
