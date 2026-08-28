package cn.com.omnimind.bot.vlm

import cn.com.omnimind.baselib.llm.OpenAiWireApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugOmniMindProviderBootstrapTest {
    @Test
    fun `debug plan uses LLMTHU GLM for LLM and VLM`() {
        val plan = DebugOmniMindProviderBootstrap.createLlmThuPlan(
            apiBase = "https://llmapi.paratera.com",
            apiKey = "llmthu-key",
            model = "GLM-5.1",
        )

        assertEquals(DebugOmniMindProviderBootstrap.LLMTHU_PROFILE_ID, plan?.profile?.id)
        assertEquals("LLMTHU GLM-5.1 (Debug)", plan?.profile?.name)
        assertEquals("https://llmapi.paratera.com", plan?.profile?.baseUrl)
        assertEquals("llmthu-key", plan?.profile?.apiKey)
        assertEquals(OpenAiWireApi.CHAT_COMPLETIONS, plan?.profile?.wireApi)
        assertEquals("GLM-5.1", plan?.model)
    }

    @Test
    fun `debug plan requires the embedded LLMTHU key`() {
        assertNull(
            DebugOmniMindProviderBootstrap.createLlmThuPlan(
                apiBase = "https://llmapi.paratera.com",
                apiKey = "",
                model = "GLM-5.1",
            )
        )
    }

    @Test
    fun `device debug build can install explicitly configured provider`() {
        assertTrue(
            DebugOmniMindProviderBootstrap.shouldInstallDebugProvider(enabled = true)
        )
        assertTrue(
            DebugOmniMindProviderBootstrap.shouldInstallDebugProvider(enabled = true)
        )
        assertFalse(
            DebugOmniMindProviderBootstrap.shouldInstallDebugProvider(enabled = false)
        )
    }

    @Test
    fun `legacy debug GLM scene binding migrates to OmniMind`() {
        assertTrue(
            DebugOmniMindProviderBootstrap.shouldReplaceDefaultBinding(
                existingProfileId = DebugOmniMindProviderBootstrap.LEGACY_OMNIMIND_PROFILE_ID,
                existingProfileConfigured = true,
            )
        )
    }

    @Test
    fun `configured custom scene binding remains user owned`() {
        assertFalse(
            DebugOmniMindProviderBootstrap.shouldReplaceDefaultBinding(
                existingProfileId = "user-provider",
                existingProfileConfigured = true,
            )
        )
    }

    @Test
    fun `missing or broken scene binding receives the debug default`() {
        assertTrue(
            DebugOmniMindProviderBootstrap.shouldReplaceDefaultBinding(
                existingProfileId = null,
                existingProfileConfigured = false,
            )
        )
        assertTrue(
            DebugOmniMindProviderBootstrap.shouldReplaceDefaultBinding(
                existingProfileId = "broken-provider",
                existingProfileConfigured = false,
            )
        )
    }

    @Test
    fun `explicit provider remains outside debug managed profile ids`() {
        assertFalse(
            DebugOmniMindProviderBootstrap.shouldReplaceDefaultBinding(
                existingProfileId = "debug-runtime-provider",
                existingProfileConfigured = true,
            )
        )
    }

    @Test
    fun `current debug provider binding remains user selected`() {
        assertFalse(
            DebugOmniMindProviderBootstrap.shouldReplaceDefaultBinding(
                existingProfileId = DebugOmniMindProviderBootstrap.LLMTHU_PROFILE_ID,
                existingProfileConfigured = true,
            )
        )
    }

}
