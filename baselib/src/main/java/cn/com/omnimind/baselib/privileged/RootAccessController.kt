package cn.com.omnimind.baselib.privileged

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RootAccessStatus { DISABLED, NOT_GRANTED, CHECKING, GRANTED, UNAVAILABLE, DENIED, TIMED_OUT }

data class RootAccessSnapshot(
    val enabled: Boolean = false,
    val status: RootAccessStatus = RootAccessStatus.DISABLED,
    val generation: Long = 0,
) {
    val granted: Boolean get() = enabled && status == RootAccessStatus.GRANTED
    fun toMap(): Map<String, Any> = mapOf("enabled" to enabled, "status" to status.name, "granted" to granted)
}

internal interface RootAccessPreferences {
    var enabled: Boolean
    var previouslyGranted: Boolean
}

/** 授权申请由显式设置操作或已授权状态恢复触发，读取状态没有副作用。 */
internal class RootAccessController(
    private val preferences: RootAccessPreferences,
    private val scope: CoroutineScope,
    private val probe: suspend () -> RootAccessStatus,
    private val revoke: () -> Unit,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(
        RootAccessSnapshot(preferences.enabled, if (preferences.enabled) RootAccessStatus.NOT_GRANTED else RootAccessStatus.DISABLED)
    )
    val state = mutableState.asStateFlow()
    val snapshot: RootAccessSnapshot get() = state.value
    private var pending: Deferred<Unit>? = null
    private var initialized = false

    fun initialize() = synchronized(lock) {
        if (!initialized) {
            initialized = true
            if (snapshot.enabled && preferences.previouslyGranted) startCheck()
        }
    }

    suspend fun setEnabled(enabled: Boolean): RootAccessSnapshot {
        if (!enabled) {
            synchronized(lock) {
                preferences.enabled = false
                preferences.previouslyGranted = false
                mutableState.value = RootAccessSnapshot(generation = snapshot.generation + 1)
                pending?.cancel()
                pending = null
                revoke()
            }
            return snapshot
        }
        val check = synchronized(lock) {
            if (snapshot.granted) return snapshot
            preferences.enabled = true
            mutableState.value = snapshot.copy(enabled = true)
            startCheck()
        }
        check.await()
        return snapshot
    }

    suspend fun requestAccess(): RootAccessSnapshot {
        val check = synchronized(lock) {
            if (!snapshot.enabled || snapshot.granted) return snapshot
            startCheck()
        }
        check.await()
        return snapshot
    }

    fun markDenied() = markUnavailable(RootAccessStatus.DENIED)

    fun markUnavailable(status: RootAccessStatus = RootAccessStatus.UNAVAILABLE) {
        synchronized(lock) {
            if (!snapshot.enabled) return
            preferences.previouslyGranted = false
            mutableState.value = snapshot.copy(status = status, generation = snapshot.generation + 1)
            revoke()
        }
    }

    private fun startCheck(): Deferred<Unit> {
        pending?.takeIf { it.isActive }?.let { return it }
        val generation = snapshot.generation + 1
        preferences.previouslyGranted = false
        mutableState.value = snapshot.copy(status = RootAccessStatus.CHECKING, generation = generation)
        return scope.async {
            val result = try { probe() } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) { RootAccessStatus.UNAVAILABLE }
            synchronized(lock) {
                if (snapshot.enabled && snapshot.generation == generation) {
                    preferences.previouslyGranted = result == RootAccessStatus.GRANTED
                    mutableState.value = snapshot.copy(status = result)
                }
            }
        }.also { pending = it }
    }
}
