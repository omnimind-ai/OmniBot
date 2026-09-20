package cn.com.omnimind.bot.omniflow

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowPluginRuntimeTest {
    @Test
    fun `enabling optional plugin verifies the resident Python runtime`() = runBlocking {
        val backend = RecordingBackend()
        val runtime = OmniFlowPluginRuntimeController(backend)

        assertFalse(runtime.isEnabled())
        assertEquals(0, backend.prepareCount)

        runtime.install(TestPlatform, OmniFlowRuntimeProvider())
        assertFalse(runtime.isEnabled())
        assertEquals(0, backend.prepareCount)

        runtime.enable(TestContext)
        assertTrue(runtime.isEnabled())
        assertEquals(1, backend.prepareCount)

        runtime.enable(TestContext)
        assertEquals(1, backend.prepareCount)

        runtime.disable()
        assertFalse(runtime.isEnabled())
        assertEquals(1, backend.shutdownCount)
    }

    @Test
    fun `failed readiness does not expose an enabled plugin`() = runBlocking {
        val backend = RecordingBackend(prepareFailure = IllegalStateException("bridge failed"))
        val runtime = OmniFlowPluginRuntimeController(backend)
        runtime.install(TestPlatform, OmniFlowRuntimeProvider())

        val error = runCatching { runtime.enable(TestContext) }.exceptionOrNull()

        assertEquals("bridge failed", error?.message)
        assertFalse(runtime.isEnabled())
    }

    private class RecordingBackend(
        private val prepareFailure: Throwable? = null,
    ) : OmniFlowPluginBackend {
        var prepareCount = 0
        var shutdownCount = 0

        override fun configure(
            platform: OmniFlowPlatform,
            runtimeProvider: OmniFlowRuntimeProvider,
        ) = Unit

        override suspend fun prepareAndStart(context: Context) {
            prepareCount += 1
            prepareFailure?.let { throw it }
        }

        override suspend fun shutdown() {
            shutdownCount += 1
        }
    }

    private object TestPlatform : OmniFlowPlatform {
        override suspend fun startProcess(
            context: Context,
            command: String,
            environment: Map<String, String>,
        ): Process = error("not used")

        override suspend fun prepareEnvironment(context: Context, command: String) = Unit

        override suspend fun resolveRuntimeSkill(
            context: Context,
            refresh: Boolean,
        ): OmniFlowSkillLocation = error("not used")

        override suspend fun bootstrapRuntimeSkill(
            context: Context,
            location: OmniFlowSkillLocation,
        ): OmniFlowSkillLocation = location

        override suspend fun reclaimRuntimeSkill(context: Context) = Unit

        override suspend fun completeJson(request: cn.com.omnimind.baselib.llm.ChatCompletionRequest): String =
            error("not used")
    }

    private object TestContext : android.content.ContextWrapper(null)
}
