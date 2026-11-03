package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.bot.agent.runtime.toolResultAcpPayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStickerCapabilityTest {
    @Test
    fun `sticker search is exposed with only a bounded keyword and optional format in both locales`() {
        for (locale in listOf(PromptLocale.ZH_CN, PromptLocale.EN_US)) {
            val definitions = AgentToolDefinitions.staticTools(locale)
            val definition = definitions.single {
                (it["function"] as JsonObject)["name"]?.jsonPrimitive?.content == "sticker_search"
            }
            val function = definition["function"] as JsonObject
            val parameters = function["parameters"] as JsonObject
            val properties = parameters["properties"] as JsonObject
            val keyword = properties["keyword"] as JsonObject
            val format = properties["format"] as JsonObject

            assertEquals(setOf("keyword", "format"), properties.keys)
            assertEquals(listOf("keyword"), (parameters["required"] as JsonArray).map { it.jsonPrimitive.content })
            assertEquals(false, parameters["additionalProperties"]?.jsonPrimitive?.booleanOrNull)
            assertEquals("string", keyword["type"]?.jsonPrimitive?.content)
            assertEquals(1, keyword["minLength"]?.jsonPrimitive?.intOrNull)
            assertEquals(24, keyword["maxLength"]?.jsonPrimitive?.intOrNull)
            assertEquals("auto", format["default"]?.jsonPrimitive?.content)
            assertEquals(listOf("auto", "static", "gif"), (format["enum"] as JsonArray).map { it.jsonPrimitive.content })
            assertFalse(function.containsKey("postToolRule"))
            assertTrue(AgentConversationModePolicy.filterToolDefinitionsForConversationMode(definitions, "chat_only").isEmpty())

            if (locale == PromptLocale.EN_US) {
                assertEquals("Find Chat Sticker", function["displayName"]?.jsonPrimitive?.content)
                assertTrue(function["description"]!!.jsonPrimitive.content.contains("has not been sent"))
                assertTrue(keyword["description"]!!.jsonPrimitive.content.contains("private information"))
            } else {
                assertEquals("搜索聊天表情包", function["displayName"]?.jsonPrimitive?.content)
                assertTrue(function["description"]!!.jsonPrimitive.content.contains("返回图片尚未发送"))
                assertTrue(keyword["description"]!!.jsonPrimitive.content.contains("隐私"))
            }
        }
        assertTrue("sticker_search" in AgentToolDefinitions.reservedToolNames())
    }

    @Test
    fun `sticker image marker survives official ACP artifact projection without image bytes`() {
        val artifact = stickerArtifact()
        val markdown = "![开心](omnibot://workspace/stickers/happy.gif \"sticker\")"
        assertEquals(markdown, artifact.renderMarkdown)
        assertEquals(markdown, artifact.toPayload()["renderMarkdown"])

        val result = ToolExecutionResult.ContextResult(
            toolName = "sticker_search",
            summaryText = "Found a sticker",
            previewJson = "{\"keyword\":\"开心\"}",
            rawResultJson = "{\"keyword\":\"开心\"}",
            artifacts = listOf(artifact),
        )
        val payload = toolResultAcpPayload(result)
        val projected = (payload["artifacts"] as JsonArray).single() as JsonObject
        assertEquals(markdown, projected["renderMarkdown"]?.jsonPrimitive?.content)
        assertEquals("image/gif", projected["mimeType"]?.jsonPrimitive?.content)
        assertEquals(artifact.id, projected["id"]?.jsonPrimitive?.content)
        assertFalse(payload.toString().contains("base64"))
    }

    @Test
    fun `sticker marker does not change ordinary images or non image artifacts`() {
        val artifact = stickerArtifact()
        assertEquals(
            "![开心](omnibot://workspace/stickers/happy.gif)",
            artifact.copy(sourceTool = "image_generate").renderMarkdown,
        )
        assertEquals(
            "[开心](omnibot://workspace/stickers/happy.gif)",
            artifact.copy(previewKind = "link", mimeType = "text/plain").renderMarkdown,
        )
        assertEquals(
            "![开心\\[鼓掌\\]](omnibot://workspace/stickers/happy.gif \"sticker\")",
            artifact.copy(title = "开心[鼓掌]").renderMarkdown,
        )
    }

    @Test
    fun `both prompt locales encourage contextual use with frequency privacy and fallback guidance`() {
        val chinese = prompt(PromptLocale.ZH_CN)
        assertTrue(chinese.contains("主动考虑使用"))
        assertTrue(chinese.contains("默认每轮最多发送一张"))
        assertTrue(chinese.contains("上一条助手回复已包含表情包"))
        assertTrue(chinese.contains("不要设定固定配额、随机概率"))
        assertTrue(chinese.contains("优先于这些默认频率建议"))
        assertTrue(chinese.contains("严肃讨论、紧急情况、悲伤倾诉和工作成果交付"))
        assertTrue(chinese.contains("不传用户原话、姓名、联系方式或其他隐私"))
        assertTrue(chinese.contains("不循环搜索或重试"))
        assertTrue(chinese.contains("artifact.renderMarkdown"))
        assertTrue(chinese.contains("只有元数据时不要声称看过图片内容"))

        val english = prompt(PromptLocale.EN_US)
        assertTrue(english.contains("Proactively consider"))
        assertTrue(english.contains("at most one sticker per turn"))
        assertTrue(english.contains("previous assistant reply contained a sticker"))
        assertTrue(english.contains("Do not impose a fixed quota, random probability"))
        assertTrue(english.contains("take priority over these default frequency suggestions"))
        assertTrue(english.contains("serious discussions, emergencies, grief, or work deliverables"))
        assertTrue(english.contains("never user messages, names, contact details, or other private information"))
        assertTrue(english.contains("Do not loop searches or retries"))
        assertTrue(english.contains("artifact.renderMarkdown"))
        assertTrue(english.contains("only metadata is available"))
        assertTrue(english.contains("Keep your AI identity"))
        assertTrue(chinese.contains("USER_SOUL_PREFERENCE"))
        assertTrue(english.contains("USER_SOUL_PREFERENCE"))
    }

    private fun stickerArtifact() = ArtifactRef(
        id = "happy-sticker",
        uri = "omnibot://workspace/stickers/happy.gif",
        title = "开心",
        mimeType = "image/gif",
        size = 1024,
        sourceTool = "sticker_search",
        workspacePath = "/workspace/stickers/happy.gif",
        androidPath = "/data/user/0/test/workspace/stickers/happy.gif",
        previewKind = "image",
    )

    private fun prompt(locale: PromptLocale): String = AgentSystemPrompt.build(
        workspace = AgentWorkspaceDescriptor(
            id = "sticker-conversation",
            rootPath = "/workspace",
            androidRootPath = "/data/user/0/test/workspace",
            uriRoot = "omnibot://workspace",
            currentCwd = "/workspace",
            androidCurrentCwd = "/data/user/0/test/workspace",
            shellRootPath = "/workspace",
            retentionPolicy = "shared_root",
        ),
        installedSkills = emptyList(),
        skillsRootShellPath = "/workspace/.omnibot/skills",
        skillsRootAndroidPath = "/data/user/0/test/workspace/.omnibot/skills",
        resolvedSkills = emptyList(),
        memoryContext = WorkspaceMemoryPromptContext(
            soul = "USER_SOUL_PREFERENCE",
            longTermMemory = "",
            todayShortMemory = "",
        ),
        locale = locale,
    )
}
