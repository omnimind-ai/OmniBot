package cn.com.omnimind.baselib.llm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderReasoningCatalogTest {
    private val catalog = ProviderReasoningCatalog.parse(
        File("src/main/assets/${ProviderReasoningCatalog.ASSET_PATH}").readText(),
    )

    private fun profile(baseUrl: String, responses: Boolean = false) = ModelProviderProfile(
        id = "user-configured", name = "An arbitrary display name", baseUrl = baseUrl,
        wireApi = if (responses) OpenAiWireApi.RESPONSES else OpenAiWireApi.CHAT_COMPLETIONS,
    )

    @Test
    fun exactOfficialResponsesRouteSupplementsOnlyMissingReasoningMetadata() {
        for (base in listOf(
            "https://api.openai.com", "https://api.openai.com/v1/",
            "https://api.openai.com:443/v1/responses#",
        )) {
            val original = ProviderModelOption("gpt-5.4", displayName = "My model", contextLimit = 1234)
            val model = catalog.enrich(profile(base, responses = true), original)
            assertEquals(true, model.reasoning)
            assertEquals(listOf("none", "low", "medium", "high", "xhigh"), model.supportedReasoningLevels)
            assertEquals(true, model.reasoningCanDisable)
            assertNull(model.defaultReasoningLevel)
            assertNull(model.reasoningDefaultEnabled)
            assertEquals("bundled_catalog", model.reasoningCapabilitySource)
            assertEquals(original.id, model.id)
            assertEquals(original.displayName, model.displayName)
            assertEquals(original.contextLimit, model.contextLimit)
        }
    }

    @Test
    fun modelNamesCannotGrantCapabilitiesToCustomOrDifferentRoutes() {
        val original = ProviderModelOption("gpt-5.4")
        for (base in listOf(
            "https://proxy.example/v1", "https://api.openai.com.proxy.example/v1",
            "https://api.openai.com:8443/v1", "http://api.openai.com/v1",
            "https://user@api.openai.com/v1", "https://api.openai.com/v1?route=custom",
            "https://api.openai.com/proxy/v1", "https://api.openai.com/v1/chat/completions",
            "https://api.openai.com/v1#", "https://api.openai.com/v1#custom",
        )) {
            assertSame(base, original, catalog.enrich(profile(base, responses = true), original))
        }
        val responsesOnly = ProviderModelOption("gpt-5.4-pro")
        assertSame(responsesOnly, catalog.enrich(profile("https://api.openai.com/v1"), responsesOnly))
        for (id in listOf("openai/gpt-5.4", "gpt-5.4:free", "gpt-5.4-custom", "GPT-5.4")) {
            val unknown = ProviderModelOption(id)
            assertSame(unknown, catalog.enrich(profile("https://api.openai.com/v1", responses = true), unknown))
        }
    }

    @Test
    fun reviewedChatCompletionsAliasesDoNotChangeTheSelectedRouteOrDeclareTools() {
        val selectedRoute = profile("https://api.openai.com/v1/chat/completions#")
        for (id in listOf("gpt-5.4", "gpt-6.1-sol")) {
            val model = catalog.enrich(selectedRoute, ProviderModelOption(id))
            assertEquals(true, model.reasoning)
            assertTrue("high" in model.supportedReasoningLevels)
            assertNull(model.toolCall)
            assertNull(model.modelsDevProviderId)
            assertEquals(id, model.id)
        }
        assertEquals(OpenAiWireApi.CHAT_COMPLETIONS, selectedRoute.wireApi)
        assertEquals("https://api.openai.com/v1/chat/completions#", selectedRoute.baseUrl)
    }

    @Test
    fun serverDeclarationsIncludingFalseRemainAuthoritative() {
        val route = profile("https://api.openai.com/v1", responses = true)
        val disabled = ProviderModelOption("gpt-5.4", reasoning = false, reasoningCapabilitySource = "provider")
        assertSame(disabled, catalog.enrich(route, disabled))
        val declared = ProviderModelOption(
            "gpt-5.4", reasoning = true, supportedReasoningLevels = listOf("high"),
            defaultReasoningLevel = "high", reasoningCanDisable = false,
            reasoningDefaultEnabled = true, reasoningCapabilitySource = "provider",
        )
        assertEquals(declared, catalog.enrich(route, declared))
    }

    @Test
    fun aReasoningFlagWithoutAMapDoesNotDeclareEffortLevelsOrDefaults() {
        val model = catalog.enrich(profile("https://api.moonshot.cn/v1"), ProviderModelOption("kimi-k2.6"))
        assertEquals(true, model.reasoning)
        assertTrue(model.supportedReasoningLevels.isEmpty())
        assertNull(model.reasoningCanDisable)
        assertNull(model.defaultReasoningLevel)
        assertNull(model.reasoningDefaultEnabled)
        assertEquals("deepseek", model.reasoningWireFormat)
        assertEquals(false, model.reasoningEffortSupported)
        val declared = ProviderModelOption(
            "kimi-k2.6", reasoning = true, reasoningWireFormat = "openai",
            reasoningEffortSupported = true, reasoningCapabilitySource = "provider",
        )
        assertEquals(declared, catalog.enrich(profile("https://api.moonshot.cn/v1"), declared))
        val k3 = ProviderModelOption("kimi-k3")
        assertEquals(true, catalog.enrich(profile("https://api.moonshot.cn/v1"), k3).reasoningContentRequired)
        assertEquals(false, catalog.enrich(
            profile("https://api.moonshot.cn/v1"), k3.copy(reasoningContentRequired = false),
        ).reasoningContentRequired)
        val mandatory = catalog.enrich(profile("https://api.moonshot.ai/v1"), ProviderModelOption("kimi-k2.7-code"))
        assertEquals(true, mandatory.reasoning)
        assertEquals(false, mandatory.reasoningCanDisable)
        assertTrue(mandatory.supportedReasoningLevels.isEmpty())
    }

    @Test
    fun onlyTheExplicitOauthProviderUsesSubscriptionCatalogDeclarations() {
        val raw = ProviderModelOption("gpt-5.6-sol")
        val apiKeyProfile = profile(ChatGptOAuthProvider.BASE_URL, responses = true)
        val subscription = apiKeyProfile.copy(sourceType = ChatGptOAuthProvider.SOURCE_TYPE)
        val apiKeyModel = catalog.enrich(apiKeyProfile, raw)
        val subscriptionModel = catalog.enrich(subscription, raw)
        assertEquals(listOf("none", "low", "medium", "high", "xhigh", "max"), apiKeyModel.supportedReasoningLevels)
        assertEquals(listOf("low", "xhigh", "max"), subscriptionModel.supportedReasoningLevels)
        assertSame(raw, catalog.enrich(subscription.copy(baseUrl = "https://proxy.example/v1"), raw))
        assertSame(raw, catalog.enrich(profile("https://chatgpt.com/backend-api", responses = true), raw))
    }

    @Test
    fun regionalAndPlanPathsMustMatchTheirOwnCatalogExactly() {
        val zhipu = catalog.enrich(profile("https://open.bigmodel.cn/api/coding/paas/v4"), ProviderModelOption("glm-5.3"))
        assertEquals(listOf("low", "high", "max"), zhipu.supportedReasoningLevels)
        assertEquals(false, zhipu.reasoningCanDisable)
        assertEquals("zai", zhipu.reasoningWireFormat)
        assertEquals(true, zhipu.reasoningEffortSupported)
        val raw = ProviderModelOption("glm-5.3")
        assertSame(raw, catalog.enrich(profile("https://open.bigmodel.cn/api/paas/v4"), raw))
        val qwen = catalog.enrich(
            profile("https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"),
            ProviderModelOption("deepseek-v4-pro"),
        )
        assertEquals(listOf("high", "max"), qwen.supportedReasoningLevels)
        assertEquals(false, qwen.reasoningCanDisable)
        assertEquals("qwen", qwen.reasoningWireFormat)
        assertEquals(true, qwen.reasoningEffortSupported)
        val deepseek = catalog.enrich(profile("https://api.deepseek.com/v1"), ProviderModelOption("deepseek-v4-pro"))
        assertEquals(listOf("high", "max"), deepseek.supportedReasoningLevels)
        assertNull(deepseek.reasoningCanDisable)
    }

    @Test
    fun anthropicRequiresItsProtocolAndDoesNotInheritUndeclaredDefaults() {
        val model = ProviderModelOption("claude-haiku-4-5")
        val route = profile("https://api.anthropic.com/v1/messages#")
        assertSame(model, catalog.enrich(route, model))
        val supported = catalog.enrich(route.copy(protocolType = "anthropic"), model)
        assertEquals(true, supported.reasoning)
        assertTrue(supported.supportedReasoningLevels.isEmpty())
        assertNull(supported.reasoningCanDisable)
        assertNull(supported.defaultReasoningLevel)
        assertNull(supported.reasoningSupportsAdaptive)
        val adaptiveModel = ProviderModelOption("claude-fable-5")
        val anthropic = route.copy(protocolType = "anthropic")
        assertEquals(true, catalog.enrich(anthropic, adaptiveModel).reasoningSupportsAdaptive)
        assertEquals(false, catalog.enrich(
            anthropic, adaptiveModel.copy(reasoningSupportsAdaptive = false),
        ).reasoningSupportsAdaptive)
    }

    @Test
    fun additionalOfficialProvidersUseOnlyTheirPinnedProtocolAndExactRoute() {
        val xiaomi = catalog.enrich(profile("https://api.xiaomimimo.com/v1"), ProviderModelOption("mimo-v2.6-pro"))
        assertEquals(true, xiaomi.reasoning)
        assertEquals("deepseek", xiaomi.reasoningWireFormat)
        assertEquals(false, xiaomi.reasoningEffortSupported)
        assertEquals(listOf("off", "high"), xiaomi.supportedReasoningLevels)
        assertEquals(true, xiaomi.reasoningContentRequired)
        for (base in listOf("https://api.minimax.io/anthropic", "https://api.minimaxi.com/anthropic/v1/messages#")) {
            val route = profile(base)
            val raw = ProviderModelOption("MiniMax-M2.7")
            assertSame(raw, catalog.enrich(route, raw))
            val minimax = catalog.enrich(route.copy(protocolType = "anthropic"), raw)
            assertEquals(true, minimax.reasoning)
            assertTrue(minimax.supportedReasoningLevels.isEmpty())
        }
        val xai = catalog.enrich(profile("https://api.x.ai/v1", responses = true), ProviderModelOption("grok-4.3"))
        assertEquals(true, xai.reasoning)
        assertEquals(listOf("none", "low", "medium", "high", "xhigh"), xai.supportedReasoningLevels)
        assertNull(xai.reasoningWireFormat)
        assertNull(xai.reasoningEffortSupported)
    }
}
