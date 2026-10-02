package cn.com.omnimind.bot.plugin

import android.content.Context
import cn.com.omnimind.bot.ui.nativehome.resolveNativeHomeLocale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Native adapter over the existing plugin platform owner. `OmniPluginHost`
 * keeps the catalog, install/update downloads and enable state; this class
 * adds nothing but typed access for the native pages.
 */
internal class NativePluginRepository(context: Context) {
    private val appContext = context.applicationContext
    private val host = OmniPluginHost.get(appContext)

    suspend fun list(): List<OmniPluginState> = host.list()

    suspend fun get(pluginId: String): OmniPluginState? =
        host.list().firstOrNull { it.descriptor.id == pluginId }

    suspend fun install(pluginId: String): OmniPluginState = host.install(pluginId)

    suspend fun update(pluginId: String): OmniPluginState = host.update(pluginId)

    suspend fun setEnabled(pluginId: String, enabled: Boolean): OmniPluginState =
        host.setEnabled(pluginId, enabled)

    suspend fun uninstall(pluginId: String) = host.uninstall(pluginId)

    fun vlmReadiness(): PluginVlmReadiness = readPluginVlmReadiness()

    fun isHidden(state: OmniPluginState): Boolean =
        (state.descriptor.presentation["visibility"] as? JsonPrimitive)?.content == "hidden"

    /** Plugin-declared `{zh, en}` text or a plain string, in the app language. */
    fun localized(element: JsonElement?): String {
        val preferences =
            appContext.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val language = preferences.getString("flutter.language_option", "system")
        val locale = resolveNativeHomeLocale(language)
        return when (element) {
            is JsonObject -> {
                val byLocale = (element[locale.language] as? JsonPrimitive)?.content
                    ?: (element["en"] as? JsonPrimitive)?.content
                    ?: (element["zh"] as? JsonPrimitive)?.content
                byLocale?.trim().orEmpty()
            }
            is JsonPrimitive -> element.content.trim()
            else -> ""
        }
    }
}
