@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.SessionId
import com.tencent.mmkv.MMKV
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*

class XiaowanDraftSessionRestoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun ownedDraftReachesProviderResolutionAfterReconnectWithoutConversationHistory() = runBlocking {
        val store = AcpAgentProfileStore(acpProfileStoreTestContext(folder.root))
        store.bindSession("draft", AcpAgentProfileStore.XIAOWAN_AGENT_ID)
        for (load in listOf(false, true)) {
            val providerBoundary = IllegalStateException("Provider resolution reached")
            val support = support(store, providerBoundary)
            // No native storage is needed: stop at the real Provider boundary,
            // after the official resume/load admission being regressed here.
            mockStatic(MMKV::class.java).use {
                val error = runCatching {
                    restore(support, "draft", load)
                }.exceptionOrNull()
                assertSame(providerBoundary, error)
            }
            assertEquals(AcpAgentProfileStore.XIAOWAN_AGENT_ID, store.agentIdForSession("draft"))
        }
    }

    @Test fun unknownForeignAndDeletedDraftsRemainUnrestorable() = runBlocking {
        val store = AcpAgentProfileStore(acpProfileStoreTestContext(folder.root))
        store.bindSession("foreign", AcpAgentProfileStore.CODEX_AGENT_ID)
        store.bindSession("deleted", AcpAgentProfileStore.XIAOWAN_AGENT_ID)
        store.unbindSession("deleted")
        val support = support(store, AssertionError("Must not resolve a Provider"))
        for (id in listOf("unknown", "foreign", "deleted")) {
            for (load in listOf(false, true)) {
                val error = runCatching { restore(support, id, load) }.exceptionOrNull()
                assertTrue(error is IllegalArgumentException)
                assertEquals("Xiaowan ACP session does not exist: $id", error?.message)
            }
        }
    }

    private suspend fun restore(support: AgentSupport, id: String, load: Boolean) {
        val parameters = SessionCreationParameters("/workspace", emptyList())
        if (load) support.loadSession(SessionId(id), parameters)
        else support.resumeSession(SessionId(id), parameters)
    }

    private fun support(store: AcpAgentProfileStore, providerBoundary: Throwable): AgentSupport {
        @Suppress("UNCHECKED_CAST")
        val type = Class.forName("cn.com.omnimind.bot.agent.runtime.XiaowanAgentSupport") as Class<AgentSupport>
        val support = mock(type, CALLS_REAL_METHODS)
        fun set(name: String, value: Any) {
            type.getDeclaredField(name).apply { isAccessible = true }.set(support, value)
        }
        val owner: suspend (String) -> Boolean = {
            store.agentIdForSession(it) == AcpAgentProfileStore.XIAOWAN_AGENT_ID
        }
        val history: suspend (String) -> Long? = { null }
        val provider: suspend () -> Unit = { throw providerBoundary }
        set("isXiaowanSession", owner)
        set("conversationIdProvider", history)
        set("ensureSharedProviderBinding", provider)
        set("activeSessions", ConcurrentHashMap<String, Any>())
        return support
    }
}
