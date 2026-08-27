package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaowanProviderCacheTest {
    private val binding = SceneModelBindingEntry(
        sceneId = "scene.dispatch.model",
        providerProfileId = "provider-1",
        modelId = "model-1",
    )

    @Test
    fun `cached models are invalidated when provider credentials change`() {
        val originalProfile = providerProfile(revision = 1L, apiKey = "old-key")
        val cached = requireNotNull(buildXiaowanModelsFromBinding(binding)).copy(
            providerProfile = originalProfile,
        )

        assertTrue(canReuseXiaowanModels(binding, originalProfile, cached))
        assertFalse(
            canReuseXiaowanModels(
                binding = binding,
                profile = providerProfile(revision = 2L, apiKey = "new-key"),
                cached = cached,
            )
        )
    }

    @Test
    fun `keyless local provider remains a usable ACP binding`() {
        val profile = providerProfile(revision = 1L, apiKey = "")

        assertTrue(hasUsableSharedProviderBinding(binding, profile))
    }

    private fun providerProfile(revision: Long, apiKey: String) = ModelProviderProfile(
        id = "provider-1",
        name = "Provider",
        baseUrl = "https://gateway.example/v1",
        apiKey = apiKey,
        revision = revision,
    )
}
