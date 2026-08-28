package cn.com.omnimind.bot.plugin.official

import cn.com.omnimind.bot.plugin.OmniPluginToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object OmniFlowManagementTools {
    const val LIST_FUNCTIONS = "list_functions"
    const val GET_FUNCTION = "get_function"
    const val DELETE_FUNCTION = "delete_function"
    const val CLEAR_FUNCTIONS = "clear_functions"
    const val LIST_RUN_LOGS = "list_run_logs"
    const val GET_RUN_LOG = "get_run_log"
    const val GET_RUN_LOG_STATE = "get_run_log_state"
    const val SAVE_FUNCTION = "save_function"
    const val GET_PYTHON_OVERRIDE = "get_omniflow_python_override"
    const val APPLY_PYTHON_OVERRIDE = "apply_omniflow_python_override"
    const val CLEAR_PYTHON_OVERRIDE = "clear_omniflow_python_override"
    const val RELOAD_PYTHON_OVERRIDE = "reload_omniflow_python_override"

    val TOOL_NAMES = linkedSetOf(
        LIST_FUNCTIONS,
        GET_FUNCTION,
        DELETE_FUNCTION,
        CLEAR_FUNCTIONS,
        LIST_RUN_LOGS,
        GET_RUN_LOG,
        GET_RUN_LOG_STATE,
        SAVE_FUNCTION,
        GET_PYTHON_OVERRIDE,
        APPLY_PYTHON_OVERRIDE,
        CLEAR_PYTHON_OVERRIDE,
        RELOAD_PYTHON_OVERRIDE,
    )

    fun definitions(): List<OmniPluginToolDefinition> = listOf(
        definition(
            LIST_FUNCTIONS,
            "List Functions",
            "List registered replayable GUI Functions. Use this before replay/recall when the " +
                "Function id is not already known; the returned function_id is an internal id.",
            properties = mapOf(
                "limit" to integerProperty("Maximum number of Functions to return."),
                "offset" to integerProperty("Pagination offset."),
                "include_hidden" to booleanProperty("Include Functions hidden from the Agent."),
            ),
        ),
        definition(
            GET_FUNCTION,
            "Get Function",
            "Read one registered Function artifact by id.",
            required = listOf("function_id"),
            properties = mapOf(
                "function_id" to stringProperty("Function id returned by list_functions."),
            ),
        ),
        definition(
            DELETE_FUNCTION,
            "Delete Function",
            "Delete one registered Function.",
            required = listOf("function_id"),
            properties = mapOf(
                "function_id" to stringProperty("Registered Function id."),
            ),
        ),
        definition(
            CLEAR_FUNCTIONS,
            "Clear Functions",
            "Delete every registered Function after explicit confirmation.",
            required = listOf("confirm"),
            properties = mapOf(
                "confirm" to booleanProperty("Must be true to clear all Functions."),
            ),
        ),
        definition(
            LIST_RUN_LOGS,
            "List RunLogs",
            "List persisted canonical GUI RunLogs.",
            properties = mapOf(
                "limit" to integerProperty("Maximum number of RunLogs to return."),
                "offset" to integerProperty("Pagination offset."),
                "source" to stringProperty("Optional source filter."),
                "status" to stringProperty("Optional status filter."),
                "model" to stringProperty("Optional model filter."),
                "query" to stringProperty("Optional text query."),
            ),
        ),
        definition(
            GET_RUN_LOG,
            "Get RunLog",
            "Read one canonical RunLog timeline.",
            required = listOf("run_id"),
            properties = mapOf(
                "run_id" to stringProperty("RunLog id returned by list_run_logs."),
            ),
        ),
        definition(
            GET_RUN_LOG_STATE,
            "Get RunLog State",
            "Read a RunLog state including screenshot path, display and XML.",
            required = listOf("state_id"),
            properties = mapOf(
                "state_id" to stringProperty("State id referenced by a RunLog step."),
            ),
        ),
        definition(
            SAVE_FUNCTION,
            "Save Function",
            "Save a replayable Function grounded in a successful RunLog. For semantic " +
                "enhancement, pass the existing Function in functions, set enhance=true, " +
                "and provide instruction. The official runtime performs the staged " +
                "source-grounded enhancement and writes the result to the same Store.",
            required = listOf("run_id"),
            properties = mapOf(
                "run_id" to stringProperty("Source RunLog id."),
                "functions" to arrayProperty("Existing Function draft to enhance.", objectProperty("Function artifact.")),
                "arguments" to objectProperty("Optional source arguments for the Function."),
                "enhance" to booleanProperty("Run the official staged semantic enhancement."),
                "instruction" to stringProperty("Optional enhancement guidance."),
            ),
        ),
        definition(
            GET_PYTHON_OVERRIDE,
            "Get OmniFlow Python Override",
            "Developer tool. With no path, inspect the editable OmniFlow Python override and " +
                "installation directories. With a path, read one omniflow/**/*.py file from " +
                "the active override or pinned runtime.",
            properties = mapOf(
                "path" to stringProperty("Optional Python path below omniflow/.")
            ),
        ),
        definition(
            APPLY_PYTHON_OVERRIDE,
            "Apply OmniFlow Python Override",
            "Developer tool. Replace one omniflow/**/*.py file, validate its Python syntax, " +
                "restart the OmniFlow worker, and automatically roll back if initialization fails.",
            required = listOf("path", "content"),
            properties = mapOf(
                "path" to stringProperty("Python path below omniflow/."),
                "content" to stringProperty("Complete UTF-8 Python source for the file."),
            ),
        ),
        definition(
            CLEAR_PYTHON_OVERRIDE,
            "Clear OmniFlow Python Override",
            "Developer recovery tool. Delete all editable Python overrides and restart the " +
                "worker from the pinned, validated runtime.",
            required = listOf("confirm"),
            properties = mapOf(
                "confirm" to booleanProperty("Must be true to restore the pinned runtime."),
            ),
        ),
        definition(
            RELOAD_PYTHON_OVERRIDE,
            "Reload OmniFlow Python Override",
            "Developer tool. Restart and initialize the worker from the current validated " +
                "override, or from the pinned runtime when no override is active.",
            properties = emptyMap(),
        ),
    )

    private fun definition(
        name: String,
        displayName: String,
        description: String,
        required: List<String> = emptyList(),
        properties: Map<String, kotlinx.serialization.json.JsonObject>,
    ) = OmniPluginToolDefinition(
        name = name,
        displayName = displayName,
        description = description,
        parameters = buildJsonObject {
            put("type", "object")
            if (required.isNotEmpty()) {
                put("required", buildJsonArray {
                    required.forEach { add(JsonPrimitive(it)) }
                })
            }
            put("properties", buildJsonObject {
                properties.forEach { (propertyName, schema) -> put(propertyName, schema) }
            })
            put("additionalProperties", false)
        },
    )

    private fun stringProperty(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun integerProperty(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun booleanProperty(description: String) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    private fun objectProperty(description: String) = buildJsonObject {
        put("type", "object")
        put("description", description)
    }

    private fun arrayProperty(
        description: String,
        items: kotlinx.serialization.json.JsonObject,
    ) = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", items)
    }
}
