package cn.com.omnimind.bot.plugin.sandbox

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxPluginPoolTest {
    @Test
    fun `vibe project without standalone app entry is rejected`() {
        val root = Files.createTempDirectory("sandbox-xiaowan-skill").toFile()
        val source = Files.createTempDirectory("xiaowan-skill-source").toFile().apply {
            resolve("skill").mkdirs()
            resolve("skill/SKILL.md").writeText(
                """
                    ---
                    name: weekly-coach
                    description: Build a practical weekly plan from goals and constraints.
                    ---

                    Use the weekly planning tool when the user asks for a plan.
                """.trimIndent(),
            )
            resolve("toolkit.json").writeText(
                """
                    {
                      "schemaVersion": 1,
                      "connectors": [{
                        "id": "coach",
                        "type": "xiaowan",
                        "config": {"system": "You are a concise planning coach."}
                      }],
                      "tools": [{
                        "name": "create_plan",
                        "displayName": "生成计划",
                        "description": "Create a weekly plan directly with Xiaowan.",
                        "parameters": {
                          "type": "object",
                          "required": ["goal"],
                          "properties": {"goal": {"type": "string"}},
                          "additionalProperties": false
                        },
                        "executor": {
                          "connector": "coach",
                          "action": "invoke",
                          "config": {"instruction": "Create a seven-day plan."}
                        }
                      }]
                    }
                """.trimIndent(),
            )
        }
        val pool = SandboxPluginPool(
            rootDirectory = root,
            databaseFactory = InMemoryDatabaseFactory(),
        )
        val manifest = SandboxProjectManifest(
            slug = "weekly-coach",
            name = "每周教练",
            description = "把目标整理成一周计划",
        )

        val checked = pool.execute(
            SandboxPluginCommand.CheckProject(source, manifest),
        )

        assertFalse(checked.success)
        assertTrue(checked.errorMessage.orEmpty().contains("standalone app entry"))
    }

    @Test
    fun `workspace project is checked published and linked without source generation`() = runBlocking {
        val root = Files.createTempDirectory("sandbox-plugin-pool").toFile()
        val dataRoot = Files.createTempDirectory("sandbox-plugin-data").toFile()
        val source = projectSource()
        val pool = SandboxPluginPool(
            rootDirectory = root,
            dataRootDirectory = dataRoot,
            databaseFactory = InMemoryDatabaseFactory(),
            now = { 1_720_000_000_000L },
        )
        val manifest = projectManifest()

        val checked = pool.execute(
            SandboxPluginCommand.CheckProject(source, manifest),
        ).requireSuccess()
        assertEquals(true, checked.payload["valid"])

        val published = pool.execute(
            SandboxPluginCommand.PublishProject(source, manifest),
        ).requireSuccess()
        val pluginId = published.payload.getValue("pluginId") as String

        assertEquals("local.project.fitness-beast", pluginId)
        assertTrue((published.payload.getValue("entryPath") as String).endsWith("index.html"))
        assertTrue((published.payload.getValue("iconPath") as String).endsWith("icon.svg"))
        assertEquals(pluginId, pool.createProviders().single().descriptor.id)
        val contribution = pool.createProviders().single().create().contribution()
        assertEquals(
            listOf("fitness_beast_record_workout", "fitness_beast_list_workouts"),
            contribution.toolGroups.single().definitions.map { it.name },
        )
        assertEquals(
            contribution.toolGroups.single().definitions.mapTo(linkedSetOf()) { it.name },
            contribution.toolGroups.single().handlerFactory().toolNames,
        )
        assertFalse(source.resolve("index.html").readText().contains(".omni/bridge.js"))
        val installedEntry = File(published.payload.getValue("entryPath") as String)
        assertTrue(installedEntry.readText().contains(".omni/bridge.js"))
        assertTrue(installedEntry.readText().contains("connect-src 'none'"))
        assertTrue(dataRoot.resolve(pluginId).resolve("project.db").isFile)
        assertFalse(root.resolve(pluginId).resolve(".omni/data/project.db").exists())
        val installedNestedPage = requireNotNull(installedEntry.parentFile)
            .resolve("details/index.html")
        assertTrue(installedNestedPage.readText().contains("../.omni/bridge.js"))
        assertTrue(installedNestedPage.readText().contains("connect-src 'none'"))
        val installedBridge = root.resolve(pluginId).resolve(".omni/bridge.js").readText()
        assertEquals("4", root.resolve(pluginId).resolve(".omni/runtime.version").readText())
        assertTrue(installedBridge.contains("__omniRuntimeVersion', { value: 4"))
        assertTrue(installedBridge.contains("call('tool.call'"))
        assertFalse(installedBridge.contains("window.__omniAppEvent"))
        assertFalse(installedBridge.contains("call('app.send'"))
        assertFalse(installedBridge.contains("call('app.cancel'"))
        assertFalse(installedBridge.contains("call('app.getState'"))

        val inserted = pool.executeTool(
            pluginId = pluginId,
            runtimeToolName = "fitness_beast_record_workout",
            arguments = JsonObject(
                mapOf(
                    "exercise" to JsonPrimitive("深蹲"),
                    "weight" to JsonPrimitive(100),
                ),
            ),
        )
        val rowId = inserted.getValue("rowId") as Long

        pool.execute(
            SandboxPluginCommand.Update(
                pluginId = pluginId,
                table = "workouts",
                id = rowId,
                values = mapOf("weight" to 105),
            ),
        ).requireSuccess()

        val queried = pool.execute(
            SandboxPluginCommand.Query(
                pluginId = pluginId,
                table = "workouts",
                where = mapOf("exercise" to "深蹲"),
                orderBy = "id DESC",
                limit = 20,
            ),
        ).requireSuccess()

        assertEquals(
            listOf(mapOf("id" to 1L, "exercise" to "深蹲", "weight" to 105)),
            queried.payload["rows"],
        )
    }

    @Test
    fun `runtime upgrade does not duplicate injected html tags`() {
        val root = Files.createTempDirectory("sandbox-plugin-runtime-upgrade").toFile()
        val source = projectSource()
        val pool = SandboxPluginPool(
            rootDirectory = root,
            databaseFactory = InMemoryDatabaseFactory(),
        )
        val pluginId = pool.execute(
            SandboxPluginCommand.PublishProject(source, projectManifest()),
        ).requireSuccess().payload.getValue("pluginId") as String
        val pluginDirectory = root.resolve(pluginId)
        val entry = pluginDirectory.resolve("index.html")
        val originalHtml = entry.readText()
        pluginDirectory.resolve(".omni/runtime.version").delete()
        pluginDirectory.resolve(".omni/bridge.js").writeText("window.omni = {}; // legacy")

        pool.createProviders()
        pool.createProviders()

        val upgradedHtml = entry.readText()
        val upgradedBridge = pluginDirectory.resolve(".omni/bridge.js").readText()
        assertEquals(originalHtml, upgradedHtml)
        assertEquals(1, upgradedHtml.split(".omni/bridge.js").size - 1)
        assertEquals(1, upgradedHtml.split("Content-Security-Policy").size - 1)
        assertEquals("4", pluginDirectory.resolve(".omni/runtime.version").readText())
        assertTrue(upgradedBridge.contains("call('tool.call'"))
        assertFalse(upgradedBridge.contains("call('app.send'"))
    }

    @Test
    fun `republishing project preserves its isolated database`() {
        val root = Files.createTempDirectory("sandbox-plugin-update").toFile()
        val dataRoot = Files.createTempDirectory("sandbox-plugin-update-data").toFile()
        val source = projectSource()
        val pool = SandboxPluginPool(
            rootDirectory = root,
            dataRootDirectory = dataRoot,
            databaseFactory = InMemoryDatabaseFactory(),
        )
        val manifest = projectManifest()
        val pluginId = pool.execute(
            SandboxPluginCommand.PublishProject(source, manifest),
        ).requireSuccess().payload.getValue("pluginId") as String
        pool.execute(
            SandboxPluginCommand.Insert(
                pluginId = pluginId,
                table = "workouts",
                values = mapOf("exercise" to "硬拉", "weight" to 120),
            ),
        ).requireSuccess()

        source.resolve("index.html").writeText("<html><head></head><body>v2</body></html>")
        val republished = pool.execute(
            SandboxPluginCommand.PublishProject(
                source,
                manifest.copy(version = "1.1.0"),
            ),
        ).requireSuccess()

        assertEquals(true, republished.payload["updated"])
        val rows = pool.execute(
            SandboxPluginCommand.Query(pluginId, "workouts"),
        ).requireSuccess().payload["rows"]
        assertEquals(
            listOf(mapOf("id" to 1L, "exercise" to "硬拉", "weight" to 120)),
            rows,
        )
    }

    @Test
    fun `uninstall removes plugin code but preserves isolated database`() = runBlocking {
        val root = Files.createTempDirectory("sandbox-plugin-uninstall").toFile()
        val dataRoot = Files.createTempDirectory("sandbox-plugin-uninstall-data").toFile()
        val source = projectSource()
        val pool = SandboxPluginPool(
            rootDirectory = root,
            dataRootDirectory = dataRoot,
            databaseFactory = InMemoryDatabaseFactory(),
        )
        val manifest = projectManifest()
        val pluginId = pool.execute(
            SandboxPluginCommand.PublishProject(source, manifest),
        ).requireSuccess().payload.getValue("pluginId") as String
        pool.execute(
            SandboxPluginCommand.Insert(
                pluginId = pluginId,
                table = "workouts",
                values = mapOf("exercise" to "卧推", "weight" to 80),
            ),
        ).requireSuccess()

        pool.createProviders().single().uninstall()

        assertFalse(root.resolve(pluginId).exists())
        assertTrue(dataRoot.resolve(pluginId).resolve("project.db").isFile)

        pool.execute(
            SandboxPluginCommand.PublishProject(source, manifest),
        ).requireSuccess()
        val rows = pool.execute(
            SandboxPluginCommand.Query(pluginId, "workouts"),
        ).requireSuccess().payload["rows"]
        assertEquals(
            listOf(mapOf("id" to 1L, "exercise" to "卧推", "weight" to 80)),
            rows,
        )
    }

    @Test
    fun `legacy database migrates out of plugin code directory`() = runBlocking {
        val root = Files.createTempDirectory("sandbox-plugin-legacy").toFile()
        val dataRoot = Files.createTempDirectory("sandbox-plugin-legacy-data").toFile()
        val source = projectSource()
        val pool = SandboxPluginPool(
            rootDirectory = root,
            dataRootDirectory = dataRoot,
            databaseFactory = InMemoryDatabaseFactory(),
        )
        val manifest = projectManifest()
        val pluginId = pool.execute(
            SandboxPluginCommand.PublishProject(source, manifest),
        ).requireSuccess().payload.getValue("pluginId") as String
        dataRoot.resolve(pluginId).deleteRecursively()
        val legacyDirectory = root.resolve(pluginId).resolve(".omni/data").apply { mkdirs() }
        val legacyDatabase = legacyDirectory.resolve("project.db").apply {
            writeText("legacy-database")
        }
        legacyDirectory.resolve(".schema-sha256").writeText("legacy-marker")

        pool.createProviders().single().install()

        val migratedDatabase = dataRoot.resolve(pluginId).resolve("project.db")
        assertTrue(migratedDatabase.isFile)
        assertEquals("legacy-database", migratedDatabase.readText())
        assertFalse(legacyDatabase.exists())
        assertTrue(dataRoot.resolve(pluginId).resolve(".schema-sha256").isFile)
    }

    @Test
    fun `project check reports undeclared ai capability at source file`() {
        val root = Files.createTempDirectory("sandbox-plugin-capability").toFile()
        val source = projectSource().apply {
            resolve("app.js").writeText("window.omni.ai.generate('plan');")
        }
        val result = SandboxPluginPool(
            rootDirectory = root,
            databaseFactory = InMemoryDatabaseFactory(),
        ).execute(
            SandboxPluginCommand.CheckProject(
                source,
                projectManifest(),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("app.js"))
        assertTrue(result.errorMessage.orEmpty().contains("xiaowan permission"))
    }

    @Test
    fun `project check rejects removed external app bridge`() {
        val root = Files.createTempDirectory("sandbox-plugin-app-capability").toFile()
        val source = projectSource().apply {
            resolve("app.js").writeText("window.omni.app.send('make a plan');")
        }
        val result = SandboxPluginPool(
            rootDirectory = root,
            databaseFactory = InMemoryDatabaseFactory(),
        ).execute(
            SandboxPluginCommand.CheckProject(
                source,
                projectManifest(),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("app.js"))
        assertFalse(result.errorMessage.orEmpty().contains("xiaowan permission"))
    }

    @Test
    fun `project check rejects dashboard database calls that bypass declared tools`() {
        val root = Files.createTempDirectory("sandbox-plugin-direct-db").toFile()
        val source = projectSource().apply {
            resolve("app.js").writeText(
                "window.omni.db.insert('workouts', { exercise: '深蹲', weight: 100 });",
            )
        }

        val result = SandboxPluginPool(
            rootDirectory = root,
            databaseFactory = InMemoryDatabaseFactory(),
        ).execute(
            SandboxPluginCommand.CheckProject(source, projectManifest()),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("app.js"))
        assertTrue(result.errorMessage.orEmpty().contains("window.omni.tools.call"))
    }

    @Test
    fun `project check rejects unknown dashboard tool references`() {
        val root = Files.createTempDirectory("sandbox-plugin-unknown-tool").toFile()
        val source = projectSource().apply {
            resolve("app.js").writeText(
                "window.omni.tools.call('missing_workout_tool', { exercise: '深蹲' });",
            )
        }

        val result = SandboxPluginPool(
            rootDirectory = root,
            databaseFactory = InMemoryDatabaseFactory(),
        ).execute(
            SandboxPluginCommand.CheckProject(source, projectManifest()),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("app.js"))
        assertTrue(result.errorMessage.orEmpty().contains("missing_workout_tool"))
    }

    @Test
    fun `ai backed Agent tool requires ai permission`() {
        val root = Files.createTempDirectory("sandbox-plugin-ai-tool").toFile()
        val source = projectSource().apply {
            resolve("app.js").writeText(
                "window.omni.tools.call('coach_plan', { goal: '恢复训练' });",
            )
            resolve("toolkit.json").writeText(
                """
                    {
                      "schemaVersion": 1,
                      "tools": [{
                        "name": "coach_plan",
                        "displayName": "生成训练计划",
                        "description": "Create a training plan directly from chat.",
                        "parameters": {
                          "type": "object",
                          "required": ["goal"],
                          "properties": {"goal": {"type": "string"}},
                          "additionalProperties": false
                        },
                        "executor": {
                          "type": "ai.generate",
                          "config": {"instruction": "Create a concise training plan."}
                        }
                      }]
                    }
                """.trimIndent(),
            )
        }
        val pool = SandboxPluginPool(
            rootDirectory = root,
            databaseFactory = InMemoryDatabaseFactory(),
        )
        val manifest = projectManifest()

        val rejected = pool.execute(SandboxPluginCommand.CheckProject(source, manifest))
        assertFalse(rejected.success)
        assertTrue(rejected.errorMessage.orEmpty().contains("xiaowan permission"))

        val accepted = pool.execute(
            SandboxPluginCommand.CheckProject(
                source,
                manifest.copy(
                    permissions = listOf(
                        SandboxProjectPermission.DATABASE,
                        SandboxProjectPermission.XIAOWAN,
                    ),
                ),
            ),
        )
        assertTrue(accepted.success)
    }

    @Test
    fun `project entry must be an html document`() {
        val result = SandboxPluginPool(
            rootDirectory = Files.createTempDirectory("sandbox-plugin-entry").toFile(),
            databaseFactory = InMemoryDatabaseFactory(),
        ).execute(
            SandboxPluginCommand.CheckProject(
                projectSource(),
                projectManifest().copy(entryPath = "app.svg"),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("HTML document"))
    }

    @Test
    fun `project icon must be a safe svg document`() {
        val source = projectSource().apply {
            resolve("icon.svg").writeText(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>",
            )
        }
        val result = SandboxPluginPool(
            rootDirectory = Files.createTempDirectory("sandbox-plugin-icon").toFile(),
            databaseFactory = InMemoryDatabaseFactory(),
        ).execute(
            SandboxPluginCommand.CheckProject(source, projectManifest()),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("unsupported active"))
    }

    private fun projectSource(): File =
        Files.createTempDirectory("vibe-project-source").toFile().apply {
            resolve("skill").mkdirs()
            resolve("skill/SKILL.md").writeText(
                """
                    ---
                    name: fitness-beast
                    description: Record workouts and help the user review training.
                    ---

                    Use the generated Xiaowan tools directly for workout requests.
                """.trimIndent(),
            )
            resolve("index.html").writeText(
                "<html><head></head><body><script src=\"app.js\"></script></body></html>",
            )
            resolve("icon.svg").writeText(
                """
                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128">
                      <rect width="128" height="128" rx="28" fill="#7C5CFC"/>
                      <path d="M35 70h58M44 52v36M84 52v36" stroke="white" stroke-width="10"/>
                    </svg>
                """.trimIndent(),
            )
            resolve("app.js").writeText(
                "window.omni.tools.call('list_workouts', { _limit: 20 });",
            )
            resolve("details").mkdirs()
            resolve("details/index.html").writeText("<html><body>Details</body></html>")
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
                      "tools": [
                        {
                          "name": "record_workout",
                          "displayName": "记录训练",
                          "description": "Record one completed workout directly.",
                          "parameters": {
                            "type": "object",
                            "required": ["exercise", "weight"],
                            "properties": {
                              "exercise": {"type": "string"},
                              "weight": {"type": "number"}
                            },
                            "additionalProperties": false
                          },
                          "executor": {
                            "type": "sqlite.insert",
                            "config": {"table": "workouts"}
                          }
                        },
                        {
                          "name": "list_workouts",
                          "displayName": "查询训练",
                          "description": "Query workout records directly.",
                          "parameters": {
                            "type": "object",
                            "properties": {
                              "exercise": {"type": "string"},
                              "_limit": {"type": "integer", "minimum": 1, "maximum": 500},
                              "_order_by": {"type": "string"}
                            },
                            "additionalProperties": false
                          },
                          "executor": {
                            "type": "sqlite.query",
                            "config": {"table": "workouts"}
                          }
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }

    private fun projectManifest(): SandboxProjectManifest = SandboxProjectManifest(
        slug = "fitness-beast",
        name = "健身兽",
        description = "训练后会成长的健身伙伴",
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

    private class RecordingSkillManager : SandboxProjectSkillManager {
        var installedSkillId: String? = null
        var installedToolNames: List<String> = emptyList()
        val enabledStates = mutableListOf<Boolean>()
        var uninstalledSkillId: String? = null

        override fun install(
            sourceDirectory: File,
            skillId: String,
            tools: List<cn.com.omnimind.bot.plugin.OmniPluginToolDefinition>,
        ) {
            assertTrue(sourceDirectory.resolve("SKILL.md").isFile)
            installedSkillId = skillId
            installedToolNames = tools.map { it.name }
        }

        override fun setEnabled(skillId: String, enabled: Boolean) {
            assertEquals(installedSkillId, skillId)
            enabledStates += enabled
        }

        override fun uninstall(skillId: String) {
            uninstalledSkillId = skillId
        }
    }

    private class InMemoryDatabase : SandboxPluginDatabase {
        private val rows = linkedMapOf<String, MutableList<MutableMap<String, Any?>>>()
        private var initialized = false

        override fun initialize(schemaSql: String) {
            check(schemaSql.contains("CREATE TABLE IF NOT EXISTS workouts"))
            rows.putIfAbsent("workouts", mutableListOf())
            initialized = true
        }

        override fun insert(table: String, values: Map<String, Any?>): Long {
            check(initialized)
            val tableRows = rows.getValue(table)
            val id = tableRows.size + 1L
            tableRows += linkedMapOf<String, Any?>("id" to id).apply { putAll(values) }
            return id
        }

        override fun query(
            table: String,
            where: Map<String, Any?>,
            orderBy: String?,
            limit: Int,
        ): List<Map<String, Any?>> {
            check(initialized)
            return rows.getValue(table)
                .filter { row -> where.all { (column, value) -> row[column] == value } }
                .take(limit)
        }

        override fun update(table: String, id: Any, values: Map<String, Any?>): Int {
            check(initialized)
            val row = rows.getValue(table).firstOrNull { it["id"] == id } ?: return 0
            row.putAll(values)
            return 1
        }

        override fun delete(table: String, id: Any): Int {
            check(initialized)
            return if (rows.getValue(table).removeAll { it["id"] == id }) 1 else 0
        }

        override fun close() = Unit
    }
}
