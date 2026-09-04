package cn.com.omnimind.bot.plugin.sandbox

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxConnectorContractTest {
    @Test
    fun `contract exposes shared dashboard tool bridge`() {
        val dashboardBridge = SandboxConnectorContract.payload()["dashboardBridge"] as Map<*, *>

        assertEquals("window.omni.tools.call", dashboardBridge["method"])
        assertTrue(dashboardBridge["toolName"].toString().contains("toolkit.json"))
        assertTrue(dashboardBridge["arguments"].toString().contains("parameters schema"))
        assertTrue(dashboardBridge["rule"].toString().contains("same declared tools as Xiaowan"))
        assertTrue(dashboardBridge["legacyDatabaseBridge"].toString().contains("project_check"))
    }

    @Test
    fun `unknown connector error explains id reference and supported types`() {
        val toolkit = SandboxProjectToolkit(
            connectors = listOf(SandboxProjectConnector(id = "assistant", type = "xiaowan")),
            tools = listOf(tool(connector = "xiaowan")),
        )

        val error = runCatching {
            SandboxProjectToolPolicy.resolveExecutor(toolkit, toolkit.tools.single())
        }.exceptionOrNull()

        requireNotNull(error)
        assertTrue(error.message.orEmpty().contains("unknown connector id 'xiaowan'"))
        assertTrue(error.message.orEmpty().contains("connectors[].id"))
        assertTrue(error.message.orEmpty().contains("sqlite"))
        assertTrue(error.message.orEmpty().contains("xiaowan"))
        assertTrue(error.message.orEmpty().contains("\"connector\":\"assistant\""))
    }

    @Test
    fun `unsupported connector error returns stable machine-readable contract`() {
        val toolkit = SandboxProjectToolkit(
            connectors = listOf(SandboxProjectConnector(id = "remote", type = "frontend")),
            tools = listOf(tool(connector = "remote")),
        )

        val error = runCatching {
            SandboxProjectToolPolicy.validate(
                pluginId = "local.project.contract-test",
                toolkit = toolkit,
                permissions = listOf(SandboxProjectPermission.XIAOWAN),
                schemaSql = null,
            )
        }.exceptionOrNull()

        requireNotNull(error)
        assertTrue(error.message.orEmpty().contains("connector type 'frontend'"))
        assertTrue(error.message.orEmpty().contains("sqlite=[insert, query, update, delete]"))
        assertTrue(error.message.orEmpty().contains("xiaowan=[invoke]"))
        assertTrue(error.message.orEmpty().contains("http_json=[get]"))
        assertTrue(error.message.orEmpty().contains("connectors[].id"))
    }

    @Test
    fun `public HTTPS JSON connector requires explicit network permission`() {
        val toolkit = SandboxProjectToolkit(
            connectors = listOf(
                SandboxProjectConnector(
                    id = "scores",
                    type = "http_json",
                    config = JsonObject(
                        mapOf("base_url" to JsonPrimitive("https://example.org/api")),
                    ),
                ),
            ),
            tools = listOf(
                SandboxProjectTool(
                    name = "list_games",
                    displayName = "List Games",
                    description = "Fetch live games from the declared public source.",
                    parameters = JsonObject(mapOf("type" to JsonPrimitive("object"))),
                    executor = SandboxProjectToolExecutorSpec(
                        connector = "scores",
                        action = "get",
                        config = JsonObject(
                            mapOf(
                                "path" to JsonPrimitive("/games"),
                                "query" to JsonObject(
                                    mapOf("date" to JsonPrimitive("\$date")),
                                ),
                                "response_path" to JsonPrimitive("data.games"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val denied = runCatching {
            SandboxProjectToolPolicy.validate(
                pluginId = "local.project.live-scores",
                toolkit = toolkit,
                permissions = listOf(SandboxProjectPermission.XIAOWAN),
                schemaSql = null,
            )
        }.exceptionOrNull()
        assertTrue(denied?.message.orEmpty().contains("network permission"))

        SandboxProjectToolPolicy.validate(
            pluginId = "local.project.live-scores",
            toolkit = toolkit,
            permissions = listOf(SandboxProjectPermission.NETWORK),
            schemaSql = null,
        )
    }

    @Test
    fun `public JSON connector rejects insecure origins`() {
        val toolkit = SandboxProjectToolkit(
            connectors = listOf(
                SandboxProjectConnector(
                    id = "scores",
                    type = "http_json",
                    config = JsonObject(
                        mapOf("base_url" to JsonPrimitive("http://example.org/api")),
                    ),
                ),
            ),
            tools = listOf(
                SandboxProjectTool(
                    name = "list_games",
                    displayName = "List Games",
                    description = "Fetch live games from the declared public source.",
                    parameters = JsonObject(mapOf("type" to JsonPrimitive("object"))),
                    executor = SandboxProjectToolExecutorSpec(
                        connector = "scores",
                        action = "get",
                        config = JsonObject(mapOf("path" to JsonPrimitive("/games"))),
                    ),
                ),
            ),
        )

        val error = runCatching {
            SandboxProjectToolPolicy.validate(
                pluginId = "local.project.live-scores",
                toolkit = toolkit,
                permissions = listOf(SandboxProjectPermission.NETWORK),
                schemaSql = null,
            )
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("must use HTTPS"))
    }

    @Test
    fun `xiaowan request defaults to concise no-reasoning execution`() {
        val request = SandboxProjectConnectorRegistry.buildXiaowanRequest(
            config = JsonObject(
                mapOf(
                    "instruction" to JsonPrimitive("Create a short plan."),
                    "max_tokens" to JsonPrimitive(256),
                ),
            ),
            args = JsonObject(mapOf("goal" to JsonPrimitive("Exercise daily"))),
        )

        assertEquals(256, request.maxCompletionTokens)
        assertEquals("none", request.reasoningEffort)
        assertEquals(false, request.enableThinking)
        assertEquals("disabled", request.thinking?.type)
        assertTrue(request.stream)
        assertFalse(request.messages.last().content.toString().contains("source code"))
    }

    @Test
    fun `xiaowan request leaves output token budget to the provider when omitted`() {
        val request = SandboxProjectConnectorRegistry.buildXiaowanRequest(
            config = JsonObject(
                mapOf("instruction" to JsonPrimitive("Return the result.")),
            ),
            args = JsonObject(mapOf("goal" to JsonPrimitive("Complete the task."))),
        )

        assertEquals(null, request.maxCompletionTokens)
    }

    @Test
    fun `framework tool title is not forwarded to project connectors`() {
        val sanitized = SandboxProjectConnectorRegistry.sanitizeArguments(
            JsonObject(
                mapOf(
                    "tool_title" to JsonPrimitive("记录健身打卡"),
                    "exercise" to JsonPrimitive("杠铃深蹲"),
                ),
            ),
        )

        assertFalse("tool_title" in sanitized)
        assertEquals("杠铃深蹲", sanitized["exercise"]?.let { (it as JsonPrimitive).content })
    }

    private fun tool(connector: String): SandboxProjectTool = SandboxProjectTool(
        name = "create_plan",
        displayName = "Create Plan",
        description = "Create a useful plan.",
        parameters = JsonObject(mapOf("type" to JsonPrimitive("object"))),
        executor = SandboxProjectToolExecutorSpec(
            connector = connector,
            action = "invoke",
            config = JsonObject(mapOf("instruction" to JsonPrimitive("Create a plan."))),
        ),
    )
}
