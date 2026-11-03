package cn.com.omnimind.bot.agent.runtime

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.webchat.ConversationDomainService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class AgentSessionBindingOwnershipTest {
    private fun context(): Context {
        val app = RuntimeEnvironment.getApplication()
        DatabaseHelper.init(app)
        val assets = Mockito.mock(AssetManager::class.java)
        Mockito.`when`(assets.open(anyString())).thenAnswer { call ->
            File("src/main/assets", call.getArgument<String>(0)).inputStream()
        }
        return object : ContextWrapper(app) {
            override fun getAssets(): AssetManager = assets
            override fun getApplicationContext(): Context = this
        }
    }

    @Test
    fun generatedConversationHasItsExplicitOwnerBeforeBindingReturns() = runBlocking {
        val context = context()
        val repository = AgentSessionBindingRepository(context)
        val id = repository.ensureBinding(
            threadId = "opencode-owned-session",
            agentId = "opencode-acp",
            title = "Imported session",
            archived = true,
        )
        val conversation = requireNotNull(DatabaseHelper.getConversationById(id))
        assertEquals("Imported session", conversation.title)
        assertTrue(conversation.isArchived)
        assertEquals("opencode-owned-session", repository.getBindingByConversationId(id)?.threadId)
        assertEquals("opencode-acp", AcpAgentProfileStore(context).agentIdForConversation(id))
        assertEquals("opencode-acp", ConversationDomainService(context).getConversationPayload(id)?.get("agentId"))
    }

    @Test
    fun existingConversationCannotBeReassignedByAnotherRuntime() = runBlocking {
        val context = context()
        val domain = ConversationDomainService(context)
        val row = domain.createConversation("Keep history", "agent", summary = "Keep context", agentId = "xiaowan-acp")
        val id = (row.getValue("id") as Number).toLong()
        val before = DatabaseHelper.getConversationById(id)
        val repository = AgentSessionBindingRepository(context)
        val failure = runCatching {
            repository.ensureBinding("foreign-runtime-session", "opencode-acp", conversationId = id)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().startsWith("agent_owner_conflict:"))
        assertEquals(before, DatabaseHelper.getConversationById(id))
        assertEquals("xiaowan-acp", AcpAgentProfileStore(context).agentIdForConversation(id))
        assertNull(repository.getBindingByConversationId(id))
    }

    @Test
    fun scheduledSubagentCreationPersistsXiaowanBeforeItsSessionIsCreated() = runBlocking {
        val context = context()
        val domain = ConversationDomainService(context)
        val row = domain.createConversation(
            title = "Scheduled subagent",
            mode = "subagent",
            scheduledTaskId = "scheduled-owner-test",
            agentId = AcpAgentProfileStore.XIAOWAN_AGENT_ID,
        )
        val id = (row.getValue("id") as Number).toLong()
        val profiles = AcpAgentProfileStore(context)
        val repository = AgentSessionBindingRepository(context)
        assertEquals(AcpAgentProfileStore.XIAOWAN_AGENT_ID, profiles.agentIdForConversation(id))
        assertNull(repository.getBindingByConversationId(id))
        assertEquals(id, repository.ensureBinding(
            threadId = "scheduled-xiaowan-session",
            agentId = AcpAgentProfileStore.XIAOWAN_AGENT_ID,
            conversationId = id,
            conversationMode = "subagent",
        ))
        val stored = requireNotNull(DatabaseHelper.getConversationById(id))
        assertEquals("subagent", stored.mode)
        assertEquals("scheduled-owner-test", stored.scheduledTaskId)
        assertEquals(AcpAgentProfileStore.XIAOWAN_AGENT_ID, domain.getConversationPayload(id)?.get("agentId"))
    }

    @Test
    fun aReservedNaSessionCannotCreateHistoryUnderANewAccount() = runBlocking {
        val context = context()
        val before = DatabaseHelper.getConversationCount()
        Mockito.mockConstruction(NaConversationAccess::class.java) { access, _ ->
            Mockito.`when`(access.captureOwner()).thenReturn("na:2222222222222222:")
        }.use {
            val failure = runCatching {
                AgentSessionBindingRepository(context).ensureBinding(
                    threadId = "na:1111111111111111:reserved-session",
                    agentId = NA_AGENT_ID,
                    title = "Private old-account title",
                )
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().startsWith("agent_owner_conflict:"))
        }
        assertEquals(before, DatabaseHelper.getConversationCount())
        assertNull(DatabaseHelper.getAgentSessionBindingByThreadId("na:1111111111111111:reserved-session"))
    }
}
