package cn.com.omnimind.bot.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.quicklog.QuickLogWidgetProvider

class QuickLogWidgetBridgeActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "QuickLogWidgetBridge"
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(
            if (newBase == null) null else AppLocaleManager.localizedContext(newBase)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent.action == QuickLogWidgetProvider.ACTION_OPEN_SHORT_MEMORIES) {
            setTheme(R.style.Theme_OmnibotApp_Transparent)
        } else {
            setTheme(R.style.Theme_OmnibotApp)
        }
        super.onCreate(savedInstanceState)
        routeWidgetAction(intent)
    }

    private fun routeWidgetAction(sourceIntent: Intent?) {
        when {
            sourceIntent == null -> OmniLog.w(TAG, "Missing widget intent")
            sourceIntent.action == QuickLogWidgetProvider.ACTION_ADD_LOG -> {
                QuickLogEditorScreen.bind(this, sourceIntent)
            }
            sourceIntent.action?.startsWith(QuickLogWidgetProvider.ACTION_EDIT_LOG) == true -> {
                QuickLogEditorScreen.bind(this, sourceIntent)
            }
            sourceIntent.action == QuickLogWidgetProvider.ACTION_OPEN_SHORT_MEMORIES -> {
                startActivity(
                    Intent(this, LauncherActivity::class.java).apply {
                        action = QuickLogWidgetProvider.ACTION_OPEN_SHORT_MEMORIES
                        putExtra("route", "/memory/memory_center_page")
                        putExtra("needClear", false)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
                finish()
            }
            else -> {
                OmniLog.w(TAG, "Unsupported widget action: ${sourceIntent.action}")
                finish()
            }
        }
    }
}
