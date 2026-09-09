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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import top.yukonga.miuix.kmp.nav.core.NavController
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import com.rk.settings.Settings
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.ui.animations.NavigationAnimationTransitions
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
fun MainActivityNavHost(
    modifier: Modifier = Modifier,
    navController: NavController,
    mainActivity: MainActivity,
) {
    val predictiveBack = rememberPredictiveBackEnabled()
    val screenCornerRadius = rememberNavSystemCornerRadius()
    val dispatcherOwner = LocalNavigationEventDispatcherOwner.current
    val detachedOwner = remember {
        object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
    }
    DisposableEffect(detachedOwner) {
        onDispose { detachedOwner.navigationEventDispatcher.dispose() }
    }
    val background = MaterialTheme.colorScheme.background

    // Keep a single saved back stack across toggle/configuration changes.
    // When disabled, only the discrete BackHandler below receives navigation;
    // no predictive preview is dispatched to the Miuix host.
    CompositionLocalProvider(
        LocalNavigationEventDispatcherOwner provides
            if (predictiveBack) dispatcherOwner ?: detachedOwner else detachedOwner,
    ) {
        NavDisplay(
            navController = navController,
            modifier = modifier.background(background),
            transition = if (predictiveBack) NavTransitions.MiuixDefault
                else NavigationAnimationTransitions.LegacyFade,
            effects = if (predictiveBack) NavDisplayEffects(
                cornerClipRadius = screenCornerRadius,
                dimAmount = 0.5f,
                backdropColor = background,
            ) else NavDisplayEffects.None,
        ) {
            entry<MainActivityRoutes.MainScreen> {
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
            entry<MainActivityRoutes.Settings> {
                UpdateStatusBar(mainActivity, show = true)
                Settings(navController = navController, mainActivity = mainActivity)
            }
            entry<MainActivityRoutes.Customization> {
                UpdateStatusBar(mainActivity, show = true)
                Customization()
            }
        }
    }
    BackHandler(enabled = !predictiveBack && navController.backStack.size > 1) {
        navController.pop()
    }
}
