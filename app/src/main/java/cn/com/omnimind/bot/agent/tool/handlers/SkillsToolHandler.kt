package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class SkillsToolHandler(
    private val helper: SharedHelper,
    private val workspaceManager: AgentWorkspaceManager
) : ToolHandler {
    override val toolNames: Set<String> = setOf("skills_list", "skills_read")

    private val skillIndexService = SkillIndexService(helper.context, workspaceManager)
    private val skillLoader = SkillLoader(workspaceManager)

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return when (toolCall.function.name) {
            "skills_list" -> executeSkillsList(
                args,
                env.workspaceDescriptor,
                callback,
            )
            "skills_read" -> executeSkillsRead(
                args,
                env.workspaceDescriptor,
                callback,
            )
            else -> ToolExecutionResult.Error(toolCall.function.name, "Unknown skills tool")
        }
    }

    private suspend fun executeSkillsList(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback,
    ): ToolExecutionResult {
        val toolName = "skills_list"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val limit = args["limit"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
            val normalizedQuery = query.lowercase()
            val entries = skillIndexService.listInstalledSkills()
                .filter { entry ->
                    if (normalizedQuery.isBlank()) true
                    else listOf(
                        entry.id,
                        entry.name,
                        resolveDistributionText(entry.description),
                        entry.shellSkillFilePath,
                        entry.shellRootPath
                    ).any { it.lowercase().contains(normalizedQuery) }
                }
                .let { skills -> if (limit != null) skills.take(limit) else skills }
            val items = entries.map { entry ->
                mapOf(
                    "id" to entry.id, "name" to entry.name,
                    "description" to resolveDistributionText(entry.description),
                    "enabled" to entry.enabled, "source" to entry.source, "installed" to entry.installed,
                    "rootPath" to entry.shellRootPath, "androidRootPath" to entry.rootPath,
                    "skillFilePath" to entry.shellSkillFilePath, "androidSkillFilePath" to entry.skillFilePath,
                    "capabilities" to buildList {
                        if (entry.hasScripts) add("scripts")
                        if (entry.hasReferences) add("references")
                        if (entry.hasAssets) add("assets")
                        if (entry.hasEvals) add("evals")
                    },
                    "metadata" to entry.metadata.mapValues { (_, value) -> resolveDistributionText(value) }
                )
            }
            val payload = linkedMapOf<String, Any?>(
                "query" to query, "count" to items.size,
                "skillsRoot" to workspaceManager.shellPathForAndroid(workspaceManager.skillsRoot()),
                "androidSkillsRoot" to workspaceManager.skillsRoot().absolutePath,
                "items" to items
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(if (items.isEmpty()) "当前没有匹配的 skills" else "共找到 ${items.size} 个 skill"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true, workspaceId = workspace.id
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "列出 skills 失败")
        }
    }

    private suspend fun executeSkillsRead(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback,
    ): ToolExecutionResult {
        val toolName = "skills_read"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            val skillId = args["skillId"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(skillId.isNotEmpty()) { "缺少 skillId" }
            val entry = skillIndexService.findInstalledSkill(skillId) ?: throw IllegalArgumentException("未找到 skill：$skillId")
            val resolved = skillLoader.load(entry, "agent 主动读取 skill") ?: throw IllegalStateException("读取 SKILL.md 失败：${entry.shellSkillFilePath}")
            val skillFile = File(entry.skillFilePath)
            val artifact = workspaceManager.buildArtifactForFile(skillFile, toolName)
            val payload = linkedMapOf<String, Any?>(
                "id" to entry.id, "name" to entry.name,
                "description" to resolveDistributionText(entry.description),
                "enabled" to entry.enabled, "source" to entry.source, "installed" to entry.installed,
                "rootPath" to entry.shellRootPath, "androidRootPath" to entry.rootPath,
                "skillFilePath" to entry.shellSkillFilePath, "androidSkillFilePath" to entry.skillFilePath,
                "scriptsDir" to resolved.scriptsDir, "assetsDir" to resolved.assetsDir,
                "references" to resolved.loadedReferences,
                "metadata" to resolved.metadata.mapValues { (_, value) -> resolveDistributionText(value) },
                "frontmatter" to resolved.frontmatter.mapValues { (_, value) -> resolveDistributionText(value) },
                "bodyMarkdown" to resolveDistributionText(resolved.bodyMarkdown),
                "uri" to artifact.uri
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已读取 skill：${entry.name}"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true, artifacts = listOf(artifact), workspaceId = workspace.id
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "读取 skill 失败")
        }
    }

    private fun resolveDistributionText(text: String): String {
        return AgentTerminalDistributionText.resolve(text, helper.terminalDistribution)
    }
}
