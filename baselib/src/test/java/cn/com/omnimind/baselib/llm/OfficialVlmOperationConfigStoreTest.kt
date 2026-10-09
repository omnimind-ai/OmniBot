package cn.com.omnimind.baselib.llm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class OfficialVlmOperationConfigStoreTest {
    @After
    fun clearBundledDefault() {
        OfficialVlmOperationConfigStore.setBundledDefault(null)
    }

    @Test
    fun `bundled default is normalized when no saved config exists`() {
        OfficialVlmOperationConfigStore.setBundledDefault(
            OfficialVlmOperationConfig(
                enabled = true,
                apiBase = " https://omnimind.example/v1/ ",
                model = " gpt-5.6-sol ",
                wireApi = "responses",
            )
        )

        assertEquals(
            OfficialVlmOperationConfig(
                enabled = true,
                apiBase = "https://omnimind.example/v1",
                model = "gpt-5.6-sol",
                wireApi = OpenAiWireApi.RESPONSES,
            ),
            OfficialVlmOperationConfigStore.getConfig(),
        )
    }

    @Test
    fun `official legacy endpoint preserves path and moves to website`() {
        assertEquals(
            "https://omnibot.omnimind.com.cn/v1?source=app",
            OfficialVlmOperationConfigStore.migrateFirstPartyServiceUrl(
                "https://omni.1775885.xyz/v1?source=app"
            ),
        )
    }

    @Test
    fun `custom and lookalike endpoints remain unchanged`() {
        listOf(
            "https://custom.example/v1",
            "https://omni.1775885.xyz.example/v1",
            "https://omni.1775885.xyz:8443/v1",
        ).forEach { value ->
            assertEquals(value, OfficialVlmOperationConfigStore.migrateFirstPartyServiceUrl(value))
        }
    }

    @Test
    fun `legacy persisted upstream keys are detected for removal`() {
        assertEquals(
            true,
            OfficialVlmOperationConfigStore.containsLegacySecretField(
                """{"enabled":true,"apiKey":"must-not-remain"}"""
            )
        )
    }
}
