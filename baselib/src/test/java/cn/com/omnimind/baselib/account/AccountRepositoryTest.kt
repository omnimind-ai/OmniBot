package cn.com.omnimind.baselib.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRepositoryTest {
    @Test
    fun loginStoresBothTokens() = runBlocking {
        val store = FakeTokenStore()
        val remote = FakeAccountRemote().apply {
            loginHandler = { _, _ -> session("access-one", "refresh-one") }
        }
        val repository = AccountRepository(remote, store)

        val loggedIn = repository.login("learner@example.com", "password")

        assertEquals("access-one", loggedIn.tokens.accessToken)
        assertEquals(loggedIn.tokens, store.tokens)
        assertTrue(repository.isSignedIn())
    }

    @Test
    fun loginFailsClosedWhenEncryptedTokenWriteCannotBeVerified() = runBlocking {
        val store = FakeTokenStore().apply { failWrites = true }
        val remote = FakeAccountRemote().apply {
            loginHandler = { _, _ -> session("access-one", "refresh-one") }
        }
        val repository = AccountRepository(remote, store)

        val error = runCatching {
            repository.login("learner@example.com", "password")
        }.exceptionOrNull()

        assertTrue(error is AccountCredentialStorageException)
        assertNull(store.tokens)
        assertFalse(repository.isSignedIn())
    }

    @Test
    fun unauthorizedSettingsRequestRefreshesRotatedTokensAndRetries() = runBlocking {
        val store = FakeTokenStore(session("expired-access", "old-refresh").tokens)
        val modeStore = FakeAiAccessModeStore()
        val remote = FakeAccountRemote().apply {
            getSettingsHandler = { accessToken ->
                settingsAccessTokens += accessToken
                if (accessToken == "expired-access") {
                    throw AccountApiException(401, "invalid_access_token", "expired")
                }
                aiSettings(AiAccessMode.PLATFORM)
            }
            refreshHandler = { refreshToken ->
                assertEquals("old-refresh", refreshToken)
                session("fresh-access", "rotated-refresh")
            }
        }
        val repository = AccountRepository(remote, store, modeStore)

        val settings = repository.getAiSettings()

        assertEquals(AiAccessMode.PLATFORM, settings.mode)
        assertEquals(listOf("expired-access", "fresh-access"), remote.settingsAccessTokens)
        assertEquals("fresh-access", store.tokens?.accessToken)
        assertEquals("rotated-refresh", store.tokens?.refreshToken)
        assertEquals(AiAccessMode.PLATFORM, modeStore.mode)
    }

    @Test
    fun unauthorizedPlatformModelsRefreshesOnceAndRetriesWithNewJwt() = runBlocking {
        val store = FakeTokenStore(session("expired-access", "old-refresh").tokens)
        val remote = FakeAccountRemote().apply {
            refreshHandler = { refreshToken ->
                assertEquals("old-refresh", refreshToken)
                session("fresh-access", "rotated-refresh")
            }
        }
        val platformModels = FakePlatformModelRemote().apply {
            handler = { accessToken ->
                accessTokens += accessToken
                if (accessToken == "expired-access") {
                    throw AccountApiException(401, "invalid_access_token", "expired")
                }
                listOf(PlatformModel("Qwen3.5-Plus"))
            }
        }
        val repository = AccountRepository(
            remote = remote,
            tokenStore = store,
            platformModels = platformModels,
        )

        val models = repository.getPlatformModels()

        assertEquals(listOf("Qwen3.5-Plus"), models.map(PlatformModel::id))
        assertEquals(listOf("expired-access", "fresh-access"), platformModels.accessTokens)
        assertEquals("fresh-access", store.tokens?.accessToken)
        assertEquals("rotated-refresh", store.tokens?.refreshToken)
    }

    @Test
    fun rejectedRefreshClearsInvalidLocalSession() = runBlocking {
        val store = FakeTokenStore(session("expired-access", "expired-refresh").tokens)
        val modeStore = FakeAiAccessModeStore(AiAccessMode.PLATFORM)
        val remote = FakeAccountRemote().apply {
            getSettingsHandler = {
                throw AccountApiException(401, "invalid_access_token", "expired")
            }
            refreshHandler = {
                throw AccountApiException(401, "invalid_refresh_token", "expired")
            }
        }
        val repository = AccountRepository(remote, store, modeStore)

        val error = runCatching { repository.getAiSettings() }.exceptionOrNull()

        assertTrue(error is AccountApiException)
        assertNull(store.tokens)
        assertNull(modeStore.mode)
        assertFalse(repository.isSignedIn())
    }

    @Test
    fun secondUnauthorizedAfterRefreshStopsAfterOneRetryAndClearsSession() = runBlocking {
        val store = FakeTokenStore(session("expired-access", "old-refresh").tokens)
        val modeStore = FakeAiAccessModeStore(AiAccessMode.PLATFORM)
        val remote = FakeAccountRemote().apply {
            getSettingsHandler = { accessToken ->
                settingsAccessTokens += accessToken
                throw AccountApiException(401, "invalid_access_token", "expired")
            }
            refreshHandler = { session("rejected-fresh-access", "rotated-refresh") }
        }
        val repository = AccountRepository(remote, store, modeStore)

        val error = runCatching { repository.getAiSettings() }.exceptionOrNull()

        assertTrue(error is AccountApiException)
        assertEquals(
            listOf("expired-access", "rejected-fresh-access"),
            remote.settingsAccessTokens,
        )
        assertEquals(listOf("old-refresh"), remote.refreshTokens)
        assertNull(store.tokens)
        assertNull(modeStore.mode)
    }

    @Test
    fun deleteAccountRetriesUnauthorizedOnceThenClearsLocalStateAfterSuccess() = runBlocking {
        val store = FakeTokenStore(session("expired-access", "old-refresh").tokens)
        val modeStore = FakeAiAccessModeStore(AiAccessMode.PLATFORM)
        val remote = FakeAccountRemote().apply {
            refreshHandler = { session("fresh-access", "rotated-refresh") }
            deleteHandler = { accessToken, _ ->
                deleteAccessTokens += accessToken
                if (accessToken == "expired-access") {
                    throw AccountApiException(401, "invalid_access_token", "expired")
                }
            }
        }
        val repository = AccountRepository(remote, store, modeStore)

        repository.deleteAccount("current password value")

        assertEquals(listOf("expired-access", "fresh-access"), remote.deleteAccessTokens)
        assertEquals(listOf("old-refresh"), remote.refreshTokens)
        assertNull(store.tokens)
        assertNull(modeStore.mode)
    }

    @Test
    fun failedDeleteAccountKeepsLocalStateForCorrectionAndRetry() = runBlocking {
        val initialTokens = session("access", "refresh").tokens
        val store = FakeTokenStore(initialTokens)
        val modeStore = FakeAiAccessModeStore(AiAccessMode.BYOK)
        val remote = FakeAccountRemote().apply {
            deleteHandler = { _, _ ->
                throw AccountApiException(
                    403,
                    "current_password_invalid",
                    "current password is incorrect",
                )
            }
        }
        val repository = AccountRepository(remote, store, modeStore)

        val error = runCatching {
            repository.deleteAccount("wrong password value")
        }.exceptionOrNull()

        assertTrue(error is AccountApiException)
        assertEquals(initialTokens, store.tokens)
        assertEquals(AiAccessMode.BYOK, modeStore.mode)
    }

    @Test
    fun unavailablePlatformIsCachedAsByokForRequestRouting() = runBlocking {
        val store = FakeTokenStore(session("access", "refresh").tokens)
        val modeStore = FakeAiAccessModeStore(AiAccessMode.PLATFORM)
        val remote = FakeAccountRemote().apply {
            getSettingsHandler = {
                aiSettings(AiAccessMode.PLATFORM, platformAvailable = false)
            }
        }
        val repository = AccountRepository(remote, store, modeStore)

        val settings = repository.getAiSettings()

        assertFalse(settings.platformAvailable)
        assertEquals(AiAccessMode.BYOK, settings.effectiveMode)
        assertEquals(AiAccessMode.BYOK, modeStore.mode)
    }

    @Test
    fun logoutClearsTokensEvenWhenServerCannotBeReached() = runBlocking {
        val store = FakeTokenStore(session("access", "refresh").tokens)
        val modeStore = FakeAiAccessModeStore(AiAccessMode.BYOK)
        val remote = FakeAccountRemote().apply {
            logoutHandler = { throw AccountException("offline") }
        }
        val repository = AccountRepository(remote, store, modeStore)

        val error = runCatching { repository.logout() }.exceptionOrNull()

        assertTrue(error is AccountException)
        assertNull(store.tokens)
        assertNull(modeStore.mode)
    }

    private fun session(accessToken: String, refreshToken: String) = AccountSession(
        user = AccountUser(
            id = "user-1",
            email = "learner@example.com",
            role = "user",
            status = "active",
            emailVerifiedAt = "2026-08-04T00:00:00Z",
            createdAt = "2026-08-04T00:00:00Z",
        ),
        tokens = AccountTokens(
            accessToken = accessToken,
            accessExpiresAt = "2026-08-04T01:00:00Z",
            refreshToken = refreshToken,
            refreshExpiresAt = "2026-09-03T01:00:00Z",
        ),
    )

    private fun aiSettings(
        mode: AiAccessMode,
        platformAvailable: Boolean = true,
    ) = AiSettings(
        mode = mode,
        keyStorage = "device",
        platform = PlatformQuota(true, 500, "new_api_quota"),
        platformAvailable = platformAvailable,
        platformUnavailableReason = if (platformAvailable) null else "平台 AI 服务暂未开放",
        updatedAt = "2026-08-04T00:00:00Z",
    )
}

