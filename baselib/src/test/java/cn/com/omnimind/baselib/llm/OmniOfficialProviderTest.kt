package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.account.PlatformModel
import cn.com.omnimind.baselib.account.PlatformModelCapabilities
import cn.com.omnimind.baselib.account.PlatformModelCatalog
import cn.com.omnimind.baselib.account.PlatformModelDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniOfficialProviderTest {
    @Test
    fun choosesServerDeclaredDefaultsForAllCapabilities() {
        val selection = OmniOfficialProvider.selectModels(
            PlatformModelCatalog(
                models = listOf(
                    PlatformModel("text-model"),
                    PlatformModel("vision-model"),
                    PlatformModel("image-model"),
                    PlatformModel("tts-model"),
                    PlatformModel("stt-model"),
                    PlatformModel("not-declared"),
                ),
                defaults = PlatformModelDefaults(
                    text = "text-model",
                    vision = "vision-model",
                    image = "image-model",
                    tts = "tts-model",
                    stt = "stt-model",
                    ttsVoice = "official-neutral",
                ),
                capabilities = PlatformModelCapabilities(
                    text = listOf("text-model"),
                    vision = listOf("vision-model"),
                    image = listOf("image-model"),
                    tts = listOf("tts-model"),
                    stt = listOf("stt-model"),
                    ttsVoices = listOf("official-neutral", "official-warm"),
                ),
                hasOfficialCatalog = true,
            )
        )

        assertEquals("text-model", selection.defaultTextModel?.id)
        assertEquals("vision-model", selection.defaultVisionModel?.id)
        assertEquals("image-model", selection.defaultImageModel?.id)
        assertEquals("tts-model", selection.defaultTtsModel?.id)
        assertEquals("stt-model", selection.defaultSttModel?.id)
        assertEquals("official-neutral", selection.defaultTtsVoiceAlias)
        assertEquals(
            listOf("official-neutral", "official-warm"),
            selection.ttsVoiceAliases,
        )
        assertTrue(selection.textModels.none { it.id == "not-declared" })
    }

    @Test
    fun preservesRememberedTextOverrideOnlyWhenServerDeclared() {
        val catalog = PlatformModelCatalog(
            models = listOf(PlatformModel("text-a"), PlatformModel("text-b")),
            defaults = PlatformModelDefaults(text = "text-a"),
            capabilities = PlatformModelCapabilities(text = listOf("text-a", "text-b")),
            hasOfficialCatalog = true,
        )

        val selected = OmniOfficialProvider.selectModels(
            catalog = catalog,
            rememberedTextModelId = "text-b",
        )

        assertEquals("text-b", selected.defaultTextModel?.id)
    }

    @Test
    fun oldGatewayFallsBackOnlyToLocallyVerifiedTextModel() {
        val selection = OmniOfficialProvider.selectModels(
            PlatformModelCatalog(
                models = listOf(
                    PlatformModel("other-openai", supportedEndpointTypes = listOf("openai")),
                    PlatformModel("Qwen3.5-Plus"),
                    PlatformModel("image-model", supportedEndpointTypes = listOf("image-generation")),
                )
            )
        )

        assertEquals(listOf("Qwen3.5-Plus"), selection.textModels.map(PlatformModel::id))
        assertEquals("Qwen3.5-Plus", selection.defaultTextModel?.id)
        assertTrue(selection.imageModels.isEmpty())
        assertNull(selection.defaultImageModel)
        assertEquals(SceneVoiceConfigStore.VOICE_DEFAULT_ZH, selection.defaultTtsVoiceAlias)
        assertEquals(OmniOfficialProvider.LEGACY_TTS_VOICE_ALIASES, selection.ttsVoiceAliases)
    }

    @Test
    fun explicitEmptyVoiceAliasCatalogDoesNotFallBackToLegacyAliases() {
        val selection = OmniOfficialProvider.selectModels(
            PlatformModelCatalog(
                models = listOf(PlatformModel("text-model"), PlatformModel("tts-model")),
                defaults = PlatformModelDefaults(
                    text = "text-model",
                    tts = "tts-model",
                    ttsVoice = "default_zh",
                ),
                capabilities = PlatformModelCapabilities(
                    text = listOf("text-model"),
                    tts = listOf("tts-model"),
                    ttsVoices = emptyList(),
                ),
                hasOfficialCatalog = true,
            )
        )

        assertTrue(selection.ttsVoiceAliases.isEmpty())
        assertNull(selection.defaultTtsVoiceAlias)
    }

    @Test
    fun defaultOnlyVoiceAliasIsAcceptedAsAStableSingleton() {
        val selection = OmniOfficialProvider.selectModels(
            PlatformModelCatalog(
                models = listOf(PlatformModel("text-model"), PlatformModel("tts-model")),
                defaults = PlatformModelDefaults(
                    text = "text-model",
                    tts = "tts-model",
                    ttsVoice = "official-neutral",
                ),
                capabilities = PlatformModelCapabilities(
                    text = listOf("text-model"),
                    tts = listOf("tts-model"),
                ),
                hasOfficialCatalog = true,
            )
        )

        assertEquals(listOf("official-neutral"), selection.ttsVoiceAliases)
        assertEquals("official-neutral", selection.defaultTtsVoiceAlias)
    }

    @Test
    fun voiceAliasListWithoutDefaultUsesFirstDeclaredAlias() {
        val selection = OmniOfficialProvider.selectModels(
            PlatformModelCatalog(
                models = listOf(PlatformModel("text-model"), PlatformModel("tts-model")),
                defaults = PlatformModelDefaults(text = "text-model", tts = "tts-model"),
                capabilities = PlatformModelCapabilities(
                    text = listOf("text-model"),
                    tts = listOf("tts-model"),
                    ttsVoices = listOf("official-warm", "official-neutral"),
                ),
                hasOfficialCatalog = true,
            )
        )

        assertEquals("official-warm", selection.defaultTtsVoiceAlias)
    }

    @Test
    fun explicitCatalogFailsClosedWhenDefaultIsOutsideCapabilityList() {
        val selection = OmniOfficialProvider.selectModels(
            PlatformModelCatalog(
                models = listOf(PlatformModel("text-a"), PlatformModel("hidden")),
                defaults = PlatformModelDefaults(text = "hidden"),
                capabilities = PlatformModelCapabilities(text = listOf("text-a")),
                hasOfficialCatalog = true,
            )
        )

        assertNull(selection.defaultTextModel)
    }

    @Test
    fun readyProvisioningStatusDoesNotBlockPlatformRouting() {
        val reason = PlatformAiProvisioningStatus(
            ready = true,
            statusText = "官方文本模型已就绪",
            defaultModelId = "Qwen3.5-Plus",
        ).routingUnavailableReasonOrNull()

        assertNull(reason)
    }
}
