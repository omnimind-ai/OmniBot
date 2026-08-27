package cn.com.omnimind.baselib.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOfflineModelTest {
    @Test
    fun catalogContainsVerifiedQwenModel() {
        val entry = LocalModelCatalog.find("qwen2.5-1.5b-instruct-q4_k_m")
        assertTrue(entry != null)
        assertEquals(LocalModelProvider.SupportedFormats.GGUF, entry?.format)
        assertEquals("Apache-2.0", entry?.license)
        assertEquals(64, entry?.sha256?.length)
        assertTrue(entry?.downloadUrl?.startsWith("https://") == true)
    }

    @Test
    fun modelIdsAreFilesystemSafe() {
        val safeIds = LocalModelCatalog.entries.map { it.id }
        assertTrue(safeIds.all { it.matches(Regex("[A-Za-z0-9._-]+")) })
    }

    @Test
    fun localProviderAdvertisesNoNetworkInference() {
        val capabilities = LocalInferenceAdapter.getLocalModelCapabilities("missing-model")
        assertTrue(capabilities.isEmpty())
    }
}
