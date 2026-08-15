package cn.com.omnimind.bot.omniflow

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowToolChannelManualRecordingTest {
    @Test
    fun manualRecordingWaitsInReadyStateBeforeCapturingTouches() {
        val source = projectSource(
            "app/src/main/java/cn/com/omnimind/bot/omniflow/OmniFlowToolChannel.kt",
        )
        val method = source
            .substringAfter("private fun startHumanTrajectoryLearning(")
            .substringBefore("private fun humanTrajectoryStatusPayload(")
        val pauseIndex = method.indexOf("HumanTrajectoryLearningSession.pauseActive()")
        val overlayIndex = method.indexOf("ManualRecordingControlOverlay.show(")

        assertTrue(pauseIndex >= 0)
        assertTrue(pauseIndex < overlayIndex)
        assertTrue(method.contains("state = ManualRecordingControlOverlay.State.READY"))
    }

    @Test
    fun manualRecordingUsesTheCanonicalFunctionRegistrationPath() {
        val source = projectSource(
            "app/src/main/java/cn/com/omnimind/bot/omniflow/OmniFlowToolChannel.kt",
        )
        val method = source
            .substringAfter("private suspend fun finalizedPayload(")
            .substringBefore("fun clear()")

        assertTrue(method.contains("OmniFlowFunctionRegistration.saveRunLog("))
        assertTrue(method.contains("runId = result.runId"))
        assertTrue(method.contains("agentVisible = true"))
        assertTrue(method.contains("modelClient = if (OmniFlowPluginRuntime.isEnabled())"))
    }

    @Test
    fun manualTextInputDoesNotRequireAPreviouslyFocusedField() {
        val source = projectSource(
            "uikit/src/main/java/cn/com/omnimind/uikit/loader/ManualRecordingControlOverlay.kt",
        )
        val actionDialog = source
            .substringAfter("private fun showManualActionDialog(")
            .substringBefore("private fun showManualInputTextDialog(")

        assertTrue(actionDialog.contains("showManualInputTextDialog(context, inputTarget)"))
        assertTrue(!actionDialog.contains("Tap an input field first"))
        assertTrue(!actionDialog.contains("请先点击输入框"))
    }

    @Test
    fun functionCallsForwardAnExplicitGoalToRuntimeFallback() {
        val source = projectSource(
            "app/src/main/java/cn/com/omnimind/bot/omniflow/OmniFlowToolChannel.kt",
        )
        val method = source
            .substringAfter("if (call.method != METHOD_CALL_TOOL) return false")
            .substringBefore("private fun startHumanTrajectoryLearning(")

        assertTrue(method.contains("val goal = payload?.get(\"goal\")"))
        assertTrue(method.contains("goal = goal.ifBlank { name }"))
    }

    private fun projectSource(path: String): String {
        var current = File(System.getProperty("user.dir")).absoluteFile
        while (!current.resolve("settings.gradle.kts").isFile) {
            current = current.parentFile ?: error("Could not locate project root")
        }
        return current.resolve(path).readText()
    }
}
