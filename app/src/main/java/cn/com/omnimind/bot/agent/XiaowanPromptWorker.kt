package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The prompt collector stays alive to deliver ACP completion after its execution worker stops. */
internal suspend fun CoroutineScope.runXiaowanPromptWorker(
    onStarted: suspend (Job) -> Unit,
    onCancelled: suspend () -> Unit,
    execute: suspend () -> Unit,
) {
    var cancelled = false
    var failure: Throwable? = null
    // Register ownership before any provider or tool code can execute.
    val worker = launch(start = CoroutineStart.LAZY) {
        try {
            execute()
        } catch (_: CancellationException) {
            cancelled = true
        } catch (error: Throwable) {
            // Report from the producer after join; failing this child would
            // cancel its parent and discard already-admitted ACP updates.
            failure = error
        }
    }
    try {
        onStarted(worker)
    } catch (error: Throwable) {
        // An unstarted lazy child still belongs to the scope. Release it if
        // registration is rejected, otherwise structured completion can hang.
        worker.cancel()
        throw error
    }
    worker.start()
    worker.join()
    failure?.let { throw it }
    // Report from the collector, not the cancelled worker. Sending can suspend under backpressure.
    if (cancelled || worker.isCancelled) onCancelled()
}
