package cn.com.omnimind.baselib.account

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private fun secureOfficialHttpClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

class AccountApiClient(
    baseUrl: String,
    private val callFactory: Call.Factory = secureOfficialHttpClient(),
    private val gson: Gson = Gson(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    allowInsecureLoopback: Boolean = false,
) : AccountRemoteDataSource {
    private val normalizedBaseUrl = OfficialEndpointSecurity.normalizeBaseUrl(
        raw = baseUrl,
        label = "baseUrl",
        allowInsecureLoopback = allowInsecureLoopback,
    )

    override suspend fun requestRegistrationCode(email: String): RegistrationCodeRequest {
        return requestEmailCode(email, "register")
    }

    override suspend fun requestPasswordResetCode(email: String): RegistrationCodeRequest {
        return requestEmailCode(email, "reset_password")
    }

    private suspend fun requestEmailCode(
        email: String,
        purpose: String,
    ): RegistrationCodeRequest {
        val response = executeJson(
            request = jsonRequest(
                path = "/v1/auth/email-codes",
                method = "POST",
                body = EmailCodeRequest(email = email.trim(), purpose = purpose),
            ),
            responseClass = EmailCodeResponse::class.java,
        )
        return RegistrationCodeRequest(
            requestId = response.requestId.required("requestId"),
            expiresInSeconds = response.expiresInSeconds,
        )
    }

    override suspend fun register(
        email: String,
        password: String,
        verificationRequestId: String,
        verificationCode: String,
    ): AccountUser = executeJson(
        request = jsonRequest(
            path = "/v1/auth/register",
            method = "POST",
            body = RegisterRequest(
                email = email.trim(),
                password = password,
                verificationRequestId = verificationRequestId.trim(),
                verificationCode = verificationCode.trim(),
            ),
        ),
        responseClass = UserResponse::class.java,
    ).toDomain()

    override suspend fun login(email: String, password: String): AccountSession =
        executeJson(
            request = jsonRequest(
                path = "/v1/auth/login",
                method = "POST",
                body = LoginRequest(email = email.trim(), password = password),
            ),
            responseClass = TokenPairResponse::class.java,
        ).toDomain()

    override suspend fun refresh(refreshToken: String): AccountSession = executeJson(
        request = jsonRequest(
            path = "/v1/auth/refresh",
            method = "POST",
            body = RefreshTokenRequest(refreshToken = refreshToken),
        ),
        responseClass = TokenPairResponse::class.java,
    ).toDomain()

    override suspend fun logout(refreshToken: String) {
        executeWithoutBody(
            jsonRequest(
                path = "/v1/auth/logout",
                method = "POST",
                body = RefreshTokenRequest(refreshToken = refreshToken),
            )
        )
    }

    override suspend fun getCurrentUser(accessToken: String): AccountUser = executeJson(
        request = authenticatedRequest("/v1/me", "GET", accessToken),
        responseClass = UserResponse::class.java,
    ).toDomain()

    override suspend fun getAiSettings(accessToken: String): AiSettings = executeJson(
        request = authenticatedRequest("/v1/me/ai-settings", "GET", accessToken),
        responseClass = AiSettingsResponse::class.java,
    ).toDomain()

    override suspend fun updateAiSettings(
        accessToken: String,
        mode: AiAccessMode,
    ): AiSettings = executeJson(
        request = jsonRequest(
            path = "/v1/me/ai-settings",
            method = "PUT",
            body = UpdateAiSettingsRequest(mode = mode.wireValue),
            accessToken = accessToken,
        ),
        responseClass = AiSettingsResponse::class.java,
    ).toDomain()

    override suspend fun resetPassword(
        email: String,
        newPassword: String,
        verificationRequestId: String,
        verificationCode: String,
    ) {
        executeWithoutBody(
            jsonRequest(
                path = "/v1/auth/password-reset",
                method = "POST",
                body = PasswordResetRequest(
                    email = email.trim(),
                    newPassword = newPassword,
                    verificationRequestId = verificationRequestId.trim(),
                    verificationCode = verificationCode.trim(),
                ),
            )
        )
    }

    override suspend fun changePassword(
        accessToken: String,
        currentPassword: String,
        newPassword: String,
    ) {
        executeWithoutBody(
            jsonRequest(
                path = "/v1/me/password",
                method = "PUT",
                body = ChangePasswordRequest(currentPassword, newPassword),
                accessToken = accessToken,
            )
        )
    }

    override suspend fun listSessions(accessToken: String): List<AccountDeviceSession> {
        val response = executeJson(
            request = authenticatedRequest("/v1/me/sessions", "GET", accessToken),
            responseClass = SessionsResponse::class.java,
        )
        val items = response.items
            ?: throw AccountProtocolException("Account response is missing sessions.items")
        return items.map { it.toDomain() }
    }

    override suspend fun revokeSession(accessToken: String, sessionId: String) {
        val normalizedSessionId = sessionId.trim().also {
            require(it.isNotEmpty()) { "sessionId is empty" }
            require(it.length <= 200) { "sessionId is too long" }
        }
        val url = endpoint("/v1/me/sessions").toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegment(normalizedSessionId)
            ?.build()
            ?: throw AccountProtocolException("Account session URL is invalid")
        executeWithoutBody(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .delete()
                .build()
        )
    }

    override suspend fun revokeOtherSessions(accessToken: String): Int {
        val response = executeJson(
            request = authenticatedRequest("/v1/me/sessions", "DELETE", accessToken),
            responseClass = RevokeSessionsResponse::class.java,
        )
        return response.revoked.requiredNonNegative("revoked")
    }

    override suspend fun listPlatformUsage(
        accessToken: String,
        limit: Int,
    ): List<PlatformUsageEntry> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val url = endpoint("/v1/me/platform-usage").toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("limit", limit.toString())
            ?.build()
            ?: throw AccountProtocolException("Account usage URL is invalid")
        val response = executeJson(
            request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .get()
                .build(),
            responseClass = PlatformUsageResponse::class.java,
        )
        val items = response.items
            ?: throw AccountProtocolException("Account response is missing usage.items")
        return items.map { it.toDomain() }
    }

    override suspend fun deleteAccount(accessToken: String, currentPassword: String) {
        executeWithoutBody(
            jsonRequest(
                path = "/v1/me",
                method = "DELETE",
                body = DeleteAccountRequest(currentPassword),
                accessToken = accessToken,
            )
        )
    }

    private fun authenticatedRequest(path: String, method: String, accessToken: String): Request =
        Request.Builder()
            .url(endpoint(path))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .method(method, null)
            .build()

    private fun jsonRequest(
        path: String,
        method: String,
        body: Any,
        accessToken: String? = null,
    ): Request {
        val builder = Request.Builder()
            .url(endpoint(path))
            .header("Accept", "application/json")
            .method(
                method,
                gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE),
            )
        if (!accessToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $accessToken")
        }
        return builder.build()
    }

    private fun endpoint(path: String): String = "$normalizedBaseUrl/${path.trimStart('/')}"

    private suspend fun <T> executeJson(request: Request, responseClass: Class<T>): T {
        val body = execute(request)
        return try {
            gson.fromJson(body, responseClass)
                ?: throw AccountProtocolException("Account server returned an empty JSON value")
        } catch (error: JsonParseException) {
            throw AccountProtocolException("Account server returned invalid JSON", error)
        }
    }

    private suspend fun executeWithoutBody(request: Request) {
        execute(request)
    }

    private suspend fun execute(request: Request): String = withContext(ioDispatcher) {
        callFactory.newCall(request).execute().use { response ->
            val responseBody = response.body
            if (responseBody != null && responseBody.contentLength() > MAX_RESPONSE_BODY_BYTES) {
                throw AccountProtocolException("Account server response is too large")
            }
            val body = responseBody?.source()?.let { source ->
                if (source.request(MAX_RESPONSE_BODY_BYTES + 1L)) {
                    throw AccountProtocolException("Account server response is too large")
                }
                source.readUtf8()
            }.orEmpty()
            if (!response.isSuccessful) {
                val envelope = runCatching {
                    gson.fromJson(body, ErrorEnvelope::class.java)
                }.getOrNull()
                throw AccountApiException(
                    statusCode = response.code,
                    errorCode = envelope?.error?.code,
                    message = envelope?.error?.message?.takeIf { it.isNotBlank() }
                        ?: "Account request failed with HTTP ${response.code}",
                )
            }
            body
        }
    }

    private fun String?.required(fieldName: String): String =
        this?.takeIf { it.isNotBlank() }
            ?: throw AccountProtocolException("Account response is missing $fieldName")

    private fun Boolean?.required(fieldName: String): Boolean =
        this ?: throw AccountProtocolException("Account response is missing $fieldName")

    private fun Long?.requiredNonNegative(fieldName: String): Long =
        this?.takeIf { it >= 0 }
            ?: throw AccountProtocolException(
                "Account response has a missing or invalid $fieldName"
            )

    private fun Int?.requiredNonNegative(fieldName: String): Int =
        this?.takeIf { it >= 0 }
            ?: throw AccountProtocolException(
                "Account response has a missing or invalid $fieldName"
            )

    private fun UserResponse.toDomain(): AccountUser = AccountUser(
        id = id.required("user.id"),
        email = email.required("user.email"),
        role = role.required("user.role"),
        status = status.required("user.status"),
        emailVerifiedAt = emailVerifiedAt.required("user.emailVerifiedAt"),
        createdAt = createdAt.required("user.createdAt"),
    )

    private fun TokenPairResponse.toDomain(): AccountSession = AccountSession(
        user = user?.toDomain()
            ?: throw AccountProtocolException("Account response is missing user"),
        tokens = AccountTokens(
            accessToken = accessToken.required("accessToken"),
            accessExpiresAt = accessExpiresAt.required("accessExpiresAt"),
            refreshToken = refreshToken.required("refreshToken"),
            refreshExpiresAt = refreshExpiresAt.required("refreshExpiresAt"),
        ),
    )

    private fun AiSettingsResponse.toDomain(): AiSettings = AiSettings(
        mode = AiAccessMode.fromWireValue(mode.required("mode")),
        keyStorage = keyStorage.required("keyStorage"),
        platform = platform?.let {
            PlatformQuota(
                enabled = it.platformEnabled,
                balance = it.balanceQuota,
                weeklyLimit = it.weeklyLimitQuota,
                weeklyUsed = it.weeklyUsedQuota,
                weeklyPeriodStart = it.weeklyPeriodStart,
                unit = it.unit.required("platform.unit"),
            )
        } ?: throw AccountProtocolException("Account response is missing platform quota"),
        platformAvailable = platformAvailable,
        platformUnavailableReason = platformUnavailableReason?.trim()?.ifEmpty { null },
        updatedAt = updatedAt.required("updatedAt"),
    )

    private fun SessionResponse.toDomain(): AccountDeviceSession = AccountDeviceSession(
        id = id.required("session.id"),
        expiresAt = expiresAt.required("session.expiresAt"),
        createdAt = createdAt.required("session.createdAt"),
        lastUsedAt = lastUsedAt.required("session.lastUsedAt"),
        current = current.required("session.current"),
    )

    private fun PlatformUsageItemResponse.toDomain(): PlatformUsageEntry = PlatformUsageEntry(
        model = model.required("usage.model"),
        promptTokens = promptTokens.requiredNonNegative("usage.promptTokens"),
        completionTokens = completionTokens.requiredNonNegative("usage.completionTokens"),
        totalTokens = totalTokens.requiredNonNegative("usage.totalTokens"),
        quotaUsed = quotaUsed.requiredNonNegative("usage.quotaUsed"),
        createdAt = createdAt.required("usage.createdAt"),
    )

    private data class EmailCodeRequest(
        @SerializedName("email") val email: String,
        @SerializedName("purpose") val purpose: String,
    )

    private data class EmailCodeResponse(
        @SerializedName("requestId") val requestId: String? = null,
        @SerializedName("expiresInSeconds") val expiresInSeconds: Long = 0,
    )

    private data class RegisterRequest(
        @SerializedName("email") val email: String,
        @SerializedName("password") val password: String,
        @SerializedName("verificationRequestId") val verificationRequestId: String,
        @SerializedName("verificationCode") val verificationCode: String,
    )

    private data class LoginRequest(
        @SerializedName("email") val email: String,
        @SerializedName("password") val password: String,
    )

    private data class RefreshTokenRequest(
        @SerializedName("refreshToken") val refreshToken: String,
    )

    private data class PasswordResetRequest(
        @SerializedName("email") val email: String,
        @SerializedName("newPassword") val newPassword: String,
        @SerializedName("verificationRequestId") val verificationRequestId: String,
        @SerializedName("verificationCode") val verificationCode: String,
    )

    private data class ChangePasswordRequest(
        @SerializedName("currentPassword") val currentPassword: String,
        @SerializedName("newPassword") val newPassword: String,
    )

    private data class DeleteAccountRequest(
        @SerializedName("currentPassword") val currentPassword: String,
    )

    private data class UpdateAiSettingsRequest(
        @SerializedName("mode") val mode: String,
    )

    private data class TokenPairResponse(
        @SerializedName("accessToken") val accessToken: String? = null,
        @SerializedName("accessExpiresAt") val accessExpiresAt: String? = null,
        @SerializedName("refreshToken") val refreshToken: String? = null,
        @SerializedName("refreshExpiresAt") val refreshExpiresAt: String? = null,
        @SerializedName("user") val user: UserResponse? = null,
    )

    private data class UserResponse(
        @SerializedName("id") val id: String? = null,
        @SerializedName("email") val email: String? = null,
        @SerializedName("role") val role: String? = null,
        @SerializedName("status") val status: String? = null,
        @SerializedName("emailVerifiedAt") val emailVerifiedAt: String? = null,
        @SerializedName("createdAt") val createdAt: String? = null,
    )

    private data class AiSettingsResponse(
        @SerializedName("mode") val mode: String? = null,
        @SerializedName("keyStorage") val keyStorage: String? = null,
        @SerializedName("platformAvailable") val platformAvailable: Boolean = false,
        @SerializedName("platformUnavailableReason") val platformUnavailableReason: String? = null,
        @SerializedName("platform") val platform: PlatformQuotaResponse? = null,
        @SerializedName("updatedAt") val updatedAt: String? = null,
    )

    private data class PlatformQuotaResponse(
        @SerializedName("platformEnabled") val platformEnabled: Boolean = false,
        @SerializedName("balanceQuota") val balanceQuota: Long = 0,
        @SerializedName("weeklyLimitQuota") val weeklyLimitQuota: Long = 0,
        @SerializedName("weeklyUsedQuota") val weeklyUsedQuota: Long = 0,
        @SerializedName("weeklyPeriodStart") val weeklyPeriodStart: String? = null,
        @SerializedName("unit") val unit: String? = null,
    )

    private data class SessionsResponse(
        @SerializedName("items") val items: List<SessionResponse>? = null,
    )

    private data class SessionResponse(
        @SerializedName("id") val id: String? = null,
        @SerializedName("expiresAt") val expiresAt: String? = null,
        @SerializedName("createdAt") val createdAt: String? = null,
        @SerializedName("lastUsedAt") val lastUsedAt: String? = null,
        @SerializedName("current") val current: Boolean? = null,
    )

    private data class RevokeSessionsResponse(
        @SerializedName("revoked") val revoked: Int? = null,
    )

    private data class PlatformUsageResponse(
        @SerializedName("items") val items: List<PlatformUsageItemResponse>? = null,
    )

    private data class PlatformUsageItemResponse(
        @SerializedName("model") val model: String? = null,
        @SerializedName("promptTokens") val promptTokens: Long? = null,
        @SerializedName("completionTokens") val completionTokens: Long? = null,
        @SerializedName("totalTokens") val totalTokens: Long? = null,
        @SerializedName("quotaUsed") val quotaUsed: Long? = null,
        @SerializedName("createdAt") val createdAt: String? = null,
    )

    private data class ErrorEnvelope(
        @SerializedName("error") val error: ErrorResponse? = null,
    )

    private data class ErrorResponse(
        @SerializedName("code") val code: String? = null,
        @SerializedName("message") val message: String? = null,
    )

    companion object {
        internal const val MAX_RESPONSE_BODY_BYTES = 1L shl 20
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
