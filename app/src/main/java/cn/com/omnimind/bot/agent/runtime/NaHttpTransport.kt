package cn.com.omnimind.bot.agent.runtime

import java.io.IOException
import java.net.ProtocolException
import java.security.cert.CertificateException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Set only after this instance advertises durable admission deduplication. */
internal object NaIdempotentAdmission

/** Recheck the captured connection before each attempt and before consuming it. */
internal class NaHttpRequestGuard(val check: () -> Unit)

/** Cancellation may stop a new admission attempt without discarding an already received run id. */
internal class NaHttpAdmissionGuard(val check: () -> Unit)

private class NaHttpRetry(val waitMillis: Long) : Exception()
private val transientStatuses = setOf(408, 429, 500, 502, 503, 504)

internal fun naRetryDelayMillis(
    attempt: Int,
    retryAfter: String? = null,
    nowMillis: Long = System.currentTimeMillis(),
): Long? {
    val fallback = 1000L * attempt.coerceIn(1, 2)
    val header = retryAfter?.trim()?.takeIf { it.isNotEmpty() } ?: return fallback
    val seconds = header.toLongOrNull()
    val wait = when {
        seconds != null && seconds >= 0 -> if (seconds > 5) return null else seconds * 1000
        else -> runCatching {
            (ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli() - nowMillis).coerceAtLeast(0)
        }.getOrNull() ?: fallback
    }
    // Do not retry earlier than the service asked. Longer waits are surfaced.
    return wait.takeIf { it <= 5000 }
}

private fun IOException.canRecover(): Boolean {
    val causes = generateSequence<Throwable>(this) { it.cause }.take(16)
    return causes.none { it is SSLPeerUnverifiedException || it is SSLHandshakeException ||
        it is CertificateException || it is ProtocolException }
}

/** One bounded transport owner. Repeating an admission keeps its exact body
 * and logical identities; other writes and terminal executions are not replayed. */
internal suspend fun <T> naHttpResponse(client: OkHttpClient, request: Request, retryReads: Boolean = true, consume: (Response) -> T): T {
    // Writes start with an empty pool so a stale idle read connection cannot
    // lose their response. Explicit admission deduplication still owns retries.
    val requestClient = if (request.method == "GET" || request.method == "HEAD") client else
        client.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .retryOnConnectionFailure(false).build()
    val repeatable = (request.method == "GET" && retryReads) ||
        (request.method == "POST" && request.tag(NaIdempotentAdmission::class.java) != null)
    val guard = request.tag(NaHttpRequestGuard::class.java)
    var attempts = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        guard?.check?.invoke()
        request.tag(NaHttpAdmissionGuard::class.java)?.check?.invoke()
        attempts++
        try {
            return suspendCancellableCoroutine { continuation ->
                val call = requestClient.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        continuation.resumeWith(Result.failure(error))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching {
                            response.use {
                                if (!continuation.isActive) throw kotlinx.coroutines.CancellationException()
                                guard?.check?.invoke()
                                if (repeatable && attempts < 3 && response.code in transientStatuses) {
                                    naRetryDelayMillis(attempts, response.header("Retry-After"))?.let {
                                        throw NaHttpRetry(it)
                                    }
                                }
                                consume(response)
                            }
                        }
                        continuation.resumeWith(result)
                    }
                })
            }
        } catch (retry: NaHttpRetry) {
            delay(retry.waitMillis)
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            if (!repeatable || attempts >= 3 || !error.canRecover()) throw error
            delay(naRetryDelayMillis(attempts)!!)
        }
    }
}
