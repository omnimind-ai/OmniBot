package cn.com.omnimind.baselib.account

import android.content.Context

interface AiAccessModeStore {
    fun read(): AiAccessMode?

    fun write(mode: AiAccessMode)

    fun clear()
}

/**
 * Persists only the user's platform/BYOK choice. This value is not a secret;
 * account tokens remain in EncryptedAccountTokenStore and BYOK keys remain in
 * the existing device-local model-provider store.
 */
class SharedPreferencesAiAccessModeStore(context: Context) : AiAccessModeStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        FILE_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    override fun read(): AiAccessMode? = preferences.getString(KEY_MODE, null)
        ?.let { runCatching { AiAccessMode.fromWireValue(it) }.getOrNull() }

    @Synchronized
    override fun write(mode: AiAccessMode) {
        preferences.edit().putString(KEY_MODE, mode.wireValue).apply()
    }

    @Synchronized
    override fun clear() {
        preferences.edit().remove(KEY_MODE).apply()
    }

    private companion object {
        const val FILE_NAME = "omni_account_ai_access"
        const val KEY_MODE = "mode"
    }
}

data class AiRequestAccess(
    val mode: AiAccessMode?,
    val platformGatewayUrl: String? = null,
    val bearerToken: String? = null,
    val unavailableReason: String? = null,
) {
    val usesPlatform: Boolean
        get() = mode == AiAccessMode.PLATFORM && unavailableReason == null
}

data class AiTransportRoute(
    val apiBase: String?,
    val apiKey: String?,
    val customHeaders: Map<String, String>,
    val protocolType: String,
    val wireApi: String,
    val routeTag: String?,
)

object AiRequestTransportPolicy {
    private const val PLATFORM_ROUTE_TAG = "platform_gateway"

    fun apply(access: AiRequestAccess, byokRoute: AiTransportRoute): AiTransportRoute {
        if (!access.usesPlatform) return byokRoute
        return AiTransportRoute(
            apiBase = access.platformGatewayUrl,
            apiKey = access.bearerToken,
            customHeaders = emptyMap(),
            protocolType = "openai_compatible",
            wireApi = "chat_completions",
            routeTag = PLATFORM_ROUTE_TAG,
        )
    }
}

/** Pure policy kept separate so the security boundary can be unit-tested. */
object AiRequestAccessResolver {
    fun resolve(
        accountConfigured: Boolean,
        signedIn: Boolean,
        cachedMode: AiAccessMode?,
        platformGatewayUrl: String?,
        accessToken: String?,
        allowInsecureLoopback: Boolean = false,
    ): AiRequestAccess {
        if (!accountConfigured || !signedIn) {
            return AiRequestAccess(mode = AiAccessMode.BYOK)
        }
        if (cachedMode == null) {
            return AiRequestAccess(
                mode = null,
                unavailableReason = "账号的 AI 使用方式尚未同步，请打开账号中心后重试",
            )
        }
        if (cachedMode == AiAccessMode.BYOK) {
            return AiRequestAccess(mode = AiAccessMode.BYOK)
        }

        val gateway = platformGatewayUrl?.trim()?.trimEnd('/').orEmpty()
        if (gateway.isEmpty()) {
            return AiRequestAccess(
                mode = AiAccessMode.PLATFORM,
                unavailableReason = "平台 AI 网关尚未配置",
            )
        }
        if (!OfficialEndpointSecurity.isAllowed(gateway, allowInsecureLoopback)) {
            return AiRequestAccess(
                mode = AiAccessMode.PLATFORM,
                unavailableReason = "Platform AI gateway must use HTTPS",
            )
        }
        val token = accessToken?.trim().orEmpty()
        if (token.isEmpty()) {
            return AiRequestAccess(
                mode = AiAccessMode.PLATFORM,
                unavailableReason = "登录状态已失效，请重新登录",
            )
        }
        return AiRequestAccess(
            mode = AiAccessMode.PLATFORM,
            platformGatewayUrl = gateway,
            bearerToken = token,
        )
    }
}
