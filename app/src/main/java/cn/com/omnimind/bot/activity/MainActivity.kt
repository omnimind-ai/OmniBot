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
import cn.com.omnimind.bot.localmodel.LocalModelFeature
import cn.com.omnimind.bot.terminal.EmbeddedTerminalAutoStartManager
import cn.com.omnimind.bot.terminal.EmbeddedTerminalBackupManager
import cn.com.omnimind.bot.terminal.EmbeddedTerminalInitCoordinator
import cn.com.omnimind.bot.terminal.EmbeddedTerminalRuntime
import cn.com.omnimind.bot.quicklog.QuickLogWidgetActionRouter
import cn.com.omnimind.bot.ui.channel.ChannelManager
import cn.com.omnimind.bot.ui.channel.FileSaveChannel
import cn.com.omnimind.bot.ui.halfScreen.HalfScreenListenerImpl
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
    private val embeddedTerminalBackupManager by lazy {
        EmbeddedTerminalBackupManager(this)
    }

    private lateinit var halfScreenListenerImpl: HalfScreenListenerImpl
    private var isHalfScreenInitialized = false

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

        halfScreenListenerImpl = HalfScreenListenerImpl(this)
        OmniLog.d(TAG, "MainActivity HalfScreenListenerImpl created (not initialized yet)")

        SchemeUtil.pushRoute(intent, channelManager, null)

        applyHideFromRecentsSetting()
        lifecycleScope.launch {
            runCatching {
                embeddedTerminalAutoStartManager.runEnabledTasksOnAppOpen()
            }.onFailure { error ->
                OmniLog.e(TAG, "MainActivity auto-start Alpine tasks failed", error)
            }
        }
        lifecycleScope.launch {
            runCatching {
                embeddedTerminalBackupManager.ensureOnAppOpen()
            }.onFailure { error ->
                OmniLog.e(TAG, "MainActivity auto-start backup scheduler failed", error)
            }
        }
        lifecycleScope.launch {
            runCatching {
                LocalModelFeature.handleAppOpen(this@MainActivity)
            }.onFailure { error ->
                OmniLog.e(TAG, "MainActivity auto-start local model service failed", error)
            }
        }
        if (savedInstanceState == null) {
            prepareEmbeddedTerminalOnFirstLaunchIfNeeded()
        }

        OmniLog.d(TAG, "MainActivity onCreate total cost: ${System.currentTimeMillis() - mainActivityStart}ms")
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

    fun initializeHalfScreenEngine() {
        if (!isHalfScreenInitialized) {
            val halfScreenInitStart = System.currentTimeMillis()
            OmniLog.d(TAG, "MainActivity initializeHalfScreenEngine start")

            halfScreenListenerImpl.init()
            isHalfScreenInitialized = true

            OmniLog.d(TAG, "MainActivity initializeHalfScreenEngine cost: ${System.currentTimeMillis() - halfScreenInitStart}ms")

            try {
                if (!AssistsUtil.Core.isInitialized()) {
                    AssistsUtil.Core.initCore(App.instance, halfScreenListenerImpl)
                    OmniLog.d(TAG, "MainActivity initializeHalfScreenEngine: AssistsCore初始化完成")
                } else {
                    OmniLog.d(TAG, "MainActivity initializeHalfScreenEngine: AssistsCore已初始化，跳过")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "MainActivity initializeHalfScreenEngine: 初始化AssistsCore异常", e)
                e.printStackTrace()
            }
        } else {
            OmniLog.d(TAG, "MainActivity HalfScreen engine already initialized, skipping")
        }
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
        if (LocalModelFeature.onActivityResult(this, requestCode, resultCode, data)) {
            return
        }
        if (isHalfScreenInitialized && halfScreenListenerImpl.onActivityResult(requestCode, resultCode, data)) {
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (isHalfScreenInitialized &&
            halfScreenListenerImpl.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ) {
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onResume() {
        super.onResume()
        TaskRuntimeSettings.attachActivity(this)
        TaskRuntimeSettings.onActivityResumed(this)
        AppUpdateManager.requestSilentCheckIfDue(this)

        try {
            val isAssistsCoreInitialized = AssistsUtil.Core.isInitialized()
            OmniLog.i(TAG, "[MainActivity] onResume: AssistsCore.isInitialized=$isAssistsCoreInitialized, isHalfScreenInitialized=$isHalfScreenInitialized")

            if (isAssistsCoreInitialized) {
                OmniLog.i(TAG, "[MainActivity] onResume: AssistsCore已初始化，跳过")
                return
            }

            if (isHalfScreenInitialized) {
                OmniLog.i(TAG, "[MainActivity] onResume: 副引擎已初始化，开始初始化AssistsCore")
                AssistsUtil.Core.initCore(App.instance, halfScreenListenerImpl)
            } else {
                OmniLog.w(TAG, "[MainActivity] onResume: 副引擎未初始化，无法初始化AssistsCore！这可能是因为当前仍停留在欢迎页或其他未渲染 HomePage 的场景")
                OmniLog.i(TAG, "[MainActivity] onResume: 将在副引擎初始化完成后自动初始化AssistsCore")
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "[MainActivity] onResume: 初始化异常", e)
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        TaskRuntimeSettings.detachActivity(this)
        if (isHalfScreenInitialized) {
            halfScreenListenerImpl.onDestroy()
        }
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
            OmniLog.w(TAG, "首次启动后台准备 Alpine 环境已跳过：当前设备 ABI 不支持 Alpine 终端。")
            return
        }

        runCatching {
            val started = EmbeddedTerminalInitCoordinator.startInBackground(applicationContext)
            OmniLog.d(
                TAG,
                if (started) {
                    "首次启动开始在后台准备内嵌 Alpine 环境。"
                } else {
                    "首次启动后台 Alpine 环境准备已在进行中，跳过重复触发。"
                }
            )
        }.onFailure { error ->
            prefs.edit()
                .putBoolean(
                    LauncherActivity.KEY_EMBEDDED_TERMINAL_FIRST_LAUNCH_INIT_PENDING,
                    true
                )
                .apply()
            OmniLog.e(TAG, "首次启动后台准备 Alpine 环境失败", error)
        }
    }
}
