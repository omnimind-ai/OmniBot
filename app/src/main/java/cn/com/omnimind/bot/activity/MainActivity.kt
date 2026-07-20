package cn.com.omnimind.bot.activity

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.App
import cn.com.omnimind.bot.terminal.EmbeddedTerminalAutoStartManager
import cn.com.omnimind.bot.terminal.EmbeddedTerminalInitCoordinator
import cn.com.omnimind.bot.terminal.EmbeddedTerminalRuntime
import cn.com.omnimind.bot.quicklog.QuickLogWidgetActionRouter
import cn.com.omnimind.bot.ui.channel.ChannelManager
import cn.com.omnimind.bot.ui.channel.FileSaveChannel
import cn.com.omnimind.bot.ui.platformview.AgentBrowserPlatformViewFactory
import cn.com.omnimind.bot.ui.platformview.EmbeddedTerminalPlatformViewFactory
import cn.com.omnimind.bot.update.AppUpdateManager
import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.bot.util.SchemeUtil
import cn.com.omnimind.bot.util.TaskRuntimeSettings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.launch

class MainActivity : FlutterActivity() {
    companion object {
        const val TAG = "AppStartup"
    }

    private var channelManager: ChannelManager = ChannelManager()
    private val embeddedTerminalAutoStartManager by lazy {
        EmbeddedTerminalAutoStartManager(this)
    }

    override fun provideFlutterEngine(context: android.content.Context): FlutterEngine {
        val provideStart = System.currentTimeMillis()
        OmniLog.d(TAG, "MainActivity provideFlutterEngine start")

        val engine = App.getCachedMainEngine()

        OmniLog.d(TAG, "MainActivity provideFlutterEngine cost: ${System.currentTimeMillis() - provideStart}ms")
        return engine
    }

    override fun shouldDestroyEngineWithHost(): Boolean {
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val mainActivityStart = System.currentTimeMillis()
        OmniLog.d(TAG, "MainActivity onCreate start")
        setTheme(StartupThemeResolver.resolveSplashTheme(this))
        applyResponsiveOrientation()
        applySoftInputResizeMode()
        super.onCreate(savedInstanceState)
        TaskRuntimeSettings.attachActivity(this)
        TaskRuntimeSettings.consumeTaskCompletionNotificationIntent(this, intent)

        if (QuickLogWidgetActionRouter.consumeInto(this, intent)) {
            finish()
            return
        }

        val channelStart = System.currentTimeMillis()
        channelManager.onCreate(this)
        OmniLog.d(TAG, "MainActivity channelManager.onCreate cost: ${System.currentTimeMillis() - channelStart}ms")

        if (!AssistsUtil.Core.isInitialized()) {
            AssistsUtil.Core.initCore(App.instance)
            OmniLog.d(TAG, "MainActivity initialized remaining chat task core")
        }

        SchemeUtil.pushRoute(intent, channelManager, null)

        applyHideFromRecentsSetting()
        lifecycleScope.launch {
            runCatching {
                embeddedTerminalAutoStartManager.runEnabledTasksOnAppOpen()
            }.onFailure { error ->
                OmniLog.e(TAG, "MainActivity auto-start Alpine tasks failed", error)
            }
        }
        if (savedInstanceState == null) {
            prepareEmbeddedTerminalOnFirstLaunchIfNeeded()
            requestNotificationPermission()
        }

        OmniLog.d(TAG, "MainActivity onCreate total cost: ${System.currentTimeMillis() - mainActivityStart}ms")
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(
                android.Manifest.permission.POST_NOTIFICATIONS,
                "android.permission.READ_NOTIFICATION"
            ), 1001)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        val configStart = System.currentTimeMillis()
        OmniLog.d(TAG, "MainActivity configureFlutterEngine start")

        super.configureFlutterEngine(flutterEngine)
        channelManager.configureFlutterEngine(flutterEngine)
        AgentBrowserPlatformViewFactory.registerWith(flutterEngine = flutterEngine)
        EmbeddedTerminalPlatformViewFactory.registerWith(flutterEngine = flutterEngine)

