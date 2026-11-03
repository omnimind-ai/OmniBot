package cn.com.omnimind.bot.activity

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.quicklog.QuickLogWidgetActionRouter

/**
 * 启动页 Activity
 *
 * OSS 版本：直接进入 MainActivity，不再依赖账号与在线协议流程。
 */
class LauncherActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LauncherActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(StartupThemeResolver.resolveSplashTheme(this))
        applyResponsiveOrientation()
        super.onCreate(savedInstanceState)
        OmniLog.d(TAG, "LauncherActivity onCreate")
        if (QuickLogWidgetActionRouter.consumeInto(this, intent)) {
            finish()
            return
        }
        showLoadingAndStartMain()
    }

    private fun applyResponsiveOrientation() {
        val allowRotation = resources.configuration.smallestScreenWidthDp >= 600 || isInMultiWindowMode
        requestedOrientation = if (allowRotation) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
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

    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            // 传递原始 Intent 的数据（用于 Deep Link 处理）
            data = this@LauncherActivity.intent.data
            action = this@LauncherActivity.intent.action
            this@LauncherActivity.intent.extras?.let { putExtras(it) }
        }
        OmniLog.d(TAG, "startMainActivity")
        startActivity(intent)
        // This activity only routes intents and never draws content. Finish
        // before onResume so the starting window can transfer to MainActivity
        // without displaying an intermediate launcher window.
        finish()
    }
}
