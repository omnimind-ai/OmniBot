package com.rk.update

import com.rk.libcommons.application
import com.rk.libcommons.ShellAssetWriter

class UpdateManager {
    fun onUpdate() {
        ShellAssetWriter.writeTerminalShellAssets(application!!)
    }
}
