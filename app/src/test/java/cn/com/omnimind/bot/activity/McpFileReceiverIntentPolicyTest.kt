package cn.com.omnimind.bot.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpFileReceiverIntentPolicyTest {
    @Test
    fun supportedShareActionsEnterPayloadHandler() {
        val supportedActions = listOf(
            "android.intent.action.SEND",
            "android.intent.action.SEND_MULTIPLE",
            "android.intent.action.VIEW",
        )

        supportedActions.forEach { action ->
            var payloadHandlerEntered = false
            val accepted = McpFileReceiverIntentPolicy.runIfSupported(action) {
                payloadHandlerEntered = true
            }

            assertTrue(accepted)
            assertTrue(payloadHandlerEntered)
        }
    }

    @Test
    fun explicitComponentAttackWithWrongActionCannotEnterPayloadHandler() {
        val attackActions = listOf(
            null,
            "",
            "android.intent.action.MAIN",
            "com.attacker.action.INJECT_DRAFT",
        )

        attackActions.forEach { action ->
            var payloadHandlerEntered = false
            val accepted = McpFileReceiverIntentPolicy.runIfSupported(action) {
                payloadHandlerEntered = true
            }

            assertFalse(accepted)
            assertFalse(payloadHandlerEntered)
        }
    }
}
