package cn.com.omnimind.baselib.account

import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.PlatformAiProvisioningStatus
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniAccountAccessTest {
    @Test
    fun blockedAndUnknownPoliciesKeepSignedInProviderVisibleWithoutAuthorizingRequests() {
        for (policy in blockedPolicies()) {
            var tokenAccessChecks = 0
            val repository = repository {
                tokenAccessChecks += 1
                policy
            }

            val access = resolveAccountAiRequestAccess(
                repository, "https://gateway.example/ai/", cloudServiceAccess = policy,
            )

            assertUnavailableProvider(access, policy.message)
            assertEquals(0, tokenAccessChecks)
            assertTrue(repository.isSignedIn())
            assertTrue(runCatching { repository.accessTokenForPlatformGateway() }.exceptionOrNull() is AccountException)
        }
    }

    @Test
    fun policyBlockDuringTokenAccessBecomesUnavailableWithoutClearingTheSession() {
        for (policy in blockedPolicies()) {
            val repository = repository { policy }

            val access = resolveAccountAiRequestAccess(
                repository,
                "https://gateway.example/ai",
                cloudServiceAccess = CloudServiceAccessState.allowedByDefault(),
            )

            assertUnavailableProvider(access, policy.message)
            assertTrue(repository.isSignedIn())
            assertTrue(runCatching { repository.accessTokenForPlatformGateway() }.exceptionOrNull() is AccountException)
        }
    }

    @Test
    fun allowedPolicyKeepsExistingAuthenticatedRouting() {
        val access = resolveAccountAiRequestAccess(
            repository { CloudServiceAccessState.allowedByDefault() },
            "https://gateway.example/ai/",
            cloudServiceAccess = CloudServiceAccessState.allowedByDefault(),
        )

        assertEquals(AiAccessMode.PLATFORM, access.mode)
        assertEquals("https://gateway.example/ai", access.platformGatewayUrl)
        assertEquals("fixture-access", access.bearerToken)
        assertTrue(access.usesPlatform)
        assertNull(access.unavailableReason)
    }

    @Test
    fun signedOutAccountRemainsByokWhileCloudPolicyIsBlocked() {
        val access = resolveAccountAiRequestAccess(
            repository(tokens = null) { error("A signed-out projection must not access a bearer") },
            "https://gateway.example/ai",
            cloudServiceAccess = blockedPolicies().first(),
        )

        assertEquals(AiAccessMode.BYOK, access.mode)
        assertNull(access.bearerToken)
        assertNull(access.platformGatewayUrl)
        assertFalse(access.usesPlatform)
    }

    private fun assertUnavailableProvider(access: AiRequestAccess, reason: String) {
        assertEquals(AiAccessMode.PLATFORM, access.mode)
        assertEquals("https://gateway.example/ai", access.platformGatewayUrl)
        assertEquals(reason, access.unavailableReason)
        assertNull(access.bearerToken)
        assertFalse(access.usesPlatform)
        val profile = requireNotNull(OmniOfficialProvider.profileOrNull(
            PlatformAiProvisioningStatus(ready = true), access,
        ))
        assertEquals("omnibot-official-ai", profile.id)
        assertEquals("https://gateway.example/ai", profile.baseUrl)
        assertEquals(reason, profile.statusText)
        assertFalse(profile.ready)
    }

    private fun blockedPolicies() = listOf(
        CloudServiceAccessState(
            allowed = false, policyKnown = true,
            currentVersion = "0.6.3.4", minimumVersion = "0.6.4",
            message = "upgrade required",
        ),
        CloudServiceAccessState(allowed = false, policyKnown = false, message = "policy unavailable"),
    )

    private fun repository(
        tokens: AccountTokens? = AccountTokens(
            "fixture-access", "2099-01-01T00:00:00Z", "fixture-refresh", "2099-01-02T00:00:00Z",
        ),
        policy: () -> CloudServiceAccessState,
    ): AccountRepository {
        val remote = Proxy.newProxyInstance(
            AccountRemoteDataSource::class.java.classLoader,
            arrayOf(AccountRemoteDataSource::class.java),
        ) { _, _, _ -> error("A passive account projection must not call the network") } as AccountRemoteDataSource
        return AccountRepository(
            remote = remote,
            tokenStore = object : AccountTokenStore {
                override fun read(): AccountTokens? = tokens
                override fun write(tokens: AccountTokens): Boolean = error("Must not change credentials")
                override fun clear(): Boolean = error("Must not clear credentials")
            },
            cloudServiceAccessProvider = policy,
        )
    }
}
