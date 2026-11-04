package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.Dispatcher
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class NaCloudAdmissionRecoveryTest {
    @get:Rule val directory = TemporaryFolder()

    private class Fixture(folder: File) : AutoCloseable {
        val context = acpProfileStoreTestContext(folder).also {
            `when`(it.cacheDir).thenReturn(folder)
        }
        val bindings = mock(AgentSessionBindingRepository::class.java)
        val profiles = AcpAgentProfileStore(context)
        val origin = naInstallationOrigin(context.noBackupFilesDir)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val emitted = mutableListOf<Map<String, Any?>>()
        fun runtime() = NaCloudRuntime(context, scope, bindings, AcpAgentProfileStore(context), emit = { emitted += it })
        override fun close() { scope.cancel() }
    }

    private fun capabilities(idempotent: Boolean = true) = MockResponse().setBody(
        if (idempotent) """{"capabilities":["idempotent_admission"]}""" else """{"capabilities":[]}""",
    )
    private fun disconnected() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
    private fun created(id: String = "remote-session") = MockResponse().setBody(
        """{"conversation":{"id":"$id","title":"New"}}""",
    )
    private fun completed() = MockResponse().setBody(
        """{"run":{"status":"completed","items":[{"id":"answer","type":"agent_message","text":"done"}]}}""",
    )
    private fun matchingRun(origin: String, userMessageId: String? = null): Map<String, Any?> = mapOf(
        "id" to "original-run", "clientMessageId" to naCloudMessageId(origin, "host-turn"),
        "clientOriginId" to origin, "input" to "hello", "conversationId" to "remote-session",
    ) + if (userMessageId == null) emptyMap() else mapOf("clientUserMessageId" to userMessageId)
    private fun conversationSnapshot(run: Map<String, Any?>?) = MockResponse().setBody(Gson().toJson(mapOf(
        "conversation" to mapOf("id" to "remote-session"),
        "runs" to if (run == null) emptyList<Any>() else listOf(run),
    )))
    private fun MockWebServer.recorded() = requireNotNull(takeRequest(2, TimeUnit.SECONDS)) { "Expected request was not received" }
    private fun RecordedRequest.json() = JsonParser.parseString(body.clone().readUtf8()).asJsonObject

    @Test fun sessionCreationRecoversLostResponseBeforeCreatingAnyLocalHistory() = runBlocking {
        Fixture(directory.newFolder()).use { fixture ->
            MockWebServer().use { server ->
                server.enqueue(capabilities())
                server.enqueue(disconnected())
                server.enqueue(created())
                server.start()
                val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))

                val result = withContext(connection) {
                    fixture.runtime().callConnected("session/new", emptyMap()) as Map<*, *>
                }

                assertEquals(connection.prefix + "remote-session", result["sessionId"])
                assertNull(result["conversationId"])
                assertFalse(mockingDetails(fixture.bindings).invocations.any { it.method.name == "ensureBinding" })
                assertEquals("/api/status", server.recorded().path)
                val first = server.recorded()
                val recovered = server.recorded()
                assertEquals("POST", first.method)
                assertEquals("/api/conversations", first.path)
                assertEquals(first.path, recovered.path)
                assertEquals(first.json(), recovered.json())
                assertTrue(first.json()["clientConversationId"].asString.isNotBlank())
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun localCreationReservationSurvivesRuntimeRecreationAfterAllResponsesAreLost() = runBlocking {
        // Production has no standalone-instance connection; the owner fixture
        // exercises the development transport used on the connected device.
        assumeTrue(NaCloudEnvironment.developmentAvailable)
        Fixture(directory.newFolder()).use { fixture ->
            MockWebServer().use { server ->
                server.enqueue(capabilities())
                repeat(3) { server.enqueue(disconnected()) }
                server.enqueue(capabilities())
                server.enqueue(created())
                server.start()
                val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
                fixture.context.getSharedPreferences("na_cloud_connection", Context.MODE_PRIVATE).edit()
                    .putBoolean("development", true).putString("baseUrl", connection.baseUrl).commit()
                fixture.profiles.bindConversation(41L, NA_AGENT_ID)
                fixture.profiles.bindNaConversationOwner(41L, connection.prefix)
                val sessionId = connection.prefix + "remote-session"
                `when`(fixture.bindings.ensureBinding(sessionId, NA_AGENT_ID, 41L, cwd = "na-cloud", title = "New"))
                    .thenReturn(41L)

                withContext(connection) {
                    val failed = runCatching {
                        fixture.runtime().callConnected("session/new", mapOf("conversationId" to 41L))
                    }.exceptionOrNull()
                    assertTrue("creation must report its unconfirmed connection failure", failed is NaRuntimeFailure)
                    assertFalse(mockingDetails(fixture.bindings).invocations.any { it.method.name == "ensureBinding" })
                    val recovered = fixture.runtime().callConnected("session/new", mapOf("conversationId" to 41L)) as Map<*, *>
                    assertEquals(sessionId, recovered["sessionId"])
                    assertEquals(41L, recovered["conversationId"])
                }

                val requests = List(6) { server.recorded() }
                assertEquals(listOf("GET", "POST", "POST", "POST", "GET", "POST"), requests.map { it.method })
                val admissions = requests.filter { it.method == "POST" }
                val reservation = admissions.first().json()["clientConversationId"].asString
                assertTrue(reservation.isNotBlank())
                assertTrue(admissions.all { it.json()["clientConversationId"].asString == reservation })
                assertEquals(1, mockingDetails(fixture.bindings).invocations.count { it.method.name == "ensureBinding" })
                verify(fixture.bindings).ensureBinding(sessionId, NA_AGENT_ID, 41L, cwd = "na-cloud", title = "New")
                assertEquals(connection.prefix, fixture.profiles.naOwnerForConversation(41L))
                assertEquals(6, server.requestCount)
            }
        }
    }

    @Test fun messageAdmissionRecoversIdenticallyAndProjectsTheOriginalTurnOnlyOnce() = runBlocking {
        Fixture(directory.newFolder()).use { fixture ->
            MockWebServer().use { server ->
                server.enqueue(capabilities())
                server.enqueue(disconnected())
                server.enqueue(MockResponse().setBody("""{"runId":"original-run"}"""))
                server.enqueue(completed())
                server.start()
                val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
                val sessionId = connection.prefix + "remote-session"
                `when`(fixture.bindings.ensureBinding(sessionId, NA_AGENT_ID, cwd = "na-cloud")).thenReturn(42L)
                val args = mapOf("sessionId" to sessionId, "requestId" to "host-turn", "userMessageId" to "host-user", "text" to "hello")
                val runtime = fixture.runtime()

                withContext(connection) {
                    val first = runtime.callConnected("session/prompt", args) as Map<*, *>
                    val duplicate = runtime.callConnected("session/prompt", args) as Map<*, *>
                    assertEquals(first, duplicate)
                    assertEquals("host-turn", first["turnId"])
                    assertEquals("end_turn", first["stopReason"])
                }

                assertEquals("/api/status", server.recorded().path)
                val first = server.recorded()
                val recovered = server.recorded()
                assertEquals("/api/conversations/remote-session/messages", first.path)
                assertEquals(first.path, recovered.path)
                assertEquals(first.json(), recovered.json())
                assertEquals(naCloudMessageId(fixture.origin, "host-turn"), recovered.json()["clientMessageId"].asString)
                assertEquals(fixture.origin, recovered.json()["clientOriginId"].asString)
                assertEquals("host-user", recovered.json()["clientUserMessageId"].asString)
                assertEquals("/api/runs/original-run", server.recorded().path)
                assertEquals(4, server.requestCount)
                assertEquals(1, mockingDetails(fixture.bindings).invocations.count { it.method.name == "ensureBinding" })
                assertEquals(1, fixture.emitted.size)
                val event = fixture.emitted.single()
                assertEquals(sessionId, event["sessionId"])
                assertEquals("host-turn", event["turnId"])
                val update = (event["params"] as Map<*, *>)["update"] as Map<*, *>
                assertEquals("agent_message_chunk", update["sessionUpdate"])
                assertEquals("done", (update["content"] as Map<*, *>)["text"])
            }
        }
    }

    @Test fun exhaustedPostResponsesRecoverByReadingTheAdmittedRunWithoutAFourthPost() = runBlocking {
        recoverAdmittedRun(idempotent = true)
    }

    @Test fun olderServerDoesNotReplayPostButCanReadAnAlreadyAdmittedRun() = runBlocking {
        recoverAdmittedRun(idempotent = false)
    }

    @Test fun attachmentRecoveryUsesOriginalUserTextAndExactOrderedAttachmentIdentity() = runBlocking {
        for (sameAttachment in listOf(true, false)) {
            Fixture(directory.newFolder()).use { fixture ->
                MockWebServer().use { server ->
                    var admittedAttachment: String? = null
                    server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse = when {
                            request.path == "/api/status" -> MockResponse().setBody("""{"capabilities":["attachments","idempotent_admission"]}""")
                            request.method == "PUT" -> {
                                assertEquals(naCloudMessageId(fixture.origin, "host-turn"), request.requestUrl!!.queryParameter("clientMessageId"))
                                MockResponse().setBody("""{"saved":true}""")
                            }
                            request.path == "/api/conversations/remote-session/messages" -> {
                                val body = request.json()
                                assertEquals("hello", body["text"].asString)
                                assertEquals(fixture.origin, body["clientOriginId"].asString)
                                admittedAttachment = body["attachmentIds"].asJsonArray.single().asString
                                disconnected()
                            }
                            request.path == "/api/conversations/remote-session" -> conversationSnapshot(
                                matchingRun(fixture.origin, "host-user") + mapOf(
                                    "userText" to "hello", "input" to "hello\n\n[附件] attachment-path.txt",
                                    "attachments" to listOf(mapOf("id" to if (sameAttachment) admittedAttachment else "different-attachment")),
                                ),
                            )
                            request.path == "/api/runs/original-run" -> completed()
                            request.method == "DELETE" -> MockResponse().setBody("{}")
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                    server.start()
                    val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
                    val sessionId = connection.prefix + "remote-session"
                    `when`(fixture.bindings.ensureBinding(sessionId, NA_AGENT_ID, cwd = "na-cloud")).thenReturn(42L)
                    val result = withContext(connection) {
                        runCatching {
                            fixture.runtime().callConnected("session/prompt", mapOf("sessionId" to sessionId,
                                "requestId" to "host-turn", "userMessageId" to "host-user", "text" to "hello",
                                "attachments" to listOf(mapOf("name" to "sample.txt", "mimeType" to "text/plain",
                                    "dataUrl" to "data:text/plain;base64,aGk=")))) as Map<*, *>
                        }
                    }
                    if (sameAttachment) assertEquals("end_turn", result.getOrThrow()["stopReason"])
                    else assertEquals(409, (result.exceptionOrNull() as? NaRuntimeFailure)?.statusCode)
                    val requests = List(8) { server.recorded() }
                    val admissions = requests.filter { it.method == "POST" }
                    assertEquals(3, admissions.size)
                    assertTrue(admissions.all { it.json() == admissions.first().json() })
                    assertEquals(if (sameAttachment) 1 else 0, fixture.emitted.size)
                    assertEquals(8, server.requestCount)
                }
            }
        }
    }

    @Test fun recoveryRejectsAReservationWhoseOriginalInputUserOriginOrConversationDoesNotMatch() = runBlocking {
        val conflicts = listOf(
            mapOf("input" to "another request"),
            mapOf("clientUserMessageId" to "another-user-item"),
            mapOf("conversationId" to "another-conversation"),
            mapOf("clientOriginId" to "another-phone"),
        )
        for (conflict in conflicts) {
            Fixture(directory.newFolder()).use { fixture ->
                MockWebServer().use { server ->
                    // Older servers allow one admission attempt, then the same
                    // read-only reconciliation must still verify its identity.
                    server.enqueue(capabilities(idempotent = false))
                    server.enqueue(disconnected())
                    server.enqueue(conversationSnapshot(matchingRun(fixture.origin, "host-user") + conflict))
                    server.start()
                    val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
                    val sessionId = connection.prefix + "remote-session"
                    `when`(fixture.bindings.ensureBinding(sessionId, NA_AGENT_ID, cwd = "na-cloud")).thenReturn(42L)

                    val failure = withContext(connection) {
                        runCatching {
                            fixture.runtime().callConnected("session/prompt", mapOf("sessionId" to sessionId,
                                "requestId" to "host-turn", "userMessageId" to "host-user", "text" to "hello"))
                        }.exceptionOrNull()
                    }

                    assertEquals("na_request_rejected", (failure as? NaRuntimeFailure)?.failureKind)
                    assertEquals(409, (failure as? NaRuntimeFailure)?.statusCode)
                    assertTrue(fixture.emitted.isEmpty())
                    val requests = List(3) { server.recorded() }
                    assertEquals(listOf("GET", "POST", "GET"), requests.map { it.method })
                    assertEquals("/api/conversations/remote-session", requests.last().path)
                    assertEquals(3, server.requestCount)
                }
            }
        }
    }

    @Test fun successfulButIncompleteAdmissionResponseRecoversTheAlreadyAcceptedRun() = runBlocking {
        Fixture(directory.newFolder()).use { fixture ->
            MockWebServer().use { server ->
                server.enqueue(capabilities())
                server.enqueue(MockResponse().setBody("{}"))
                server.enqueue(conversationSnapshot(matchingRun(fixture.origin, "host-user")))
                server.enqueue(completed())
                server.start()
                val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
                val sessionId = connection.prefix + "remote-session"
                `when`(fixture.bindings.ensureBinding(sessionId, NA_AGENT_ID, cwd = "na-cloud")).thenReturn(42L)

                val result = withContext(connection) {
                    fixture.runtime().callConnected("session/prompt", mapOf("sessionId" to sessionId,
                        "requestId" to "host-turn", "userMessageId" to "host-user", "text" to " hello ")) as Map<*, *>
                }

                assertEquals("end_turn", result["stopReason"])
                assertEquals("host-turn", result["turnId"])
                val requests = List(4) { server.recorded() }
                assertEquals(listOf("GET", "POST", "GET", "GET"), requests.map { it.method })
                assertEquals("/api/conversations/remote-session", requests[2].path)
                assertEquals("/api/runs/original-run", requests[3].path)
                assertEquals(4, server.requestCount)
                assertEquals(1, fixture.emitted.size)
                assertEquals(1, mockingDetails(fixture.bindings).invocations.count { it.method.name == "ensureBinding" })
            }
        }
    }

    @Test fun sameRuntimeCannotReuseATurnReservationWithDifferentText() = runBlocking {
        Fixture(directory.newFolder()).use { fixture ->
            MockWebServer().use { server ->
                server.enqueue(capabilities())
                server.enqueue(MockResponse().setBody("""{"runId":"original-run"}"""))
                server.enqueue(completed())
                server.start()
                val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
                val sessionId = connection.prefix + "remote-session"
                `when`(fixture.bindings.ensureBinding(sessionId, NA_AGENT_ID, cwd = "na-cloud")).thenReturn(42L)
                val args = mapOf("sessionId" to sessionId, "requestId" to "host-turn", "userMessageId" to "host-user", "text" to "hello")
                val runtime = fixture.runtime()
                withContext(connection) {
                    val first = runtime.callConnected("session/prompt", args) as Map<*, *>
                    assertEquals("end_turn", first["stopReason"])
                    val failure = runCatching {
                        runtime.callConnected("session/prompt", args + ("text" to "a different task"))
                    }.exceptionOrNull()
                    assertEquals("na_request_rejected", (failure as? NaRuntimeFailure)?.failureKind)
                    assertEquals(409, (failure as? NaRuntimeFailure)?.statusCode)
                }
                assertEquals(3, server.requestCount)
                assertEquals(1, fixture.emitted.size)
                assertEquals(1, mockingDetails(fixture.bindings).invocations.count { it.method.name == "ensureBinding" })
            }
        }
    }

    @Test fun cancellationDuringAdmissionRecoveryNeverSendsAnotherMessageAndCancelsAnyExistingRun() = runBlocking {
        for (alreadyAdmitted in listOf(false, true)) {
            Fixture(directory.newFolder()).use { fixture ->
                MockWebServer().use { server ->
                    server.enqueue(capabilities())
                    server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "1").setBody("temporary"))
                    server.enqueue(conversationSnapshot(if (alreadyAdmitted) matchingRun(fixture.origin) else null))
                    if (alreadyAdmitted) {
                        server.enqueue(MockResponse().setBody("{}"))
                        server.enqueue(MockResponse().setBody("""{"run":{"status":"cancelled","items":[]}}"""))
                    }
                    server.start()
                    val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
                    val sessionId = connection.prefix + "remote-session"
                    `when`(fixture.bindings.ensureBinding(sessionId, NA_AGENT_ID, cwd = "na-cloud")).thenReturn(42L)
                    val runtime = fixture.runtime()
                    withContext(connection) {
                        val pending = async(Dispatchers.Default) {
                            runtime.callConnected("session/prompt", mapOf("sessionId" to sessionId,
                                "requestId" to "host-turn", "text" to "hello")) as Map<*, *>
                        }
                        try {
                            assertEquals("/api/status", server.recorded().path)
                            assertEquals("/api/conversations/remote-session/messages", server.recorded().path)
                            val stopped = runtime.callConnected("session/cancel", mapOf("sessionId" to sessionId)) as Map<*, *>
                            assertEquals(true, stopped["ok"])
                            val result = withTimeout(5000) { pending.await() }
                            assertEquals("cancelled", result["stopReason"])
                            assertEquals("host-turn", result["turnId"])
                        } finally {
                            pending.cancelAndJoin()
                            fixture.scope.cancel()
                        }
                    }
                    assertEquals("/api/conversations/remote-session", server.recorded().path)
                    if (alreadyAdmitted) {
                        val cancellation = server.recorded()
                        assertEquals("POST", cancellation.method)
                        assertEquals("/api/runs/original-run/cancel", cancellation.path)
                        assertEquals("/api/runs/original-run", server.recorded().path)
                    }
                    assertEquals(if (alreadyAdmitted) 5 else 3, server.requestCount)
                    assertTrue(fixture.emitted.isEmpty())
                }
            }
        }
    }

    @Test fun cancellationCannotClaimNoRunExistsWhenReconciliationFailsOrHasAnInvalidSnapshot() = runBlocking {
        for (snapshotCase in listOf("unavailable", "missing_runs", "missing_id", "partial_history")) {
            Fixture(directory.newFolder()).use { fixture ->
                MockWebServer().use { server ->
                    server.enqueue(capabilities())
                    server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "1").setBody("temporary"))
                    when (snapshotCase) {
                        "unavailable" -> repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable")) }
                        "missing_runs" -> server.enqueue(MockResponse().setBody("""{"conversation":{"id":"remote-session"}}"""))
                        "partial_history" -> server.enqueue(MockResponse().setBody("""{"conversation":{"id":"remote-session"},"runs":[],"hasMoreRuns":true,"runDetailsMayBeTruncated":true,"nextRunCursor":"older-run"}"""))
                        else -> server.enqueue(conversationSnapshot(matchingRun(fixture.origin) - "id"))
                    }
                    server.start()
                    val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
                    val sessionId = connection.prefix + "remote-session"
                    `when`(fixture.bindings.ensureBinding(sessionId, NA_AGENT_ID, cwd = "na-cloud")).thenReturn(42L)
                    val runtime = fixture.runtime()
                    withContext(connection) {
                        // The runtime and this waiter have supervisor-owned
                        // lifetimes so its expected error cannot cancel the
                        // test before we inspect the reported uncertainty.
                        val pending = fixture.scope.async(connection + Dispatchers.Default) {
                            runtime.callConnected("session/prompt", mapOf("sessionId" to sessionId,
                                "requestId" to "host-turn", "text" to "hello"))
                        }
                        try {
                            assertEquals("/api/status", server.recorded().path)
                            assertEquals("/api/conversations/remote-session/messages", server.recorded().path)
                            runtime.callConnected("session/cancel", mapOf("sessionId" to sessionId))
                            val failure = runCatching { withTimeout(10000) { pending.await() } }.exceptionOrNull()
                            assertEquals(snapshotCase, "na_admission_unconfirmed", (failure as? NaRuntimeFailure)?.failureKind)
                        } finally {
                            pending.cancelAndJoin()
                            fixture.scope.cancel()
                        }
                    }
                    val reads = if (snapshotCase == "unavailable") 3 else 1
                    repeat(reads) {
                        val request = server.recorded()
                        assertEquals("GET", request.method)
                        assertEquals("/api/conversations/remote-session", request.path)
                    }
                    assertEquals(reads + 2, server.requestCount)
                    assertTrue(fixture.emitted.isEmpty())
                }
            }
        }
    }

    private suspend fun recoverAdmittedRun(idempotent: Boolean) {
        Fixture(directory.newFolder()).use { fixture ->
            MockWebServer().use { server ->
                server.enqueue(capabilities(idempotent))
                val attempts = if (idempotent) 3 else 1
                repeat(attempts) { server.enqueue(disconnected()) }
                // Legacy servers may omit clientOriginId, but retain the
                // installation-prefixed admission key from the request.
                server.enqueue(conversationSnapshot(matchingRun(fixture.origin) - "clientOriginId"))
                server.enqueue(completed())
                server.start()
                val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
                val sessionId = connection.prefix + "remote-session"
                `when`(fixture.bindings.ensureBinding(sessionId, NA_AGENT_ID, cwd = "na-cloud")).thenReturn(42L)
                val result = withContext(connection) {
                    fixture.runtime().callConnected("session/prompt", mapOf("sessionId" to sessionId, "requestId" to "host-turn", "text" to "hello")) as Map<*, *>
                }

                assertEquals("end_turn", result["stopReason"])
                assertEquals("host-turn", result["turnId"])
                val requests = List(attempts + 3) { server.recorded() }
                assertEquals("/api/status", requests.first().path)
                val posts = requests.filter { it.method == "POST" }
                assertEquals(attempts, posts.size)
                assertTrue(posts.all { it.json()["clientMessageId"].asString == naCloudMessageId(fixture.origin, "host-turn") })
                assertTrue(posts.all { it.json()["clientOriginId"].asString == fixture.origin })
                assertTrue(posts.all { it.json() == posts.first().json() })
                assertEquals("GET", requests[attempts + 1].method)
                assertEquals("/api/conversations/remote-session", requests[attempts + 1].path)
                assertEquals("/api/runs/original-run", requests.last().path)
                assertEquals(attempts + 3, server.requestCount)
                assertEquals(1, fixture.emitted.size)
                assertEquals(1, mockingDetails(fixture.bindings).invocations.count { it.method.name == "ensureBinding" })
            }
        }
    }

    private suspend fun NaCloudRuntime.callConnected(method: String, args: Map<String, Any?>): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                javaClass.getDeclaredMethod("handleConnected", String::class.java, Map::class.java, Continuation::class.java)
                    .apply { isAccessible = true }.invoke(this, method, args, continuation)
            } catch (error: InvocationTargetException) {
                throw (error.targetException ?: error)
            }
        }
}
