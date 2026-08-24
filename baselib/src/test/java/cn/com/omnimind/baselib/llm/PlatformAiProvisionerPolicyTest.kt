package cn.com.omnimind.baselib.llm

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformAiProvisionerPolicyTest {
    @Test
    fun `overlapping catalog requests share one in-flight refresh`() = runBlocking {
        val coalescer = SuspendRequestCoalescer<String>()
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async {
            coalescer.run {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
                "fresh catalog"
            }
        }
        started.await()
        val second = async {
            coalescer.run {
                calls.incrementAndGet()
                "duplicate catalog"
            }
        }
        yield()

        assertEquals(1, calls.get())
        release.complete(Unit)
        assertEquals("fresh catalog", first.await())
        assertEquals("fresh catalog", second.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun `official model lists are selected by requested capability`() {
        val status = PlatformAiProvisioningStatus(
            ready = true,
            models = listOf(ProviderModelOption("text-model")),
            embeddingModels = listOf(ProviderModelOption("embedding-model")),
        )

        assertEquals(listOf("text-model"), status.modelsForCapability("text").map { it.id })
        assertEquals(
            listOf("embedding-model"),
            status.modelsForCapability("embedding").map { it.id },
        )
        assertTrue(status.modelsForCapability("unsupported").isEmpty())
    }

    @Test
    fun `embedding catalog refresh uses cooldown after any attempt`() {
        val cooldown = 1_000L

        assertTrue(shouldRefreshEmbeddingCatalog(0L, 100L, cooldown))
        assertFalse(shouldRefreshEmbeddingCatalog(100L, 1_099L, cooldown))
        assertTrue(shouldRefreshEmbeddingCatalog(100L, 1_100L, cooldown))
        assertTrue(shouldRefreshEmbeddingCatalog(2_000L, 1_000L, cooldown))
    }

    @Test
    fun `failed refresh preserves a ready text catalog`() {
        val ready = PlatformAiProvisioningStatus(
            ready = true,
            statusText = "ready",
            defaultModelId = "text-model",
            models = listOf(ProviderModelOption("text-model")),
            defaultVisionModelId = "vision-model",
        )

        assertEquals(
            ready,
            preserveLastKnownGoodCatalogOrFailure(ready, "refresh failed"),
        )
    }

    @Test
    fun `failed initial refresh still reports the failure`() {
        val failed = preserveLastKnownGoodCatalogOrFailure(
            previous = PlatformAiProvisioningStatus(),
            failureStatusText = "refresh failed",
        )

        assertFalse(failed.ready)
        assertTrue(failed.models.isEmpty())
        assertEquals("refresh failed", failed.statusText)
    }
}
