package cn.com.omnimind.bot.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import cn.com.omnimind.bot.agent.tool.handlers.decodeImageWriteContentForFileName
import cn.com.omnimind.bot.agent.tool.handlers.ImageGenerationToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.normalizeSvgWriteContentForFileName
import java.io.File
import java.net.InetAddress
import java.nio.file.Files

class SkillRuntimeBehaviorTest {
    private fun entry(
        id: String,
        description: String,
        enabled: Boolean = true,
        installed: Boolean = true
    ): SkillIndexEntry {
        return SkillIndexEntry(
            id = id,
            name = id,
            description = description,
            rootPath = "/tmp/$id",
            shellRootPath = "/workspace/.omnibot/skills/$id",
            skillFilePath = "/tmp/$id/SKILL.md",
            shellSkillFilePath = "/workspace/.omnibot/skills/$id/SKILL.md",
            hasScripts = false,
            hasReferences = false,
            hasAssets = false,
            hasEvals = false,
            enabled = enabled,
            installed = installed
        )
    }

    @Test
    fun resolveMatchesSkipsDisabledAndUninstalledSkills() {
        val matches = SkillTriggerMatcher.resolveMatches(
            userMessage = "请用 skill-creator 帮我创建一个新的技能",
            entries = listOf(
                entry(
                    id = "skill-creator",
                    description = "用于创建和更新技能",
                    enabled = false
                ),
                entry(
                    id = "skill-creator",
                    description = "用于创建和更新技能",
                    installed = false
                )
            )
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun resolveMatchesFindSkillsFromChineseTriggerPhrase() {
        val matches = SkillTriggerMatcher.resolveMatches(
            userMessage = "帮我找个 skill 来处理 changelog",
            entries = listOf(
                entry(
                    id = "find-install-skills",
                    description = "Find and install relevant Omnibot skills. Use when the user asks \"找个 skill\", \"有没有这个功能的 skill\", \"find a skill for X\", \"is there a skill for X\", or wants to extend the agent with an installable workflow."
                )
            )
        )

        assertTrue(matches.any { it.entry.id == "find-install-skills" })
    }

    @Test
    fun resolveMatchesInstallCodexPetFromCliRequest() {
        val matches = SkillTriggerMatcher.resolveMatches(
            userMessage = "帮我执行 npx codex-pets add claude-pixel 安装宠物",
            entries = listOf(
                entry(
                    id = "install-codex-pet",
                    description = "Install shared Codex pet packages into Omnibot. Use for commands such as \"npx codex-pets add\"."
                )
            )
        )

        assertTrue(matches.any { it.entry.id == "install-codex-pet" })
    }

    @Test
    fun builtinInstallCodexPetSkillUsesCompatibleWorkspacePath() {
        val skillFile = File("src/main/assets/builtin_skills/install-codex-pet/SKILL.md")
        val text = skillFile.readText()

        assertTrue(text.contains("CODEX_HOME=/workspace/.omnibot"))
        assertFalse(text.contains("npx --yes"))
        assertTrue(text.contains("AGENT_RUNTIME_CODEX_PETS_LOCK_REQUIRED"))
        assertTrue(text.contains("validate_codex_pet.sh"))
        assertTrue(File("src/main/assets/builtin_skills/install-codex-pet/scripts/validate_codex_pet.sh").isFile)
        assertFalse(File("src/main/assets/builtin_skills/hatch-pet").exists())
    }

    @Test
    fun builtinSkillDiscoveryFailsClosedWithoutAuditedCliLock() {
        val skillFile = File("src/main/assets/builtin_skills/find-install-skills/SKILL.md")
        val text = skillFile.readText()

        assertTrue(text.contains("AGENT_RUNTIME_SKILLS_CLI_LOCK_REQUIRED"))
        assertFalse(text.contains("npx", ignoreCase = true))
        assertTrue(text.contains("skills_list"))
    }

    @Test
    fun byokImageGenerationKeepsItsDocumentedDefaultModel() {
        assertEquals("gpt-image-2", ImageGenerationToolHandler.DEFAULT_IMAGE_MODEL)
        assertTrue(AgentToolDefinitions.imageGenerateTool.toString().contains("gpt-image-2"))
    }

    @Test
    fun imageGenerationEndpointSupportsBaseAndFullEndpointUrls() {
        assertEquals(
            "https://cloud.omnimind.com.cn/v1/images/generations",
            ImageGenerationToolHandler.resolveImageGenerationEndpoint(
                "https://cloud.omnimind.com.cn",
                "sk-test"
            )
        )
        assertEquals(
            "https://cloud.omnimind.com.cn/v1/images/generations",
            ImageGenerationToolHandler.resolveImageGenerationEndpoint(
                "https://cloud.omnimind.com.cn/v1/images/generations",
                "sk-test"
            )
        )
        assertEquals(
            "https://cloud.omnimind.com.cn/custom/images",
            ImageGenerationToolHandler.resolveImageGenerationEndpoint(
                "https://cloud.omnimind.com.cn/custom/images#",
                "sk-test"
            )
        )
    }

    @Test
    fun officialImageDownloadRejectsInsecureAndPrivateUrls() {
        assertTrue(
            ImageGenerationToolHandler.isSafePlatformDownloadUrl(
                "https://cdn.example.com/generated/image.png"
            )
        )
        assertFalse(ImageGenerationToolHandler.isSafePlatformDownloadUrl("http://cdn.example.com/a.png"))
        assertFalse(ImageGenerationToolHandler.isSafePlatformDownloadUrl("https://127.0.0.1/a.png"))
        assertFalse(ImageGenerationToolHandler.isSafePlatformDownloadUrl("https://192.168.1.8/a.png"))
        assertFalse(ImageGenerationToolHandler.isSafePlatformDownloadUrl("https://device.local/a.png"))
        assertTrue(
            ImageGenerationToolHandler.isPublicPlatformAddress(
                InetAddress.getByName("8.8.8.8")
            )
        )
        assertFalse(
            ImageGenerationToolHandler.isPublicPlatformAddress(
                InetAddress.getByName("100.64.0.1")
            )
        )
        assertFalse(
            ImageGenerationToolHandler.isPublicPlatformAddress(
                InetAddress.getByName("fc00::1")
            )
        )
    }

    @Test
    fun decodeImageWriteContentAcceptsBase64DataUriForBinaryPetImages() {
        val pngBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D
        )
        val decoded = decodeImageWriteContentForFileName(
            "current.png",
            "data:image/png;base64,iVBORw0KGgoAAAAN"
        )

        assertArrayEquals(pngBytes, decoded)
        assertEquals(null, decodeImageWriteContentForFileName("current.png", "not-image-data"))
        assertEquals(null, decodeImageWriteContentForFileName("current.svg", "iVBORw0KGgoAAAAN"))
    }

    @Test
    fun normalizeSvgWriteContentExtractsSvgFromMarkdownFence() {
        val svg = normalizeSvgWriteContentForFileName(
            "current.svg",
            """
                ```svg
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                  <circle cx="12" cy="12" r="6"/>
                </svg>
                ```
            """.trimIndent()
        )

        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.endsWith("</svg>"))
        assertFalse(svg.contains("```"))
    }

