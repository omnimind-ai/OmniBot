package cn.com.omnimind.bot.terminal

import android.content.Context
import cn.com.omnimind.bot.agent.WorkspaceMountEntry
import cn.com.omnimind.bot.agent.WorkspaceMountManager
import com.ai.assistance.operit.terminal.TerminalManager
import com.rk.settings.Settings
import com.rk.terminal.runtime.TerminalDistribution

/**
 * Native adapter for the terminal settings page over the existing owners:
 * `EmbeddedTerminalSetupManager` (package inventory), `TerminalDistribution` +
 * `EmbeddedTerminalInitCoordinator` + `TerminalManager` (rootfs switch, same
 * sequence as the channel handler), `EmbeddedTerminalAutoStartManager`
 * (boot tasks) and `WorkspaceMountManager` (workspace mount symlinks).
 */
internal class NativeTerminalSettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val setupManager = EmbeddedTerminalSetupManager(appContext)
    private val autoStartManager = EmbeddedTerminalAutoStartManager(appContext)
    private val mountManager = WorkspaceMountManager(appContext)

    suspend fun packageInventory(): Map<String, EmbeddedTerminalSetupManager.PackageInventoryItem> =
        setupManager.getPackageInventory()

    fun selectedDistribution(): TerminalDistribution.Spec = TerminalDistribution.selected()

    fun supportedDistributions(): List<TerminalDistribution.Spec> = TerminalDistribution.supported

    /** Same sequence as the channel's switchEmbeddedTerminalDistribution. */
    suspend fun switchDistribution(distributionId: String): TerminalDistribution.Spec {
        val distribution = TerminalDistribution.supported.firstOrNull { it.id == distributionId }
            ?: throw IllegalArgumentException("Unsupported terminal distribution: $distributionId")
        val current = TerminalDistribution.selected()
        if (current.id == distribution.id) return distribution
        val preparation = EmbeddedTerminalInitCoordinator.prepareDistribution(
            context = appContext,
            distribution = distribution,
        )
        if (!preparation.success) {
            throw IllegalStateException(preparation.message)
        }
        TerminalManager.getInstance(appContext).closeAllSessions()
        Settings.terminal_distribution = distribution.workingMode
        Settings.working_Mode = distribution.workingMode
        return distribution
    }

    fun cancelDistributionSwitch(): Boolean = EmbeddedTerminalInitCoordinator.cancelCurrent()

    fun addInitProgressListener(listener: (Map<String, Any?>) -> Unit) =
        EmbeddedTerminalInitCoordinator.addListener(listener)

    fun removeInitProgressListener(listener: (Map<String, Any?>) -> Unit) =
        EmbeddedTerminalInitCoordinator.removeListener(listener)

    suspend fun listAutoStartTasks(): List<EmbeddedTerminalAutoStartManager.AutoStartTaskSnapshot> =
        autoStartManager.listTasks()

    suspend fun saveAutoStartTask(
        id: String?,
        name: String,
        command: String,
        workingDirectory: String?,
        enabled: Boolean,
    ): EmbeddedTerminalAutoStartManager.AutoStartTaskSnapshot =
        autoStartManager.saveTask(id, name, command, workingDirectory, enabled)

    suspend fun deleteAutoStartTask(taskId: String) = autoStartManager.deleteTask(taskId)

    suspend fun runAutoStartTask(taskId: String): EmbeddedTerminalAutoStartManager.TaskRunResult =
        autoStartManager.runTaskNow(taskId)

    fun listMounts(): List<WorkspaceMountEntry> = mountManager.listMounts()

    fun suggestMountAlias(sourcePath: String): String = mountManager.suggestUniqueAlias(sourcePath)

    fun validateMountAlias(alias: String): WorkspaceMountManager.AliasValidation =
        mountManager.validateAlias(alias)

    fun mount(sourcePath: String, alias: String): WorkspaceMountEntry =
        mountManager.mount(sourcePath, alias)

    fun unmount(linkPath: String) = mountManager.unmount(linkPath)
}
