package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class XiaowanPromptWorkerTest {
    private val immediateDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = block.run()
    }

    @Test fun `collector cancellation during registration releases unstarted worker`() = runBlocking {
        withTimeout(5000) {
            repeat(20) {
                val registering = CompletableDeferred<Job>()
                var executed = false
                var terminalPublished = false
                val collector = launch {
                    channelFlow<Unit> {
                        runXiaowanPromptWorker(
                            { worker -> registering.complete(worker); awaitCancellation() },
                            { terminalPublished = true },
                        ) { executed = true }
                    }.collect()
                }
                val worker = registering.await()
                collector.cancelAndJoin()
                assertTrue("Collector retained its lazy worker", worker.isCompleted)
                assertTrue(worker.isCancelled)
                assertFalse(executed)
                assertFalse("Disconnected collector published a terminal response", terminalPublished)
                val next = channelFlow {
                    runXiaowanPromptWorker({}, { send("unexpected cancellation") }) { send("next completed") }
                }.toList()
                assertEquals(listOf("next completed"), next)
            }
        }
    }

    @Test fun `stop while registration suspends prevents execution and completes once`() = runBlocking {
        withTimeout(5000) {
            repeat(20) {
                val registering = CompletableDeferred<Job>()
                val registered = CompletableDeferred<Unit>()
                var executed = false
                val result = async {
                    channelFlow<String> {
                        runXiaowanPromptWorker(
                            { worker -> registering.complete(worker); registered.await() },
                            { send("cancelled") },
                        ) { executed = true }
                    }.toList()
                }
                registering.await().cancelAndJoin()
                registered.complete(Unit)
                assertEquals(listOf("cancelled"), result.await())
                assertFalse(executed)
            }
        }
    }

    @Test fun `immediate dispatcher cannot execute before stop registration`() = runBlocking(immediateDispatcher) {
        var executed = false
        var cancelled = 0
        coroutineScope {
            runXiaowanPromptWorker({ it.cancel() }, { cancelled++ }) { executed = true }
        }
        assertFalse("Worker ran before the owner could cancel it", executed)
        assertEquals(1, cancelled)
    }

    @Test fun `failed registration does not execute or retain a child`() = runBlocking(immediateDispatcher) {
        var executed = false
        var cancelled = false
        val failure = withTimeout(1000) {
            runCatching {
                coroutineScope {
                    runXiaowanPromptWorker(
                        { throw IllegalStateException("Session closed") },
                        { cancelled = true },
                    ) { executed = true }
                }
            }.exceptionOrNull()
        }
        assertEquals("Session closed", failure?.message)
        assertFalse("Rejected registration executed a tool", executed)
        assertFalse("Admission failure must remain an error", cancelled)
    }

    @Test fun `provider failure drains admitted updates before surfacing the error`() = runBlocking {
        repeat(40) {
            val received = mutableListOf<String>()
            var terminal: Throwable? = null
            channelFlow {
                try {
                    runXiaowanPromptWorker({}, { send("wrong cancellation") }) {
                        repeat(3) { send("partial-$it") }
                        throw IllegalStateException("provider failure")
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    close(error)
                }
            }.buffer(64).onEach { delay(1); received += it }
                .catch { terminal = it }.collect()
            assertEquals(listOf("partial-0", "partial-1", "partial-2"), received)
            assertEquals("provider failure", terminal?.message)
        }
    }

    @Test fun `slow collector receives exactly one cancellation and next prompt completes`() = runBlocking {
        withTimeout(10000) {
            repeat(40) {
                for (capacity in listOf(0, 64)) {
                    val started = CompletableDeferred<Unit>()
                    val result = channelFlow {
                        runXiaowanPromptWorker(
                            onStarted = { worker -> launch { started.await(); worker.cancel() } },
                            onCancelled = { send("cancelled") },
                        ) {
                            send("running")
                            started.complete(Unit)
                            awaitCancellation()
                        }
                        runXiaowanPromptWorker({}, { send("unexpected cancellation") }) { send("next completed") }
                    }.buffer(capacity).onEach { if (it == "running") delay(5) }.toList()
                    assertEquals(listOf("running", "cancelled", "next completed"), result)
                }
            }
        }
    }

    @Test fun `stop before worker starts still completes the admitted prompt`() = runBlocking {
        val result = channelFlow<String> {
            runXiaowanPromptWorker(
                onStarted = { it.cancel() },
                onCancelled = { send("cancelled") },
            ) { fail("Stopped worker must not execute tools") }
        }.toList()
        assertEquals(listOf("cancelled"), result)
    }

    @Test fun `repeated stop in one session does not cancel another session`() = runBlocking {
        withTimeout(1000) {
            val otherStarted = CompletableDeferred<Unit>()
            val releaseOther = CompletableDeferred<Unit>()
            val other = async {
                channelFlow {
                    runXiaowanPromptWorker({}, { send("wrong cancellation") }) {
                        otherStarted.complete(Unit)
                        releaseOther.await()
                        send("other completed")
                    }
                }.toList()
            }
            otherStarted.await()
            val stopped = channelFlow<String> {
                runXiaowanPromptWorker(
                    onStarted = { worker -> repeat(3) { worker.cancel() } },
                    onCancelled = { send("cancelled") },
                ) { fail("Stopped worker ran") }
            }.toList()
            assertEquals(listOf("cancelled"), stopped)
            assertTrue(other.isActive)
            releaseOther.complete(Unit)
            assertEquals(listOf("other completed"), other.await())
        }
    }

    @Test fun `execution error propagates instead of becoming cancellation or success`() = runBlocking {
        val failure = IllegalStateException("provider rejected request")
        var cancellationReported = false
        try {
            channelFlow<String> {
                runXiaowanPromptWorker({}, { cancellationReported = true }) { throw failure }
            }.collect()
            fail("Execution error was swallowed")
        } catch (actual: IllegalStateException) {
            // Coroutine stack-trace recovery can copy an exception while retaining its original cause.
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
            assertEquals(failure.message, actual.message)
        }
        assertFalse(cancellationReported)
    }

    @Test fun `normal completion does not emit cancellation`() = runBlocking {
        val result = channelFlow {
            runXiaowanPromptWorker({}, { send("cancelled") }) { send("completed") }
        }.toList()
        assertEquals(listOf("completed"), result)
    }

    @Test fun `execution cancellation exception is reported even without explicit job cancellation`() = runBlocking {
        val result = channelFlow<String> {
            runXiaowanPromptWorker({}, { send("cancelled") }) { throw CancellationException("tool cancelled") }
        }.toList()
        assertEquals(listOf("cancelled"), result)
    }

    @Test fun `collector disconnect does not hang waiting to publish a terminal event`() = runBlocking {
        withTimeout(1000) {
            val result = channelFlow {
                runXiaowanPromptWorker({}, { send("cancelled") }) {
                    send("running")
                    awaitCancellation()
                }
            }.take(1).toList()
            assertEquals(listOf("running"), result)
        }
    }
}
