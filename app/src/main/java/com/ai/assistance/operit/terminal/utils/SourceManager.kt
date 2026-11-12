package com.ai.assistance.operit.terminal.utils

import android.content.Context
import com.rk.terminal.runtime.AlpineRepositoryManager
import com.rk.terminal.runtime.UbuntuRepositoryManager
import com.rk.settings.Settings
import com.rk.terminal.ui.screens.settings.WorkingMode

class SourceManager(
    @Suppress("unused") private val context: Context
) {
    fun buildRepositorySetupCommand(): String {
        return when (distributionWorkingMode) {
            WorkingMode.ALPINE -> AlpineRepositoryManager.buildSelectedRepositoryUpdateWithFallbackCommand()
            WorkingMode.UBUNTU -> UbuntuRepositoryManager.buildSelectedRepositoryUpdateWithFallbackCommand()
            else -> ""
        }
    }

    val distributionWorkingMode: Int
        get() = Settings.terminal_distribution
}
