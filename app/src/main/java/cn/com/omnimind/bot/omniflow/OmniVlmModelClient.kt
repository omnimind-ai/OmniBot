package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.bot.agent.AgentLlmClient

internal fun AgentLlmClient.asOmniFlowModelClient(): OmniFlowModelClient =
    object : OmniFlowModelClient {
        override suspend fun streamTurn(
            request: ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
        ): ChatCompletionTurn {
            val turn = this@asOmniFlowModelClient.streamTurn(
                request = request,
                onReasoningUpdate = onReasoningUpdate,
            )
            // OmniFlow's canonical action schema already defines coordinates as
            // device-independent 0..1000 values. Keep model output unchanged;
            // provider/model-name based coordinate adapters are intentionally
            // not part of the protocol boundary.
            return turn
        }
    }
