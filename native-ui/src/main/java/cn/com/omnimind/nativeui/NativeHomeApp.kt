package cn.com.omnimind.nativeui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import cn.com.omnimind.nativeui.home.HomeDrawer
import cn.com.omnimind.nativeui.home.HomeScreen
import cn.com.omnimind.nativeui.settings.SettingsScreen
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import cn.com.omnimind.nativeui.theme.OmniTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition
import kotlin.math.abs

@Serializable
internal sealed interface HomeRoute : NavKey {
    @Serializable data object Home : HomeRoute
    @Serializable data object Settings : HomeRoute
}

private val legacyFade = navGraphicsTransition(
    motion = NavMotion(programmatic = NavSettleSpec.Tween(250, LinearEasing)),
) { scope -> alpha = 1f - abs(scope.relativeDepth).coerceIn(0f, 1f) }

/** One saved navigation stack. The host owns data and compatibility destinations. */
@Composable
fun NativeHomeApp(
    state: NativeHomeState,
    onOpen: (LegacyDestination) -> Unit,
    onLocalServiceChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    OmniTheme(state.theme) {
        val palette = LocalOmniPalette.current
        val backStack = rememberNavBackStack<HomeRoute>(HomeRoute.Home)
        val dispatcherOwner = LocalNavigationEventDispatcherOwner.current
        // Disabling predictive back must disable progress delivery, not only change the animation.
        val detachedOwner = remember {
            object : NavigationEventDispatcherOwner {
                override val navigationEventDispatcher = NavigationEventDispatcher()
            }
        }
        DisposableEffect(detachedOwner) { onDispose { detachedOwner.navigationEventDispatcher.dispose() } }
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides
            if (state.predictiveBack) dispatcherOwner ?: detachedOwner else detachedOwner) {
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize().background(palette.page),
                transition = if (state.predictiveBack) NavTransitions.MiuixDefault else legacyFade,
                effects = if (state.predictiveBack) NavDisplayEffects(
                    cornerClipRadius = 32.dp, dimAmount = .9f, backdropColor = palette.page,
                ) else NavDisplayEffects.None,
            ) {
                entry<HomeRoute.Home> {
                    HomeWithDrawer(state, { backStack.add(HomeRoute.Settings) }, onOpen, onRetry)
                }
                entry<HomeRoute.Settings> {
                    SettingsScreen(state, { backStack.removeLastOrNull() }, onOpen, onLocalServiceChange)
                }
            }
        }
        BackHandler(enabled = !state.predictiveBack && backStack.size > 1) { backStack.removeLastOrNull() }
    }
}

@Composable
private fun HomeWithDrawer(
    state: NativeHomeState,
    onSettings: () -> Unit,
    onOpen: (LegacyDestination) -> Unit,
    onRetry: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navigate: (() -> Unit) -> Unit = { action ->
        scope.launch { drawer.close(); action() }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val drawerWidth = maxWidth * .8f
        ModalNavigationDrawer(
            drawerState = drawer,
            scrimColor = palette.scrim,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(drawerWidth), drawerShape = RectangleShape,
                    drawerContainerColor = palette.drawer, drawerTonalElevation = 0.dp,
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                ) {
                    HomeDrawer(state, { navigate(onSettings) }, { navigate {} },
                        { destination -> navigate { onOpen(destination) } }, onRetry)
                }
            },
        ) {
            HomeScreen(state, { scope.launch { drawer.open() } }, onOpen)
        }
    }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
}
