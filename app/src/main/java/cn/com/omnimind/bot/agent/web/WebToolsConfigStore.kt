package cn.com.omnimind.bot.agent.web

import cn.com.omnimind.baselib.llm.DeepSeekProvider
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.util.AppSecretStore
import com.tencent.mmkv.MMKV
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class WebToolBackend(val wireName: String) {
    BUILTIN("builtin"),
    DEEPSEEK("deepseek"),
    CUSTOM("custom");

    companion object {
        fun fromWire(value: String?): WebToolBackend = when (value?.trim()?.lowercase(Locale.ROOT)) {
            "deepseek" -> DEEPSEEK
            "custom" -> CUSTOM
            else -> BUILTIN
        }
    }
}

@Serializable
enum class WebHttpMethod {
    POST,
    GET;

    companion object {
        fun fromWire(value: String?): WebHttpMethod = when (value?.trim()?.uppercase(Locale.ROOT)) {
            "GET" -> GET
            else -> POST
        }
    }
}

@Serializable
data class DeepSeekWebConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    }
}

@Serializable
data class CustomWebSearchConfig(
    val method: WebHttpMethod = WebHttpMethod.POST,
    val url: String = "",
    val headersText: String = "",
    val bodyTemplate: String = DEFAULT_BODY_TEMPLATE,
    val resultsPath: String = DEFAULT_RESULTS_PATH,
    val titleField: String = DEFAULT_TITLE_FIELD,
    val urlField: String = DEFAULT_URL_FIELD,
    val snippetField: String = DEFAULT_SNIPPET_FIELD,
) {
    companion object {
        const val DEFAULT_BODY_TEMPLATE = """{"query": {{query_json}}, "count": {{count}}}"""
        const val DEFAULT_RESULTS_PATH = "results"
        const val DEFAULT_TITLE_FIELD = "title"
        const val DEFAULT_URL_FIELD = "url"
        const val DEFAULT_SNIPPET_FIELD = "snippet"
    }
}

@Serializable
data class CustomWebFetchConfig(
    val method: WebHttpMethod = WebHttpMethod.POST,
    val url: String = "",
    val headersText: String = "",
    val bodyTemplate: String = DEFAULT_BODY_TEMPLATE,
    val contentField: String = DEFAULT_CONTENT_FIELD,
    val titleField: String = DEFAULT_TITLE_FIELD,
) {
    companion object {
        const val DEFAULT_BODY_TEMPLATE = """{"url": {{url_json}}}"""
        const val DEFAULT_CONTENT_FIELD = "content"
        const val DEFAULT_TITLE_FIELD = "title"
    }
}

@Serializable
data class WebToolsSettings(
    val searchBackend: WebToolBackend = WebToolBackend.BUILTIN,
    val fetchBackend: WebToolBackend = WebToolBackend.BUILTIN,
    val deepSeek: DeepSeekWebConfig = DeepSeekWebConfig(),
    val customSearch: CustomWebSearchConfig = CustomWebSearchConfig(),
    val customFetch: CustomWebFetchConfig = CustomWebFetchConfig(),
)

data class WebToolsCredentials(
    val deepSeekApiKey: String = "",
    val customSearchApiKey: String = "",
    val customFetchApiKey: String = "",
)

data class WebToolsRuntimeConfig(
    val searchBackend: WebToolBackend = WebToolBackend.BUILTIN,
    val fetchBackend: WebToolBackend = WebToolBackend.BUILTIN,
    val deepSeekBaseUrl: String = DeepSeekWebConfig.DEFAULT_BASE_URL,
    val deepSeekApiKey: String = "",
    val customSearch: CustomWebSearchConfig = CustomWebSearchConfig(),
    val customSearchApiKey: String = "",
    val customFetch: CustomWebFetchConfig = CustomWebFetchConfig(),
    val customFetchApiKey: String = "",
)

data class DeepSeekProviderLink(
    val apiKey: String,
    val baseUrl: String,
)

class WebToolsValidationException(message: String) : IllegalArgumentException(message)

/**
 * 网页搜索与读取后端配置存储。
 * 非敏感结构保存在 MMKV；独立 API Key 保存在 Keystore 加密的 AppSecretStore，
 * 并支持零配置复用模型提供商中的 DeepSeek API Key 与 Base URL。
 */
