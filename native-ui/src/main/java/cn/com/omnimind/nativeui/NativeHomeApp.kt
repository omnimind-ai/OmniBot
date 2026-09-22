package cn.com.omnimind.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import cn.com.omnimind.nativeui.home.ConversationArchiveScreen
import cn.com.omnimind.nativeui.home.HomeDrawer
import cn.com.omnimind.nativeui.home.HomeScreen
import cn.com.omnimind.nativeui.settings.SettingsScreen
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import cn.com.omnimind.nativeui.theme.OmniTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius

@Serializable
internal sealed interface HomeRoute : NavKey {
    @Serializable data object Home : HomeRoute
    @Serializable data object Settings : HomeRoute
    @Serializable data object Archive : HomeRoute
}

/** Miuix owns the saved page stack, transitions, and predictive back; Android owns back-to-home. */
@Composable
fun NativeHomeApp(
    state: NativeHomeState,
    actions: NativeHomeActions,
) {
    OmniTheme(state.theme) {
        val palette = LocalOmniPalette.current
        val backStack = rememberNavBackStack<HomeRoute>(HomeRoute.Home)
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize().background(palette.page),
            effects = NavDisplayEffects(
                cornerClipRadius = rememberNavSystemCornerRadius(),
                backdropColor = palette.page,
            ),
        ) {
            entry<HomeRoute.Home> {
                HomeWithDrawer(
                    state = state,
                    onSettings = { backStack.add(HomeRoute.Settings) },
                    onArchive = { backStack.add(HomeRoute.Archive) },
                    actions = actions,
                )
            }
            entry<HomeRoute.Archive> {
                ConversationArchiveScreen(state, actions) { backStack.removeLastOrNull() }
            }
            entry<HomeRoute.Settings> {
                SettingsScreen(state, { backStack.removeLastOrNull() }, actions)
            }
        }
    }
}

@Composable
private fun HomeWithDrawer(
    state: NativeHomeState,
    onSettings: () -> Unit,
    onArchive: () -> Unit,
    actions: NativeHomeActions,
) {
    val palette = LocalOmniPalette.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navigate: (() -> Unit) -> Unit = { action ->
        scope.launch {
            drawer.close()
            action()
        }
    }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), containerColor = palette.page) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val drawerWidth = maxWidth * .8f
            ModalNavigationDrawer(
                drawerState = drawer,
                scrimColor = palette.scrim,
                drawerContent = {
                    ModalDrawerSheet(
                        // This overload owns drawer back handling, including gesture cancel/commit.
                        drawerState = drawer,
                        modifier = Modifier.width(drawerWidth), drawerShape = RectangleShape,
                        drawerContainerColor = palette.drawer, drawerTonalElevation = 0.dp,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    ) {
                        HomeDrawer(
                            state = state,
                            onSettings = { navigate(onSettings) },
                            onArchive = { navigate(onArchive) },
                            onNewConversation = { scope.launch { drawer.close() } },
                            actions = actions.copy(open = { destination -> navigate { actions.open(destination) } }),
                        )
                    }
                },
            ) {
                HomeScreen(state, { actions.refresh(); scope.launch { drawer.open() } }, actions.open)
            }
        }
    }
}
