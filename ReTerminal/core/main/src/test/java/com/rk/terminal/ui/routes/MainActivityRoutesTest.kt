package com.rk.terminal.ui.routes

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityRoutesTest {
    @Test
    fun savedRouteHierarchyRestoresSettingsAndCustomization() {
        val routes: List<MainActivityRoutes> = listOf(
            MainActivityRoutes.MainScreen,
            MainActivityRoutes.Settings,
            MainActivityRoutes.Customization,
        )
        val encoded = Json.encodeToString(routes)
        assertEquals(routes, Json.decodeFromString<List<MainActivityRoutes>>(encoded))
    }
}