object WebToolsConfigStore {
    private const val KEY_SETTINGS = "web_tools_config_v1"
    private const val SECRET_DEEPSEEK_API_KEY = "web_tools.deepseek_api_key"
    private const val SECRET_CUSTOM_SEARCH_API_KEY = "web_tools.custom_search_api_key"
    private const val SECRET_CUSTOM_FETCH_API_KEY = "web_tools.custom_fetch_api_key"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun getSettings(): WebToolsSettings {
        val mmkv = runCatching { MMKV.defaultMMKV() }.getOrNull() ?: return WebToolsSettings()
        val raw = mmkv.decodeString(KEY_SETTINGS)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return WebToolsSettings()
        return runCatching { json.decodeFromString<WebToolsSettings>(raw) }
            .getOrDefault(WebToolsSettings())
    }

    @Synchronized
    fun getCredentials(): WebToolsCredentials = WebToolsCredentials(
        deepSeekApiKey = AppSecretStore.read(SECRET_DEEPSEEK_API_KEY).orEmpty(),
        customSearchApiKey = AppSecretStore.read(SECRET_CUSTOM_SEARCH_API_KEY).orEmpty(),
        customFetchApiKey = AppSecretStore.read(SECRET_CUSTOM_FETCH_API_KEY).orEmpty(),
    )

    fun deepSeekProviderCredentials(): DeepSeekProviderLink? = runCatching {
        val profiles = ModelProviderConfigStore.listProfiles()
        val official = profiles.firstOrNull {
            it.id == DeepSeekProvider.OFFICIAL_PROFILE_ID && it.apiKey.isNotBlank()
        }
        val matched = official ?: profiles.firstOrNull { profile ->
            profile.apiKey.isNotBlank() && (
                profile.protocolType.equals(DeepSeekProvider.PROTOCOL_TYPE, ignoreCase = true) ||
                    profile.sourceType.equals(DeepSeekProvider.PROTOCOL_TYPE, ignoreCase = true) ||
                    DeepSeekProvider.isOfficialBaseUrl(profile.baseUrl)
                )
        }
        matched?.let {
            DeepSeekProviderLink(
                apiKey = it.apiKey.trim(),
                baseUrl = normalizeDeepSeekBaseUrl(it.baseUrl),
            )
        }
    }.getOrNull()

    fun resolveRuntimeConfig(): WebToolsRuntimeConfig {
        val settings = getSettings()
        val credentials = runCatching { getCredentials() }.getOrDefault(WebToolsCredentials())
        val providerLink = deepSeekProviderCredentials()
        val deepSeekApiKey = credentials.deepSeekApiKey.trim().ifEmpty {
            providerLink?.apiKey.orEmpty()
        }
        val configuredUrl = settings.deepSeek.baseUrl.trim()
        val deepSeekBaseUrl = when {
            configuredUrl.isNotEmpty() && configuredUrl != DeepSeekWebConfig.DEFAULT_BASE_URL ->
                configuredUrl
            providerLink != null && providerLink.baseUrl.isNotEmpty() ->
                providerLink.baseUrl
            configuredUrl.isNotEmpty() -> configuredUrl
            else -> DeepSeekWebConfig.DEFAULT_BASE_URL
        }
        return WebToolsRuntimeConfig(
            searchBackend = settings.searchBackend,
            fetchBackend = settings.fetchBackend,
            deepSeekBaseUrl = deepSeekBaseUrl,
            deepSeekApiKey = deepSeekApiKey,
            customSearch = settings.customSearch,
            customSearchApiKey = credentials.customSearchApiKey.trim(),
            customFetch = settings.customFetch,
            customFetchApiKey = credentials.customFetchApiKey.trim(),
        )
    }

