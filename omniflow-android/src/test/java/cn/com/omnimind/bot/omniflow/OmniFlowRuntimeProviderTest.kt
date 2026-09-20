package cn.com.omnimind.bot.omniflow

import android.content.Context
import android.content.ContextWrapper
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowRuntimeProviderTest {
    @Test
    fun `install keeps packaged runtime while update requests official refresh`() = runBlocking {
        val platform = RefreshRecordingPlatform()
        val provider = OmniFlowRuntimeProvider()

        runCatching { provider.install(TestContext, platform) }
        runCatching { provider.update(TestContext, platform) }

        assertEquals(listOf(false, true), platform.refreshRequests)
    }

    @Test
    fun `bridge contract mismatch triggers packaged runtime recovery`() {
        assertTrue(
            isOmniFlowRuntimeCompatibilityFailure(
                IllegalStateException("omniflow_bridge_contract_mismatch:old")
            )
        )
        assertFalse(
            isOmniFlowRuntimeCompatibilityFailure(
                IllegalStateException("omniflow_tool_call_failed")
            )
        )
    }

    private class RefreshRecordingPlatform : OmniFlowPlatform {
        val refreshRequests = mutableListOf<Boolean>()

        override suspend fun startProcess(
            context: Context,
            command: String,
            environment: Map<String, String>,
        ): Process = error("not used")

        override suspend fun prepareEnvironment(context: Context, command: String) = Unit

        override suspend fun resolveRuntimeSkill(
            context: Context,
            refresh: Boolean,
        ): OmniFlowSkillLocation {
            refreshRequests += refresh
            error("stop after refresh observation")
        }

        override suspend fun bootstrapRuntimeSkill(
            context: Context,
            location: OmniFlowSkillLocation,
        ): OmniFlowSkillLocation = location

        override suspend fun reclaimRuntimeSkill(context: Context) = Unit

        override suspend fun completeJson(
            request: cn.com.omnimind.baselib.llm.ChatCompletionRequest,
        ): String = error("not used")
    }

    private object TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
