package cn.com.omnimind.bot.webchat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class RealtimeEvent(
    val id: String,
    val event: String,
    val data: Map<String, Any?>,
    val timestamp: Long
)

object RealtimeHub {
    private data class PendingEvent(
        val event: RealtimeEvent,
        val isVisible: suspend () -> Boolean,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = MutableSharedFlow<RealtimeEvent>()
    private val pendingEvents = Channel<PendingEvent>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (pending in pendingEvents) {
                if (visible(pending.isVisible)) events.emit(pending.event)
            }
        }
    }

    fun stream(): SharedFlow<RealtimeEvent> = events.asSharedFlow()

    /** Full mirror snapshots have no consumer while WebChat is disconnected.
     * A new client loads its initial state through the conversation API; the
     * event stream has no replay. Keep observed snapshots on the existing
     * publication path so event ordering and wire payloads remain unchanged.
     */
    suspend fun publishSnapshot(
        event: String,
        isVisible: suspend () -> Boolean = { true },
        data: suspend () -> Map<String, Any?>,
    ) {
        if (events.subscriptionCount.value == 0 || !visible(isVisible)) return
        val snapshot = data()
        if (visible(isVisible)) publish(event, snapshot, isVisible)
    }

    private suspend fun visible(check: suspend () -> Boolean): Boolean = try {
        check()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Failure to establish ownership must not expose a cached snapshot or
        // kill publication for every other conversation.
        false
    }

    fun publish(
        event: String,
        data: Map<String, Any?> = emptyMap(),
        isVisible: suspend () -> Boolean = { true },
    ) {
        val timestamp = System.currentTimeMillis()
        val payload = linkedMapOf<String, Any?>(
            "event" to event,
            "timestamp" to timestamp
        ).apply {
            putAll(data)
        }
        val wrapped = RealtimeEvent(
            id = UUID.randomUUID().toString(),
            event = event,
            data = payload,
            timestamp = timestamp
        )
        check(pendingEvents.trySend(PendingEvent(wrapped, isVisible)).isSuccess) {
            "Realtime event queue is unavailable"
        }
    }
}
