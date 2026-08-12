package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.update.PrivacyConsentDecision
import cn.com.omnimind.bot.update.PrivacyConsentPolicy

/** One shared fail-closed gate for boot restoration and already-scheduled receiver triggers. */
internal object WorkspaceScheduleConsentPolicy {
    fun allowsAutomaticRun(decision: PrivacyConsentDecision): Boolean =
        PrivacyConsentPolicy.allowsAutomaticExternalActivity(decision)
}
