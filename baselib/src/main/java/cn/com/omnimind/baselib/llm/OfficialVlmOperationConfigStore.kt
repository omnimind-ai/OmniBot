package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import java.net.URI

object OfficialVlmOperationConfigStore {
    private const val TAG = "OfficialVlmOperationConfigStore"
    private const val KEY_OFFICIAL_VLM_OPERATION_CONFIG =
        "official_vlm_operation_config_v1"

    private val gson = Gson()
    private val defaultConfig = OfficialVlmOperationConfig()

    @Volatile
    private var bundledDefault: OfficialVlmOperationConfig? = null

    fun getConfig(): OfficialVlmOperationConfig {
        val raw = runCatching {
            MMKV.defaultMMKV().decodeString(KEY_OFFICIAL_VLM_OPERATION_CONFIG)
        }.getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val saved = raw?.let(::parse)
        if (saved != null && (containsLegacySecretField(raw) || raw.contains("https://omni.1775885.xyz"))) {
            saveConfig(saved)
        }
        return saved ?: bundledDefault ?: defaultConfig
    }

    fun saveConfig(config: OfficialVlmOperationConfig): OfficialVlmOperationConfig {
        val normalized = normalize(config)
        MMKV.defaultMMKV()?.encode(
            KEY_OFFICIAL_VLM_OPERATION_CONFIG,
            gson.toJson(normalized)
        )
        return normalized
    }

    fun setBundledDefault(config: OfficialVlmOperationConfig?) {
        bundledDefault = config
            ?.let(::normalize)
            ?.takeIf(OfficialVlmOperationConfig::isConfigured)
    }

    fun normalize(config: OfficialVlmOperationConfig): OfficialVlmOperationConfig {
        return OfficialVlmOperationConfig(
            enabled = config.enabled,
            apiBase = migrateFirstPartyServiceUrl(config.apiBase.trim().trimEnd('/')),
            model = config.model.trim(),
            wireApi = OpenAiWireApi.normalize(config.wireApi)
        )
    }

    // Only the official operation cache is migrated. User Provider profiles
    // are stored separately and keep their chosen endpoints.
    internal fun migrateFirstPartyServiceUrl(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull() ?: return value
        if (uri.scheme != "https" || uri.host != "omni.1775885.xyz" ||
            uri.port != -1 || uri.userInfo != null) return value
        return "https://omnibot.omnimind.com.cn" +
            uri.rawPath.orEmpty() +
            (uri.rawQuery?.let { "?$it" } ?: "") +
            (uri.rawFragment?.let { "#$it" } ?: "")
    }

    internal fun parse(raw: String): OfficialVlmOperationConfig? {
        return runCatching {
            gson.fromJson(raw, OfficialVlmOperationConfig::class.java)
        }.onFailure {
            OmniLog.w(TAG, "parse official VLM config failed: ${it.message}")
        }.getOrNull()?.let(::normalize)
    }

    internal fun containsLegacySecretField(raw: String): Boolean {
        return raw.contains("\"apiKey\"") || raw.contains("\"api_key\"")
    }
}
