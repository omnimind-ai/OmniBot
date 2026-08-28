package cn.com.omnimind.bot.voice

import cn.com.omnimind.baselib.llm.ChatCompletionAudioRequest
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.SceneVoiceConfig
import cn.com.omnimind.baselib.llm.SceneVoiceConfigStore
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SceneVoiceResolvedBinding(
    val providerProfileId: String,
    val apiBase: String,
    val apiKey: String,
    val modelId: String
)

data class SceneVoiceParsedAudioPayload(
    val base64Data: String,
    val format: String?
)

object SceneVoiceTtsProtocol {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun buildChatCompletionRequest(
        text: String,
        modelId: String,
        config: SceneVoiceConfig,
        format: String,
        stream: Boolean
    ): ChatCompletionRequest {
        return ChatCompletionRequest(
            messages = listOf(
                ChatCompletionMessage(
                    role = "assistant",
                    content = JsonPrimitive(composeStyledAssistantText(text, config))
                )
            ),
            model = modelId,
            stream = stream,
            audio = ChatCompletionAudioRequest(
                voice = config.voiceId,
                format = format
            )
        )
    }

    fun composeStyledAssistantText(text: String, config: SceneVoiceConfig): String {
        val body = text.trim()
        if (body.isEmpty()) {
            return ""
        }
        val stylePayload = composeStylePayload(config)
        return if (stylePayload.isEmpty()) body else "$stylePayload$body"
    }

    fun composeStylePayload(config: SceneVoiceConfig): String {
        val preset = config.stylePreset.trim()
        val custom = config.customStyle.trim()
        if (preset == SceneVoiceConfigStore.STYLE_SING) {
            return "<style>${SceneVoiceConfigStore.STYLE_SING}</style>"
        }
        val styleText = buildString {
            if (preset.isNotEmpty() && preset != SceneVoiceConfigStore.STYLE_DEFAULT) {
                append(preset)
            }
            if (custom.isNotEmpty()) {
                if (isNotEmpty()) {
                    append("，")
                }
                append(custom)
            }
        }
        return if (styleText.isEmpty()) "" else "<style>$styleText</style>"
    }

    fun buildCacheKey(
        messageId: String,
        text: String,
        providerProfileId: String,
        modelId: String,
        voiceId: String,
        stylePayload: String
    ): String {
        val raw = listOf(
            messageId.trim(),
            sha256(text.trim()),
            providerProfileId.trim(),
            modelId.trim(),
            voiceId.trim(),
            stylePayload.trim()
        ).joinToString(separator = "|")
        return sha256(raw)
    }

    fun extractStreamAudioBase64(raw: String): String? {
        val element = parseJson(raw) ?: return null
        return extractAudioPayloadCandidates(element).firstOrNull()?.base64Data
    }

    fun parseNonStreamingAudio(raw: String): SceneVoiceParsedAudioPayload? {
        val element = parseJson(raw) ?: return null
        return extractAudioPayloadCandidates(element).firstOrNull()
    }

    private fun extractAudioPayloadCandidates(element: JsonElement): List<SceneVoiceParsedAudioPayload> {
        return when (element) {
            is JsonArray -> element.flatMap(::extractAudioPayloadCandidates)
            is JsonObject -> buildList {
                addAll(extractFromRootObject(element))
                val choices = element["choices"] as? JsonArray
                choices?.forEach { choice ->
                    val choiceObj = choice as? JsonObject ?: return@forEach
                    listOf("delta", "message").forEach { field ->
                        val payload = choiceObj[field] as? JsonObject ?: return@forEach
                        addAll(extractFromRootObject(payload))
                    }
                }
            }
            else -> emptyList()
        }
    }

    private fun extractFromRootObject(obj: JsonObject): List<SceneVoiceParsedAudioPayload> {
        val audioObj = obj["audio"] as? JsonObject
        val directAudioObj = obj["output_audio"] as? JsonObject
        return buildList {
            parseAudioObject(audioObj)?.let(::add)
            parseAudioObject(directAudioObj)?.let(::add)
            extractString(obj["audio"])?.let { direct ->
                add(SceneVoiceParsedAudioPayload(base64Data = direct, format = obj["format"].contentOrNull()))
            }
            extractString(obj["data"])?.let { direct ->
                if (looksLikeBase64Audio(direct)) {
                    add(SceneVoiceParsedAudioPayload(base64Data = direct, format = obj["format"].contentOrNull()))
                }
            }
        }
    }

    private fun parseAudioObject(obj: JsonObject?): SceneVoiceParsedAudioPayload? {
        if (obj == null) {
            return null
        }
        val base64 = extractString(obj["data"]) ?: return null
        return SceneVoiceParsedAudioPayload(
            base64Data = base64,
            format = obj["format"].contentOrNull()
        )
    }

    private fun extractString(element: JsonElement?): String? {
        return when (element) {
            is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    private fun JsonElement?.contentOrNull(): String? {
        return (this as? JsonPrimitive)?.contentOrNull
    }

    private fun parseJson(raw: String): JsonElement? {
        val normalized = raw.trim()
        if (normalized.isEmpty() || normalized == "[DONE]") {
            return null
        }
        return runCatching {
            json.parseToJsonElement(normalized)
        }.getOrNull()
    }

    private fun looksLikeBase64Audio(value: String): Boolean {
        if (value.length < 16) {
            return false
        }
        return value.all { character ->
            character.isLetterOrDigit() || character == '+' || character == '/' || character == '='
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
