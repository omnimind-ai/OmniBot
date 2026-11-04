package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ProtocolException
import java.security.cert.CertificateException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class NaHttpRecoveryTest {
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()
    private val admissionBody = """{"text":"你好","clientMessageId":"same-logical-turn"}"""

    private fun post(server: MockWebServer, idempotent: Boolean): Request = Request.Builder()
        .url(server.url("/api/conversations/session/messages"))
        .post(admissionBody.toRequestBody("application/json".toMediaType()))
        .apply {
            if (idempotent) tag(NaIdempotentAdmission::class.java, NaIdempotentAdmission)
        }
        .build()

    @Test fun confirmedIdempotentPostRecoversLostResponseWithIdenticalAdmission() = runBlocking {
        MockWebServer().use { server ->
            // The server received the entire first admission but its response
            // was lost. Reuse its stable turn key instead of creating a turn.
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody("""{"runId":"original-run"}"""))
            server.start()

            val result = naHttpResponse(client, post(server, idempotent = true)) { it.body.string() }

            assertEquals("""{"runId":"original-run"}""", result)
            assertEquals(2, server.requestCount)
            val first = server.takeRequest()
            val recovered = server.takeRequest()
            assertEquals("POST", first.method)
            assertEquals(first.method, recovered.method)
            assertEquals(first.path, recovered.path)
            assertEquals(admissionBody, first.body.readUtf8())
            assertEquals(admissionBody, recovered.body.readUtf8())
        }
    }

    @Test fun transientReadFailureRecoversBeforeExposingAResponseToCaller() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("temporarily unavailable"))
            server.enqueue(MockResponse().setBody("ready"))
            server.start()
            var consumed = 0

            val result = naHttpResponse(client, Request.Builder().url(server.url("/api/status")).build()) {
                consumed++
                it.body.string()
            }

            assertEquals("ready", result)
            assertEquals(1, consumed)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun authorizationAndConflictResponsesAreNeverRetriedEvenForIdempotentAdmissions() = runBlocking {
        for (status in listOf(401, 403, 409)) {
            for (admission in listOf(false, true)) {
                MockWebServer().use { server ->
                    server.enqueue(MockResponse().setResponseCode(status))
                    server.enqueue(MockResponse().setBody("must not be requested"))
                    server.start()
                    val request = if (admission) post(server, idempotent = true)
                        else Request.Builder().url(server.url("/api/status")).build()

                    assertEquals(status, naHttpResponse(client, request) { it.code })
                    assertEquals("status=$status admission=$admission", 1, server.requestCount)
                }
            }
        }
    }

    @Test fun ordinaryPostDoesNotReplayOnHttpFailureOrLostResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("must not be requested"))
            server.start()
            assertEquals(503, naHttpResponse(client, post(server, idempotent = false)) { it.code })
            assertEquals(1, server.requestCount)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody("must not be requested"))
            server.start()
            val failure = runCatching {
                naHttpResponse(client, post(server, idempotent = false)) { it.code }
            }.exceptionOrNull()
            assertTrue("lost response must surface without admitting twice", failure is IOException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun retryBudgetIsThreeTotalAttemptsForReadsAndConfirmedAdmissions() = runBlocking {
        for (admission in listOf(false, true)) {
            MockWebServer().use { server ->
                repeat(4) { index ->
                    server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable-$index"))
                }
                server.start()
                val request = if (admission) post(server, idempotent = true)
                    else Request.Builder().url(server.url("/api/status")).build()
                var consumed = 0

                val result = naHttpResponse(client, request) {
                    consumed++
                    it.code to it.body.string()
                }

                assertEquals(503 to "unavailable-2", result)
                assertEquals(1, consumed)
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun cancellationDuringRetryAfterStopsTheSameRequestWithoutAnotherAttempt() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "5"))
            server.enqueue(MockResponse().setBody("must not be requested"))
            server.start()
            val responseReceived = CountDownLatch(1)
            val observedClient = client.newBuilder().eventListener(object : EventListener() {
                override fun responseHeadersEnd(call: Call, response: Response) {
                    responseReceived.countDown()
                }
            }).build()
            val request = Request.Builder().url(server.url("/api/status")).build()
            val execution = launch(Dispatchers.Default) {
                naHttpResponse(observedClient, request) { it.code }
                fail("cancellation must stop recovery")
            }
            try {
                assertTrue(responseReceived.await(2, TimeUnit.SECONDS))
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                delay(100)
                withTimeout(1500) { execution.cancelAndJoin() }
                assertTrue(execution.isCancelled)
                assertEquals(1, server.requestCount)
                assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            } finally {
                execution.cancelAndJoin()
            }
        }
    }

    @Test fun connectionGuardPreventsAdmissionReplayAfterOwnerChangesDuringRecovery() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "5").setBody("temporary"))
            server.enqueue(MockResponse().setBody("must not be requested under a new owner"))
            server.start()
            val connectionCurrent = AtomicBoolean(true)
            val observedClient = client.newBuilder().eventListener(object : EventListener() {
                override fun responseBodyEnd(call: Call, byteCount: Long) {
                    // Closing the first retryable response happens after its
                    // response guard passes and before the next attempt.
                    connectionCurrent.set(false)
                }
            }).build()
            val request = post(server, idempotent = true).newBuilder()
                .tag(NaHttpRequestGuard::class.java, NaHttpRequestGuard {
                    if (!connectionCurrent.get()) throw NaRuntimeException("na_connection_changed", "message_admission")
                })
                .build()

            val failure = runCatching { naHttpResponse(observedClient, request) { it.code } }.exceptionOrNull()

            assertEquals("na_connection_changed", (failure as? NaRuntimeFailure)?.failureKind)
            assertFalse(connectionCurrent.get())
            assertEquals(1, server.requestCount)
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun retryAfterUsesServerTimingAndDoesNotRetryBeforeLongRequiredWaits() {
        val now = Instant.parse("2026-11-03T17:00:00Z").toEpochMilli()
        fun date(offsetSeconds: Long) = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.ofEpochMilli(now).plusSeconds(offsetSeconds).atZone(ZoneOffset.UTC),
        )

        assertEquals(1000L, naRetryDelayMillis(1, nowMillis = now))
        assertEquals(2000L, naRetryDelayMillis(2, nowMillis = now))
        assertEquals(3000L, naRetryDelayMillis(1, "3", now))
        assertEquals(0L, naRetryDelayMillis(1, "0", now))
        assertEquals(5000L, naRetryDelayMillis(1, "5", now))
        assertEquals(4000L, naRetryDelayMillis(1, date(4), now))
        assertEquals(0L, naRetryDelayMillis(2, date(-1), now))
        assertEquals(1000L, naRetryDelayMillis(1, "invalid-date", now))
        assertEquals(2000L, naRetryDelayMillis(2, "", now))
        assertNull(naRetryDelayMillis(1, "6", now))
        assertNull(naRetryDelayMillis(2, date(6), now))
    }

    @Test fun certificateAndProtocolFailuresDoNotConsumeTheRetryBudget() = runBlocking {
        val failures = listOf(
            SSLPeerUnverifiedException("certificate hostname mismatch"),
            SSLHandshakeException("certificate rejected").apply {
                initCause(CertificateException("untrusted certificate"))
            },
            ProtocolException("unexpected response protocol"),
        )
        for (failure in failures) {
            var attempts = 0
            val failingClient = client.newBuilder().addInterceptor {
                attempts++
                throw failure
            }.build()
            val request = Request.Builder().url("https://example.test/api/status").build()

            val surfaced = runCatching { naHttpResponse(failingClient, request) { it.code } }.exceptionOrNull()

            assertEquals(failure.javaClass, surfaced?.javaClass)
            assertEquals(failure.message, surfaced?.message)
            assertEquals(failure.javaClass.simpleName, 1, attempts)
        }
    }
}
