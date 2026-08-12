package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.update.PrivacyConsentDecision
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceScheduleConsentPolicyTest {
    @Test
    fun bootRestoreAndExistingAlarmTriggersAreBothExplicitOptIn() {
        assertFalse(
            WorkspaceScheduleConsentPolicy.allowsAutomaticRun(
                PrivacyConsentDecision.PENDING,
            )
        )
        assertFalse(
            WorkspaceScheduleConsentPolicy.allowsAutomaticRun(
                PrivacyConsentDecision.DECLINED,
            )
        )
        assertTrue(
            WorkspaceScheduleConsentPolicy.allowsAutomaticRun(
                PrivacyConsentDecision.GRANTED,
            )
        )
    }

    @Test
    fun oldAlarmTriggerIntentsAreBlockedUntilCurrentConsentIsGranted() {
        listOf(
            AgentAlarmReceiver.ACTION_AGENT_ALARM_PRE_ALERT_TRIGGER,
            AgentAlarmReceiver.ACTION_AGENT_ALARM_RING_TRIGGER,
        ).forEach { action ->
            assertFalse(
                AgentAlarmReceiverConsentPolicy.shouldHandle(
                    action,
                    PrivacyConsentDecision.PENDING,
                )
            )
            assertFalse(
                AgentAlarmReceiverConsentPolicy.shouldHandle(
                    action,
                    PrivacyConsentDecision.DECLINED,
                )
            )
            assertTrue(
                AgentAlarmReceiverConsentPolicy.shouldHandle(
                    action,
                    PrivacyConsentDecision.GRANTED,
                )
            )
        }
    }

    @Test
    fun userCanStillStopAnAlreadyRingingAlarmAfterConsentChanges() {
        assertTrue(
            AgentAlarmReceiverConsentPolicy.shouldHandle(
                AgentAlarmReceiver.ACTION_AGENT_ALARM_CLOSE,
                PrivacyConsentDecision.DECLINED,
            )
        )
        assertTrue(
            AgentAlarmReceiverConsentPolicy.shouldHandle(
                AgentAlarmReceiver.ACTION_AGENT_ALARM_SNOOZE,
                PrivacyConsentDecision.PENDING,
            )
        )
    }
}
