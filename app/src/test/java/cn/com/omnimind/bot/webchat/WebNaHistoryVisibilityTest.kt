package cn.com.omnimind.bot.webchat

import cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStore
import cn.com.omnimind.bot.agent.runtime.NaCloudConnection
import cn.com.omnimind.bot.agent.runtime.NaConversationAccess
import cn.com.omnimind.bot.agent.runtime.NA_AGENT_ID
import cn.com.omnimind.bot.agent.runtime.acpProfileStoreTestContext
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebNaHistoryVisibilityTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `logout while constructing a mirror suppresses history and permits terminal cleanup`() = runBlocking {
        val profiles = AcpAgentProfileStore(acpProfileStoreTestContext(directory.root))
        profiles.bindConversation(1, NA_AGENT_ID)
        val alice = NaCloudConnection("https://cloud.example.test", userId = "alice").prefix
        var current: String? = alice
        val access = NaConversationAccess(profiles, { "${alice}session" }, { current })
        val owner = access.captureOwner()
        val name = "na-visibility-${UUID.randomUUID()}"
        val received = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            RealtimeHub.stream().filter { it.event == name }.take(1).collect {
                received += it.data.getValue("kind") as String
            }
        }
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var cleanedUp = false
        val publication = launch(start = CoroutineStart.UNDISPATCHED) {
            RealtimeHub.publishSnapshot(name, { access.canRead(1, owner) }) {
                entered.complete(Unit)
                released.await()
                mapOf("kind" to "Alice's private history")
            }
            cleanedUp = true // Same continuation as the ACP terminal cleanup.
        }
        entered.await()
        current = null
        released.complete(Unit)
        publication.join()
        var readAfterLogout = false
        RealtimeHub.publishSnapshot(name, { access.canRead(1) }) {
            readAfterLogout = true
            mapOf("kind" to "must not be read")
        }
        RealtimeHub.publish(name, mapOf("kind" to "terminal-cleanup-complete"))
        withTimeout(5_000) { collector.join() }
        assertTrue(cleanedUp)
        assertFalse(readAfterLogout)
        assertEquals(listOf("terminal-cleanup-complete"), received)
    }

    @Test fun `queued Na snapshot is rejected after account switch without dropping local history`() = runBlocking {
        val profiles = AcpAgentProfileStore(acpProfileStoreTestContext(directory.root))
        profiles.bindConversation(1, NA_AGENT_ID)
        profiles.bindConversation(2, AcpAgentProfileStore.XIAOWAN_AGENT_ID)
        val alice = NaCloudConnection("https://cloud.example.test", userId = "alice").prefix
        val bob = NaCloudConnection("https://cloud.example.test", userId = "bob").prefix
        var current: String? = alice
        val access = NaConversationAccess(profiles, { if (it == 1L) "${alice}session" else null }, { current })
        val owner = access.captureOwner()
        val name = "na-queue-${UUID.randomUUID()}"
        val received = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            RealtimeHub.stream().filter { it.event == name }.take(2).collect {
                received += it.data.getValue("kind") as String
            }
        }
        val blocked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        RealtimeHub.publish(name, mapOf("kind" to "blocker"), isVisible = {
            blocked.complete(Unit)
            release.await()
            true
        })
        blocked.await()
        RealtimeHub.publishSnapshot(name, { access.canRead(1, owner) }) {
            mapOf("kind" to "queued Alice secret")
        }
        current = bob
        RealtimeHub.publish(name, mapOf("kind" to "local history"), isVisible = { access.canRead(2, owner) })
        release.complete(Unit)
        withTimeout(5_000) { collector.join() }
        assertEquals(listOf("blocker", "local history"), received)
    }
}
