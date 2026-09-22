package cn.com.omnimind.nativeui.settings

import androidx.compose.runtime.Immutable

enum class UpdateDownloadSource { Worker, GitHub }
enum class AboutOperation { Refresh, Check, Beta, Source, Install }
enum class AboutNotice { CheckFailed, UpToDate, PreferenceFailed, InstallFailed, DownloadFailed, InstallPermissionRequired, InstallerOpened, NotificationsDenied, BrowserUnavailable }

@Immutable
data class AboutState(
    val currentVersion: String = "",
    val latestVersion: String = "",
    val hasUpdate: Boolean = false,
    val canInstall: Boolean = false,
    val releaseUrl: String = "",
    val releaseNotes: String = "",
    val publishedAt: Long = 0,
    val betaEnabled: Boolean = false,
    val downloadSource: UpdateDownloadSource = UpdateDownloadSource.Worker,
    val operation: AboutOperation? = null,
    val showUpdate: Boolean = false,
    val notice: AboutNotice? = null,
)

data class AboutActions(
    val primary: () -> Unit,
    val dismissUpdate: () -> Unit,
    val confirmUpdate: () -> Unit,
    val setBeta: (Boolean) -> Unit,
    val setDownloadSource: (UpdateDownloadSource) -> Unit,
    val openRequestLogs: () -> Unit,
    val openRuntimeLogs: () -> Unit,
    val openUserGuide: () -> Unit,
)
