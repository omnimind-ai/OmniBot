package cn.com.omnimind.bot.webchat

import kotlinx.coroutines.CoroutineScope
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = MutableSharedFlow<RealtimeEvent>()
    private val pendingEvents = Channel<RealtimeEvent>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in pendingEvents) {
                events.emit(event)
            }
        }
    }

    fun stream(): SharedFlow<RealtimeEvent> = events.asSharedFlow()

    fun publish(
        event: String,
        data: Map<String, Any?> = emptyMap()
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
        check(pendingEvents.trySend(wrapped).isSuccess) {
            "Realtime event queue is unavailable"
        }
    }
}
