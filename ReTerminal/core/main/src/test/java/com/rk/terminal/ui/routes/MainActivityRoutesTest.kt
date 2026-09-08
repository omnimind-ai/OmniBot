package com.rk.terminal.ui.routes

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.miuix.kmp.nav.core.NavController
import top.yukonga.miuix.kmp.nav.core.navBackStackOf

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

    @Test
    fun backReturnsThroughSettingsAndKeepsTerminalRoot() {
        val controller = NavController(navBackStackOf(MainActivityRoutes.MainScreen))
        controller.push(MainActivityRoutes.Settings)
        controller.push(MainActivityRoutes.Customization)
        assertTrue(controller.pop())
        assertEquals(MainActivityRoutes.Settings, controller.backStack.last())
        assertTrue(controller.pop())
        assertEquals(MainActivityRoutes.MainScreen, controller.backStack.last())
        assertFalse(controller.pop())
    }
}
