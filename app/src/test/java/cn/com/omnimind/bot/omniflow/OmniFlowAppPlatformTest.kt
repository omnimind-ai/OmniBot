package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.assists.controller.http.SceneChatCompletionResponse
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class OmniFlowAppPlatformTest {
    @Test
    fun `alpine python preparation reinstalls only numpy when missing`() {
        val command = buildOmniFlowPythonPrepareCommand("3.12")

        assertTrue(command.contains("command -v python3"))
        assertTrue(command.contains("reason=python_missing"))
        assertTrue(command.contains("reason=python_version_mismatch"))
        assertTrue(command.contains("if ! python3 -c 'import numpy'"))
        assertTrue(command.contains("apk --no-check-certificate add --no-cache py3-numpy"))
        assertTrue(command.contains("OMNIFLOW_PYTHON_STAGE=repair_index_refresh"))
        assertTrue(
            command.indexOf("apk --no-check-certificate add --no-cache py3-numpy") <
                command.indexOf("apk --no-check-certificate update")
        )
        assertTrue(command.contains("python3 -c 'import numpy'"))
        assertTrue(command.contains("OMNIFLOW_PYTHON_STAGE=repair_start package=python-numpy"))
        assertTrue(command.contains("OMNIFLOW_PYTHON_STAGE=probe_ready source=environment"))
        assertTrue(command.contains("/etc/omnibot-python-environment"))
        assertTrue(command.contains("alpine-python3.12-numpy-v1"))
        assertFalse(command.contains("apt-get"))
        assertFalse(command.contains("py3-pip"))
        assertFalse(command.contains("printf '%s\\\\n'"))
        assertFalse(command.contains("command -v uv"))
        assertFalse(command.contains("uv sync"))
        assertTrue(command.trimEnd().endsWith("OMNIFLOW_PYTHON_STAGE=ready'"))
        assertFalse(command.contains("nodejs"))
    }

    @Test
    fun `ubuntu python preparation reinstalls only numpy without apt update`() {
        val command = buildOmniFlowPythonPrepareCommand(
            expectedVersion = "3.12",
            distributionId = "ubuntu",
        )

        assertTrue(command.contains("apt-get install -y --no-install-recommends python3-numpy"))
        assertTrue(command.contains("python3-numpy"))
        assertTrue(command.contains("ubuntu-python3.12-numpy-v1"))
        assertTrue(command.contains("apt-get update"))
        assertTrue(command.contains("OMNIFLOW_PYTHON_STAGE=repair_index_refresh"))
        assertFalse(command.contains("--reinstall"))
        assertFalse(command.contains("python3-pip"))
        assertFalse(command.contains("setup-ubuntu-repository"))
        assertFalse(command.contains("apk --wait"))
    }

    @Test
    fun `python preparation failure keeps actionable package output`() {
        val message = buildOmniFlowPythonFailureMessage(
            error = "proot warning: can't sanitize binding \"/proc/self/fd/0\": No such file or directory",
            output = "ERROR: unable to select packages: py3-numpy",
            rawOutputPreview = "",
        )

        assertTrue(message.contains("unable to select packages: py3-numpy"))
        assertFalse(message.contains("proot warning"))
    }

    @Test
    fun `json completion reads submit json native tool arguments`() {
        val response = SceneChatCompletionResponse(
            success = true,
            code = "200",
            message = "success",
            parser = ModelSceneRegistry.ResponseParser.TEXT_CONTENT,
            toolCalls = listOf(
                AssistantToolCall(
                    id = "call-1",
                    function = AssistantToolCallFunction(
                        name = "submit_json",
                        arguments = """{"parameters":[]}""",
                    ),
                ),
            ),
        )

        assertEquals(
            """{"parameters":[]}""",
            resolveOmniFlowJsonCompletion(response),
        )
    }
}
