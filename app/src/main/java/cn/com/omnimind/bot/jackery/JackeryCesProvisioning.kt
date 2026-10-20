package cn.com.omnimind.bot.jackery

import android.content.Context
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.agent.AgentPromptSettingsStore
import cn.com.omnimind.bot.mcp.RemoteMcpConfigStore
import cn.com.omnimind.bot.mcp.RemoteMcpServerConfig
import cn.com.omnimind.bot.mcp.RemoteMcpTransport
import org.json.JSONObject
import java.io.File
import java.net.URI

/** Staff provisioning uses the existing Provider and prompt owners. No demo
 * endpoint, battery value, or model readiness is fabricated. */
object JackeryCesProvisioning {
    const val PROFILE_ID = "jackery-ces-on-device"
    private const val SEARCH_SERVER_ID = "jackery-ces-search"
    const val PRODUCT_NAME = "Jackery Power AI"
    const val MODEL_DISPLAY_NAME = "Jackery Power AI · On-device"
    val enabled: Boolean get() = BuildConfig.APP_EDITION == "jackeryCes"
    const val CHAT_PROMPT = """You are Jackery Power AI, a helpful assistant powered by the connected on-device AI system. When asked who you are, identify yourself as Jackery Power AI, never as Omnibot, Xiaowan, or the underlying model. Answer English questions in English and use English by default. Be concise and factual. Use the available web search tool when current information is needed. Never claim you searched or verified something unless a tool result supports it. Do not invent battery levels, runtime, power, connection, or model readiness. Ask exhibition staff for help if the device is unavailable."""

    private fun connectionFile(context: Context) = File(context.filesDir, "jackery-ces/connection.json")
    private fun connection(context: Context): JSONObject = connectionFile(context).let {
        if (it.isFile) JSONObject(it.readText()) else JSONObject()
    }
    fun modelApiBase(context: Context): String = ModelProviderConfigStore.normalizeBaseUrl(connection(context)
        .optString("modelApiBase", BuildConfig.JACKERY_MODEL_API_BASE).trim()).orEmpty()
    fun modelId(context: Context): String = connection(context)
        .optString("modelId", BuildConfig.JACKERY_MODEL_ID).trim()
    fun statusUrl(context: Context): String = connection(context)
        .optString("statusUrl", BuildConfig.JACKERY_STATUS_URL).trim()

    fun applyAppearance(context: Context) {
        if (!enabled) return
        check(context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).edit()
            .putString("flutter.theme_option", "light")
            .putString("flutter.language_option", "en")
            .putBoolean("flutter.welcome_completed", true)
            .putBoolean("flutter.pet_overlay_visible", false)
            .putBoolean("flutter.mcp_local_service_enabled", false)
            .commit()) { "Could not restore CES appearance settings." }
        AppLocaleManager.brandOverride = PRODUCT_NAME
    }

    @Synchronized
    fun install(context: Context) {
        if (!enabled) return
        applyAppearance(context)
        val provision = connection(context)
        val rawBase = provision.optString("modelApiBase", BuildConfig.JACKERY_MODEL_API_BASE).trim()
        val base = if (rawBase.isBlank()) "" else requireNotNull(ModelProviderConfigStore.normalizeBaseUrl(rawBase)) {
            "The device model endpoint is invalid."
        }
        val model = provision.optString("modelId", BuildConfig.JACKERY_MODEL_ID).trim()
        require(model.isBlank() || SceneModelBindingStore.isValidModelName(model)) { "The device model ID is invalid." }
        val search = provision.optJSONObject("searchMcp")
        val searchConfig = search?.let {
            val endpoint = it.getString("endpointUrl").trim()
            val uri = URI(endpoint)
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.fragment == null) { "The search MCP endpoint is invalid." }
            val previousSearch = RemoteMcpConfigStore.getServer(SEARCH_SERVER_ID)
                ?.takeIf { saved -> saved.endpointUrl == endpoint }
            RemoteMcpServerConfig(
                id = SEARCH_SERVER_ID,
                name = "Web search",
                endpointUrl = endpoint,
                bearerToken = if (it.has("bearerToken")) it.getString("bearerToken") else
                    previousSearch?.bearerToken.orEmpty(),
                transport = RemoteMcpTransport.fromValue(it.optString("transport", "http")),
                enabled = true,
            )
        }
        val previous = ModelProviderConfigStore.getProfile(PROFILE_ID)
        // A supplied credential is immediately moved to the existing encrypted
        // Provider store. Retain it on reset only for the same endpoint.
        val key = if (provision.has("apiKey")) provision.getString("apiKey") else
            previous?.takeIf { ModelProviderConfigStore.sameCanonicalEndpoint(it.baseUrl, base) }?.apiKey.orEmpty()
        val profile = ModelProviderProfile(PROFILE_ID, MODEL_DISPLAY_NAME, baseUrl = base, apiKey = key)
        ModelProviderConfigStore.replaceProfiles(listOf(profile), PROFILE_ID)
        searchConfig?.let(RemoteMcpConfigStore::upsertServer)
        if (provision.has("apiKey") || search?.has("bearerToken") == true) {
            provision.remove("apiKey")
            search?.remove("bearerToken")
            connectionFile(context).writeText(provision.toString(2))
        }
        if (base.isNotBlank() && model.isNotBlank()) {
            val saved = requireNotNull(ModelProviderConfigStore.getProfile(PROFILE_ID))
            ModelProviderConfigStore.rememberModels(context, saved, listOf(
                ProviderModelOption(model, MODEL_DISPLAY_NAME)
            ))
            SceneModelBindingStore.replaceBindings(listOf("scene.dispatch.model", "scene.compactor.context.chat")
                .map { SceneModelBindingEntry(it, PROFILE_ID, model) })
        } else {
            SceneModelBindingStore.replaceBindings(emptyList())
        }
        AgentPromptSettingsStore.writeChatPrompt(context, CHAT_PROMPT)
        AgentPromptSettingsStore.writeSoul(context, CHAT_PROMPT)
    }
}
