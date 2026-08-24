package cn.com.omnimind.bot.omniflow.ui

import android.content.Context
import cn.com.omnimind.androidgui.AndroidGuiAccessibilityStatus
import cn.com.omnimind.androidgui.AndroidGuiEnvironment
import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.OobActionSchema
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class ExecutionControls private constructor(
    private val controls: ExecutionOverlay.Session?,
    private val guiEnvironment: AndroidGuiEnvironment,
    private val stopRequested: AtomicBoolean,
    private val completionRequested: AtomicBoolean,
    private val dispatchStop: () -> Unit,
) {
    suspend fun awaitRunning() {
        controls?.awaitRunning()
        if (completionRequested.get()) throw ManualCompletionRequested()
        if (stopRequested.get()) throw CancellationException("GUI execution stopped")
    }

    fun requestStop() {
        controls?.requestStop() ?: dispatchStop()
    }

    fun update(message: String) {
        controls?.update(message)
    }

    fun updatePhase(phase: ExecutionPhase) {
        controls?.updatePhase(phase)
    }

    suspend fun avoidAction(action: Action) {
        val activeControls = controls ?: return
        activeControls.avoidTarget(action.relativeTargetY())
        val display = guiEnvironment.displaySize()
        val feedback = executionActionFeedback(
            action = action,
            displayWidth = display.first,
            displayHeight = display.second,
        ) ?: return
        withContext(Dispatchers.Main.immediate) {
            activeControls.showActionFeedback(feedback)
        }
    }

    suspend fun restoreDefaultPosition() {
        controls?.restoreDefaultPosition()
    }

    suspend fun hideForScreenshot() {
        controls?.hideForScreenshot()
    }

    suspend fun showAfterScreenshot() {
        controls?.showAfterScreenshot()
    }

    suspend fun finish(message: String, visibleMs: Long = 900L) {
        withContext(NonCancellable) {
            controls?.finish(message, visibleMs)
        }
    }

    companion object {
        suspend fun start(
            context: Context,
            title: String,
            initialPhase: ExecutionPhase,
            onStop: () -> Unit,
            onComplete: () -> Unit,
        ): ExecutionControls {
            val stopRequested = AtomicBoolean(false)
            val completionRequested = AtomicBoolean(false)
            val dispatchStop = {
                if (stopRequested.compareAndSet(false, true)) onStop()
            }
            val dispatchComplete = {
                if (completionRequested.compareAndSet(false, true)) onComplete()
            }
            val guiEnvironment = AndroidGuiEnvironment(context)
            if (
                guiEnvironment.accessibilityStatus() ==
                AndroidGuiAccessibilityStatus.CONNECTING
            ) {
                guiEnvironment.awaitReady()
            }
            val controls = withContext(Dispatchers.Main) {
                ExecutionOverlay.show(
                    context = context,
                    goal = title,
                    initialPhase = initialPhase,
                    onComplete = dispatchComplete,
                    onStop = dispatchStop,
                )
            }
            return ExecutionControls(
                controls,
                guiEnvironment,
                stopRequested,
                completionRequested,
                dispatchStop,
            )
        }
    }
}

internal class ManualCompletionRequested : CancellationException("GUI task completed by user")

private fun Action.relativeTargetY(): Double? = when (tool) {
    OobActionSchema.TOOL_CLICK,
    OobActionSchema.TOOL_LONG_PRESS,
    -> (args[OobActionSchema.ARG_Y] as? Number)?.toDouble()
    OobActionSchema.TOOL_SWIPE -> listOfNotNull(
        (args[OobActionSchema.ARG_Y1] as? Number)?.toDouble(),
        (args[OobActionSchema.ARG_Y2] as? Number)?.toDouble(),
    ).takeIf(List<Double>::isNotEmpty)?.average()
    else -> null
}
