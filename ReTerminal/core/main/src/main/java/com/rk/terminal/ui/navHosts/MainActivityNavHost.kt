package com.rk.terminal.ui.navHosts

import android.content.res.Configuration
import android.os.Build
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.rk.settings.Settings
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.ui.routes.MainActivityRoutes
import com.rk.terminal.ui.screens.customization.Customization
import com.rk.terminal.ui.screens.downloader.Downloader
import com.rk.terminal.ui.screens.settings.Settings
import com.rk.terminal.ui.screens.terminal.Rootfs
import com.rk.terminal.ui.screens.terminal.TerminalScreen

var showStatusBar = mutableStateOf(Settings.statusBar)
var horizontal_statusBar = mutableStateOf(Settings.horizontal_statusBar)

fun showStatusBar(show: Boolean,window: Window){
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q){
        if (show){
            window.decorView.windowInsetsController!!.show(
                android.view.WindowInsets.Type.statusBars()
            )
        }else{
            window.decorView.windowInsetsController!!.hide(
                android.view.WindowInsets.Type.statusBars()
            )
        }
    }else{
        if (show){
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.statusBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
        }else{
            WindowInsetsControllerCompat(window,window.decorView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.statusBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
}

@Composable
fun UpdateStatusBar(mainActivityActivity: MainActivity,show: Boolean = true){
    LaunchedEffect(show) {
        showStatusBar(show = show, window = mainActivityActivity.window)
    }
}

@Composable
fun MainActivityNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController,
    mainActivity: MainActivity,
) {
    // Navigation Compose owns the back stack, transitions and system back.
    NavHost(
        navController = navController,
        startDestination = MainActivityRoutes.MainScreen,
        modifier = modifier.background(MaterialTheme.colorScheme.background),
    ) {
        composable<MainActivityRoutes.MainScreen> {
            if (Rootfs.isDownloaded.value) {
                val config = LocalConfiguration.current
                if (Configuration.ORIENTATION_LANDSCAPE == config.orientation) {
                    UpdateStatusBar(mainActivity, show = horizontal_statusBar.value)
                } else {
                    UpdateStatusBar(mainActivity, show = showStatusBar.value)
                }
                TerminalScreen(mainActivityActivity = mainActivity, navController = navController)
            } else {
                Downloader(mainActivity = mainActivity, navController = navController)
            }
        }
        composable<MainActivityRoutes.Settings> {
            UpdateStatusBar(mainActivity, show = true)
            Settings(navController = navController, mainActivity = mainActivity)
        }
        composable<MainActivityRoutes.Customization> {
            UpdateStatusBar(mainActivity, show = true)
            Customization()
        }
    }
}
