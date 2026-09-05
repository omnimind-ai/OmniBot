package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolDefinitionsMusicTest {

    @Test
    fun `music playback tool is exposed in static tools`() {
        val toolNames = AgentToolDefinitions.staticTools()
            .mapNotNull { definition ->
                ((definition["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull)
            }

        assertTrue(toolNames.contains("music_playback_control"))
    }

    @Test
    fun `english browser tool metadata is localized without a static tab cap`() {
        val browserTool = AgentToolDefinitions.staticTools(PromptLocale.EN_US)
            .first { ((it["function"] as JsonObject)["name"]?.jsonPrimitive?.contentOrNull) == "browser_use" }
        val function = browserTool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val required = parameters["required"] as JsonArray

        assertEquals("Browser Action", function["displayName"]?.jsonPrimitive?.contentOrNull)
        val description = function["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue(description.contains("Control an off-screen browser."))
        assertFalse(description.contains("up to 3 tabs"))
        assertTrue("tool_title" !in properties)
        assertEquals(listOf("action"), required.map { it.jsonPrimitive.contentOrNull })
    }

    @Test
    fun `browser get cookies keywords schema declares a concrete type`() {
        val browserTool = AgentToolDefinitions.staticTools(PromptLocale.ZH_CN)
            .first { ((it["function"] as JsonObject)["name"]?.jsonPrimitive?.contentOrNull) == "browser_use" }
        val function = browserTool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val keywords = properties["keywords"] as JsonObject

        assertEquals("string", keywords["type"]?.jsonPrimitive?.contentOrNull)
        assertTrue(
            keywords["description"]?.jsonPrimitive?.contentOrNull
                ?.contains("get_cookies 的 cookie 名过滤关键词") == true
        )
    }

    @Test
    fun `schedule subagent tool description requires execution prompt instead of scheduling wording`() {
        val scheduleTool = AgentToolDefinitions.staticTools(PromptLocale.ZH_CN)
            .first { ((it["function"] as JsonObject)["name"]?.jsonPrimitive?.contentOrNull) == "schedule_task_create" }
        val function = scheduleTool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val subagentPrompt = properties["subagentPrompt"] as JsonObject

        assertTrue(
            function["description"]?.jsonPrimitive?.contentOrNull
                ?.contains("targetKind=subagent") == true
        )
        assertTrue(
            subagentPrompt["description"]?.jsonPrimitive?.contentOrNull
                ?.contains("真正要完成的动作") == true
        )
    }

    @Test
    fun `artifact ref treats pdf and html as inline renderable resources`() {
        val pdf = ArtifactRef(
            id = "pdf",
            uri = "omnibot://workspace/docs/spec.pdf",
            title = "spec.pdf",
            mimeType = "application/pdf",
            size = 128,
            sourceTool = "test",
            workspacePath = "/workspace/docs/spec.pdf",
            androidPath = "/tmp/spec.pdf",
            previewKind = "pdf"
        )
        val html = pdf.copy(
            id = "html",
            uri = "omnibot://workspace/docs/index.html",
            title = "index.html",
            mimeType = "text/html",
            androidPath = "/tmp/index.html",
            previewKind = "html"
        )

        assertEquals("pdf", pdf.embedKind)
        assertTrue(pdf.inlineRenderable)
        assertEquals("[spec.pdf](omnibot://workspace/docs/spec.pdf)", pdf.renderMarkdown)

        assertEquals("html", html.embedKind)
        assertTrue(html.inlineRenderable)
        assertEquals("[index.html](omnibot://workspace/docs/index.html)", html.renderMarkdown)
    }
}
