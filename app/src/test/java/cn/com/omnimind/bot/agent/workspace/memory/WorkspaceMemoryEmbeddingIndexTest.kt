package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.bot.agent.workspace.memory.LongTermMemoryIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceMemoryEmbeddingIndexTest {
    @Test
    fun `explicit BYOK binding is preferred but official binding is not`() {
        val byok = ModelProviderProfile(
            id = "provider-a",
            name = "Provider A",
            baseUrl = "https://example.com/v1",
            apiKey = "secret",
        )

        assertEquals(byok, explicitByokEmbeddingProfile("provider-a", byok))
        assertEquals(null, explicitByokEmbeddingProfile("other-provider", byok))
        assertEquals(
            null,
            explicitByokEmbeddingProfile("omnibot-official-ai", byok),
        )
    }

    @Test
    fun `embedding identity changes with route provider and model`() {
        val base = embeddingConfig()

        assertNotEquals(
            base.embeddingConfigId(),
            base.copy(modelId = "embedding-model-b").embeddingConfigId(),
        )
        assertNotEquals(
            base.embeddingConfigId(),
            base.copy(providerProfileId = "provider-b").embeddingConfigId(),
        )
        assertNotEquals(
            base.embeddingConfigId(),
            base.copy(apiBase = "https://other.example.com/v1").embeddingConfigId(),
        )
        assertNotEquals(
            base.embeddingConfigId(),
            base.copy(usesPlatform = true).embeddingConfigId(),
        )
    }

    @Test
    fun `index reuse requires matching identity and dimensions`() {
        val config = embeddingConfig()
        val configId = requireNotNull(config.embeddingConfigId())
        val chunk = MemoryChunk(
            id = "chunk-1",
            source = "MEMORY.md",
            date = null,
            text = "remember this",
        )
        val current = MemoryIndexEntry(
            id = chunk.id,
            source = chunk.source,
            date = chunk.date,
            text = chunk.text,
            embedding = listOf(0.1, 0.2, 0.3),
            embeddingConfigId = configId,
            embeddingDimensions = 3,
        )

        assertTrue(
            current.canReuseFor(chunk, config, configId, 3, shouldRequestEmbeddings = true)
        )
        assertFalse(
            current.canReuseFor(chunk, config, configId, 4, shouldRequestEmbeddings = true)
        )
        assertFalse(
            current.copy(embeddingConfigId = "stale").canReuseFor(
                chunk,
                config,
                configId,
                3,
                shouldRequestEmbeddings = true,
            )
        )
        assertFalse(
            current.copy(
                embeddingConfigId = null,
                embeddingDimensions = null,
            ).canReuseFor(
                chunk,
                config,
                configId,
                3,
                shouldRequestEmbeddings = true,
            )
        )
        assertTrue(
            current.canReuseFor(chunk, config, configId, null, shouldRequestEmbeddings = false)
        )
    }

    @Test
    fun `index keeps the loadable long term memory slug`() {
        val config = embeddingConfig()
        val configId = requireNotNull(config.embeddingConfigId())
        val text = "用户长期偏好中文回复"
        val slug = LongTermMemoryIndex.makeSlug(text)
        val chunk = MemoryChunk(
            id = "chunk-1",
            source = ".omnibot/memory/MEMORY.md",
            date = null,
            text = text,
            slug = slug,
        )
        val current = MemoryIndexEntry(
            id = chunk.id,
            source = chunk.source,
            date = chunk.date,
            text = chunk.text,
            slug = slug,
            embedding = listOf(0.1, 0.2, 0.3),
            embeddingConfigId = configId,
            embeddingDimensions = 3,
        )

        assertTrue(
            current.canReuseFor(chunk, config, configId, 3, shouldRequestEmbeddings = true)
        )
        assertFalse(
            current.copy(slug = null).canReuseFor(
                chunk,
                config,
                configId,
                3,
                shouldRequestEmbeddings = true,
            )
        )
    }

    @Test
    fun `cosine similarity rejects vectors from different dimensions`() {
        assertEquals(1.0, cosineSimilarity(listOf(1.0, 0.0), listOf(2.0, 0.0)), 0.000001)
        assertEquals(0.0, cosineSimilarity(listOf(1.0, 0.0), listOf(2.0)), 0.0)
    }

    private fun embeddingConfig(): WorkspaceMemoryEmbeddingConfig =
        WorkspaceMemoryEmbeddingConfig(
            enabled = true,
            configured = true,
            sceneId = "scene.memory.embedding",
            providerProfileId = "provider-a",
            providerProfileName = "Provider A",
            modelId = "embedding-model-a",
            apiBase = "https://example.com/v1",
            hasApiKey = true,
            usesPlatform = false,
        )
}
