package cn.com.omnimind.bot.agent.runtime

import java.util.concurrent.TimeoutException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun <T : Any> withMcpRequestTimeout(
    timeoutMillis: Long,
    method: String,
    request: suspend () -> T,
): T = withTimeoutOrNull(timeoutMillis) { request() } ?: run {
    // Only this request's deadline becomes a tool/transport failure. Parent
    // cancellation still belongs to the ACP prompt owner and must propagate.
    currentCoroutineContext().ensureActive()
    throw TimeoutException("MCP request $method timed out after $timeoutMillis ms")
}
