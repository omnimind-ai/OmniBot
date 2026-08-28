package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.runlog.State
import cn.com.omnimind.baselib.runlog.actionOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OmniVlmPluginTest {
    @Test
    fun `VLM delegates execution to its configured backend`() = runBlocking {
        val backend = RecordingBackend()
        val runtime = OmniVlmPlugin(backend)
        var afterExecutionCount = 0

        val result = runtime.execute(
            context = TestContext,
            request = OmniVlmPlugin.Request(goal = " open settings ", runId = " run-1 "),
            modelClient = UnusedModelClient,
            hooks = OmniVlmPlugin.Hooks(
                afterExecution = { afterExecutionCount += 1 },
            ),
        )

        assertEquals("open settings", backend.request?.goal)
        assertEquals("run-1", backend.request?.runId)
        assertEquals(true, result.payload["success"])
        assertEquals(1, afterExecutionCount)
    }

    @Test
    fun `default request delegates guidance ownership to Python harness`() {
        val arguments = OmniVlmPlugin.Request("order me a coffee").runGuiArguments()

        assertEquals(false, arguments.containsKey("step_skill_guidance"))
        assertEquals("order me a coffee", arguments["goal"])
        assertEquals(20, arguments["max_steps"])
    }

    @Test
    fun `explicit guidance remains a temporary caller override`() {
        val arguments = OmniVlmPlugin.Request(
            goal = "open settings",
            stepSkillGuidance = "temporary experiment guidance",
        ).runGuiArguments()

        assertEquals(
            "temporary experiment guidance",
            arguments["step_skill_guidance"],
        )
    }

    @Test
    fun `device host blocks payment confirmation actions`() {
        val state = State.create(
            packageName = "com.example.shop",
            activityName = "CheckoutActivity",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = "<node text='立即支付'/>",
        )

        assertEquals(true, blocksPaymentConfirmation(state, actionOf("click")))
        assertEquals(
            false,
            blocksPaymentConfirmation(state, actionOf("press_key", mapOf("key" to "BACK"))),
        )
    }

    private class RecordingBackend : OmniVlmBackend {
        var request: OmniVlmPlugin.Request? = null

        override suspend fun execute(
            context: Context,
            request: OmniVlmPlugin.Request,
            modelClient: OmniFlowModelClient,
            hooks: OmniVlmPlugin.Hooks,
        ): OmniVlmPlugin.Result {
            this.request = request
            return OmniVlmPlugin.Result(mapOf("success" to true), null)
        }

        override fun stop(runId: String): Boolean = false
    }

    private object UnusedModelClient : OmniFlowModelClient {
        override suspend fun streamTurn(
            request: ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
        ): ChatCompletionTurn = error("not used")
    }

    private object TestContext : android.content.ContextWrapper(null)
}
