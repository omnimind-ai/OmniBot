package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import cn.com.omnimind.bot.ui.channel.DemoHistoryMutationResult
import io.flutter.plugin.common.MethodChannel

class AcpMaintenanceAdmissionTest {
    @Test fun resetWaitsForAdmittedRequestAndBlocksEveryNewRequest() = runBlocking {
        val admission = AcpMaintenanceAdmission()
        val request = admission.admitRequest()
        val pending = admission.startReset()
        assertFalse(request.isCompleted)
        assertEquals(listOf(request), pending)
        try { admission.admitRequest(); fail("Reset must reject a new request") }
        catch (_: IllegalStateException) { }
        val drained = async { pending.forEach { it.await() }; true }
        yield()
        assertFalse(drained.isCompleted)
        admission.releaseRequest(request)
        assertTrue(drained.await())
        admission.finishReset()
        admission.releaseRequest(admission.admitRequest())
    }

    @Test fun projectionFenceDrainsExistingCallbackAndRejectsLateCallbacks() = runBlocking {
        val admission = AcpMaintenanceAdmission()
        val projection = requireNotNull(admission.admitProjection())
        admission.startReset()
        val pending = admission.fenceProjections()
        assertEquals(listOf(projection), pending)
        assertNull(admission.admitProjection())
        assertFalse(projection.isCompleted)
        admission.releaseProjection(projection)
        pending.forEach { it.await() }
        admission.finishReset()
        assertNotNull(admission.admitProjection())
    }

    @Test fun failedRequestAndAbortedResetReleaseAdmissionWithoutCompletingOtherRequests() {
        val admission = AcpMaintenanceAdmission()
        val other = admission.admitRequest()
        val invalidRequest = admission.admitRequest()
        try { error("Compatibility adapter rejected the request") }
        catch (_: IllegalStateException) { }
        finally { admission.releaseRequest(invalidRequest) }
        assertTrue(invalidRequest.isCompleted)
        assertEquals(listOf(other), admission.startReset())
        admission.finishReset()
        assertFalse(other.isCompleted)
        admission.releaseRequest(admission.admitRequest())
        admission.releaseRequest(other)
    }

    @Test fun queuedHistoryWriteCannotRunAfterResetDeletesItsContent() = runBlocking {
        val admission = AcpMaintenanceAdmission()
        val content = mutableListOf<String>()
        val queuedCreate = admission.admitHistoryMutation(persistenceDrain = false)
        val reply = DemoHistoryMutationResult(object : MethodChannel.Result {
            override fun success(result: Any?) = Unit
            override fun error(code: String, message: String?, details: Any?) = Unit
            override fun notImplemented() = Unit
        }) { admission.releaseHistoryMutation(queuedCreate) }
        val proceed = CompletableDeferred<Unit>()
        val create = launch {
            proceed.await()
            content.add("initial conversation")
            reply.success(1L)
        }
        admission.startReset()
        try { admission.admitHistoryMutation(false); fail("New creation must be rejected") }
        catch (_: IllegalStateException) { }
        admission.allowPersistenceDrain()
        val pending = admission.fenceHistoryMutations()
        val reset = async { pending.forEach { it.await() }; content.clear() }
        yield()
        assertFalse(reset.isCompleted)
        proceed.complete(Unit)
        create.join()
        reset.await()
        reply.success(1L)
        assertTrue(content.isEmpty())
        admission.finishReset()
    }

    @Test fun admittedCoordinatorPersistenceDrainsBeforeHistoryFence() = runBlocking {
        val admission = AcpMaintenanceAdmission()
        admission.startReset()
        admission.allowPersistenceDrain()
        val update = admission.admitHistoryMutation(persistenceDrain = true)
        val pending = admission.fenceHistoryMutations()
        assertEquals(listOf(update), pending)
        try { admission.admitHistoryMutation(true); fail("Deletion must reject late persistence") }
        catch (_: IllegalStateException) { }
        admission.releaseHistoryMutation(update)
        pending.forEach { it.await() }
        admission.finishReset()
    }
}
