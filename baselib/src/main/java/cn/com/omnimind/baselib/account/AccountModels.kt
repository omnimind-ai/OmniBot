package cn.com.omnimind.baselib.account

enum class AiAccessMode(val wireValue: String) {
    PLATFORM("platform"),
    BYOK("byok");

    companion object {
        fun fromWireValue(value: String): AiAccessMode =
            entries.firstOrNull { it.wireValue == value.trim().lowercase() }
                ?: throw AccountProtocolException("Unknown AI access mode: $value")
    }
}

data class AccountUser(
    val id: String,
    val email: String,
    val role: String,
    val status: String,
    val emailVerifiedAt: String,
    val createdAt: String,
)

data class AccountTokens(
    val accessToken: String,
    val accessExpiresAt: String,
    val refreshToken: String,
    val refreshExpiresAt: String,
)

data class AccountSession(
    val user: AccountUser,
    val tokens: AccountTokens,
)

data class RegistrationCodeRequest(
    val requestId: String,
    val expiresInSeconds: Long,
)

data class AccountDeviceSession(
    val id: String,
    val expiresAt: String,
    val createdAt: String,
    val lastUsedAt: String,
    val current: Boolean,
)

data class PlatformUsageEntry(
    val model: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val quotaUsed: Long,
    val createdAt: String,
)

data class PlatformQuota(
    val enabled: Boolean,
    val balance: Long,
    val unit: String,
    val weeklyLimit: Long = 0,
    val weeklyUsed: Long = 0,
    val weeklyPeriodStart: String? = null,
)

data class PlatformModel(
    val id: String,
    val ownedBy: String? = null,
    val supportedEndpointTypes: List<String> = emptyList(),
)

data class PlatformModelDefaults(
    val text: String? = null,
    val vision: String? = null,
    val image: String? = null,
    val embedding: String? = null,
    val tts: String? = null,
    val ttsVoice: String? = null,
)

data class PlatformModelCapabilities(
    val text: List<String> = emptyList(),
    val vision: List<String> = emptyList(),
    val image: List<String> = emptyList(),
    val embedding: List<String> = emptyList(),
    val tts: List<String> = emptyList(),
    /** Null means the older catalog did not publish stable TTS voice aliases. */
    val ttsVoices: List<String>? = null,
)

/**
 * Public, user-scoped product catalog returned by the official gateway.
 * It deliberately contains only safe model identifiers and capability labels.
 */
data class PlatformModelCatalog(
    val models: List<PlatformModel> = emptyList(),
    val version: String? = null,
    val defaults: PlatformModelDefaults = PlatformModelDefaults(),
    val capabilities: PlatformModelCapabilities = PlatformModelCapabilities(),
    val displayNames: Map<String, String> = emptyMap(),
    val hasOfficialCatalog: Boolean = false,
)

data class AiSettings(
    val mode: AiAccessMode,
    val keyStorage: String,
    val platform: PlatformQuota,
    val platformAvailable: Boolean = false,
    val platformUnavailableReason: String? = null,
    val updatedAt: String,
) {
    val effectiveMode: AiAccessMode
        get() = if (platformAvailable) mode else AiAccessMode.BYOK
}

/**
 * Client-side result of the update Worker's cloud-service version policy.
 *
 * Account and official AI traffic fail closed while the policy is unknown or
 * stale. Local BYOK providers do not use this state and remain available.
 */
data class CloudServiceAccessState(
    val allowed: Boolean,
    val policyKnown: Boolean,
    val currentVersion: String = "",
    val minimumVersion: String = "",
    val message: String = "",
) {
    companion object {
        fun allowedByDefault(): CloudServiceAccessState = CloudServiceAccessState(
            allowed = true,
            policyKnown = true,
        )
    }
}

open class AccountException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class AccountNotConfiguredException :
    AccountException("Account server URL is not configured")

class AccountNotAuthenticatedException :
    AccountException("The user is not signed in")

class AccountCredentialStorageException :
    AccountException("Secure account credential storage is unavailable")

class CloudServiceUpgradeRequiredException(
    val currentVersion: String,
    val minimumVersion: String,
    message: String,
) : AccountException(message)

class CloudServicePolicyUnavailableException(message: String) :
    AccountException(message)

class PlatformGatewayNotConfiguredException :
    AccountException("Platform AI gateway is not configured")

class PlatformModelsUnavailableException(message: String) :
    AccountException(message)

class AccountApiException(
    val statusCode: Int,
    val errorCode: String?,
    message: String,
) : AccountException(message)

class AccountProtocolException(message: String, cause: Throwable? = null) :
    AccountException(message, cause)
