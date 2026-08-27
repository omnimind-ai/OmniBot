package cn.com.omnimind.baselib.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelCatalogTest {
    @Test
    fun catalogContainsVerifiedHttpsGGUFModel() {
        val model = LocalModelCatalog.entries.single()
        assertEquals(LocalModelProvider.SupportedFormats.GGUF, model.format)
        assertTrue(model.downloadUrl.startsWith("https://"))
        assertEquals(64, model.sha256.length)
        assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(model.sizeBytes > 1_000_000_000L)
        assertEquals("Apache-2.0", model.license)
    }

    @Test
    fun catalogLookupUsesStableId() {
        val model = LocalModelCatalog.entries.single()
        assertEquals(model, LocalModelCatalog.find(model.id))
        assertEquals(null, LocalModelCatalog.find("../../unsafe"))
    }
}
