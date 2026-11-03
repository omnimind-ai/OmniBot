package cn.com.omnimind.baselib.account

import android.content.Context

interface AiAccessModeStore {
    fun read(): AiAccessMode?

    fun write(mode: AiAccessMode)

    fun clear()
}

/**
 * Retains the legacy server setting for protocol compatibility. Official AI is
 * now exposed as an additional provider after sign-in, so this value no longer
 * selects a global request route.
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
    const val PLATFORM_ROUTE_TAG = "platform_gateway"

    fun isPlatformRoute(routeTag: String?): Boolean = routeTag == PLATFORM_ROUTE_TAG

    fun apply(access: AiRequestAccess, byokRoute: AiTransportRoute): AiTransportRoute {
        if (!isPlatformRoute(byokRoute.routeTag) || !access.usesPlatform) return byokRoute
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
    @Suppress("UNUSED_PARAMETER")
    fun resolve(
        accountConfigured: Boolean,
        signedIn: Boolean,
        cachedMode: AiAccessMode?,
        platformGatewayUrl: String?,
        accessToken: String?,
        allowInsecureLoopback: Boolean = false,
        cloudServiceAccess: CloudServiceAccessState =
            CloudServiceAccessState.allowedByDefault(),
    ): AiRequestAccess {
        if (!accountConfigured || !signedIn) {
            return AiRequestAccess(mode = AiAccessMode.BYOK)
        }
        val gateway = platformGatewayUrl?.trim()?.trimEnd('/').orEmpty()
        val safeGateway = gateway.takeIf {
            OfficialEndpointSecurity.isAllowed(it, allowInsecureLoopback)
        }
        if (!cloudServiceAccess.allowed) {
            return AiRequestAccess(
                mode = AiAccessMode.PLATFORM,
                // Keep the selected Provider address visible while requests
                // are blocked. Metadata availability grants no credentials.
                platformGatewayUrl = safeGateway,
                unavailableReason = cloudServiceAccess.message.ifBlank {
                    if (cloudServiceAccess.policyKnown) {
                        "请升级应用后再使用账号与官方云服务"
                    } else {
                        "无法验证云服务最低版本，请联网检查更新"
                    }
                },
            )
        }
        if (gateway.isEmpty()) {
            return AiRequestAccess(
                mode = AiAccessMode.PLATFORM,
                unavailableReason = "平台 AI 网关尚未配置",
            )
        }
        if (safeGateway == null) {
            return AiRequestAccess(
                mode = AiAccessMode.PLATFORM,
                unavailableReason = "Platform AI gateway must use HTTPS",
            )
        }
        val token = accessToken?.trim().orEmpty()
        if (token.isEmpty()) {
            return AiRequestAccess(
                mode = AiAccessMode.PLATFORM,
                platformGatewayUrl = safeGateway,
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