    @Test
    fun normalizeSvgWriteContentInlinesSimpleClassStyles() {
        val svg = normalizeSvgWriteContentForFileName(
            "current.svg",
            """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                  <defs>
                    <style>
                      .body { fill: #ffdc50; }
                      .accent { fill: #8b5a2b; stroke: #111111; stroke-width: 2; }
                    </style>
                  </defs>
                  <rect class="body" x="4" y="4" width="16" height="16"/>
                  <circle class="accent" cx="12" cy="12" r="4"/>
                </svg>
            """.trimIndent()
        )

        assertFalse(svg.contains("<style>"))
        assertFalse(svg.contains("""class="""))
        assertTrue(svg.contains("""fill="#ffdc50""""))
        assertTrue(svg.contains("""fill="#8b5a2b""""))
        assertTrue(svg.contains("""stroke="#111111""""))
    }

    @Test
    fun buildOmitsDisabledAndUninstalledSkillsFromPromptIndex() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-1",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = listOf(
                entry(
                    id = "active-skill",
                    description = "可正常使用的技能"
                ),
                entry(
                    id = "disabled-skill",
                    description = "已禁用的技能",
                    enabled = false
                ),
                entry(
                    id = "removed-skill",
                    description = "已删除但可恢复的技能",
                    installed = false
                )
            ),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null
        )

        assertTrue(prompt.contains("id=active-skill"))
        assertFalse(prompt.contains("id=disabled-skill"))
        assertFalse(prompt.contains("id=removed-skill"))
    }

    @Test
    fun failureHookWritesErrorsLogAndFindsRelatedHints() {
        val skillsRoot = Files.createTempDirectory("self-improving-skill-test").toFile()
        val skillRoot = skillsRoot.resolve(SelfImprovingSkillFailureHook.SKILL_ID)
        val dataDir = skillRoot.resolve("data").apply { mkdirs() }
        val errorsFile = dataDir.resolve("ERRORS.md")
        errorsFile.writeText(
            """
            # Errors

            ## [ERR-20260409-OLD] terminal_execute

            **记录时间**: 2026-04-09T00:00:00Z
            **优先级**: high
            **状态**: pending
            **领域**: runtime

            ### 摘要
            旧的终端失败

            ---
            """.trimIndent() + "\n"
        )

        val payload = SelfImprovingSkillFailureHook.capture(
            skillsRoot = skillsRoot,
            skill = ResolvedSkillContext(
                skillId = SelfImprovingSkillFailureHook.SKILL_ID,
                frontmatter = mapOf("name" to SelfImprovingSkillFailureHook.SKILL_ID),
                bodyMarkdown = "先检查失败原因\n不要重复相同步骤",
                triggerReason = "test"
            ),
            userMessage = "修复终端命令报错",
            toolName = "terminal_execute",
            toolType = "terminal",
            argumentsJson = """{"command":"bad cmd"}""",
            result = ToolExecutionResult.TerminalResult(
                toolName = "terminal_execute",
                summaryText = "命令执行失败",
                previewJson = "{}",
                rawResultJson = """{"stderr":"not found"}""",
                success = false
            )
        )

        assertNotNull(payload)
        assertTrue(errorsFile.readText().contains("命令执行失败"))
        assertTrue(payload!!.guidance.contains("self-improving-agent"))
        assertTrue(payload.relatedHints.any { it.contains("ERR-20260409-OLD") })
    }
}
