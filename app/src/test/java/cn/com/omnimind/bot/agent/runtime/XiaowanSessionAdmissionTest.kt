@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)
package cn.com.omnimind.bot.agent.runtime

import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.model.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class XiaowanSessionAdmissionTest {
    private class Fixture {
        @Suppress("UNCHECKED_CAST")
        val type = Class.forName("cn.com.omnimind.bot.agent.runtime.XiaowanAgentSession") as Class<AgentSession>
        val session = mock(type, CALLS_REAL_METHODS)
        val mutex = Mutex()
        val closing = Mutex()
        val closed = AtomicBoolean(false)
        fun set(name: String, value: Any) {
            type.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
        init {
            set("promptMutex", mutex)
            set("closeMutex", closing)
            set("closed", closed)
            val field = type.getDeclaredField("sessionId").apply { isAccessible = true }
            field.set(session, if (field.type == String::class.java) "admission-test" else SessionId("admission-test"))
        }
        suspend fun configure() = session.setConfigOption(
            SessionConfigId("reasoning_effort"), SessionConfigOptionValue.StringValue("high"), null,
        )
    }

    @Test fun closingSessionBlocksWorkerRegistrationAndRejectsExecution() = runBlocking {
        val fixture = Fixture()
        fixture.closing.lock()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { fixture.session.prompt(emptyList(), null).collect() }.exceptionOrNull()
        }
        yield()
        val completedBeforeCloseReleased = result.isCompleted
        fixture.closed.set(true)
        fixture.closing.unlock()
        val error = withTimeout(5000) { result.await() }
        assertFalse("Prompt bypassed the ongoing close", completedBeforeCloseReleased)
        assertTrue("Worker entered after close: $error", error is IllegalStateException && error.message.orEmpty().contains("closed"))
    }

    @Test fun queuedPromptRechecksClosureBeforeCreatingWorker() = runBlocking {
        val fixture = Fixture()
        fixture.mutex.lock()
        val flow = fixture.session.prompt(emptyList(), null)
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { flow.collect() }.exceptionOrNull()
        }
        // The producer runs on this same event loop and suspends on promptMutex.
        yield()
        assertFalse(result.isCompleted)
        fixture.closed.set(true)
        fixture.mutex.unlock()
        val error = withTimeout(5000) { result.await() }
        assertTrue("Queued prompt entered a closed session: $error", error is IllegalStateException && error.message.orEmpty().contains("closed"))
    }

    @Test fun configurationRejectsReservedPromptBeforeWorkerStarts() = runBlocking {
        val fixture = Fixture()
        fixture.mutex.lock()
        try {
            val error = runCatching { fixture.configure() }.exceptionOrNull()
            assertTrue("Expected idle gate, got $error", error is IllegalStateException && error.message.orEmpty().contains("idle"))
        } finally { fixture.mutex.unlock() }
    }

    @Test fun configurationRejectsPromptStillCleaningUpAfterCancellation() = runBlocking {
        val fixture = Fixture()
        val stopping = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { stopping.complete(Unit); finish.await() } }
        }
        fixture.set("activePromptJob", job)
        fixture.mutex.lock()
        try {
            job.cancel(); stopping.await()
            assertFalse(job.isActive)
            assertFalse(job.isCompleted)
            val error = runCatching { fixture.configure() }.exceptionOrNull()
            assertTrue("Expected idle gate, got $error", error is IllegalStateException && error.message.orEmpty().contains("idle"))
        } finally { fixture.mutex.unlock(); finish.complete(Unit); job.join() }
    }

    @Test fun configurationRejectsClosedSessionBeforeTouchingSettings() = runBlocking {
        val fixture = Fixture(); fixture.closed.set(true)
        val error = runCatching { fixture.configure() }.exceptionOrNull()
        assertTrue("Expected closed gate, got $error", error is IllegalStateException && error.message.orEmpty().contains("closed"))
        assertFalse("Failed configuration retained prompt lock", fixture.mutex.isLocked)
    }

    @Test fun configurationWaitingForCloseRechecksClosureBeforeWriting() = runBlocking {
        val fixture = Fixture()
        fixture.closing.lock()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { fixture.configure() }.exceptionOrNull()
        }
        assertFalse(result.isCompleted)
        fixture.closed.set(true)
        fixture.closing.unlock()
        val error = withTimeout(5000) { result.await() }
        assertTrue("Configuration entered after close: $error",
            error is IllegalStateException && error.message.orEmpty().contains("closed"))
        assertFalse(fixture.mutex.isLocked)
        assertFalse(fixture.closing.isLocked)
    }

    @Test fun cancellingWaitingConfigurationDoesNotRetainAdmissionLocks() = runBlocking {
        val fixture = Fixture()
        fixture.closing.lock()
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { fixture.configure() }
        assertFalse(pending.isCompleted)
        withTimeout(5000) { pending.cancelAndJoin() }
        // The original owner still holds closeMutex; the cancelled waiter must
        // neither release that owner's lock nor acquire the prompt reservation.
        assertTrue(fixture.closing.isLocked)
        assertFalse(fixture.mutex.isLocked)
        fixture.closing.unlock()
        fixture.mutex.lock()
        try {
            val error = withTimeout(5000) { runCatching { fixture.configure() }.exceptionOrNull() }
            assertTrue("Next configuration could not reach the existing idle gate: $error",
                error is IllegalStateException && error.message.orEmpty().contains("idle"))
            assertFalse(fixture.closing.isLocked)
        } finally { fixture.mutex.unlock() }
    }
}
