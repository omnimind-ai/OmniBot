package cn.com.omnimind.bot.plugin.sandbox

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SandboxPluginManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String,
    val publisher: String = "Local user",
    val capabilities: List<String> = listOf("Xiaowan skill", "Agent-callable business tools"),
    val permissions: List<String> = listOf(SandboxProjectPermission.XIAOWAN),
    val visibility: String = "visible",
    val frontend: SandboxPluginFrontend? = null,
    val database: SandboxPluginDatabaseSpec? = null,
    val skill: SandboxPluginSkillSpec? = null,
    val toolkit: SandboxPluginToolkitSpec? = null,
    val createdAtEpochMs: Long,
)

@Serializable
data class SandboxPluginFrontend(
    val entry: String = "frontend/index.html",
    val icon: String = "icon.svg",
)

@Serializable
data class SandboxPluginDatabaseSpec(
    val schema: String = "database/schema.sql",
)

@Serializable
data class SandboxPluginSkillSpec(
    val path: String = "skill/SKILL.md",
)

@Serializable
data class SandboxPluginToolkitSpec(
    val path: String = "toolkit.json",
)

@Serializable
data class SandboxProjectManifest(
    val slug: String,
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    @SerialName("entry_path")
    val entryPath: String? = null,
    @SerialName("icon_path")
    val iconPath: String? = null,
    @SerialName("schema_path")
    val schemaPath: String? = null,
    @SerialName("skill_path")
    val skillPath: String = "skill/SKILL.md",
    @SerialName("toolkit_path")
    val toolkitPath: String = "toolkit.json",
    val permissions: List<String> = listOf(SandboxProjectPermission.XIAOWAN),
)

@Serializable
data class SandboxProjectToolkit(
    val schemaVersion: Int = 1,
    val connectors: List<SandboxProjectConnector> = emptyList(),
    val tools: List<SandboxProjectTool> = emptyList(),
)

@Serializable
data class SandboxProjectConnector(
    val id: String,
    val type: String,
    val config: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SandboxProjectTool(
    val name: String,
    val displayName: String,
    val description: String,
    val parameters: JsonObject,
    val executor: SandboxProjectToolExecutorSpec,
)

@Serializable
data class SandboxProjectToolExecutorSpec(
    val type: String? = null,
    val connector: String? = null,
    val action: String? = null,
    val config: JsonObject = JsonObject(emptyMap()),
)

object SandboxProjectPermission {
    const val DATABASE = "database"
    const val XIAOWAN = "xiaowan"
    const val AI = "ai"
    const val NETWORK = "network"

    val supported: Set<String> = setOf(DATABASE, XIAOWAN, AI, NETWORK)
}

sealed interface SandboxPluginCommand {
    data class CheckProject(
        val sourceDirectory: File,
        val manifest: SandboxProjectManifest,
    ) : SandboxPluginCommand

    data class PublishProject(
        val sourceDirectory: File,
        val manifest: SandboxProjectManifest,
    ) : SandboxPluginCommand

    data class Insert(
        val pluginId: String,
        val table: String,
        val values: Map<String, Any?>,
    ) : SandboxPluginCommand

    data class Query(
        val pluginId: String,
        val table: String,
        val where: Map<String, Any?> = emptyMap(),
        val orderBy: String? = null,
        val limit: Int = SandboxQueryLimits.DEFAULT,
        val offset: Int = 0,
    ) : SandboxPluginCommand

    data class Update(
        val pluginId: String,
        val table: String,
        val id: Any,
        val values: Map<String, Any?>,
    ) : SandboxPluginCommand

    data class Delete(
        val pluginId: String,
        val table: String,
        val id: Any,
    ) : SandboxPluginCommand
}

data class SandboxPluginResult(
    val success: Boolean,
    val payload: Map<String, Any?> = emptyMap(),
    val errorMessage: String? = null,
) {
    fun requireSuccess(): SandboxPluginResult {
        check(success) { errorMessage ?: "Sandbox plugin command failed" }
        return this
    }

    companion object {
        fun success(payload: Map<String, Any?>): SandboxPluginResult =
            SandboxPluginResult(success = true, payload = payload)

        fun failure(error: Throwable): SandboxPluginResult = SandboxPluginResult(
            success = false,
            errorMessage = error.message ?: error.javaClass.simpleName,
        )
    }
}

interface SandboxPluginDatabaseFactory {
    fun open(databaseFile: File): SandboxPluginDatabase
}

interface SandboxPluginDatabase : AutoCloseable {
    fun initialize(schemaSql: String)

    fun insert(table: String, values: Map<String, Any?>): Long

    fun query(
        table: String,
        where: Map<String, Any?>,
        orderBy: String?,
        limit: Int,
        offset: Int = 0,
    ): List<Map<String, Any?>>

    fun update(table: String, id: Any, values: Map<String, Any?>): Int

    fun delete(table: String, id: Any): Int
}

internal object SandboxQueryLimits {
    const val DEFAULT = 100
    const val MAX = 500
}
