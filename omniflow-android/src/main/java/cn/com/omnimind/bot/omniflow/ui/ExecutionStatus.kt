package cn.com.omnimind.bot.omniflow.ui

import android.content.Context
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.bot.omniflow.R

internal enum class ExecutionPhase {
    REASONING,
    AUTOMATIC;

    fun getLabel(context: Context): String {
        val localizedContext = AppLocaleManager.localizedContext(context)
        return when (this) {
            REASONING -> localizedContext.getString(R.string.omniflow_phase_reasoning)
            AUTOMATIC -> localizedContext.getString(R.string.omniflow_phase_automatic)
        }
    }
}

internal fun initialExecutionPhase(usesModel: Boolean): ExecutionPhase =
    if (usesModel) ExecutionPhase.REASONING else ExecutionPhase.AUTOMATIC

internal class ExecutionStatusState(
    private val context: Context,
    initialPhase: ExecutionPhase
) {
    private var activePhase = initialPhase
    private var paused = false

    val label: String
        @Synchronized get() {
            val localizedContext = AppLocaleManager.localizedContext(context)
            return if (paused) {
                localizedContext.getString(R.string.omniflow_paused_label)
            } else {
                activePhase.getLabel(context)
            }
        }

    @Synchronized
    fun updatePhase(phase: ExecutionPhase) {
        activePhase = phase
    }

    @Synchronized
    fun setPaused(value: Boolean) {
        paused = value
    }
}
