package cn.com.omnimind.bot.plugin.sandbox

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import cn.com.omnimind.bot.activity.MainActivity
import cn.com.omnimind.bot.activity.PluginAppActivity
import cn.com.omnimind.bot.plugin.OmniPluginHost
import com.caverock.androidsvg.SVG
import java.io.File
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class SandboxShortcutStatus(val wireName: String) {
    REQUESTED("requested"),
    UPDATED("updated"),
    UNSUPPORTED("unsupported"),
    REQUEST_REJECTED("request_rejected"),
    UNAVAILABLE("unavailable"),
}

data class SandboxShortcutResult(
    val status: SandboxShortcutStatus,
    val shortcutId: String,
    val launchUri: String,
    val reason: String? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "status" to status.wireName,
        "shortcutId" to shortcutId,
        "launchUri" to launchUri,
        "reason" to reason,
    )
}

class SandboxPluginShortcutManager(context: Context) {
    private val appContext = context.applicationContext
    private val shortcutManager = appContext.getSystemService(ShortcutManager::class.java)

    suspend fun pinOrUpdate(pluginId: String): SandboxShortcutResult {
        val state = OmniPluginHost.get(appContext).list()
            .firstOrNull { it.descriptor.id == pluginId }
            ?: throw IllegalArgumentException("Unknown plugin: $pluginId")
        require(state.installed && state.enabled) {
            "Plugin must be installed and enabled: $pluginId"
        }
        val isVibeApp = pluginId.startsWith(VIBE_PLUGIN_PREFIX)
        val dashboard = if (isVibeApp) {
            SandboxPluginPool(appContext).dashboard(pluginId)
        } else {
            null
        }
        val dashboardAction = state.descriptor.presentation["dashboard"] as? JsonObject
        val dashboardRoute = dashboardAction
            ?.get("route")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            .orEmpty()
        if (!isVibeApp && dashboardRoute.isEmpty()) {
            return SandboxShortcutResult(
                status = SandboxShortcutStatus.UNAVAILABLE,
                shortcutId = dashboardShortcutId(pluginId),
                launchUri = "",
                reason = "This plugin does not provide a dashboard",
            )
        }
        val shortcutId = if (isVibeApp) {
            SandboxPluginAppLaunchSpec.shortcutId(pluginId)
        } else {
            dashboardShortcutId(pluginId)
        }
        val launchUri = if (isVibeApp) {
            SandboxPluginAppLaunchSpec.uri(pluginId)
        } else {
            dashboardRoute
        }
        val title = dashboard?.getValue("title") as? String ?: state.descriptor.name
        val intent = Intent(appContext, if (isVibeApp) PluginAppActivity::class.java else MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (isVibeApp) {
                data = Uri.parse(launchUri)
                putExtra(
                    "route",
                    "/home/plugin_dashboard?pluginId=${Uri.encode(pluginId)}",
                )
            } else {
                putExtra("route", dashboardRoute)
            }
            putExtra("needClear", false)
        }
        val shortcut = ShortcutInfo.Builder(appContext, shortcutId)
            .setShortLabel(title)
            .setLongLabel(title)
            .setIcon(
                if (dashboard != null) {
                    Icon.createWithAdaptiveBitmap(
                        SandboxSvgIconRenderer.render(
                            appContext,
                            File(dashboard.getValue("iconPath") as String),
                        ),
                    )
                } else {
                    Icon.createWithResource(appContext, appContext.applicationInfo.icon)
                },
            )
            .setIntent(intent)
            .build()
        val pinned = shortcutManager.pinnedShortcuts.any { it.id == shortcutId }
        if (pinned) {
            shortcutManager.enableShortcuts(listOf(shortcutId))
            require(shortcutManager.updateShortcuts(listOf(shortcut))) {
                "Launcher refused to update the existing Vibe App shortcut"
            }
            return SandboxShortcutResult(
                status = SandboxShortcutStatus.UPDATED,
                shortcutId = shortcutId,
                launchUri = launchUri,
            )
        }
        if (!shortcutManager.isRequestPinShortcutSupported) {
            return SandboxShortcutResult(
                status = SandboxShortcutStatus.UNSUPPORTED,
                shortcutId = shortcutId,
                launchUri = launchUri,
            )
        }
        return SandboxShortcutResult(
            status = if (shortcutManager.requestPinShortcut(shortcut, null)) {
                SandboxShortcutStatus.REQUESTED
            } else {
                SandboxShortcutStatus.REQUEST_REJECTED
            },
            shortcutId = shortcutId,
            launchUri = launchUri,
        )
    }

    fun disable(pluginId: String) {
        val shortcutId = if (pluginId.startsWith(VIBE_PLUGIN_PREFIX)) {
            SandboxPluginAppLaunchSpec.shortcutId(pluginId)
        } else {
            dashboardShortcutId(pluginId)
        }
        if (shortcutManager.pinnedShortcuts.any { it.id == shortcutId }) {
            shortcutManager.disableShortcuts(
                listOf(shortcutId),
                "This Vibe App plugin is not installed",
            )
        }
    }

    private fun dashboardShortcutId(pluginId: String): String =
        "$DASHBOARD_SHORTCUT_PREFIX$pluginId"

    private companion object {
        const val VIBE_PLUGIN_PREFIX = "local.project."
        const val DASHBOARD_SHORTCUT_PREFIX = "plugin-dashboard:"
    }
}

object SandboxSvgIconRenderer {
    fun render(context: Context, file: File): Bitmap {
        require(file.isFile) { "SVG app icon is missing: ${file.absolutePath}" }
        val size = (96 * context.resources.displayMetrics.density)
            .roundToInt()
            .coerceIn(192, 512)
        val svg = file.inputStream().use(SVG::getFromInputStream)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        svg.documentWidth = size.toFloat()
        svg.documentHeight = size.toFloat()
        svg.renderToCanvas(canvas)
        return bitmap
    }
}
