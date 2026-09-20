package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.bot.agent.AgentLlmClient
import android.content.Context
import cn.com.omnimind.bot.agent.ProviderFailureJournal

internal fun AgentLlmClient.asOmniFlowModelClient(context: Context? = null): OmniFlowModelClient =
    object : OmniFlowModelClient {
        override suspend fun streamTurn(
            request: ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
        ): ChatCompletionTurn {
            val turn = try {
                this@asOmniFlowModelClient.streamTurn(
                    request = request,
                    onReasoningUpdate = onReasoningUpdate,
                )
            } catch (error: Exception) {
                context?.let { ProviderFailureJournal.record(it, error) }
                throw error
            }
            // OmniFlow's canonical action schema already defines coordinates as
            // device-independent 0..1000 values. Keep model output unchanged;
            // provider/model-name based coordinate adapters are intentionally
            // not part of the protocol boundary.
            return turn
        }
    }
