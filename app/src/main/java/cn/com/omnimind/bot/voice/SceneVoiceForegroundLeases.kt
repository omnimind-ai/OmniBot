package cn.com.omnimind.bot.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** Android foreground leases only; each manager still owns its queue and players. */
internal class SceneVoiceForegroundLeases(
    private val canStart: () -> Boolean,
    private val startService: (Long) -> Unit,
    private val stopService: () -> Unit,
) {
    class Lease internal constructor(
        internal val owner: Any,
        internal val generation: Long,
        internal val onStopped: (Lease, String?) -> Unit,
    ) {
        internal val ready = CompletableDeferred<Unit>()
        @Volatile var active = true
            internal set
        val isReady: Boolean get() = active && ready.isCompleted && !ready.isCancelled
        suspend fun awaitReady() = ready.await()
    }

    private val owners = linkedMapOf<Any, Lease>()
    private var generation = 0L
    private var foreground = false

    @Synchronized
    fun acquire(owner: Any, onStopped: (Lease, String?) -> Unit): Lease {
        owners[owner]?.let { return it }
        val first = owners.isEmpty()
        if (first) {
            check(canStart()) { "请打开应用后开始朗读 / Open the app to start reading aloud" }
            generation++
            foreground = false
        }
        val lease = Lease(owner, generation, onStopped)
        owners[owner] = lease
        if (foreground) lease.ready.complete(Unit)
        if (first) {
            try {
                startService(generation)
            } catch (error: Exception) {
                owners.remove(owner)
                lease.active = false
                lease.ready.completeExceptionally(error)
                throw error
            }
        }
        return lease
    }

    @Synchronized
    fun release(lease: Lease) {
        if (owners[lease.owner] !== lease) return
        owners.remove(lease.owner)
        lease.active = false
        lease.ready.cancel(CancellationException("Voice playback stopped"))
        if (owners.isEmpty()) {
            foreground = false
            stopService()
        }
    }

    @Synchronized
    fun onForeground(generation: Long): Boolean {
        if (generation != this.generation || owners.isEmpty()) return false
        foreground = true
        owners.values.forEach { it.ready.complete(Unit) }
        return true
    }

    @Synchronized
    fun hasOwners(): Boolean = owners.isNotEmpty()

    fun stopAll(error: String? = null, expectedGeneration: Long? = null) {
        val (stoppedGeneration, stopped) = synchronized(this) {
            if (expectedGeneration != null && generation != expectedGeneration) return
            val leases = owners.values.toList()
            owners.clear()
            foreground = false
            generation to leases
        }
        // Never call a manager while holding the registry lock: managers release
        // their lease while holding their own queue lock.
        stopped.forEach { lease ->
            lease.onStopped(lease, error)
            lease.active = false
            lease.ready.cancel(CancellationException(error ?: "Voice playback stopped"))
        }
        synchronized(this) {
            if (stopped.isNotEmpty() && owners.isEmpty() && generation == stoppedGeneration) {
                stopService()
            }
        }
    }
}
