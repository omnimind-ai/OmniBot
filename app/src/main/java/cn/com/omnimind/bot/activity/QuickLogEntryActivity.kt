package cn.com.omnimind.bot.activity

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import cn.com.omnimind.baselib.i18n.AppLocaleManager

class QuickLogEntryActivity : ComponentActivity() {
    companion object {
        const val EXTRA_LOG_ID = "extra_quick_log_id"
        const val EXTRA_LOG_CONTENT = "extra_quick_log_content"
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(
            if (newBase == null) null else AppLocaleManager.localizedContext(newBase)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        QuickLogEditorScreen.bind(this, intent)
    }
}
