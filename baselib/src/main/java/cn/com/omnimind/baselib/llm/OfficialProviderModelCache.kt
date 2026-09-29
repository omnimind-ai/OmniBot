package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.account.AiRequestAccess
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.util.Base64

/** Offline launch metadata only; never grants access or stores an account token. */
internal object OfficialProviderModelCache {
    fun scope(access: AiRequestAccess): String? {
        if (!access.usesPlatform) return null
        return scope(access.bearerToken, access.platformGatewayUrl)
    }

    internal fun scope(token: String?, gateway: String?): String? = runCatching {
        val parts = token?.split('.') ?: return null
        if (parts.size != 3 || gateway.isNullOrBlank()) return null
        val claims = JsonParser.parseString(
            String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8),
        ).asJsonObject
        val identity = listOf("iss", "sub", "sid").map {
            claims[it]?.asString?.takeIf(String::isNotBlank) ?: return null
        } + gateway.trim().trimEnd('/')
        MessageDigest.getInstance("SHA-256").digest(Gson().toJson(identity).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun encode(scope: String, models: List<ProviderModelOption>): String = Gson().toJson(
        mapOf("scope" to scope, "models" to models.filter { it.id.isNotBlank() }.distinctBy { it.id }),
    )

    fun decode(raw: String?, expectedScope: String?): List<ProviderModelOption> = runCatching {
        if (raw.isNullOrBlank() || expectedScope == null) return emptyList()
        val root = JsonParser.parseString(raw).asJsonObject
        if (root["scope"]?.asString != expectedScope) return emptyList()
        root["models"].asJsonArray.map { Gson().fromJson(it, ProviderModelOption::class.java) }
            .filter { !it.id.isNullOrBlank() }.distinctBy { it.id }
    }.getOrDefault(emptyList())
}
