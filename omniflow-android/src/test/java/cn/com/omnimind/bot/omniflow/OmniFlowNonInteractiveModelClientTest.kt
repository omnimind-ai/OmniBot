package cn.com.omnimind.bot.omniflow

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowNonInteractiveModelClientTest {
    @Test
    fun `non interactive tools retain the model host for offline enhancement`() {
        val source = projectSource(
            "omniflow-android/src/main/java/cn/com/omnimind/bot/omniflow/OmniFlow.kt",
        )
        val branch = source
            .substringAfter("if (!OmniFlowPythonRuntime.toolDefinition(context, toolCall.name).interactive)")
            .substringBefore("val startedAtMs")

        assertTrue(branch.contains("modelClient = modelClient"))
    }

    private fun projectSource(path: String): String {
        var current = File(System.getProperty("user.dir")).absoluteFile
        while (!current.resolve("settings.gradle.kts").isFile) {
            current = current.parentFile ?: error("Could not locate project root")
        }
        return current.resolve(path).readText()
    }
}
