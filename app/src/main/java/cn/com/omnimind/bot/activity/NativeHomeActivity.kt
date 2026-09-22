package cn.com.omnimind.bot.activity

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.com.omnimind.bot.ui.nativehome.LegacyHomeNavigator
import cn.com.omnimind.bot.ui.nativehome.NativeHomeRepository
import cn.com.omnimind.bot.ui.nativehome.NativeHomeViewModel
import cn.com.omnimind.bot.ui.nativehome.resolveNativeHomeLocale
import cn.com.omnimind.bot.util.PredictiveBackGate
import cn.com.omnimind.nativeui.NativeHomeApp
import cn.com.omnimind.nativeui.ThemePreference

/** Opt-in host for the first native slice. MainActivity still owns the Flutter compatibility pages. */
class NativeHomeActivity : ComponentActivity() {
    private lateinit var viewModel: NativeHomeViewModel
    private var languageOption: String? = null

    override fun attachBaseContext(newBase: Context) {
        languageOption = readLanguage(newBase)
        val locale = resolveNativeHomeLocale(languageOption, newBase.resources.configuration.locales[0])
        val localized = newBase.createConfigurationContext(
            Configuration(newBase.resources.configuration).apply { setLocale(locale) },
        )
        super.attachBaseContext(localized)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(StartupThemeResolver.resolveSplashTheme(this))
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PredictiveBackGate.install(this)
        viewModel = ViewModelProvider(this, NativeHomeViewModel.Factory(NativeHomeRepository(this)))[NativeHomeViewModel::class.java]
        val navigator = LegacyHomeNavigator(this)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (state.theme) {
                ThemePreference.System -> systemDark
                ThemePreference.Light -> false
                ThemePreference.Dark -> true
            }
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                window.isNavigationBarContrastEnforced = false
            }
            LaunchedEffect(state.error) {
                state.error?.let { Toast.makeText(this@NativeHomeActivity, it, Toast.LENGTH_LONG).show() }
            }
            NativeHomeApp(state, navigator::open, viewModel::setLocalServiceEnabled, viewModel::refresh)
        }
    }

    override fun onResume() {
        super.onResume()
        if (languageOption != readLanguage(this)) {
            recreate()
            return
        }
        viewModel.refresh()
    }

    private fun readLanguage(context: Context): String? = context.getSharedPreferences(
        "FlutterSharedPreferences", MODE_PRIVATE,
    ).getString("flutter.language_option", "system")
}
