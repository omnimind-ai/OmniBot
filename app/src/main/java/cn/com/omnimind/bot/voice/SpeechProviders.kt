package cn.com.omnimind.bot.voice

import cn.com.omnimind.baselib.llm.DeepSeekProvider
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import kotlinx.coroutines.flow.Flow

internal interface SpeechRecognitionProvider {
    /** Collects 16 kHz mono PCM16-LE. Normal completion of [frames] authorizes transcription. */
    suspend fun transcribe(frames: Flow<ByteArray>): String
}

internal interface SpeechSynthesisProvider {
    /** Emits sample-aligned 24 kHz mono PCM16-LE until synthesis completes or throws. */
    fun synthesize(text: String, voiceId: String): Flow<ByteArray>
}

internal class SpeechProviderException(val code: String, message: String) : Exception(message)

internal data class SpeechVoice(val id: String, val label: String, val englishLabel: String)

internal data class SpeechProviderRegistration(
    val id: String,
    val label: String,
    val isReady: () -> Boolean,
    val recognition: SpeechRecognitionProvider? = null,
    val synthesis: SpeechSynthesisProvider? = null,
    val voices: List<SpeechVoice> = emptyList(),
)

internal object SpeechProviders {
    private fun deepSeekKey(): String? {
        val profile = ModelProviderConfigStore.getProfile(DeepSeekProvider.OFFICIAL_PROFILE_ID)
        return profile?.takeIf { DeepSeekProvider.isOfficialBaseUrl(it.baseUrl) }
            ?.apiKey?.trim()?.takeIf(String::isNotEmpty)
    }

    private val deepSeek = DeepSeekSpeechProvider(::deepSeekKey)
    private val providers = listOf(
        SpeechProviderRegistration(
            id = DeepSeekProvider.PROTOCOL_TYPE,
            label = DeepSeekProvider.OFFICIAL_PROFILE_NAME,
            isReady = { deepSeekKey() != null },
            recognition = deepSeek,
            synthesis = deepSeek,
            voices = listOf(
                SpeechVoice("stella", "海星", "Stella"),
                SpeechVoice("tide", "暗潮", "Tide"),
            ),
        ),
    )

    fun find(id: String): SpeechProviderRegistration? = providers.firstOrNull { it.id == id }

    fun describe(): List<Map<String, Any>> = providers.map { provider ->
        mapOf(
            "id" to provider.id,
            "label" to provider.label,
            "ready" to provider.isReady(),
            "recognition" to (provider.recognition != null),
            "synthesis" to (provider.synthesis != null),
            "voices" to provider.voices.map {
                mapOf("id" to it.id, "label" to it.label, "englishLabel" to it.englishLabel)
            },
        )
    }
}
