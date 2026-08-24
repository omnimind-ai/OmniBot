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
                    PlatformModel("embedding-model"),
                    PlatformModel("not-declared"),
                ),
                defaults = PlatformModelDefaults(
                    text = "text-model",
                    vision = "vision-model",
                    image = "image-model",
                    embedding = "embedding-model",
                ),
                capabilities = PlatformModelCapabilities(
                    text = listOf("text-model"),
                    vision = listOf("vision-model"),
                    image = listOf("image-model"),
                    embedding = listOf("embedding-model"),
                ),
                hasOfficialCatalog = true,
            )
        )

        assertEquals("text-model", selection.defaultTextModel?.id)
        assertEquals("vision-model", selection.defaultVisionModel?.id)
        assertEquals("image-model", selection.defaultImageModel?.id)
        assertEquals("embedding-model", selection.defaultEmbeddingModel?.id)
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
        assertTrue(selection.embeddingModels.isEmpty())
        assertNull(selection.defaultEmbeddingModel)
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
