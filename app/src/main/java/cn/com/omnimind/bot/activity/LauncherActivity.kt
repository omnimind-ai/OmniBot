package cn.com.omnimind.bot.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.quicklog.QuickLogWidgetActionRouter
import cn.com.omnimind.bot.util.PredictiveBackGate

/**
 * 启动页 Activity
 *
 * Chooses the opt-in Compose home or the existing Flutter entry point.
 * Explicit routes retain their existing Activity owner during the migration.
 */
class LauncherActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LauncherActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(StartupThemeResolver.resolveSplashTheme(this))
        AppEntryBehaviors.applyResponsiveOrientation(this)
        super.onCreate(savedInstanceState)
        PredictiveBackGate.install(this)
        OmniLog.d(TAG, "LauncherActivity onCreate")
        if (QuickLogWidgetActionRouter.consumeInto(this, intent)) {
            finish()
            return
        }
        showLoadingAndStartMain()
    }

    /**
     * 展示 Loading 动画并启动 MainActivity
     * 使用主题的 windowBackground 作为等待背景，Flutter 渲染完成后自然覆盖
     */
    private fun showLoadingAndStartMain() {
        OmniLog.d(TAG, "showLoadingAndStartMain")
        // 主题已设置 windowBackground，无需额外布局
        // 启动 MainActivity（Flutter 引擎）
        startMainActivity()
    }


    private fun welcomeCompleted(): Boolean =
        getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
            .getBoolean("flutter.welcome_completed", false)

    private fun startMainActivity() {
        // Explicit native routes/notification intents continue to use their existing owner.
        val hasRoutedIntent = intent.data != null || intent.extras?.isEmpty == false
        // Native Home skipped onboarding entirely, so a fresh install never set up a Provider (5e-10);
        // with native Home on, first use now runs the native onboarding (5f-1b).
        val destination = when {
            !BuildConfig.NATIVE_HOME_ENABLED || hasRoutedIntent -> MainActivity::class.java
            welcomeCompleted() -> NativeHomeActivity::class.java
            else -> NativeOnboardingActivity::class.java
        }
        val intent = Intent(this, destination).apply {
            // 传递原始 Intent 的数据（用于 Deep Link 处理）
            data = this@LauncherActivity.intent.data
            action = this@LauncherActivity.intent.action
            this@LauncherActivity.intent.extras?.let { putExtras(it) }
        }
        OmniLog.d(TAG, "startMainActivity")
        startActivity(intent)
        // 不调用 finish()，让 MainActivity 的 Flutter 页面自然覆盖 Loading
        // LauncherActivity 会在 MainActivity 渲染完成后被系统回收
    }

    override fun onStop() {
        super.onStop()
        // 当 MainActivity 覆盖 LauncherActivity 后，finish 自己
        if (!isFinishing) {
            OmniLog.d(TAG, "LauncherActivity onStop, finishing")
            finish()
        }
    }
}
