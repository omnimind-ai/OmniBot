package com.rk.terminal.ui.routes

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
sealed interface MainActivityRoutes : NavKey {
    @Serializable
    data object Settings : MainActivityRoutes
    @Serializable
    data object Customization : MainActivityRoutes
    @Serializable
    data object MainScreen : MainActivityRoutes
}
