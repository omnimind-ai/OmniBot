package cn.com.omnimind.bot.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class WorkspaceScheduledTaskContractTest {
    @Test
    fun `create owns identity and update cannot resurrect deleted tasks`() {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val storage = mutableMapOf<String, String?>()
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        `when`(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(mock(AlarmManager::class.java))
        `when`(prefs.getString(anyString(), nullable(String::class.java))).thenAnswer { invocation ->
            storage[invocation.getArgument<String>(0)] ?: invocation.getArgument<String?>(1)
        }
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), nullable(String::class.java))).thenAnswer { invocation ->
            storage[invocation.getArgument(0)] = invocation.getArgument(1)
            editor
        }
        mockStatic(PendingIntent::class.java).use { pending ->
            pending.`when`<PendingIntent> {
                PendingIntent.getBroadcast(any(), anyInt(), any(), anyInt())
            }.thenReturn(mock(PendingIntent::class.java))
            val scheduler = WorkspaceScheduledTaskScheduler(context)
            val input = mapOf<String, Any?>(
                "title" to "OOB disabled regression", "targetKind" to "subagent",
                "scheduleType" to "countdown", "countdownMinutes" to 60,
                "repeatDaily" to false, "enabled" to false,
                "notificationEnabled" to false, "subagentPrompt" to "Return cerulean."
            )
            val created = scheduler.createTask(input)
            val id = created["taskId"].toString()
            assertTrue(id.isNotBlank())
            assertEquals(false, created["enabled"])
            val second = scheduler.createTask(input + ("taskId" to id))
            assertNotEquals(id, second["taskId"])
            val reopened = WorkspaceScheduledTaskScheduler(context)
            assertEquals(2, reopened.listTasks().size)
            assertTrue((reopened.listTasks().single { it["taskId"] == id }["createdAt"] as Long) > 0)
            reopened.updateTask(mapOf("taskId" to id, "title" to "updated", "enabled" to false))
            assertEquals("updated", reopened.listTasks().single { it["taskId"] == id }["title"])
            assertTrue(reopened.deleteTask(id))
            assertFalse(reopened.deleteTask(id))
            assertTrue(runCatching { reopened.updateTask(input + ("taskId" to id)) }.exceptionOrNull() is IllegalArgumentException)
            assertFalse(reopened.listTasks().any { it["taskId"] == id })
        }
    }
}
