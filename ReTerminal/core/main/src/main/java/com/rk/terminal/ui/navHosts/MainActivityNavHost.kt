package com.rk.terminal.ui.navHosts


import android.content.res.Configuration
import android.os.Build
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.rk.settings.Settings
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.ui.animations.NavigationAnimationTransitions
import com.rk.terminal.ui.components.PhysicalScreenCornerClip
import com.rk.terminal.ui.components.rememberScreenCornerRadii
import com.rk.terminal.ui.routes.MainActivityRoutes
import com.rk.terminal.ui.screens.customization.Customization
import com.rk.terminal.ui.screens.downloader.Downloader
import com.rk.terminal.ui.screens.settings.Settings
import com.rk.terminal.ui.screens.terminal.Rootfs
import com.rk.terminal.ui.screens.terminal.TerminalScreen
import com.rk.terminal.util.PredictiveBackGate

var showStatusBar = mutableStateOf(Settings.statusBar)
var horizontal_statusBar = mutableStateOf(Settings.horizontal_statusBar)

/**
 * Current state of the main app's predictive-back toggle
 * (`flutter.predictive_back_enabled` in FlutterSharedPreferences, default on).
 * Re-read on every ON_RESUME so changes made in the Flutter settings page
 * are picked up while this activity is alive.
 */
@Composable
fun rememberPredictiveBackEnabled(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(PredictiveBackGate.isPredictiveBackEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = PredictiveBackGate.isPredictiveBackEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return enabled
}

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
fun MainActivityNavHost(modifier: Modifier = Modifier,navController: NavHostController,mainActivity: MainActivity) {
    val predictiveBack = rememberPredictiveBackEnabled()
    val screenCornerRadii = rememberScreenCornerRadii()
    NavHost(
        navController = navController,
        startDestination = MainActivityRoutes.MainScreen.route,
        modifier = modifier,
        enterTransition = { NavigationAnimationTransitions.enterTransition(predictiveBack) },
        exitTransition = { NavigationAnimationTransitions.exitTransition(predictiveBack) },
        popEnterTransition = { NavigationAnimationTransitions.popEnterTransition(predictiveBack) },
        popExitTransition = { NavigationAnimationTransitions.popExitTransition(predictiveBack) },
    ) {

        composable(MainActivityRoutes.MainScreen.route) {
            PhysicalScreenCornerClip(
                enabled = predictiveBack,
                screenCorners = screenCornerRadii,
            ) {
                if (Rootfs.isDownloaded.value){
                    val config = LocalConfiguration.current
                    if (Configuration.ORIENTATION_LANDSCAPE == config.orientation){
                        UpdateStatusBar(mainActivity, show = horizontal_statusBar.value)
                    }else{
                        UpdateStatusBar(mainActivity, show = showStatusBar.value)
                    }

                    TerminalScreen(mainActivityActivity = mainActivity, navController = navController)
                }else{
                    Downloader(mainActivity = mainActivity, navController = navController)
                }
            }
        }
        composable(MainActivityRoutes.Settings.route) {
            PhysicalScreenCornerClip(
                enabled = predictiveBack,
                screenCorners = screenCornerRadii,
            ) {
                UpdateStatusBar(mainActivity,show = true)
                Settings(navController = navController, mainActivity = mainActivity)
            }
        }
        composable(MainActivityRoutes.Customization.route){
            PhysicalScreenCornerClip(
                enabled = predictiveBack,
                screenCorners = screenCornerRadii,
            ) {
                UpdateStatusBar(mainActivity,show = true)
                Customization()
            }
        }
    }
}
