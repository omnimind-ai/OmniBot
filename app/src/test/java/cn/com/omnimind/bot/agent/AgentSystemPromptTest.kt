package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        assertTrue(prompt.contains("只有用户明确要求分派或并行"))
        assertTrue(prompt.contains("完整、自足的 instruction"))
        assertTrue(prompt.contains("仅当用户明确要求持久化信息"))
        assertTrue(prompt.contains("不要为了简短、去重或摘要而改写它"))
        assertTrue(!prompt.contains("只写客观、简短、可复用的信息"))
        assertTrue(!prompt.contains("不要等用户明确要求分派"))
    }

    @Test
    fun buildCachedSystemPromptContentUsesStandardTextContent() {
        val content = OmniAgentExecutor.buildCachedSystemPromptContent("system prompt")
        assertEquals(JsonPrimitive("system prompt"), content)
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
        assertTrue(prompt.contains("only when the user explicitly asks to delegate or parallelize"))
        assertTrue(prompt.contains("complete, self-contained instructions"))
        assertTrue(prompt.contains("only when the user explicitly asks to persist information"))
        assertTrue(prompt.contains("without shortening, deduplicating, or summarizing it"))
        assertTrue(!prompt.contains("keep notes concrete, short, reusable, and non-duplicative"))
        assertTrue(!prompt.contains("Proactively use a listed"))
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
    fun buildKeepsCompleteInstalledSkillDescriptionsAndDoesNotRequirePrivateToolTitles() {
        val longDescription = buildString {
            repeat(220) { append(('a'.code + it % 26).toChar()) }
            append(" COMPLETE_SKILL_DESCRIPTION")
        }
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-skill-description",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = listOf(
                SkillIndexEntry(
                    id = "complete-description",
                    name = "complete-description",
                    description = longDescription,
                    rootPath = "/workspace/.omnibot/skills/complete-description",
                    shellRootPath = "/workspace/.omnibot/skills/complete-description",
                    skillFilePath = "/workspace/.omnibot/skills/complete-description/SKILL.md",
                    shellSkillFilePath = "/workspace/.omnibot/skills/complete-description/SKILL.md",
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
            terminalDistribution = TerminalDistribution.alpine
        )

        assertTrue(prompt.contains(longDescription))
        assertTrue(!prompt.contains("tool_title"))
        assertTrue(!prompt.contains("4-12 word"))
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
    }
}
