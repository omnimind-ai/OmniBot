package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NaConversationAccessTest {
    @get:Rule val directory = TemporaryFolder()
    private val alice = NaCloudConnection("https://cloud.example.test", userId = "alice").prefix
    private val bob = NaCloudConnection("https://cloud.example.test", userId = "bob").prefix

    @Test fun accountSwitchHidesListsAndDirectReadsWithoutDeletingHistory() = runBlocking {
        val context = acpProfileStoreTestContext(directory.root)
        val profiles = AcpAgentProfileStore(context)
        profiles.bindConversation(1, NA_AGENT_ID)
        profiles.bindConversation(2, NA_AGENT_ID)
        profiles.bindConversation(3, AcpAgentProfileStore.XIAOWAN_AGENT_ID)
        val sessions = mapOf(1L to "${alice}one", 2L to "${bob}two")
        val history = mutableMapOf(1L to "Alice history", 2L to "Bob history", 3L to "Local/BYOK history")
        var current: String? = alice
        val access = NaConversationAccess(profiles, { sessions[it] }, { current })
        suspend fun visible() = history.keys.filter { access.canRead(it) }
        assertEquals(listOf(1L, 3L), visible())
        assertEquals("Alice history", access.read(1) { history[1] })
        current = null
        assertEquals(listOf(3L), visible())
        assertTrue(runCatching { access.read(1) { history[1] } }.isFailure)
        current = bob
        assertEquals(listOf(2L, 3L), visible())
        assertTrue(runCatching { access.read(1) { history[1] } }.isFailure)
        current = alice
        val reopened = NaConversationAccess(AcpAgentProfileStore(context), { sessions[it] }, { current })
        assertEquals("Alice history", reopened.read(1) { history[1] })
        assertEquals(3, history.size)
    }

    @Test fun inFlightHistoryResultCannotCrossAnAccountChange() = runBlocking {
        val profiles = AcpAgentProfileStore(acpProfileStoreTestContext(directory.root))
        profiles.bindConversation(1, NA_AGENT_ID)
        var current: String? = alice
        val access = NaConversationAccess(profiles, { "${alice}one" }, { current })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async {
            runCatching { access.read(1) { entered.complete(Unit); release.await(); "private snapshot" } }
        }
        entered.await()
        current = bob
        release.complete(Unit)
        assertTrue(pending.await().isFailure)
    }

    @Test fun pendingAndDetachedConversationsKeepTheirOriginalOwner() = runBlocking {
        val context = acpProfileStoreTestContext(directory.root)
        val profiles = AcpAgentProfileStore(context)
        profiles.bindConversation(1, NA_AGENT_ID)
        profiles.bindNaConversationOwner(1, alice)
        profiles.bindConversation(2, NA_AGENT_ID) // legacy, ownership cannot be proved
        val reopened = AcpAgentProfileStore(context)
        val access = NaConversationAccess(reopened, { null }, { alice })
        assertTrue(access.canRead(1))
        assertFalse(access.canRead(2))
        assertTrue(runCatching { reopened.bindNaConversationOwner(1, bob) }.isFailure)
        assertEquals(alice, reopened.naOwnerForConversation(1))
    }

    @Test fun legacySessionStillProtectsHistoryWithoutAConversationProfileBinding() = runBlocking {
        val profiles = AcpAgentProfileStore(acpProfileStoreTestContext(directory.root))
        val access = NaConversationAccess(profiles, { "${alice}legacy" }, { bob })
        assertFalse(access.canRead(10))
        assertTrue(runCatching { access.read(10) { "private legacy history" } }.isFailure)
    }
}
