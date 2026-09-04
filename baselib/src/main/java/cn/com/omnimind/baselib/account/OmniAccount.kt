package cn.com.omnimind.baselib.account

import android.content.Context

object OmniAccount {
    @Volatile
    private var configuredBaseUrl: String = ""

    @Volatile
    private var configuredRepository: AccountRepository? = null

    @Volatile
    private var configuredPlatformGatewayUrl: String = ""

    @Volatile
    private var configuredAllowInsecureLoopback: Boolean = false

    @Volatile
    private var configuredCloudServiceAccessProvider: () -> CloudServiceAccessState =
        CloudServiceAccessState::allowedByDefault

    fun initialize(
        context: Context,
        baseUrl: String,
        platformGatewayUrl: String = "",
        allowInsecureLoopback: Boolean = false,
        cloudServiceAccessProvider: () -> CloudServiceAccessState =
            CloudServiceAccessState::allowedByDefault,
    ) {
        configuredCloudServiceAccessProvider = cloudServiceAccessProvider
        val normalized = baseUrl.trim().takeIf(String::isNotEmpty)?.let {
            OfficialEndpointSecurity.normalizeBaseUrl(
                raw = it,
                label = "account base URL",
                allowInsecureLoopback = allowInsecureLoopback,
            )
        }.orEmpty()
        val normalizedGateway = platformGatewayUrl.trim().takeIf(String::isNotEmpty)?.let {
            OfficialEndpointSecurity.normalizeBaseUrl(
                raw = it,
                label = "platform gateway URL",
                allowInsecureLoopback = allowInsecureLoopback,
            )
        }.orEmpty()
        if (normalized.isEmpty()) {
            configuredBaseUrl = ""
            configuredPlatformGatewayUrl = ""
            configuredAllowInsecureLoopback = false
            configuredRepository = null
            return
        }
        if (
            configuredRepository != null &&
            configuredBaseUrl == normalized &&
            configuredPlatformGatewayUrl == normalizedGateway
        ) return
        synchronized(this) {
            if (
                configuredRepository != null &&
                configuredBaseUrl == normalized &&
                configuredPlatformGatewayUrl == normalizedGateway
            ) return
            configuredRepository = AccountRepository(
                remote = AccountApiClient(
                    baseUrl = normalized,
                    allowInsecureLoopback = allowInsecureLoopback,
                ),
                tokenStore = EncryptedAccountTokenStore(context),
                aiAccessModeStore = SharedPreferencesAiAccessModeStore(context),
                platformModels = normalizedGateway
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        PlatformModelApiClient(
                            gatewayBaseUrl = it,
                            allowInsecureLoopback = allowInsecureLoopback,
                        )
                    },
                cloudServiceAccessProvider = ::currentCloudServiceAccess,
            )
            configuredBaseUrl = normalized
            configuredPlatformGatewayUrl = normalizedGateway
            configuredAllowInsecureLoopback = allowInsecureLoopback
        }
    }

    fun isConfigured(): Boolean = configuredRepository != null

    fun repository(): AccountRepository =
        configuredRepository ?: throw AccountNotConfiguredException()

    fun currentCloudServiceAccess(): CloudServiceAccessState =
        configuredCloudServiceAccessProvider()

    fun currentAiRequestAccess(): AiRequestAccess {
        val repository = configuredRepository
        val cloudServiceAccess = currentCloudServiceAccess()
        if (repository == null) {
            return AiRequestAccess(mode = AiAccessMode.BYOK)
        }
        val signedIn = try {
            repository.isSignedIn()
        } catch (_: AccountCredentialStorageException) {
            return AiRequestAccess(
                mode = AiAccessMode.PLATFORM,
                unavailableReason = "登录凭证暂时无法读取，请重启应用后重试",
            )
        }
        if (!signedIn) {
            return AiRequestAccess(mode = AiAccessMode.BYOK)
        }
        val accessToken = try {
            repository.accessTokenForPlatformGateway()
        } catch (_: AccountCredentialStorageException) {
            return AiRequestAccess(
                mode = AiAccessMode.PLATFORM,
                unavailableReason = "登录凭证暂时无法读取，请重启应用后重试",
            )
        } catch (_: AccountNotAuthenticatedException) {
            null
        }
        return AiRequestAccessResolver.resolve(
            accountConfigured = true,
            signedIn = signedIn,
            cachedMode = repository.cachedAiAccessMode(),
            platformGatewayUrl = configuredPlatformGatewayUrl,
            accessToken = accessToken,
            allowInsecureLoopback = configuredAllowInsecureLoopback,
            cloudServiceAccess = cloudServiceAccess,
        )
    }
}
