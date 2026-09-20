package cn.com.omnimind.baselib.runlog

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalRunLogSerializationTest {
    @Test
    fun persistedProtocolKeysDoNotDependOnKotlinFieldNames() {
        // A hostile naming policy exposes fields which still depend on reflection
        // names. Actual R8 serialization is additionally covered by Release replay.
        val gson = GsonBuilder().setFieldNamingStrategy { "renamed_${it.name}" }.create()
        for (status in listOf("running", "succeeded", "failed", "cancelled")) {
            val record = CanonicalRunLogRecord(
                runId = "serialization-regression", goal = "Search settings", status = status,
                success = status == "succeeded", error = "test error", startedAtMs = 10L,
                finishedAtMs = 20L, steps = listOf(mapOf("step_index" to 0)),
                finalStateId = "state_final", diagnostics = mapOf("event_seq" to 3L),
            )
            val value = JsonParser.parseString(gson.toJson(record)).asJsonObject
            assertEquals(setOf("schema_version", "run_id", "goal", "status", "success", "error",
                "started_at_ms", "finished_at_ms", "steps", "final_state_id", "diagnostics"), value.keySet())
            assertEquals(status, value["status"].asString)
            assertEquals(status == "succeeded", value["success"].asBoolean)
            assertEquals("test error", value["error"].asString)
            assertEquals(1, value["steps"].asJsonArray.size())
            assertEquals(3L, value["diagnostics"].asJsonObject["event_seq"].asLong)
        }
    }
}
