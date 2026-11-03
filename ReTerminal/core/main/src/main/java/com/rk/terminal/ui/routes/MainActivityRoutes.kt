package com.rk.terminal.ui.routes

import kotlinx.serialization.Serializable

@Serializable
sealed interface MainActivityRoutes {
    @Serializable
    data object Settings : MainActivityRoutes
    @Serializable
    data object Customization : MainActivityRoutes
    @Serializable
    data object MainScreen : MainActivityRoutes
}
