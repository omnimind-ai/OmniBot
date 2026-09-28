package cn.com.omnimind.bot.model

import android.content.Context
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.ProviderCustomHeaderUtils
import cn.com.omnimind.baselib.util.LegacyFlutterPreferences
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import org.json.JSONArray
import org.json.JSONObject

/** One owner for Provider mutations and the existing Flutter model-list preferences. */
internal class ProviderEditorRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

    fun profiles(): List<ModelProviderProfile> = ModelProviderConfigStore.listProfiles()
    fun editingId(): String = ModelProviderConfigStore.getEditingProfileId()
    fun select(id: String): ModelProviderProfile = ModelProviderConfigStore.setEditingProfile(id)

    fun save(
        id: String?, name: String, baseUrl: String, apiKey: String?,
        headers: Map<String, String>?, sourceType: String?, protocolType: String, wireApi: String,
    ): ModelProviderProfile {
        require(baseUrl.isBlank() || ModelProviderConfigStore.isValidBaseUrl(baseUrl)) { "Invalid Provider Base URL" }
        val existing = id?.let(ModelProviderConfigStore::getProfile)
        val saved = ModelProviderConfigStore.saveProfile(
            id = id, name = name, baseUrl = baseUrl,
            apiKey = apiKey ?: existing?.apiKey.orEmpty(),
            customHeaders = headers ?: existing?.customHeaders.orEmpty(),
            sourceType = sourceType, protocolType = protocolType, wireApi = wireApi,
        )
        AgentRuntimeManager.getIfInitialized()?.invalidateSharedProviderRuntime(saved.id)
        return saved
    }

    fun delete(id: String): List<ModelProviderProfile> {
        val profiles = ModelProviderConfigStore.deleteProfile(id)
        AgentRuntimeManager.getIfInitialized()?.invalidateSharedProviderRuntime(id)
        return profiles
    }

    fun validateHeaders(headers: List<Pair<String, String>>): Map<String, String> {
        val seen = mutableSetOf<String>()
        val values = linkedMapOf<String, String>()
        headers.forEach { (name, value) ->
            val key = name.trim()
            if (key.isEmpty() && value.isBlank()) return@forEach
            require(key.isNotEmpty()) { "Header name is required" }
            require(!ProviderCustomHeaderUtils.isForbiddenHeaderName(key)) { "Header name is not allowed" }
            require(key.none { it == '\r' || it == '\n' } && value.none { it == '\r' || it == '\n' }) {
                "Header must not contain a line break"
            }
            require(seen.add(ProviderCustomHeaderUtils.normalizeHeaderName(key))) { "Duplicate header name" }
            values[key] = value
        }
        return ProviderCustomHeaderUtils.sanitizeCustomHeaders(values)
    }

    fun requestUrl(baseUrl: String, protocolType: String, wireApi: String): String {
        val normalized = ModelProviderConfigStore.normalizeBaseUrl(baseUrl) ?: return ""
        val base = ModelProviderConfigStore.stripDirectRequestUrlMarker(normalized)
        if (ModelProviderConfigStore.hasDirectRequestUrlMarker(normalized)) return base
        val suffix = when {
            protocolType == "anthropic" -> "/messages"
            protocolType == "openai_compatible" && wireApi == "responses" -> "/responses"
            else -> "/chat/completions"
        }
        return base + if (ModelProviderConfigStore.hasVersionedBasePath(base)) suffix else "/v1$suffix"
    }

    fun manualIds(profileId: String): List<String> = readIds(MANUAL_KEY, profileId,
        legacyKey = "flutter.manual_provider_model_ids_v1")

    fun hiddenIds(profileId: String): List<String> = readIds(HIDDEN_KEY, profileId)

    fun saveManualIds(profileId: String, ids: List<String>) = writeIds(MANUAL_KEY, profileId, ids)
    fun saveHiddenIds(profileId: String, ids: List<String>) = writeIds(HIDDEN_KEY, profileId, ids)

    private fun readIds(key: String, profileId: String, legacyKey: String? = null): List<String> = synchronized(LOCK) {
        val map = parseMap(key)
        val values = map.optJSONArray(profileId)?.let { array ->
            List(array.length()) { array.optString(it) }
        } ?: if (legacyKey != null && !map.has(profileId) && editingId() == profileId)
            LegacyFlutterPreferences.readStringList(preferences, legacyKey)
        else emptyList()
        values.map(String::trim).filter { it.isNotEmpty() && !it.startsWith("scene.") }.distinct()
    }

    private fun writeIds(key: String, profileId: String, ids: List<String>): Unit = synchronized(LOCK) {
        require(profileId.isNotBlank()) { "Provider profile is required" }
        val map = parseMap(key)
        val normalized = ids.map(String::trim).filter { it.isNotEmpty() && !it.startsWith("scene.") }.distinct()
        map.put(profileId, JSONArray(normalized))
        check(preferences.edit().putString(key, map.toString()).commit()) { "Could not save Provider models" }
    }

    private fun parseMap(key: String): JSONObject = runCatching {
        JSONObject(preferences.getString(key, "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    private companion object {
        val LOCK = Any()
        const val MANUAL_KEY = "flutter.manual_provider_model_ids_v2"
        const val HIDDEN_KEY = "flutter.hidden_chat_provider_model_ids_v1"
    }
}
