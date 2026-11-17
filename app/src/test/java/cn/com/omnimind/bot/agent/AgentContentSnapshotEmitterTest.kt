package cn.com.omnimind.bot.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContentSnapshotEmitterTest {
    @Test
    fun `burst keeps the immediate first snapshot and the complete latest text`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val callbacks = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val output = mutableListOf<String>()
        try {
            val emitter = emitter(scope, callbacks, output)
            emitter.offer("first")
            val first = callbacks.tryReceive().getOrThrow()
            repeat(300) { emitter.offer("first" + "追加 🤖".repeat(it + 1)) }
            emitter.close()

            first()
            drain(callbacks)

            assertEquals(listOf("first", "first" + "追加 🤖".repeat(300)), output)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `timer publishes the latest pending snapshot without another provider event`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val callbacks = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val output = mutableListOf<String>()
        try {
            val emitter = emitter(scope, callbacks, output)
            emitter.offer("a")
            callbacks.receive()()
            emitter.offer("ab")
            emitter.offer("abc")

            withTimeout(2_000) { callbacks.receive()() }
            emitter.close()
            drain(callbacks)

            assertEquals(listOf("a", "abc"), output)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `slow consumer has one replaceable queued snapshot across many update intervals`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val callbacks = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val output = mutableListOf<String>()
        val enqueued = Channel<Unit>(Channel.UNLIMITED)
        var now = 0L
        try {
            val emitter = AgentContentSnapshotEmitter(
                scope = scope,
                lock = Any(),
                enqueue = {
                    callbacks.trySend(it).getOrThrow()
                    enqueued.trySend(Unit).getOrThrow()
                },
                onSnapshot = { output += it },
                nowMillis = { now },
            )
            emitter.offer("first")
            enqueued.receive()
            emitter.offer("next")
            withTimeout(2_000) { enqueued.receive() }
            repeat(1_000) {
                now += 50L
                emitter.offer("snapshot-$it")
            }
            emitter.close()
            drain(callbacks)

            assertTrue(enqueued.tryReceive().isFailure)
            assertEquals(listOf("first", "snapshot-999"), output)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `flushed snapshots cannot move newer text before reasoning or tool barriers`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val callbacks = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val output = mutableListOf<String>()
        try {
            val emitter = emitter(scope, callbacks, output)
            emitter.offer("first")
            emitter.offer("before reasoning")
            emitter.flush()
            callbacks.trySend { output += "reasoning" }.getOrThrow()
            emitter.offer("before tool")
            emitter.flush()
            callbacks.trySend { output += "tool" }.getOrThrow()
            emitter.offer("after tool")
            emitter.close()
            drain(callbacks)

            assertEquals(
                listOf("first", "before reasoning", "reasoning", "before tool", "tool", "after tool"),
                output,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `close preserves accepted partial text and cancels later timer emissions`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val callbacks = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val output = mutableListOf<String>()
        try {
            val emitter = emitter(scope, callbacks, output)
            emitter.offer("partial")
            emitter.offer("partial with final accepted chunk")
            emitter.close()
            emitter.offer("unaccepted late snapshot")
            delay(100L)
            drain(callbacks)

            assertEquals(listOf("partial", "partial with final accepted chunk"), output)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancellation drops undisplayed pending and sealed queued content`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val callbacks = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val output = mutableListOf<String>()
        try {
            val emitter = emitter(scope, callbacks, output)
            emitter.offer("displayed prefix")
            callbacks.receive()()
            emitter.offer("sealed pending text")
            emitter.flush()
            emitter.offer("unsealed pending tail")
            emitter.cancelPending()
            emitter.offer("late provider callback")
            delay(100L)
            drain(callbacks)

            assertEquals(listOf("displayed prefix"), output)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `suspended callback does not hold the producer or timer lock`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val callbacks = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val output = mutableListOf<String>()
        try {
            val emitter = AgentContentSnapshotEmitter(
                scope = scope,
                lock = Any(),
                enqueue = { callbacks.trySend(it).getOrThrow() },
                onSnapshot = {
                    output += it
                    if (it == "first") {
                        started.complete(Unit)
                        release.await()
                    }
                },
                nowMillis = { 0L },
            )
            emitter.offer("first")
            val consumer = launch { callbacks.receive()() }
            withTimeout(2_000) { started.await() }
            emitter.offer("while suspended")
            emitter.close()
            release.complete(Unit)
            consumer.join()
            drain(callbacks)

            assertEquals(listOf("first", "while suspended"), output)
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `parallel streams do not share pending text timers or barriers`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val callbacks = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val firstOutput = mutableListOf<String>()
        val secondOutput = mutableListOf<String>()
        try {
            val first = emitter(scope, callbacks, firstOutput)
            val second = emitter(scope, callbacks, secondOutput)
            first.offer("a")
            second.offer("b")
            first.offer("a-final")
            second.offer("b-final")
            first.close()
            second.close()
            drain(callbacks)

            assertEquals(listOf("a", "a-final"), firstOutput)
            assertEquals(listOf("b", "b-final"), secondOutput)
        } finally {
            scope.cancel()
        }
    }

    private fun emitter(
        scope: CoroutineScope,
        callbacks: Channel<suspend () -> Unit>,
        output: MutableList<String>,
    ) = AgentContentSnapshotEmitter(
        scope = scope,
        lock = Any(),
        enqueue = { callbacks.trySend(it).getOrThrow() },
        onSnapshot = { output += it },
        nowMillis = { 0L },
    )

    private suspend fun drain(callbacks: Channel<suspend () -> Unit>) {
        while (true) {
            val callback = callbacks.tryReceive().getOrNull() ?: return
            callback()
        }
    }
}
