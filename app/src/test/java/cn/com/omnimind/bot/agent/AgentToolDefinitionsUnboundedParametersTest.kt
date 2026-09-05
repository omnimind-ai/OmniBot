package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolDefinitionsUnboundedParametersTest {
    @Test
    fun `tools do not advertise application character truncation`() {
        for (locale in listOf(PromptLocale.ZH_CN, PromptLocale.EN_US)) {
            for (tool in AgentToolDefinitions.staticTools(locale)) {
                val function = tool["function"] as JsonObject
                val properties = (function["parameters"] as? JsonObject)?.get("properties") as? JsonObject
                assertFalse("${function["name"]} exposes maxChars", properties?.containsKey("maxChars") == true)
            }
        }
    }

    @Test
    fun `app query does not advertise a host result cap`() {
        val description = propertyDescription(
            toolName = "context_apps_query",
            propertyName = "limit",
            locale = PromptLocale.EN_US,
        )

        assertTrue(description.contains("When omitted"))
        assertFalse(description.contains("Default 20"))
        assertFalse(description.contains("range 1-100"))
    }

    @Test
    fun `file listing leaves result count and depth to the execution environment`() {
        val depth = propertyDescription("file_list", "maxDepth", PromptLocale.EN_US)
        val count = propertyDescription("file_list", "limit", PromptLocale.EN_US)

        assertTrue(depth.contains("execution environment"))
        assertTrue(count.contains("complete result"))
        assertFalse(depth.contains("range 1-6"))
        assertFalse(count.contains("range 1-1000"))
    }

    @Test
    fun `memory search does not advertise an application result cap`() {
        val description = propertyDescription("memory_search", "limit", PromptLocale.EN_US)

        assertTrue(description.contains("complete result"))
        assertFalse(description.contains("Default 8"))
        assertFalse(description.contains("range 1-20"))
    }

    @Test
    fun `calendar event list does not advertise an application result cap`() {
        val chinese = propertyDescription("calendar_event_list", "limit", PromptLocale.ZH_CN)
        val english = propertyDescription("calendar_event_list", "limit", PromptLocale.EN_US)

        assertTrue(chinese.contains("完整结果"))
        assertTrue(english.contains("complete result"))
        assertFalse(chinese.contains("默认 50"))
        assertFalse(english.contains("Default 50"))
        assertFalse(chinese.contains("1-200"))
        assertFalse(english.contains("1-200"))
    }

    @Test
    fun `memory persistence is available without a per turn host policy`() {
        val description = toolDescription("memory_write_daily", PromptLocale.ZH_CN)

        assertTrue(description.contains("需要跨会话保留"))
        assertFalse(description.contains("每轮的默认动作"))
        assertFalse(description.contains("宁可多写"))
    }

    @Test
    fun `file search and skills list do not advertise host result caps`() {
        val descriptions = listOf(
            propertyDescription("file_search", "maxResults", PromptLocale.EN_US),
            propertyDescription("skills_list", "limit", PromptLocale.EN_US),
        )

        descriptions.forEach { description ->
            assertTrue(description.contains("complete result"))
            assertFalse(description.contains("Default 50"))
            assertFalse(description.contains("range 1-200"))
        }
    }

    private fun propertyDescription(
        toolName: String,
        propertyName: String,
        locale: PromptLocale,
    ): String {
        val tool = (AgentToolDefinitions.staticTools(locale) + AgentToolDefinitions.memoryTools(locale))
            .single { (it["function"] as JsonObject)["name"]?.jsonPrimitive?.content == toolName }
        val function = tool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val property = properties[propertyName] as JsonObject
        return property["description"]!!.jsonPrimitive.content
    }

    private fun toolDescription(toolName: String, locale: PromptLocale): String {
        val tool = AgentToolDefinitions.memoryTools(locale)
            .single { (it["function"] as JsonObject)["name"]?.jsonPrimitive?.content == toolName }
        return ((tool["function"] as JsonObject)["description"]!!).jsonPrimitive.content
    }
}
