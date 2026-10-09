package cn.com.omnimind.bot.activity

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.lifecycle.LifecycleCoroutineScope
import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.terminal.EmbeddedTerminalAutoStartManager
import cn.com.omnimind.bot.update.AppUpdateManager
import kotlinx.coroutines.launch

/**
 * Launch and foreground behaviors shared by the app's two entry activities
 * (batch 5e-7e). They lived only in [MainActivity], so the opt-in native
 * Home never auto-started terminal tasks, refreshed the account session or
 * checked for updates.
 */
internal object AppEntryBehaviors {
    private const val TAG = "AppEntryBehaviors"

    /** Phones stay portrait; tablets (smallest width 600 dp) rotate freely. */
    fun applyResponsiveOrientation(activity: Activity) {
        activity.requestedOrientation = orientationFor(activity.resources.configuration.smallestScreenWidthDp)
    }

    fun orientationFor(smallestScreenWidthDp: Int): Int =
        if (smallestScreenWidthDp >= 600) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

    /**
     * Starts the enabled terminal auto-start tasks once per launch. Call it
     * only on a fresh start (no saved state): a running session reports
     * `alreadyRunning`, but a recreate must not even try.
     */
    fun onAppOpen(activity: Activity, scope: LifecycleCoroutineScope) {
        scope.launch {
            runCatching { EmbeddedTerminalAutoStartManager(activity).runEnabledTasksOnAppOpen() }
                .onFailure { OmniLog.e(TAG, "Terminal auto-start tasks failed", it) }
        }
    }

    /** The update check (rate-limited by its owner) and a best-effort account session refresh. */
    fun onForeground(activity: Activity, scope: LifecycleCoroutineScope) {
        AppUpdateManager.requestSilentCheckIfDue(activity)
        scope.launch {
            runCatching {
                if (OmniAccount.isConfigured()) OmniAccount.repository().refreshSessionIfNeeded()
            }.onFailure { error ->
                // The request owner still handles a real 401; this only keeps a normally
                // expired access token from looking like a logout after app switching.
                OmniLog.w(TAG, "Foreground account session refresh skipped", error)
            }
        }
    }
}
