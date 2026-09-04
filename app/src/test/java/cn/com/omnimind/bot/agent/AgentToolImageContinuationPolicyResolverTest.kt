package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.DeepSeekProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolImageContinuationPolicyResolverTest {
    @Test
    fun unknownProviderKeepsImageContinuationWhenCapabilityIsUnknown() {
        val routeInfo = HttpController.ChatCompletionRouteInfo(
            requestedModel = "scene.dispatch.model",
            resolvedModel = "mimo-v2.5",
            apiBase = "https://relay.example.com/v1",
            providerProfileId = "profile-1",
            providerProfileName = "Provider 1",
            routeTag = "custom_openai_compat",
            bindingApplied = false,
            bindingProfileMissing = false,
            overrideApplied = true,
            protocolType = "openai_compatible"
        )

        val policy = AgentToolImageContinuationPolicyResolver.resolve(routeInfo)

        assertTrue(policy.supportsToolImageContinuation)
    }

    @Test
    fun officialDeepSeekV4FlashDisablesToolImageContinuationBeforeRequest() {
        val routeInfo = HttpController.ChatCompletionRouteInfo(
            requestedModel = "deepseek-v4-flash",
            resolvedModel = "deepseek-v4-flash",
            apiBase = DeepSeekProvider.OFFICIAL_BASE_URL,
            providerProfileId = DeepSeekProvider.OFFICIAL_PROFILE_ID,
            providerProfileName = DeepSeekProvider.OFFICIAL_PROFILE_NAME,
            routeTag = "deepseek_official",
            bindingApplied = true,
            bindingProfileMissing = false,
            overrideApplied = false,
            protocolType = DeepSeekProvider.PROTOCOL_TYPE,
            providerCapabilities = DeepSeekProvider.requestCapabilities(
                protocolType = DeepSeekProvider.PROTOCOL_TYPE,
                apiBase = DeepSeekProvider.OFFICIAL_BASE_URL,
                model = "deepseek-v4-flash",
            )
        )

        val policy = AgentToolImageContinuationPolicyResolver.resolve(routeInfo)

        assertFalse(policy.supportsToolImageContinuation)
    }

    @Test
    fun officialDeepSeekVisionModelKeepsToolImageContinuation() {
        val routeInfo = HttpController.ChatCompletionRouteInfo(
            requestedModel = "deepseek-v4-flash-vision-exp",
            resolvedModel = "deepseek-v4-flash-vision-exp",
            apiBase = DeepSeekProvider.OFFICIAL_BASE_URL,
            providerProfileId = DeepSeekProvider.OFFICIAL_PROFILE_ID,
            providerProfileName = DeepSeekProvider.OFFICIAL_PROFILE_NAME,
            routeTag = "deepseek_official",
            bindingApplied = true,
            bindingProfileMissing = false,
            overrideApplied = false,
            protocolType = DeepSeekProvider.PROTOCOL_TYPE,
            providerCapabilities = DeepSeekProvider.requestCapabilities(
                protocolType = DeepSeekProvider.PROTOCOL_TYPE,
                apiBase = DeepSeekProvider.OFFICIAL_BASE_URL,
                model = "deepseek-v4-flash-vision-exp",
            )
        )

        val policy = AgentToolImageContinuationPolicyResolver.resolve(routeInfo)

        assertTrue(policy.supportsToolImageContinuation)
    }
}