private class FakeAiAccessModeStore(initial: AiAccessMode? = null) : AiAccessModeStore {
    var mode: AiAccessMode? = initial

    override fun read(): AiAccessMode? = mode

    override fun write(mode: AiAccessMode) {
        this.mode = mode
    }

    override fun clear() {
        mode = null
    }
}

private class FakeTokenStore(initial: AccountTokens? = null) : AccountTokenStore {
    var tokens: AccountTokens? = initial
    var failWrites: Boolean = false
    var failClears: Boolean = false

    override fun read(): AccountTokens? = tokens

    override fun write(tokens: AccountTokens): Boolean {
        if (failWrites) return false
        this.tokens = tokens
        return this.tokens == tokens
    }

    override fun clear(): Boolean {
        if (failClears) return false
        tokens = null
        return tokens == null
    }
}

private class FakeAccountRemote : AccountRemoteDataSource {
    var loginHandler: suspend (String, String) -> AccountSession = { _, _ -> unused() }
    var refreshHandler: suspend (String) -> AccountSession = { unused() }
    var logoutHandler: suspend (String) -> Unit = { unused() }
    var getSettingsHandler: suspend (String) -> AiSettings = { unused() }
    var deleteHandler: suspend (String, String) -> Unit = { _, _ -> unused() }
    val refreshTokens = mutableListOf<String>()
    val settingsAccessTokens = mutableListOf<String>()
    val deleteAccessTokens = mutableListOf<String>()

