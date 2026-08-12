package cn.com.omnimind.bot.agent.tool.handlers

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject

internal enum class LocalUserConfirmationDecision {
    APPROVED,
    DENIED,
    UNAVAILABLE,
}

/**
 * The only issuer for local UI grants. The grant is created inside the native positive-button
 * callback and consumed before this method returns, so neither prompts nor model tool arguments
 * can manufacture, retain, or replay it.
 */
internal object LocalUserConfirmationStore : Application.ActivityLifecycleCallbacks {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tokenStore = LocalUserConfirmationTokenStore()
    private val requestInProgress = AtomicBoolean(false)

    @Volatile
    private var initialized = false

    @Volatile
    private var resumedActivity = WeakReference<Activity>(null)

    fun initialize(application: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            application.registerActivityLifecycleCallbacks(this)
            initialized = true
        }
    }

    suspend fun requestAndConsume(
        operationName: String,
        arguments: JsonObject,
        title: String,
        message: String,
        english: Boolean,
    ): LocalUserConfirmationDecision {
        if (!requestInProgress.compareAndSet(false, true)) {
            return LocalUserConfirmationDecision.UNAVAILABLE
        }
        return suspendCancellableCoroutine { continuation ->
            var dialog: AlertDialog? = null
            val completed = AtomicBoolean(false)
            fun finish(decision: LocalUserConfirmationDecision) {
                if (!completed.compareAndSet(false, true)) return
                requestInProgress.set(false)
                if (continuation.isActive) continuation.resume(decision)
            }

            continuation.invokeOnCancellation {
                mainHandler.post {
                    finish(LocalUserConfirmationDecision.UNAVAILABLE)
                    dialog?.dismiss()
                }
            }

            val posted = mainHandler.post {
                if (!continuation.isActive) {
                    finish(LocalUserConfirmationDecision.UNAVAILABLE)
                    return@post
                }
                val activity = resumedActivity.get()
                if (activity == null || activity.isFinishing || activity.isDestroyed) {
                    finish(LocalUserConfirmationDecision.UNAVAILABLE)
                    return@post
                }

                dialog = AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(if (english) "Approve once" else "仅批准本次") { _, _ ->
                        val token = tokenStore.issueFromTrustedUserAction(operationName, arguments)
                        val approved = tokenStore.consume(token, operationName, arguments)
                        finish(
                            if (approved) LocalUserConfirmationDecision.APPROVED
                            else LocalUserConfirmationDecision.UNAVAILABLE,
                        )
                    }
                    .setNegativeButton(if (english) "Deny" else "拒绝") { _, _ ->
                        finish(LocalUserConfirmationDecision.DENIED)
                    }
                    .setOnCancelListener { finish(LocalUserConfirmationDecision.DENIED) }
                    .setOnDismissListener { finish(LocalUserConfirmationDecision.DENIED) }
                    .create()

                runCatching { dialog?.show() }
                    .onFailure { finish(LocalUserConfirmationDecision.UNAVAILABLE) }
            }
            if (!posted) finish(LocalUserConfirmationDecision.UNAVAILABLE)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity.get() === activity) resumedActivity = WeakReference(null)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumedActivity.get() === activity) resumedActivity = WeakReference(null)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
