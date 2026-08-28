package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.CanonicalRunLogRecord
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.OobActionSchema
import com.google.gson.GsonBuilder
import java.security.MessageDigest

object RunLogReusableFunctionCompiler {
    fun compile(
        record: CanonicalRunLogRecord,
        agentVisible: Boolean = true,
    ): Map<String, Any?> {
        require(record.success && record.status == "succeeded" && record.doneReason != "error") {
            "run_log_not_successful"
        }
        val functionSteps = record.steps.mapNotNull { rawStep ->
            val step = InternalRunLogStore.canonicalStep(rawStep)
            val result = stringMap(step["result"])
            val action = stringMap(step["action"])
            val tool = action["tool"]?.toString().orEmpty()
            if (result["success"] != true || tool !in OobActionSchema.replayableToolNames) {
                return@mapNotNull null
            }
            // The official OmniFlow save_function pipeline compares the
            // submitted Function action with the public RunLog projection.
            // Grounding-only fields (target_description, node ids, etc.) are
            // deliberately omitted from that projection, so they must not be
            // copied into a persisted Function either.
            val persistedArgs = stringMap(action["args"]).filterKeys { key ->
                key in OobActionSchema.persistedArgs(tool).map { it.name }
            }
            linkedMapOf<String, Any?>(
                "step_index" to 0,
                "source_state_id" to step["before_state_id"],
                "action" to linkedMapOf(
                    "tool" to tool,
                    "args" to persistedArgs,
                ),
            )
        }.mapIndexed { index, step -> step + ("step_index" to index) }
        require(functionSteps.isNotEmpty()) { "run_log_no_replayable_steps" }

        val goal = record.goal.trim().ifEmpty {
            record.operationDescription.trim().ifEmpty { "复用指令" }
        }
        val identity = compactGson.toJson(
            linkedMapOf(
                "goal" to goal,
                "steps" to functionSteps.map { step ->
                    linkedMapOf(
                        "source_state_id" to step["source_state_id"],
                        "action" to step["action"],
                    )
                },
            ),
        )
        return linkedMapOf(
            "schema_version" to "omniflow.function.v2",
            "function_id" to "recorded_${sha256(identity).take(16)}",
            "name" to goal.take(120),
            "description" to goal,
            "input_schema" to linkedMapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any?>(),
                "required" to emptyList<String>(),
                "additionalProperties" to false,
            ),
            "bindings" to emptyList<Map<String, Any?>>(),
            "steps" to functionSteps,
            "checker_rules" to emptyList<Map<String, Any?>>(),
            "agent_visible" to agentVisible,
        )
    }

    private fun stringMap(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)
            ?.entries
            ?.associate { (key, item) -> key.toString() to item }
            .orEmpty()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    private val compactGson = GsonBuilder().disableHtmlEscaping().create()
}