    override suspend fun requestRegistrationCode(email: String): RegistrationCodeRequest = unused()

    override suspend fun requestPasswordResetCode(email: String): RegistrationCodeRequest = unused()

    override suspend fun register(
        email: String,
        password: String,
        verificationRequestId: String,
        verificationCode: String,
    ): AccountUser = unused()

    override suspend fun login(email: String, password: String): AccountSession =
        loginHandler(email, password)

    override suspend fun refresh(refreshToken: String): AccountSession {
        refreshTokens += refreshToken
        return refreshHandler(refreshToken)
    }

    override suspend fun logout(refreshToken: String) = logoutHandler(refreshToken)

    override suspend fun getCurrentUser(accessToken: String): AccountUser = unused()

    override suspend fun getAiSettings(accessToken: String): AiSettings =
        getSettingsHandler(accessToken)

    override suspend fun updateAiSettings(
        accessToken: String,
        mode: AiAccessMode,
    ): AiSettings = unused()

    override suspend fun resetPassword(
        email: String,
        newPassword: String,
        verificationRequestId: String,
        verificationCode: String,
    ): Unit = unused()

    override suspend fun changePassword(
        accessToken: String,
        currentPassword: String,
        newPassword: String,
    ): Unit = unused()

    override suspend fun listSessions(accessToken: String): List<AccountDeviceSession> = unused()

    override suspend fun revokeSession(accessToken: String, sessionId: String): Unit = unused()

    override suspend fun revokeOtherSessions(accessToken: String): Int = unused()

    override suspend fun listPlatformUsage(
        accessToken: String,
        limit: Int,
    ): List<PlatformUsageEntry> = unused()

    override suspend fun deleteAccount(accessToken: String, currentPassword: String) =
        deleteHandler(accessToken, currentPassword)

    private fun <T> unused(): T = error("Unexpected fake remote call")
}

private class FakePlatformModelRemote : PlatformModelRemoteDataSource {
    var handler: suspend (String) -> List<PlatformModel> = { unused() }
    val accessTokens = mutableListOf<String>()

    override suspend fun listModels(accessToken: String): List<PlatformModel> =
        handler(accessToken)

    private fun <T> unused(): T = error("Unexpected fake platform-model call")
}