    @Synchronized
    fun save(
        settings: WebToolsSettings,
        credentials: WebToolsCredentials,
    ): WebToolsSettings {
        val normalized = normalizeSettings(settings)
        val trimmedCreds = WebToolsCredentials(
            deepSeekApiKey = credentials.deepSeekApiKey.trim(),
            customSearchApiKey = credentials.customSearchApiKey.trim(),
            customFetchApiKey = credentials.customFetchApiKey.trim(),
        )
        validate(normalized, trimmedCreds, deepSeekProviderCredentials() != null)

        val mmkv = MMKV.defaultMMKV() ?: error("Web tools configuration storage is unavailable")
        val previousRaw = mmkv.decodeString(KEY_SETTINGS)
        val prevDeepSeek = AppSecretStore.readWithStatus(SECRET_DEEPSEEK_API_KEY)
        val prevCustomSearch = AppSecretStore.readWithStatus(SECRET_CUSTOM_SEARCH_API_KEY)
        val prevCustomFetch = AppSecretStore.readWithStatus(SECRET_CUSTOM_FETCH_API_KEY)
        check(prevDeepSeek.succeeded && prevCustomSearch.succeeded && prevCustomFetch.succeeded) {
            "Secure credential storage is unavailable"
        }

        try {
            check(writeSecret(SECRET_DEEPSEEK_API_KEY, trimmedCreds.deepSeekApiKey)) {
                "Failed to save DeepSeek web API key"
            }
            check(writeSecret(SECRET_CUSTOM_SEARCH_API_KEY, trimmedCreds.customSearchApiKey)) {
                "Failed to save custom search API key"
            }
            check(writeSecret(SECRET_CUSTOM_FETCH_API_KEY, trimmedCreds.customFetchApiKey)) {
                "Failed to save custom fetch API key"
            }
            val encoded = json.encodeToString(normalized)
            check(mmkv.encode(KEY_SETTINGS, encoded) && mmkv.decodeString(KEY_SETTINGS) == encoded) {
                "Failed to save web tools settings"
            }
            return normalized
        } catch (failure: Exception) {
            runCatching { writeSecret(SECRET_DEEPSEEK_API_KEY, prevDeepSeek.value.orEmpty()) }
            runCatching { writeSecret(SECRET_CUSTOM_SEARCH_API_KEY, prevCustomSearch.value.orEmpty()) }
            runCatching { writeSecret(SECRET_CUSTOM_FETCH_API_KEY, prevCustomFetch.value.orEmpty()) }
            runCatching {
                if (previousRaw == null) mmkv.removeValueForKey(KEY_SETTINGS)
                else mmkv.encode(KEY_SETTINGS, previousRaw)
            }
            throw failure
        }
    }

    fun validate(
        settings: WebToolsSettings,
        credentials: WebToolsCredentials,
        hasProviderDeepSeekKey: Boolean,
    ) {
        val usesDeepSeek = settings.searchBackend == WebToolBackend.DEEPSEEK ||
            settings.fetchBackend == WebToolBackend.DEEPSEEK
        if (usesDeepSeek) {
            if (credentials.deepSeekApiKey.isBlank() && !hasProviderDeepSeekKey) {
                throw WebToolsValidationException("请填写 DeepSeek API Key，或先在模型提供商中配置 DeepSeek")
            }
            runCatching {
                ConfigurableWebBackends.deepSeekEndpoint(settings.deepSeek.baseUrl, "search")
            }.getOrElse {
                throw WebToolsValidationException("DeepSeek Base URL 须为合法的 HTTP(S) 地址")
            }
        }
        if (settings.searchBackend == WebToolBackend.CUSTOM) {
            val rawUrl = settings.customSearch.url.trim()
            if (rawUrl.isEmpty()) {
                throw WebToolsValidationException("请填写自定义搜索接口地址")
            }
            val probeUrl = rawUrl
                .replace("{{query}}", "omnibot")
                .replace("{{raw_query}}", "omnibot")
                .replace("{{count}}", "5")
            runCatching { WebHttpTransport.validateUrl(probeUrl) }.getOrElse {
                throw WebToolsValidationException("自定义搜索接口须为合法的 HTTP(S) 地址")
            }
            runCatching {
                ConfigurableWebBackends.parseCustomHeaders(
                    settings.customSearch.headersText,
                    credentials.customSearchApiKey,
                )
            }.getOrElse {
                throw WebToolsValidationException(it.message ?: "自定义搜索请求头格式无效")
            }
        }
        if (settings.fetchBackend == WebToolBackend.CUSTOM) {
            val rawUrl = settings.customFetch.url.trim()
            if (rawUrl.isEmpty()) {
                throw WebToolsValidationException("请填写自定义读取接口地址")
            }
            val probeUrl = rawUrl
                .replace("{{url}}", "https%3A%2F%2Fexample.com")
                .replace("{{raw_url}}", "https://example.com")
            runCatching { WebHttpTransport.validateUrl(probeUrl) }.getOrElse {
                throw WebToolsValidationException("自定义读取接口须为合法的 HTTP(S) 地址")
            }
            runCatching {
                ConfigurableWebBackends.parseCustomHeaders(
                    settings.customFetch.headersText,
                    credentials.customFetchApiKey,
                )
            }.getOrElse {
                throw WebToolsValidationException(it.message ?: "自定义读取请求头格式无效")
            }
        }
    }

