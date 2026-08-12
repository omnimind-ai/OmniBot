package cn.com.omnimind.bot.activity

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.App
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.quicklog.QuickLogWidgetActionRouter
import cn.com.omnimind.bot.update.PrivacyConsentDecision
import cn.com.omnimind.bot.update.PrivacyConsentPolicy
import cn.com.omnimind.bot.update.PrivacyConsentStore
import cn.com.omnimind.bot.util.PredictiveBackGate

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
        PredictiveBackGate.install(this)
        OmniLog.d(TAG, "LauncherActivity onCreate")
        if (PrivacyConsentPolicy.shouldPrompt(PrivacyConsentStore.getDecision(this))) {
            showPrivacyConsentDialog()
            return
        }
        continueLaunch()
    }

    private fun continueLaunch() {
        if (QuickLogWidgetActionRouter.consumeInto(this, intent)) {
            finish()
            return
        }
        showLoadingAndStartMain()
    }

    private fun showPrivacyConsentDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.privacy_consent_title)
            .setMessage(R.string.privacy_consent_message)
            .setPositiveButton(R.string.privacy_consent_allow) { _, _ ->
                recordPrivacyDecisionAndContinue(PrivacyConsentDecision.GRANTED)
            }
            .setNegativeButton(R.string.privacy_consent_decline) { _, _ ->
                recordPrivacyDecisionAndContinue(PrivacyConsentDecision.DECLINED)
            }
            .setCancelable(false)
            .show()
    }

    private fun recordPrivacyDecisionAndContinue(decision: PrivacyConsentDecision) {
        PrivacyConsentStore.recordDecision(this, decision)
        (application as? App)?.initSDKsAfterPrivacyConsent()
        continueLaunch()
    }

    private fun applyResponsiveOrientation() {
        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
        requestedOrientation = if (isTablet) {
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
