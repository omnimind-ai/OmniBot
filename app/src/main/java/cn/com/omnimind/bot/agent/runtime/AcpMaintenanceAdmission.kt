package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.CompletableDeferred

/** Request/projection resource admission only. ACP still owns every turn outcome. */
internal class AcpMaintenanceAdmission {
    private val lock = Any()
    private var resetting = false
    private var projectionsFenced = false
    private var historyFenced = false
    private var persistenceDrainAllowed = false
    private val requests = mutableSetOf<CompletableDeferred<Unit>>()
    private val projections = mutableSetOf<CompletableDeferred<Unit>>()
    private val historyMutations = mutableSetOf<CompletableDeferred<Unit>>()

    fun admitHistoryMutation(persistenceDrain: Boolean): CompletableDeferred<Unit> = synchronized(lock) {
        check(!historyFenced && (!resetting || (persistenceDrain && persistenceDrainAllowed))) {
            "Demo reset is in progress. Please try again when it finishes."
        }
        CompletableDeferred<Unit>().also(historyMutations::add)
    }

    fun releaseHistoryMutation(completion: CompletableDeferred<Unit>) = synchronized(lock) {
        historyMutations.remove(completion)
        completion.complete(Unit)
        Unit
    }

    fun allowPersistenceDrain() = synchronized(lock) {
        check(resetting && !historyFenced)
        persistenceDrainAllowed = true
    }

    fun fenceHistoryMutations(): List<CompletableDeferred<Unit>> = synchronized(lock) {
        check(resetting)
        historyFenced = true
        persistenceDrainAllowed = false
        historyMutations.toList()
    }

    fun admitRequest(): CompletableDeferred<Unit> = synchronized(lock) {
        check(!resetting) { "Demo reset is in progress. Please try again when it finishes." }
        CompletableDeferred<Unit>().also(requests::add)
    }

    fun admitProjection(): CompletableDeferred<Unit>? = synchronized(lock) {
        if (projectionsFenced) null else CompletableDeferred<Unit>().also(projections::add)
    }

    fun releaseRequest(completion: CompletableDeferred<Unit>) = synchronized(lock) {
        requests.remove(completion)
        completion.complete(Unit)
        Unit
    }

    fun releaseProjection(completion: CompletableDeferred<Unit>) = synchronized(lock) {
        projections.remove(completion)
        completion.complete(Unit)
        Unit
    }

    fun startReset(): List<CompletableDeferred<Unit>> = synchronized(lock) {
        check(!resetting)
        resetting = true
        requests.toList()
    }

    fun fenceProjections(): List<CompletableDeferred<Unit>> = synchronized(lock) {
        check(resetting)
        projectionsFenced = true
        projections.toList()
    }

    fun finishReset() = synchronized(lock) {
        resetting = false
        projectionsFenced = false
        historyFenced = false
        persistenceDrainAllowed = false
    }
}
