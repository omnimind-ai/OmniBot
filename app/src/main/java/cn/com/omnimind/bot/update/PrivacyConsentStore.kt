package cn.com.omnimind.bot.update

import android.content.Context
import androidx.annotation.VisibleForTesting
import java.util.Locale

enum class PrivacyConsentDecision(val storedValue: String) {
    PENDING("pending"),
    GRANTED("granted"),
    DECLINED("declined");

    companion object {
        @VisibleForTesting
        internal fun fromStored(raw: String?): PrivacyConsentDecision {
            val normalized = raw?.trim()?.lowercase(Locale.ROOT)
            return entries.firstOrNull { it.storedValue == normalized } ?: PENDING
        }
    }
}

object PrivacyConsentPolicy {
    fun shouldPrompt(decision: PrivacyConsentDecision): Boolean {
        return decision == PrivacyConsentDecision.PENDING
    }

    fun allowsOptionalTelemetry(decision: PrivacyConsentDecision): Boolean {
        return decision == PrivacyConsentDecision.GRANTED
    }

    /**
     * Covers automatic account/model sync, updater checks, LAN listeners, and
     * background agent schedule restoration. Only the current notice version's
     * explicit opt-in may cause those side effects during process/boot startup.
     */
    fun allowsAutomaticExternalActivity(decision: PrivacyConsentDecision): Boolean {
        return decision == PrivacyConsentDecision.GRANTED
    }
}

object PrivacyConsentStore {
    private const val PREFS_NAME = "privacy_consent"
    private const val KEY_DECISION = "decision"
    private const val KEY_NOTICE_VERSION = "notice_version"
    private const val KEY_DECIDED_AT = "decided_at"
    const val CURRENT_NOTICE_VERSION = 1

    fun getDecision(context: Context): PrivacyConsentDecision {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return decisionForStoredVersion(
            storedVersion = prefs.getInt(KEY_NOTICE_VERSION, 0),
            storedDecision = prefs.getString(KEY_DECISION, null),
        )
    }

    @VisibleForTesting
    internal fun decisionForStoredVersion(
        storedVersion: Int,
        storedDecision: String?,
    ): PrivacyConsentDecision = if (storedVersion == CURRENT_NOTICE_VERSION) {
        PrivacyConsentDecision.fromStored(storedDecision)
    } else {
        PrivacyConsentDecision.PENDING
    }

    fun recordDecision(context: Context, decision: PrivacyConsentDecision) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (decision == PrivacyConsentDecision.PENDING) {
            prefs.edit().clear().apply()
            return
        }
        prefs.edit()
            .putString(KEY_DECISION, decision.storedValue)
            .putInt(KEY_NOTICE_VERSION, CURRENT_NOTICE_VERSION)
            .putLong(KEY_DECIDED_AT, System.currentTimeMillis())
            .apply()
    }

    fun hasOptionalTelemetryConsent(context: Context): Boolean {
        return PrivacyConsentPolicy.allowsOptionalTelemetry(getDecision(context))
    }
}
