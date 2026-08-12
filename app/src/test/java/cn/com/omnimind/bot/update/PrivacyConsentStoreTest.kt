package cn.com.omnimind.bot.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyConsentStoreTest {
    @Test
    fun unknownOrMissingValueIsPending() {
        assertEquals(PrivacyConsentDecision.PENDING, PrivacyConsentDecision.fromStored(null))
        assertEquals(PrivacyConsentDecision.PENDING, PrivacyConsentDecision.fromStored("unknown"))
    }

    @Test
    fun persistedDecisionIsParsedCaseInsensitively() {
        assertEquals(PrivacyConsentDecision.GRANTED, PrivacyConsentDecision.fromStored(" GRANTED "))
        assertEquals(PrivacyConsentDecision.DECLINED, PrivacyConsentDecision.fromStored("declined"))
    }

    @Test
    fun firstRunPromptAndTelemetryAreExplicitOptIn() {
        assertTrue(PrivacyConsentPolicy.shouldPrompt(PrivacyConsentDecision.PENDING))
        assertFalse(PrivacyConsentPolicy.shouldPrompt(PrivacyConsentDecision.GRANTED))
        assertFalse(PrivacyConsentPolicy.shouldPrompt(PrivacyConsentDecision.DECLINED))

        assertFalse(PrivacyConsentPolicy.allowsOptionalTelemetry(PrivacyConsentDecision.PENDING))
        assertFalse(PrivacyConsentPolicy.allowsOptionalTelemetry(PrivacyConsentDecision.DECLINED))
        assertTrue(PrivacyConsentPolicy.allowsOptionalTelemetry(PrivacyConsentDecision.GRANTED))
    }

    @Test
    fun automaticNetworkLanAndBackgroundRestoreRequireCurrentExplicitGrant() {
        assertFalse(
            PrivacyConsentPolicy.allowsAutomaticExternalActivity(
                PrivacyConsentDecision.PENDING,
            )
        )
        assertFalse(
            PrivacyConsentPolicy.allowsAutomaticExternalActivity(
                PrivacyConsentDecision.DECLINED,
            )
        )
        assertTrue(
            PrivacyConsentPolicy.allowsAutomaticExternalActivity(
                PrivacyConsentDecision.GRANTED,
            )
        )
    }

    @Test
    fun noticeVersionChangeReturnsToPendingAndDisablesStartupSideEffects() {
        val decision = PrivacyConsentStore.decisionForStoredVersion(
            storedVersion = PrivacyConsentStore.CURRENT_NOTICE_VERSION - 1,
            storedDecision = PrivacyConsentDecision.GRANTED.storedValue,
        )

        assertEquals(PrivacyConsentDecision.PENDING, decision)
        assertFalse(PrivacyConsentPolicy.allowsAutomaticExternalActivity(decision))
    }
}
