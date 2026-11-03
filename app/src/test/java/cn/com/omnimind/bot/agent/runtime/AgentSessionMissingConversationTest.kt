package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import cn.com.omnimind.baselib.database.ConversationDao
import cn.com.omnimind.baselib.database.DatabaseHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class AgentSessionMissingConversationTest {
    @Test fun aLateSessionBindingCannotRecreateADeletedConversation() = runBlocking {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        // Room is an implementation dependency of baselib, so keep the
        // database fixture behind reflection at the Android module boundary.
        val databaseClass = Class.forName("cn.com.omnimind.baselib.database.AppDatabase")
        val database = mock(databaseClass)
        val conversations = mock(ConversationDao::class.java)
        `when`(databaseClass.getMethod("conversationDao").invoke(database)).thenReturn(conversations)
        val databaseField = DatabaseHelper.javaClass.getDeclaredField("database").apply { isAccessible = true }
        val previous = databaseField.get(null)
        databaseField.set(null, database)
        try {
            try {
                AgentSessionBindingRepository(context).ensureBinding("late-session", "xiaowan-acp", conversationId = 42L)
                fail("A deleted Conversation must not be replaced by a new empty row")
            } catch (error: IllegalArgumentException) {
                assertEquals("Conversation 42 no longer exists.", error.message)
            }
            assertEquals(listOf("getById"), mockingDetails(conversations).invocations.map { it.method.name })
            assertEquals(listOf("conversationDao"), mockingDetails(database).invocations.map { it.method.name })
        } finally { databaseField.set(null, previous) }
    }
}
