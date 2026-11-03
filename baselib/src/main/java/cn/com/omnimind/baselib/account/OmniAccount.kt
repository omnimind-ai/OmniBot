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

    fun currentAiRequestAccess(): AiRequestAccess = resolveAccountAiRequestAccess(
        repository = configuredRepository,
        platformGatewayUrl = configuredPlatformGatewayUrl,
        allowInsecureLoopback = configuredAllowInsecureLoopback,
        cloudServiceAccess = currentCloudServiceAccess(),
    )
}

/** Projects account availability without turning a cloud-policy block into a failed settings read. */
internal fun resolveAccountAiRequestAccess(
    repository: AccountRepository?,
    platformGatewayUrl: String,
    allowInsecureLoopback: Boolean = false,
    cloudServiceAccess: CloudServiceAccessState,
): AiRequestAccess {
    fun resolve(signedIn: Boolean, accessToken: String? = null): AiRequestAccess =
        AiRequestAccessResolver.resolve(
            accountConfigured = repository != null,
            signedIn = signedIn,
            cachedMode = null,
            platformGatewayUrl = platformGatewayUrl,
            accessToken = accessToken,
            allowInsecureLoopback = allowInsecureLoopback,
            cloudServiceAccess = cloudServiceAccess,
        )

    fun unavailable(reason: String): AiRequestAccess =
        resolve(signedIn = true).copy(unavailableReason = reason)

    if (repository == null) {
        return resolve(signedIn = false)
    }
    val signedIn = try {
        repository.isSignedIn()
    } catch (_: AccountCredentialStorageException) {
        return unavailable("登录凭证暂时无法读取，请重启应用后重试")
    }
    if (!signedIn || !cloudServiceAccess.allowed) {
        // Token access enforces the request policy and may throw. A passive
        // Provider/settings read needs only its unavailable descriptor.
        return resolve(signedIn = signedIn)
    }
    val accessToken = try {
        repository.accessTokenForPlatformGateway()
    } catch (_: AccountCredentialStorageException) {
        return unavailable("登录凭证暂时无法读取，请重启应用后重试")
    } catch (error: CloudServiceUpgradeRequiredException) {
        return unavailable(error.message.orEmpty())
    } catch (error: CloudServicePolicyUnavailableException) {
        // The policy can change after the snapshot above was captured.
        return unavailable(error.message.orEmpty())
    } catch (_: AccountNotAuthenticatedException) {
        null
    }
    return resolve(signedIn = true, accessToken = accessToken)
}
