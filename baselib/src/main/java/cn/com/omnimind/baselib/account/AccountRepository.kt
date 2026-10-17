package cn.com.omnimind.baselib.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val sessionLock = Any()
    private val generation = MutableStateFlow(0L)
    val sessionGeneration = generation.asStateFlow()
    private var cloudIdentity: Pair<String, AccountUser>? = null
    private val localIdentity = MutableStateFlow(runCatching { tokenStore.read()?.localUserId }.getOrNull())
    val localUserId = localIdentity.asStateFlow()

    fun cachedCloudUserId(): String? = synchronized(sessionLock) { tokenStore.read()?.localUserId }

    private fun rememberVerifiedIdentity(accessToken: String, user: AccountUser) = synchronized(sessionLock) {
        val tokens = tokenStore.read() ?: throw AccountNotAuthenticatedException()
        if (tokens.accessToken != accessToken) throw AccountNotAuthenticatedException()
        if (tokens.localUserId != user.id && !tokenStore.write(tokens.copy(localUserId = user.id))) {
            throw AccountCredentialStorageException()
        }
        cloudIdentity = accessToken to user
        localIdentity.value = user.id
    }

    fun requireSessionGeneration(expected: Long) = synchronized(sessionLock) {
        if (generation.value != expected || tokenStore.read() == null) {
            throw AccountNotAuthenticatedException()
        }
    }

    /** Official Cloud uses this account's existing encrypted credentials and
     * refresh owner. A write is never replayed after an HTTP 401 or lost reply. */
    suspend fun <T> withCloudAccessToken(
        expectedGeneration: Long = generation.value,
        expectedUserId: String? = null,
        retryUnauthorized: Boolean = true,
        operation: suspend (AccountUser, String) -> T,
    ): T {
        requireSessionGeneration(expectedGeneration)
        refreshSessionIfNeeded()
        return authorized(retryUnauthorized) { accessToken ->
            requireSessionGeneration(expectedGeneration)
            val cached = synchronized(sessionLock) { cloudIdentity?.takeIf { it.first == accessToken }?.second }
            val user = cached ?: remote.getCurrentUser(accessToken).also {
                synchronized(sessionLock) {
                    requireSessionGeneration(expectedGeneration)
                    rememberVerifiedIdentity(accessToken, it)
                }
            }
            if (expectedUserId != null && user.id != expectedUserId) throw AccountNotAuthenticatedException()
            requireSessionGeneration(expectedGeneration)
            operation(user, accessToken).also { requireSessionGeneration(expectedGeneration) }
        }
    }

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
        val expectedGeneration = generation.value
        val response = remote.login(email, password)
        val session = response.copy(tokens = response.tokens.copy(localUserId = response.user.id))
        synchronized(sessionLock) {
            if (generation.value != expectedGeneration) throw AccountNotAuthenticatedException()
            if (!tokenStore.write(session.tokens)) {
                clearLocalSession()
                throw AccountCredentialStorageException()
            }
            cloudIdentity = session.tokens.accessToken to session.user
            localIdentity.value = session.user.id
            generation.value += 1
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

    suspend fun currentUser(): AccountUser = authorized { token ->
        remote.getCurrentUser(token).also { rememberVerifiedIdentity(token, it) }
    }

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
        // Fence in-flight refresh/Cloud requests before awaiting the network.
        clearLocalSession()
        if (tokens != null && cloudServiceAccessProvider().allowed) {
            remote.logout(tokens.refreshToken)
        }
    }

    private suspend fun <T> authorized(operation: suspend (String) -> T): T = authorized(true, operation)

    private suspend fun <T> authorized(retryUnauthorized: Boolean, operation: suspend (String) -> T): T {
        requireCloudServiceAccess()
        val initial = tokenStore.read() ?: throw AccountNotAuthenticatedException()
        return try {
            operation(initial.accessToken)
        } catch (error: AccountApiException) {
            if (error.statusCode != 401 || !retryUnauthorized) throw error
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
            synchronized(sessionLock) {
                if (tokenStore.read()?.accessToken == rejectedAccessToken) {
                    clearLocalSession()
                }
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
            val response = remote.refresh(current.refreshToken)
            response.copy(tokens = response.tokens.copy(localUserId = response.user.id)).also { refreshed ->
                synchronized(sessionLock) {
                    if (tokenStore.read() != current) throw AccountNotAuthenticatedException()
                    if (!tokenStore.write(refreshed.tokens)) {
                        clearLocalSession()
                        throw AccountCredentialStorageException()
                    }
                    cloudIdentity = refreshed.tokens.accessToken to refreshed.user
                    localIdentity.value = refreshed.user.id
                }
            }
        } catch (error: AccountApiException) {
            if (error.statusCode == 401) {
                synchronized(sessionLock) {
                    if (tokenStore.read() == current) clearLocalSession()
                }
            }
            throw error
        }
    }

    private fun clearLocalSession() = synchronized(sessionLock) {
        generation.value += 1
        cloudIdentity = null
        localIdentity.value = null
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
