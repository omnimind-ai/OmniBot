package cn.com.omnimind.bot.plugin.sandbox

import cn.com.omnimind.baselib.llm.contentText
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxPluginBridgeRuntimeTest {
    @Test
    fun `dashboard tool call executes the declared project connector`() = runBlocking {
        val root = Files.createTempDirectory("sandbox-dashboard-tool").toFile()
        val source = projectSource()
        val pool = SandboxPluginPool(
            rootDirectory = root,
            databaseFactory = InMemoryDatabaseFactory(),
        )
        val pluginId = pool.execute(
            SandboxPluginCommand.PublishProject(source, projectManifest()),
        ).requireSuccess().payload.getValue("pluginId") as String
        val runtime = SandboxPluginBridgeRuntime(pool)

        val inserted = runtime.invoke(
            pluginId = pluginId,
            method = "tool.call",
            params = mapOf(
                "name" to "record_workout",
                "arguments" to mapOf("exercise" to "深蹲", "weight" to 100),
            ),
        )
        val queried = runtime.invoke(
            pluginId = pluginId,
            method = "tool.call",
            params = mapOf(
                "name" to "list_workouts",
                "arguments" to mapOf("exercise" to "深蹲", "_limit" to 20),
            ),
        )

        assertEquals(1L, inserted["rowId"])
        assertEquals(
            listOf(mapOf("id" to 1L, "exercise" to "深蹲", "weight" to 100L)),
            queried["rows"],
        )
    }

    @Test
    fun `dashboard tool call rejects undeclared arguments`() = runBlocking {
        val root = Files.createTempDirectory("sandbox-dashboard-args").toFile()
        val pool = SandboxPluginPool(
            rootDirectory = root,
            databaseFactory = InMemoryDatabaseFactory(),
        )
        val pluginId = pool.execute(
            SandboxPluginCommand.PublishProject(projectSource(), projectManifest()),
        ).requireSuccess().payload.getValue("pluginId") as String
        val runtime = SandboxPluginBridgeRuntime(pool)

        val error = runCatching {
            runtime.invoke(
                pluginId = pluginId,
                method = "tool.call",
                params = mapOf(
                    "name" to "record_workout",
                    "arguments" to mapOf(
                        "exercise" to "深蹲",
                        "weight" to 100,
                        "created_at" to "pretend-time",
                    ),
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("created_at"))
    }

    @Test
    fun `fast html generation disables provider thinking`() {
        val request = XiaowanChatCompletionRequestFactory.create(
            prompt = "生成三条训练建议",
            system = "使用中文",
            maxTokens = 500,
            temperature = 0.5,
        )

        assertEquals("none", request.reasoningEffort)
        assertFalse(request.enableThinking ?: true)
        assertEquals("disabled", request.thinking?.type)
        assertEquals(500, request.maxCompletionTokens)
        assertEquals("使用中文", request.messages.first().contentText())
        assertEquals("生成三条训练建议", request.messages.last().contentText())
    }

    private fun projectSource(): File =
        Files.createTempDirectory("dashboard-tool-source").toFile().apply {
            resolve("skill").mkdirs()
            resolve("skill/SKILL.md").writeText(
                """
                    ---
                    name: bridge-fitness
                    description: Record and query workouts from chat and the Dashboard.
                    ---

                    Use the declared workout tools.
                """.trimIndent(),
            )
            resolve("index.html").writeText(
                "<html><head></head><body><script src=\"app.js\"></script></body></html>",
            )
            resolve("app.js").writeText(
                "window.omni.tools.call('list_workouts', { _limit: 20 });",
            )
            resolve("icon.svg").writeText(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 32\"><path d=\"M4 16h24\"/></svg>",
            )
            resolve("schema.sql").writeText(
                """
                    CREATE TABLE IF NOT EXISTS workouts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        exercise TEXT NOT NULL,
                        weight REAL NOT NULL
                    );
                """.trimIndent(),
            )
            resolve("toolkit.json").writeText(
                """
                    {
                      "schemaVersion": 1,
                      "connectors": [{"id":"store","type":"sqlite","config":{}}],
                      "tools": [
                        {
                          "name":"record_workout",
                          "displayName":"记录训练",
                          "description":"Record one workout.",
                          "parameters":{
                            "type":"object",
                            "required":["exercise","weight"],
                            "properties":{
                              "exercise":{"type":"string"},
                              "weight":{"type":"number"}
                            },
                            "additionalProperties":false
                          },
                          "executor":{"connector":"store","action":"insert","config":{"table":"workouts"}}
                        },
                        {
                          "name":"list_workouts",
                          "displayName":"查询训练",
                          "description":"List matching workouts.",
                          "parameters":{
                            "type":"object",
                            "properties":{
                              "exercise":{"type":"string"},
                              "_limit":{"type":"integer"}
                            },
                            "additionalProperties":false
                          },
                          "executor":{"connector":"store","action":"query","config":{"table":"workouts"}}
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }

    private fun projectManifest() = SandboxProjectManifest(
        slug = "bridge-fitness",
        name = "Bridge Fitness",
        description = "Record and query workouts from one shared tool contract.",
        entryPath = "index.html",
        iconPath = "icon.svg",
        schemaPath = "schema.sql",
        permissions = listOf(SandboxProjectPermission.DATABASE),
    )

    private class InMemoryDatabaseFactory : SandboxPluginDatabaseFactory {
        private val databases = linkedMapOf<String, InMemoryDatabase>()

        override fun open(databaseFile: File): SandboxPluginDatabase {
            databaseFile.parentFile?.mkdirs()
            databaseFile.createNewFile()
            return databases.getOrPut(databaseFile.canonicalPath) { InMemoryDatabase() }
        }
    }

    private class InMemoryDatabase : SandboxPluginDatabase {
        private val rows = mutableListOf<MutableMap<String, Any?>>()

        override fun initialize(schemaSql: String) = Unit

        override fun insert(table: String, values: Map<String, Any?>): Long {
            val id = rows.size + 1L
            rows += linkedMapOf<String, Any?>("id" to id).apply { putAll(values) }
            return id
        }

        override fun query(
            table: String,
            where: Map<String, Any?>,
            orderBy: String?,
            limit: Int,
        ): List<Map<String, Any?>> = rows
            .filter { row -> where.all { (key, value) -> row[key] == value } }
            .take(limit)

        override fun update(table: String, id: Any, values: Map<String, Any?>): Int = 0

        override fun delete(table: String, id: Any): Int = 0

        override fun close() = Unit
    }
}
