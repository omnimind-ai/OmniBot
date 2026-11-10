package cn.com.omnimind.bot.agent.personal

import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.bot.agent.AgentToolDefinitions
import cn.com.omnimind.bot.agent.AgentEventAdapter
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PersonalContextQueryTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun `page reports continuation only after observing an extra row`() {
        val page = ContextPage.from(json("""{"limit":2,"offset":4}"""))
        val rows = (1..3).map { mapOf<String, Any?>("id" to it) }
        assertEquals(6, page.result("test", rows)["nextOffset"])
        assertEquals(2, page.result("test", rows)["count"])
        assertEquals(false, page.result("test", rows.take(2))["hasMore"])
        assertNull(page.result("test", rows.take(2))["nextOffset"])
    }

    @Test fun `invalid page sizes are rejected rather than silently clamped`() {
        for (text in listOf("""{"limit":0}""", """{"limit":201}""", """{"offset":-1}""", """{"limit":"wrong"}""")) {
            assertThrows(IllegalArgumentException::class.java) { ContextPage.from(json(text)) }
        }
    }

    @Test fun `time filters honor offsets and reject inverted intervals`() {
        val range = ContextTimeRange.from(json("""{"startAt":"2026-10-13T08:00:00+08:00","endAt":"2026-10-14T00:00:00Z"}"""))
        assertEquals(Instant.parse("2026-10-13T00:00:00Z").toEpochMilli(), range.start)
        assertThrows(IllegalArgumentException::class.java) { ContextTimeRange.from(json("""{"startAt":"2026-10-15T00:00:00Z","endAt":"2026-10-14T00:00:00Z"}""")) }
    }

    @Test fun `search treats SQL wildcards as literal text`() {
        assertEquals("%a\\%b\\_c\\\\d%", likePattern("a%b_c\\d"))
    }

    @Test fun `missing personal permission stays a tool result not a terminal turn`() {
        val adapter = AgentEventAdapter(Json)
        val denied = ToolExecutionResult.ContextResult("contacts_query", "Permission required", "{}", "{}", false)
        assertFalse(adapter.isConversationStoppingResult(denied))
        assertTrue(adapter.hasUserVisibleOutput(denied))
    }

    @Test fun `contact updates distinguish untouched and cleared fields`() {
        val patch = ContactPatch.from(json("""{"phones":[],"organization":"Example"}"""), false)
        assertEquals(emptyList<String>(), patch.phones)
        assertNull(patch.emails)
        assertNull(patch.name)
        assertEquals("Example", patch.organization)
        assertThrows(IllegalArgumentException::class.java) { ContactPatch.from(json("{}"), false) }
        assertThrows(IllegalArgumentException::class.java) { ContactPatch.from(json("""{"name":""}"""), true) }
        assertThrows(IllegalArgumentException::class.java) { ContactPatch.from(json("""{"phones":[42]}"""), false) }
    }

    @Test fun `personal tools enter the native catalog once with calendar compatibility`() {
        val definitions = AgentToolDefinitions.staticTools(PromptLocale.EN_US)
        val names = definitions.map { it.getValue("function").jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.containsAll(PersonalContextTools.names))
        assertFalse(PersonalContextTools.names.any { it.contains("health") || it.contains("send_sms") })
        val list = definitions.first { it.getValue("function").jsonObject.getValue("name").jsonPrimitive.content == "calendar_event_list" }
        val parameters = list.getValue("function").jsonObject.getValue("parameters").jsonObject
        assertTrue(parameters.getValue("properties").jsonObject.containsKey("offset"))
        val notifications = definitions.first { it.getValue("function").jsonObject.getValue("name").jsonPrimitive.content == "context_notifications_query" }
        val notificationsDescription = notifications.getValue("function").jsonObject.getValue("description").jsonPrimitive.content
        assertTrue(notificationsDescription.contains("Proactively query read-only context when helpful"))
        assertFalse(notificationsDescription.contains("Query only for the user's task."))
    }
}
