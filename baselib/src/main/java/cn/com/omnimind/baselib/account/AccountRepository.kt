package cn.com.omnimind.baselib.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.format.DateTimeParseException

class AccountRepository(
    private val remote: AccountRemoteDataSource,
    private val tokenStore: AccountTokenStore,
    private val aiAccessModeStore: AiAccessModeStore = VolatileAiAccessModeStore(),
    private val platformModels: PlatformModelRemoteDataSource? = null,
    private val cloudServiceAccessProvider: () -> CloudServiceAccessState =
        CloudServiceAccessState::allowedByDefault,
) {
    private val refreshMutex = Mutex()

    /**
     * Access tokens are short lived.  A foreground transition can happen
     * without any account API request in between, so waiting for the first
     * official-AI request to receive 401 makes the resume path look like a
     * logout.  Refresh only when the durable expiry says it is necessary;
     * ordinary foreground transitions stay offline and do not rotate tokens.
     */
    suspend fun refreshSessionIfNeeded(
        now: Instant = Instant.now(),
        refreshSkewSeconds: Long = ACCESS_TOKEN_REFRESH_SKEW_SECONDS,
    ): Boolean {
        requireCloudServiceAccess()
        val current = tokenStore.read() ?: return false
        if (!isAccessTokenExpiring(
                expiresAt = current.accessExpiresAt,
                now = now,
                refreshSkewSeconds = refreshSkewSeconds,
            )
        ) {
            return false
        }
        refreshSession()
        return true
    }

    fun isSignedIn(): Boolean = tokenStore.read() != null

    suspend fun requestRegistrationCode(email: String): RegistrationCodeRequest {
        requireCloudServiceAccess()
        return remote.requestRegistrationCode(email)
    }

    suspend fun requestPasswordResetCode(email: String): RegistrationCodeRequest {
        requireCloudServiceAccess()
        return remote.requestPasswordResetCode(email)
    }

    suspend fun register(
        email: String,
        password: String,
        verificationRequestId: String,
        verificationCode: String,
    ): AccountUser {
        requireCloudServiceAccess()
        return remote.register(
            email = email,
            password = password,
            verificationRequestId = verificationRequestId,
            verificationCode = verificationCode,
        )
    }

    suspend fun login(email: String, password: String): AccountSession {
        requireCloudServiceAccess()
        val session = remote.login(email, password)
        if (!tokenStore.write(session.tokens)) {
            tokenStore.clear()
            throw AccountCredentialStorageException()
        }
        aiAccessModeStore.clear()
        try {
            getAiSettings()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Login itself succeeded. The UI overview and app-start sync can
            // retry settings without discarding the valid session.
        }
        return session
    }

    suspend fun currentUser(): AccountUser = authorized(remote::getCurrentUser)

    suspend fun getAiSettings(): AiSettings = authorized(remote::getAiSettings).also {
        aiAccessModeStore.write(it.effectiveMode)
    }

    suspend fun updateAiSettings(mode: AiAccessMode): AiSettings =
        authorized { accessToken -> remote.updateAiSettings(accessToken, mode) }.also {
            aiAccessModeStore.write(it.effectiveMode)
        }

    suspend fun resetPassword(
        email: String,
        newPassword: String,
        verificationRequestId: String,
        verificationCode: String,
    ) {
        requireCloudServiceAccess()
        remote.resetPassword(email, newPassword, verificationRequestId, verificationCode)
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) =
        authorized { accessToken ->
            remote.changePassword(accessToken, currentPassword, newPassword)
        }

    suspend fun listSessions(): List<AccountDeviceSession> =
        authorized(remote::listSessions)

    suspend fun revokeSession(sessionId: String) =
        authorized { accessToken -> remote.revokeSession(accessToken, sessionId) }

    suspend fun revokeOtherSessions(): Int = authorized(remote::revokeOtherSessions)

    suspend fun listPlatformUsage(limit: Int = 20): List<PlatformUsageEntry> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return authorized { accessToken -> remote.listPlatformUsage(accessToken, limit) }
    }

    suspend fun deleteAccount(currentPassword: String) {
        // Keep local credentials on ordinary failures so the user can correct the
        // password and retry. Clear them only after the server confirms deletion.
        authorized { accessToken -> remote.deleteAccount(accessToken, currentPassword) }
        clearLocalSession()
    }

    suspend fun getPlatformModels(): List<PlatformModel> {
        return getPlatformModelCatalog().models
    }

    suspend fun getPlatformModelCatalog(): PlatformModelCatalog {
        val source = platformModels ?: throw PlatformGatewayNotConfiguredException()
        return authorized(source::getCatalog)
    }

    fun accessTokenForPlatformGateway(): String {
        requireCloudServiceAccess()
        return tokenStore.read()?.accessToken ?: throw AccountNotAuthenticatedException()
    }

    fun cachedAiAccessMode(): AiAccessMode? = aiAccessModeStore.read()

    suspend fun refreshSession(): AccountSession {
        requireCloudServiceAccess()
        val current = tokenStore.read() ?: throw AccountNotAuthenticatedException()
        return refreshMutex.withLock {
            val latest = tokenStore.read() ?: throw AccountNotAuthenticatedException()
            if (latest.accessToken != current.accessToken) {
                return@withLock AccountSession(
                    user = remote.getCurrentUser(latest.accessToken),
                    tokens = latest,
                )
            }
            refreshAndStore(latest)
        }
    }

    suspend fun logout() {
        val tokens = tokenStore.read()
        try {
            if (tokens != null && cloudServiceAccessProvider().allowed) {
                remote.logout(tokens.refreshToken)
            }
        } finally {
            clearLocalSession()
        }
    }

    private suspend fun <T> authorized(operation: suspend (String) -> T): T {
        requireCloudServiceAccess()
        val initial = tokenStore.read() ?: throw AccountNotAuthenticatedException()
        return try {
            operation(initial.accessToken)
        } catch (error: AccountApiException) {
            if (error.statusCode != 401) throw error
            val refreshed = refreshAfterUnauthorized(initial)
            try {
                operation(refreshed.accessToken)
            } catch (retryError: AccountApiException) {
                if (retryError.statusCode == 401) {
                    clearRejectedSession(refreshed.accessToken)
                }
                throw retryError
            }
        }
    }

    /**
     * A newly refreshed access token being rejected means this local session is
     * no longer usable. Clear it only if no concurrent request has already
     * installed a newer token.
     */
    private suspend fun clearRejectedSession(rejectedAccessToken: String) {
        refreshMutex.withLock {
            if (tokenStore.read()?.accessToken == rejectedAccessToken) {
                clearLocalSession()
            }
        }
    }

    private suspend fun refreshAfterUnauthorized(stale: AccountTokens): AccountTokens =
        refreshMutex.withLock {
            val current = tokenStore.read() ?: throw AccountNotAuthenticatedException()
            if (current.accessToken != stale.accessToken) {
                return@withLock current
            }
            refreshAndStore(current).tokens
        }

    private suspend fun refreshAndStore(current: AccountTokens): AccountSession {
        return try {
            remote.refresh(current.refreshToken).also { refreshed ->
                if (!tokenStore.write(refreshed.tokens)) {
                    tokenStore.clear()
                    throw AccountCredentialStorageException()
                }
            }
        } catch (error: AccountApiException) {
            if (error.statusCode == 401) {
                clearLocalSession()
            }
            throw error
        }
    }

    private fun clearLocalSession() {
        val tokensCleared = try {
            tokenStore.clear()
        } finally {
            aiAccessModeStore.clear()
        }
        if (!tokensCleared) {
            throw AccountCredentialStorageException()
        }
    }

    private fun requireCloudServiceAccess() {
        val access = cloudServiceAccessProvider()
        if (access.allowed) return
        val message = access.message.ifBlank {
            if (access.policyKnown) {
                "请升级应用后再使用账号与官方云服务"
            } else {
                "无法验证云服务最低版本，请联网检查更新"
            }
        }
        if (access.policyKnown && access.minimumVersion.isNotBlank()) {
            throw CloudServiceUpgradeRequiredException(
                currentVersion = access.currentVersion,
                minimumVersion = access.minimumVersion,
                message = message,
            )
        }
        throw CloudServicePolicyUnavailableException(message)
    }

    private fun isAccessTokenExpiring(
        expiresAt: String,
        now: Instant,
        refreshSkewSeconds: Long,
    ): Boolean {
        val expiry = try {
            Instant.parse(expiresAt)
        } catch (_: DateTimeParseException) {
            // Keep the existing reactive 401 recovery for legacy/unknown
            // timestamps.  A malformed timestamp must not force a logout or
            // cause a refresh on every app resume.
            return false
        }
        return !expiry.isAfter(now.plusSeconds(refreshSkewSeconds.coerceAtLeast(0L)))
    }

    private companion object {
        const val ACCESS_TOKEN_REFRESH_SKEW_SECONDS = 30L
    }
}

private class VolatileAiAccessModeStore : AiAccessModeStore {
    @Volatile
    private var mode: AiAccessMode? = null

    override fun read(): AiAccessMode? = mode

    override fun write(mode: AiAccessMode) {
        this.mode = mode
    }

    override fun clear() {
        mode = null
    }
}
