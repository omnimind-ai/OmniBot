package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException

class NaHistorySyncTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun installationIdentitySurvivesRestartButIsDifferentOnAnotherPhone() {
        val phoneA = folder.newFolder("phone-a-no-backup")
        val first = naInstallationOrigin(phoneA)
        assertEquals(first, naInstallationOrigin(phoneA))
        assertNotEquals(first, naInstallationOrigin(folder.newFolder("phone-b-no-backup")))
    }

    @Test fun anotherPhonesEventsInvalidateSnapshotsWithoutSubmittingOrReplayingTurns() = runBlocking {
        var snapshots = 0
        var notifications = 0
        val refreshed = CompletableDeferred<Unit>()
        val job = launch {
            observeNaHistoryChanges(
                snapshot = { snapshots++; mapOf("eventCursor" to 7L) },
                subscribe = { cursor, receive ->
                    assertEquals(7L, cursor)
                    receive(mapOf("id" to 7L, "type" to "item.updated")) // duplicate
                    receive(mapOf("id" to 8L, "type" to "activity.created")) // not chat
                    receive(mapOf("id" to 9L, "type" to "message.created", "conversationId" to "phone-a"))
                    receive(mapOf("id" to 10L, "type" to "item.updated", "conversationId" to "phone-b"))
                    receive(mapOf("id" to 11L, "type" to "files.updated"))
                    receive(mapOf("id" to 12L, "type" to "run.started", "conversationId" to "phone-b"))
                    awaitCancellation()
                },
                checkOwner = {},
                changed = { if (++notifications == 2) refreshed.complete(Unit) },
                coalesceMillis = 1,
            )
        }
        withTimeout(2000) { refreshed.await() }
        job.cancelAndJoin()
        assertEquals(2, snapshots)
        assertEquals(2, notifications)
    }

    @Test fun reconnectAndCompactedCursorBootstrapAgainBeforeFollowingChanges() = runBlocking {
        var snapshots = 0
        var subscriptions = 0
        val caughtUp = CompletableDeferred<Unit>()
        val job = launch {
            observeNaHistoryChanges(
                snapshot = { mapOf("eventCursor" to (++snapshots * 100L)) },
                subscribe = { cursor, _ ->
                    if (++subscriptions == 1) {
                        assertEquals(100L, cursor)
                        throw IOException("HTTP 409 event_history_compacted")
                    }
                    assertEquals(200L, cursor)
                    caughtUp.complete(Unit)
                    awaitCancellation()
                },
                checkOwner = {}, changed = {}, retryMillis = 1,
            )
        }
        withTimeout(2000) { caughtUp.await() }
        job.cancelAndJoin()
        assertEquals(2, snapshots)
    }

    @Test fun accountSwitchRejectsLateFramesAndCancelsRefresh() = runBlocking {
        var ownerIsCurrent = true
        var notifications = 0
        val error = runCatching {
            observeNaHistoryChanges(
                snapshot = { mapOf("eventCursor" to 1L) },
                subscribe = { _, receive ->
                    ownerIsCurrent = false
                    receive(mapOf("id" to 2L, "type" to "message.created"))
                },
                checkOwner = { check(ownerIsCurrent) { "owner changed" } },
                changed = { notifications++ }, retryMillis = 1,
            )
        }.exceptionOrNull()
        assertEquals("owner changed", error?.message)
        assertEquals(1, notifications)
    }

    @Test fun sameMillisecondOnDifferentPhonesHasDifferentServerAdmissionIdentity() {
        assertNotEquals(naCloudMessageId("phone-a", "123-ai"), naCloudMessageId("phone-b", "123-ai"))
        assertEquals(naCloudMessageId("phone-a", "123-ai"), naCloudMessageId("phone-a", "123-ai"))
    }

    @Test fun onlyTheOriginatingPhoneMayUseLocalHostMessageAndTurnIdentities() {
        val run = mapOf("id" to "server-run", "clientMessageId" to "phone-a:123-ai",
            "clientOriginId" to "phone-a", "clientUserMessageId" to "123-user", "status" to "completed")
        val response = mapOf("conversation" to mapOf("id" to "conversation"), "runs" to listOf(run))
        fun turn(origin: String?) = (naThreadSnapshot("session", response, origin)["turns"] as List<*>).single() as Map<*, *>
        fun user(origin: String?) = (turn(origin)["items"] as List<*>).first() as Map<*, *>
        assertEquals("123-ai", turn("phone-a")["id"])
        assertEquals("123-user", user("phone-a")["hostMessageId"])
        assertEquals("phone-a:123-ai", turn("phone-b")["id"])
        assertNull(user("phone-b")["hostMessageId"])
        assertNull(user(null)["hostMessageId"])
        val legacy = response + ("runs" to listOf(run - "clientOriginId"))
        val legacyTurn = (naThreadSnapshot("session", legacy, "phone-a")["turns"] as List<*>).single() as Map<*, *>
        // Older servers ignore origin but retain the random installation prefix.
        assertEquals("123-ai", legacyTurn["id"])
        assertEquals("123-user", ((legacyTurn["items"] as List<*>).first() as Map<*, *>)["hostMessageId"])
        val original = legacy + ("runs" to listOf((run - "clientOriginId") + ("clientMessageId" to "123-ai")))
        val originalTurn = (naThreadSnapshot("session", original, "phone-a")["turns"] as List<*>).single() as Map<*, *>
        val originalUser = (originalTurn["items"] as List<*>).first() as Map<*, *>
        assertNull(originalUser["hostMessageId"])
        assertEquals("123-user", originalUser["legacyHostMessageId"])
    }

    @Test fun cloudListMetadataUsesServerTimeAndBoundsThePreview() {
        val row = mapOf("updatedAt" to "2026-11-04T01:02:03Z", "messageCount" to 12, "lastMessage" to "x".repeat(2000))
        val metadata = naConversationListMetadata(row)!!
        assertEquals(java.time.Instant.parse("2026-11-04T01:02:03Z").toEpochMilli(), metadata.updatedAt)
        assertEquals(12, metadata.messageCount)
        assertEquals(1000, metadata.preview?.length)
        assertNull(naConversationListMetadata(row + ("messageCount" to -1)))
    }
}
