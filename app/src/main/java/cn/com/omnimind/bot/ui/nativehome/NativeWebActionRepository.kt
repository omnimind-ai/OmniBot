package cn.com.omnimind.bot.ui.nativehome

import android.content.Context
import cn.com.omnimind.bot.plugin.OmniPluginActionDefinition
import cn.com.omnimind.bot.plugin.OmniPluginHost
import cn.com.omnimind.nativeui.WebProcessStatus
import cn.com.omnimind.nativeui.WebQuickAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** A projection of plugin metadata. Action IDs and process ownership stay inside the plugin. */
internal class NativeWebActionRepository(context: Context) {
    private val context = context.applicationContext
    private val host = OmniPluginHost.get(this.context)

    suspend fun list(): List<WebQuickAction> = withContext(Dispatchers.IO) {
        host.listActions().filter(::isQuickAction).sortedWith(
            compareBy<OmniPluginActionDefinition> { (it.presentation["quickLaunchOrder"] as? JsonPrimitive)?.intOrNull ?: 0 }
                .thenBy { it.displayName },
        ).map { definition ->
            val statusId = definition.presentation.text("statusAction")
            val status = if (statusId.isBlank()) WebProcessStatus.Unknown else try {
                val response = host.invokeAction(definition.ownerPluginId!!, statusId, JsonObject(emptyMap()))
                when {
                    (response["running"] as? JsonPrimitive)?.booleanOrNull != true -> WebProcessStatus.Stopped
                    response.text("code") == "RUNNING" -> WebProcessStatus.Running
                    else -> WebProcessStatus.Starting
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                WebProcessStatus.Unknown
            }
            WebQuickAction(
                pluginId = definition.ownerPluginId!!,
                actionId = definition.id,
                label = localized(definition, "shortLabel"),
                agentId = definition.presentation.text("agentId"),
                status = status,
                canStop = statusId.isNotBlank() && definition.presentation.text("stopAction").isNotBlank(),
            )
        }
    }

    suspend fun invoke(action: WebQuickAction, stop: Boolean): WebActionResult = withContext(Dispatchers.IO) {
        val definition = host.listActions().singleOrNull {
            it.ownerPluginId == action.pluginId && it.id == action.actionId && isQuickAction(it)
        } ?: error("Plugin action is no longer available")
        val actionId = if (stop) definition.presentation.text("stopAction").also { require(it.isNotBlank()) }
            else definition.id
        val response = host.invokeAction(action.pluginId, actionId, JsonObject(emptyMap()))
        WebActionResult(
            code = response.text("code"),
            packageId = response.text("packageId").ifBlank { definition.presentation.text("packageId") },
            stopped = (response["success"] as? JsonPrimitive)?.booleanOrNull == true &&
                (response["running"] as? JsonPrimitive)?.booleanOrNull != true,
        )
    }

    private fun isQuickAction(action: OmniPluginActionDefinition): Boolean {
        val presentation = action.presentation
        val placements = (presentation["placements"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content }
        return !action.ownerPluginId.isNullOrBlank() && presentation.text("agentId").isNotBlank() &&
            (presentation.text("placement") == PLACEMENT || PLACEMENT in placements)
    }

    private fun localized(action: OmniPluginActionDefinition, key: String): String {
        val preferences = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val locale = resolveNativeHomeLocale(preferences.getString("flutter.language_option", "system"), context.resources.configuration.locales[0])
        val value = action.presentation[key]
        return ((value as? JsonObject)?.text(locale.language) ?: (value as? JsonPrimitive)?.content)
            ?.takeIf(String::isNotBlank) ?: action.displayName
    }

    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.content?.trim().orEmpty()

    private companion object { const val PLACEMENT = "home_drawer_quick_launch" }
}

internal data class WebActionResult(val code: String, val packageId: String, val stopped: Boolean)