    fun toChannelMap(
        settings: WebToolsSettings = getSettings(),
        credentials: WebToolsCredentials = runCatching { getCredentials() }.getOrDefault(WebToolsCredentials()),
        providerLink: DeepSeekProviderLink? = deepSeekProviderCredentials(),
    ): Map<String, Any?> = linkedMapOf(
        "searchBackend" to settings.searchBackend.wireName,
        "fetchBackend" to settings.fetchBackend.wireName,
        "deepSeekBaseUrl" to settings.deepSeek.baseUrl,
        "deepSeekApiKey" to credentials.deepSeekApiKey,
        "hasProviderDeepSeekKey" to (providerLink != null),
        "providerDeepSeekBaseUrl" to (providerLink?.baseUrl ?: ""),
        "customSearch" to mapOf(
            "method" to settings.customSearch.method.name,
            "url" to settings.customSearch.url,
            "headersText" to settings.customSearch.headersText,
            "bodyTemplate" to settings.customSearch.bodyTemplate,
            "resultsPath" to settings.customSearch.resultsPath,
            "titleField" to settings.customSearch.titleField,
            "urlField" to settings.customSearch.urlField,
            "snippetField" to settings.customSearch.snippetField,
            "apiKey" to credentials.customSearchApiKey,
        ),
        "customFetch" to mapOf(
            "method" to settings.customFetch.method.name,
            "url" to settings.customFetch.url,
            "headersText" to settings.customFetch.headersText,
            "bodyTemplate" to settings.customFetch.bodyTemplate,
            "contentField" to settings.customFetch.contentField,
            "titleField" to settings.customFetch.titleField,
            "apiKey" to credentials.customFetchApiKey,
        ),
    )

    private fun normalizeSettings(settings: WebToolsSettings): WebToolsSettings = settings.copy(
        deepSeek = DeepSeekWebConfig(
            baseUrl = settings.deepSeek.baseUrl.trim().ifEmpty { DeepSeekWebConfig.DEFAULT_BASE_URL },
        ),
        customSearch = settings.customSearch.copy(
            url = settings.customSearch.url.trim(),
            headersText = settings.customSearch.headersText.trim(),
            bodyTemplate = settings.customSearch.bodyTemplate.trim()
                .ifEmpty { CustomWebSearchConfig.DEFAULT_BODY_TEMPLATE },
            resultsPath = settings.customSearch.resultsPath.trim()
                .ifEmpty { CustomWebSearchConfig.DEFAULT_RESULTS_PATH },
            titleField = settings.customSearch.titleField.trim()
                .ifEmpty { CustomWebSearchConfig.DEFAULT_TITLE_FIELD },
            urlField = settings.customSearch.urlField.trim()
                .ifEmpty { CustomWebSearchConfig.DEFAULT_URL_FIELD },
            snippetField = settings.customSearch.snippetField.trim()
                .ifEmpty { CustomWebSearchConfig.DEFAULT_SNIPPET_FIELD },
        ),
        customFetch = settings.customFetch.copy(
            url = settings.customFetch.url.trim(),
            headersText = settings.customFetch.headersText.trim(),
            bodyTemplate = settings.customFetch.bodyTemplate.trim()
                .ifEmpty { CustomWebFetchConfig.DEFAULT_BODY_TEMPLATE },
            contentField = settings.customFetch.contentField.trim(),
            titleField = settings.customFetch.titleField.trim(),
        ),
    )

    private fun normalizeDeepSeekBaseUrl(rawBaseUrl: String): String {
        val trimmed = rawBaseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        if (DeepSeekProvider.isOfficialBaseUrl(trimmed)) {
            return DeepSeekWebConfig.DEFAULT_BASE_URL
        }
        return when {
            trimmed.endsWith("/anthropic/v1", ignoreCase = true) ->
                trimmed.dropLast("/anthropic/v1".length)
            trimmed.endsWith("/anthropic", ignoreCase = true) ->
                trimmed.dropLast("/anthropic".length)
            else -> trimmed
        }
    }

    private fun writeSecret(key: String, value: String): Boolean {
        val trimmed = value.trim()
        return if (trimmed.isEmpty()) {
            AppSecretStore.delete(key)
        } else {
            AppSecretStore.write(key, trimmed)
        }
    }
}