        OmniLog.d(TAG, "MainActivity configureFlutterEngine cost: ${System.currentTimeMillis() - configStart}ms")
    }

    override fun shouldHandleDeeplinking(): Boolean {
        return false
    }

    private fun applyResponsiveOrientation() {
        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
        requestedOrientation = if (isTablet) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun applySoftInputResizeMode() {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        TaskRuntimeSettings.consumeTaskCompletionNotificationIntent(this, intent)

        if (QuickLogWidgetActionRouter.consumeInto(this, intent)) {
            finish()
            return
        }

        SchemeUtil.pushRoute(intent, channelManager, null)

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (FileSaveChannel.onActivityResult(this, requestCode, resultCode, data)) {
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        TaskRuntimeSettings.attachActivity(this)
        TaskRuntimeSettings.onActivityResumed(this)
        AppUpdateManager.requestSilentCheckIfDue(this)

        if (!AssistsUtil.Core.isInitialized()) {
            AssistsUtil.Core.initCore(App.instance)
        }
    }

    override fun onDestroy() {
        TaskRuntimeSettings.detachActivity(this)
        super.onDestroy()
    }

    override fun onPause() {
        TaskRuntimeSettings.onActivityPaused(this)
        super.onPause()
    }

    private fun applyHideFromRecentsSetting() {
        lifecycleScope.launch {
            try {
                val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                val hideFromRecents = prefs.getBoolean("flutter.hide_from_recents", false)
                setExcludeFromRecents(hideFromRecents)
                OmniLog.d(TAG, "启动时应用后台隐藏设置: $hideFromRecents")
            } catch (e: Exception) {
                OmniLog.e(TAG, "应用后台隐藏设置失败", e)
            }
        }
    }

    private fun setExcludeFromRecents(exclude: Boolean) {
        try {
            val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager != null) {
                val appTasks = activityManager.appTasks
                for (appTask in appTasks) {
                    appTask.setExcludeFromRecents(exclude)
                }
                OmniLog.d(TAG, "设置应用从最近任务中排除: $exclude")
            } else {
                OmniLog.e(TAG, "无法获取ActivityManager")
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "设置excludeFromRecents失败", e)
        }
    }

    private fun prepareEmbeddedTerminalOnFirstLaunchIfNeeded() {
        val shouldPrepare = intent?.getBooleanExtra(
            LauncherActivity.EXTRA_PREPARE_EMBEDDED_TERMINAL_ON_FIRST_LAUNCH,
            false
        ) == true
        if (!shouldPrepare) {
            return
        }

        val prefs = getSharedPreferences(LauncherActivity.STARTUP_PREFS_NAME, Context.MODE_PRIVATE)
        val pending = prefs.getBoolean(
            LauncherActivity.KEY_EMBEDDED_TERMINAL_FIRST_LAUNCH_INIT_PENDING,
            true
        )
        if (!pending) {
            return
        }

        prefs.edit()
            .putBoolean(
                LauncherActivity.KEY_EMBEDDED_TERMINAL_FIRST_LAUNCH_INIT_PENDING,
                false
            )
            .apply()

        if (!EmbeddedTerminalRuntime.isSupportedDevice()) {
            OmniLog.w(TAG, "首次启动后台准备终端环境已跳过：当前设备 ABI 不支持内嵌终端。")
            return
        }

        runCatching {
            val started = EmbeddedTerminalInitCoordinator.startInBackground(applicationContext)
            OmniLog.d(
                TAG,
                if (started) {
                    "首次启动开始在后台准备内嵌终端环境。"
                } else {
                    "首次启动后台终端环境准备已在进行中，跳过重复触发。"
                }
            )
        }.onFailure { error ->
            prefs.edit()
                .putBoolean(
                    LauncherActivity.KEY_EMBEDDED_TERMINAL_FIRST_LAUNCH_INIT_PENDING,
                    true
                )
                .apply()
            OmniLog.e(TAG, "首次启动后台准备终端环境失败", error)
        }
    }
}
