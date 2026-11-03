package cn.com.omnimind.baselib.privileged

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DeviceActionExecutionTest {
    private val request = PrivilegedRequest("request", "device_control.set_state")
    private fun execution(allowed: () -> Boolean = { true }) = DeviceActionExecution(request, 10, { error("Unexpected shell") }, allowed, 3, 0)
    private fun accepted() = PrivilegedResult("request", request.action, true, "ok", "")

    @Test fun acceptedCommandWithoutObservedChangeIsNotSuccess() = runBlocking {
        var writes = 0
        val result = execution().mutate(JsonPrimitive(true), read = { JsonPrimitive(false) }) { writes++; accepted() }
        assertEquals(1, writes)
        assertFalse(result.success)
        assertEquals("state_change_unconfirmed", result.code)
        assertEquals(JsonPrimitive(true), result.observation!!["commandAccepted"])
        assertEquals(JsonPrimitive(false), result.observation["verified"])
    }

    @Test fun asynchronousChangesAreReadBackWithoutRepeatingTheWrite() = runBlocking {
        var reads = 0
        var writes = 0
        val result = execution().mutate(JsonPrimitive(true), read = { JsonPrimitive(++reads >= 3) }) { writes++; accepted() }
        assertTrue(result.success)
        assertEquals(1, writes)
        assertEquals(JsonPrimitive(true), result.observation!!["changed"])
        assertEquals(JsonPrimitive(10), result.observation["userId"])
    }

    @Test fun alreadyMatchingStateDoesNotWriteAgain() = runBlocking {
        val result = execution().mutate(JsonPrimitive(true), read = { JsonPrimitive(true) }) { error("Must not write") }
        assertTrue(result.success)
        assertEquals(JsonPrimitive(false), result.observation!!["changed"])
    }

    @Test fun failedCommandCannotBecomeSuccessFromAnUnrelatedStateChange() = runBlocking {
        var read = false
        val result = execution().mutate(JsonPrimitive(true), read = { JsonPrimitive(read) }) {
            read = true
            accepted().copy(success = false, code = "command_failed")
        }
        assertFalse(result.success)
        assertEquals("command_failed", result.code)
    }

    @Test fun accessChangeBeforeWritePreventsMutation() = runBlocking {
        var allowed = true
        try {
            execution { allowed }.mutate(JsonPrimitive(true), read = { allowed = false; JsonPrimitive(false) }) { error("Must not write") }
            fail("Expected access change")
        } catch (error: DeviceActionException) { assertEquals("privileged_access_changed", error.code) }
    }

    @Test fun revokedAccessDuringVerificationReportsUnconfirmedWithoutAnotherWrite() = runBlocking {
        var allowed = true
        var reads = 0
        val result = execution { allowed }.mutate(JsonPrimitive(true), read = {
            if (++reads > 1) throw DeviceActionException("privileged_access_changed", "Revoked")
            JsonPrimitive(false)
        }) { allowed = false; accepted() }
        assertEquals("state_change_unconfirmed", result.code)
        assertFalse(result.success)
    }

    @Test fun resultKeepsTypedObservationAcrossNativeMapBoundary() {
        val observation = DeviceActionExecution.observation(null, JsonPrimitive(true), JsonPrimitive(true), true, true, 10, "device")
        val map = accepted().copy(observation = observation).toMap()["observation"] as Map<*, *>
        assertEquals(true, map["verified"])
        assertNull(map["changed"])
        assertEquals(10L, map["userId"])
    }
}
