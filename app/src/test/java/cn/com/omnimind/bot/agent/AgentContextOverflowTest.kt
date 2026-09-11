package cn.com.omnimind.bot.agent

import org.junit.Assert.*
import org.junit.Test

class AgentContextOverflowTest {
    @Test fun recognizesProviderInputErrorsIncludingReportedRegressions() {
        for (reason in listOf("Prompt exceeds max length",
            "Input length 1119534 exceeds the maximum length 1048566",
            "prompt is too long: 213462 tokens > 200000 maximum",
            "context_length_exceeded", "Your input exceeds the context window of this model",
            "The input token count (1196265) exceeds the maximum number of tokens allowed (1048575)",
            "Range of input length should be [1, 10000]", "Your request exceeded model token limit: 100")) {
            assertTrue(reason, AgentContextOverflow.isOverflow(AgentStreamRequestException(400, reason, null)))
        }
        assertTrue(AgentContextOverflow.isOverflow(AgentStreamRequestException(422, "invalid request", "context_length_exceeded")))
    }

    @Test fun doesNotCompactForRateLimitsOutputParameterErrorsOrUnrelatedLengths() {
        for ((status, reason) in listOf(429 to "too many tokens", 503 to "too many tokens",
            400 to "Throttling error: Too many tokens, please wait",
            400 to "rate limit: token limit exceeded", 400 to "too many requests: too many tokens",
            400 to "max_tokens exceeds maximum length", 400 to "input length must be positive",
            400 to "token limit must be an integer", 400 to "file name exceeds maximum length")) {
            assertFalse(reason, AgentContextOverflow.isOverflow(AgentStreamRequestException(status, reason, null)))
        }
    }
}
