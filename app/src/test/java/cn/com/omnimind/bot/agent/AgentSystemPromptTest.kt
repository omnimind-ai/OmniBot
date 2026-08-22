package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentSystemPromptTest {
    @Test
    fun buildMentionsWorkspaceVenvInsteadOfBreakingSystemPackages() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-1",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace/demo",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace/demo",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = emptyList(),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null,
            locale = PromptLocale.ZH_CN,
            terminalDistribution = TerminalDistribution.alpine
        )

        assertTrue(prompt.contains(".venv"))
        assertTrue(prompt.contains("uv"))
        assertTrue(prompt.contains("--copies"))
        assertTrue(prompt.contains("不要使用 `--break-system-packages`"))
        assertTrue(!prompt.contains("tools_search"))
        assertTrue(prompt.contains("完整的能力目录"))
        assertTrue(prompt.contains("完整工具 schema"))
        assertTrue(prompt.contains("工作区文件与产物"))
        assertTrue(prompt.contains("手机和 Android 原生操作"))
        assertTrue(prompt.contains("设备原生能力"))
        assertTrue(prompt.contains("当前工具列表中已经注入"))
        assertTrue(prompt.contains("完整、自足的 instruction"))
        assertTrue(prompt.contains("终端、高权限、删除以及需要用户确认的动作仍由父 Agent 处理"))
    }

    @Test
    fun buildCachedSystemPromptContentAddsEphemeralCacheControl() {
        val content = OmniAgentExecutor.buildCachedSystemPromptContent("system prompt")
        val blocks = content as JsonArray
        val firstBlock = blocks.first() as JsonObject

        assertEquals("\"text\"", firstBlock["type"].toString())
        assertEquals("\"system prompt\"", firstBlock["text"].toString())
        assertEquals(
            "\"ephemeral\"",
            (firstBlock["cache_control"] as JsonObject)["type"].toString()
        )
    }

    @Test
    fun exactTimeIsExposedAsAZeroArgumentTool() {
        val function = AgentToolDefinitions.contextTimeNowTool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject

        assertEquals("context_time_now", function["name"]?.jsonPrimitive?.content)
        assertEquals("object", parameters["type"]?.jsonPrimitive?.content)
        assertTrue((parameters["properties"] as JsonObject).isEmpty())
    }

    @Test
    fun buildUsesEnglishPromptWhenLocaleIsEnglish() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-1",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace/demo",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace/demo",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = emptyList(),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null,
            locale = PromptLocale.EN_US,
            terminalDistribution = TerminalDistribution.alpine
        )

        assertTrue(prompt.contains("You are an AI Agent operating inside the Alpine environment"))
        assertTrue(prompt.contains("File and artifact rules"))
        assertTrue(prompt.contains("Skills:"))
        assertTrue(!prompt.contains("tools_search"))
        assertTrue(prompt.contains("complete capability catalog"))
        assertTrue(prompt.contains("complete schemas for installed capabilities"))
        assertTrue(prompt.contains("workspace files and artifacts"))
        assertTrue(prompt.contains("phone and Android-native operations"))
        assertTrue(prompt.contains("device-native capability"))
        assertTrue(prompt.contains("Proactively use a listed"))
        assertTrue(prompt.contains("complete, self-contained instructions"))
        assertTrue(prompt.contains("The parent agent remains responsible"))
    }

    @Test
    fun buildUsesOnlySelectedUbuntuNameInModelFacingEnvironmentText() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-ubuntu",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace/demo",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace/demo",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = listOf(
                SkillIndexEntry(
                    id = "dynamic-skill",
                    name = "dynamic-skill",
                    description = "Runs in {{OMNIBOT_TERMINAL_DISTRIBUTION}}.",
                    rootPath = "/workspace/.omnibot/skills/dynamic-skill",
                    shellRootPath = "/workspace/.omnibot/skills/dynamic-skill",
                    skillFilePath = "/workspace/.omnibot/skills/dynamic-skill/SKILL.md",
                    shellSkillFilePath = "/workspace/.omnibot/skills/dynamic-skill/SKILL.md",
                    hasScripts = false,
                    hasReferences = false,
                    hasAssets = false,
                    hasEvals = false
                )
            ),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null,
            locale = PromptLocale.EN_US,
            terminalDistribution = TerminalDistribution.ubuntu
        )

        assertTrue(prompt.contains("inside the Ubuntu environment"))
        assertTrue(prompt.contains("description=Runs in Ubuntu."))
        assertTrue(!prompt.contains("Alpine"))
        assertTrue(!prompt.contains("{{OMNIBOT_TERMINAL_DISTRIBUTION}}"))
    }

    @Test
    fun buildKeepsTurnMemoryAndSkillBodiesOutOfCachedSystemPrompt() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-harness",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = emptyList(),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = listOf(
                ResolvedSkillContext(
                    skillId = "turn-only",
                    frontmatter = mapOf("name" to "turn-only"),
                    bodyMarkdown = "TURN_ONLY_SKILL_BODY",
                    triggerReason = "test"
                )
            ),
            memoryContext = WorkspaceMemoryPromptContext(
                soul = "SOUL_STAYS_STABLE",
                longTermMemory = "VOLATILE_LONG_TERM_MEMORY",
                todayShortMemory = "VOLATILE_DAILY_MEMORY",
                longTermIndexSummary = "VOLATILE_MEMORY_INDEX"
            ),
            locale = PromptLocale.EN_US,
            terminalDistribution = TerminalDistribution.alpine
        )

        assertTrue(prompt.contains("SOUL_STAYS_STABLE"))
        assertTrue(!prompt.contains("tools_search"))
        assertTrue(prompt.contains("memory capability"))
        assertTrue(!prompt.contains("skills_read"))
        assertTrue(!prompt.contains("memory_search"))
        assertTrue(!prompt.contains("memory_load"))
        assertTrue(!prompt.contains("[skills.loaded]"))
        assertTrue(!prompt.contains("[memory.context]"))
        assertTrue(!prompt.contains("TURN_ONLY_SKILL_BODY"))
        assertTrue(!prompt.contains("VOLATILE_LONG_TERM_MEMORY"))
        assertTrue(!prompt.contains("VOLATILE_DAILY_MEMORY"))
        assertTrue(!prompt.contains("VOLATILE_MEMORY_INDEX"))
    }
}
