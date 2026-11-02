package cn.com.omnimind.baselib.llm

import com.google.gson.Gson
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelCapabilitiesTest {
    @Test
    fun deepSeekEnabledEffortsDoNotExcludeTheSeparateDisableCapability() {
        val model = parse("""
            {"effort":{"supported_levels":["low","high","max"],"default_level":"high"}}
        """, "https://api.deepseek.com/v1")

        assertEquals(true, model.reasoning)
        assertEquals(listOf("low", "high", "max"), model.supportedReasoningLevels)
        assertEquals("high", model.defaultReasoningLevel)
        assertEquals(true, model.reasoningCanDisable)
        assertNull(model.reasoningDefaultEnabled)
        assertNull(model.reasoningSupportsAdaptive)
        assertEquals("provider", model.reasoningCapabilitySource)
    }

    @Test
    fun deepSeekAnthropicRouteUsesDeclaredEffortsThroughAdaptiveThinking() {
        for (base in listOf(
            "https://api.deepseek.com/anthropic", "https://api.deepseek.com/anthropic/v1/",
            anthropicEndpointWithPort(443),
        )) {
            val model = parse("""
                {"effort":{"supported_levels":["low","high","max"],"default_level":"high"}}
            """, base)
            assertEquals(base, true, model.reasoning)
            assertEquals(base, listOf("low", "high", "max"), model.supportedReasoningLevels)
            assertEquals(base, true, model.reasoningCanDisable)
            assertEquals(base, true, model.reasoningSupportsAdaptive)
        }
    }

    @Test
    fun deepSeekRouteDefaultsNeverOverrideExplicitFalse() {
        val model = parse("""
            {"effort":{"supported_levels":["high","max"]},
             "reasoningCanDisable":false,"reasoningSupportsAdaptive":false}
        """, "https://api.deepseek.com/anthropic")
        assertEquals(false, model.reasoningCanDisable)
        assertEquals(false, model.reasoningSupportsAdaptive)

        val unknown = parse("""{"reasoning":true}""", "https://api.deepseek.com/anthropic")
        assertNull(unknown.reasoningCanDisable)
        assertNull(unknown.reasoningSupportsAdaptive)
    }

    @Test
    fun deepSeekCompatibleNamesAndNonOfficialRoutesDoNotGainProtocolDefaults() {
        for (base in listOf(
            "https://proxy.invalid/anthropic", "https://api.deepseek.com.proxy.invalid/anthropic",
            "http://api.deepseek.com/anthropic", anthropicEndpointWithPort(8443),
            "https://user@api.deepseek.com/anthropic", "https://api.deepseek.com/anthropic?route=custom",
            "https://api.deepseek.com/anthropic#custom", "https://api.deepseek.com/proxy/anthropic",
            "https://api.deepseek.com/anthropic/v2", "https://api.deepseek.com/v1?route=custom",
        )) {
            val model = parse("""
                {"id":"deepseek-v4-pro","effort":{"supported_levels":["high","max"]}}
            """, base)
            assertNull(base, model.reasoningCanDisable)
            assertNull(base, model.reasoningSupportsAdaptive)
        }
    }

    @Test
    fun deepSeekShapeAtACustomRouteDoesNotInheritOfficialToggleSemantics() {
        val model = parse("""
            {"effort":{"supported_levels":["low","high","max"],"default_level":"high"}}
        """, "https://gateway.invalid/v1")

        assertEquals(true, model.reasoning)
        assertEquals(listOf("low", "high", "max"), model.supportedReasoningLevels)
        assertNull(model.reasoningCanDisable)
    }

    @Test
    fun codexDeclarationsProveReasoningWithoutASeparateBoolean() {
        val model = parse("""
            {"supported_reasoning_levels":[{"effort":"none"},{"effort":"low"},"high"],
             "default_reasoning_level":"none"}
        """)

        assertEquals(true, model.reasoning)
        assertEquals(listOf("none", "low", "high"), model.supportedReasoningLevels)
        assertEquals("none", model.defaultReasoningLevel)
        assertEquals(true, model.reasoningCanDisable)
        assertEquals(false, model.reasoningDefaultEnabled)
    }

    @Test
    fun camelCaseCatalogFieldsPreserveOrderAndNormalizeValues() {
        val model = parse("""
            {"supportedReasoningLevels":["OFF"," HIGH ",{"value":"max"},"high"],
             "defaultReasoningLevel":"HIGH","reasoningDefaultEnabled":false}
        """)

        assertEquals(true, model.reasoning)
        assertEquals(listOf("off", "high", "max"), model.supportedReasoningLevels)
        assertEquals("high", model.defaultReasoningLevel)
        assertEquals(true, model.reasoningCanDisable)
        assertEquals(false, model.reasoningDefaultEnabled)
    }

    @Test
    fun explicitFalseWinsOverEffortsParametersAndCatalogSupport() {
        val model = parse("""
            {"reasoning":false,"effort":{"supported_levels":["low","high"]},
             "supported_parameters":["reasoning","enable_thinking"],
             "reasoningDefaultEnabled":true}
        """, "https://api.deepseek.com")

        assertEquals(false, model.reasoning)
        assertTrue(model.supportedReasoningLevels.isEmpty())
        assertNull(model.defaultReasoningLevel)
        assertNull(model.reasoningCanDisable)
        assertEquals(false, model.reasoningDefaultEnabled)
        assertEquals("provider", model.reasoningCapabilitySource)
    }

    @Test
    fun anthropicUsesEachThinkingTypeDeclarationIndependently() {
        val model = parse("""
            {"capabilities":{
              "thinking":{"supported":true,"types":{
                "enabled":{"supported":false},"adaptive":{"supported":true},
                "disabled":{"supported":false},"between_tools":{"supported":true}}},
              "effort":{"supported":true,"low":{"supported":true},
                "medium":{"supported":false},"high":{"supported":true},
                "xhigh":{"supported":false},"max":{"supported":true}}}}
        """)

        assertEquals(true, model.reasoning)
        assertEquals(listOf("low", "high", "max"), model.supportedReasoningLevels)
        assertEquals(false, model.reasoningCanDisable)
        assertEquals(true, model.reasoningSupportsAdaptive)
        assertNull(model.defaultReasoningLevel)
        assertNull(model.reasoningDefaultEnabled)
    }

    @Test
    fun anthropicThinkingSupportAloneDoesNotProveItCanBeDisabled() {
        val model = parse("""
            {"capabilities":{"thinking":{"supported":true},"effort":{"supported":false}}}
        """)

        assertEquals(true, model.reasoning)
        assertTrue(model.supportedReasoningLevels.isEmpty())
        assertNull(model.reasoningCanDisable)
        assertNull(model.reasoningSupportsAdaptive)
    }

    @Test
    fun anthropicDisabledTypeDoesNotProveThinkingSupport() {
        val model = parse("""
            {"capabilities":{"thinking":{"supported":false,"types":{"disabled":{"supported":true}}},
              "effort":{"supported":true,"high":{"supported":true}}}}
        """)

        assertEquals(false, model.reasoning)
        assertTrue(model.supportedReasoningLevels.isEmpty())
        assertNull(model.reasoningCanDisable)
    }

    @Test
    fun adaptiveSupportRemainsAnIndependentExplicitCapability() {
        val model = parse("""
            {"capabilities":{"thinking":{"supported":true,"types":{
              "enabled":{"supported":true},"adaptive":{"supported":false}}}}}
        """)

        assertEquals(true, model.reasoning)
        assertEquals(false, model.reasoningSupportsAdaptive)
        assertEquals(false, ProviderModelCapabilities.normalize(model).reasoningSupportsAdaptive)
        assertNull(ProviderModelCapabilities.normalize(ProviderModelOption("unknown", reasoning = true))
            .reasoningSupportsAdaptive)
    }

    @Test
    fun explicitWireCapabilitiesArePreservedWithoutGuessingFromModelNames() {
        for (names in listOf(
            listOf("reasoningWireFormat", "reasoningEffortSupported", "reasoningContentRequired"),
            listOf("reasoning_wire_format", "reasoning_effort_supported", "reasoning_content_required"),
        )) {
            val model = parse("""
                {"reasoning":true,"${names[0]}":"deepseek","${names[1]}":false,"${names[2]}":true}
            """)
            assertEquals("deepseek", model.reasoningWireFormat)
            assertEquals(false, model.reasoningEffortSupported)
            assertEquals(true, model.reasoningContentRequired)
            assertEquals(model, ProviderModelCapabilities.normalize(model))
        }
        val unknown = parse("""{"id":"kimi-k3","reasoning":true}""", "https://api.moonshot.cn/v1")
        assertNull(unknown.reasoningWireFormat)
        assertNull(unknown.reasoningEffortSupported)
        assertNull(unknown.reasoningContentRequired)
    }

    @Test
    fun anExplicitReasoningEffortParameterDeclarationDoesNotChooseAThinkingFormat() {
        val model = parse("""{"supported_parameters":["reasoning_effort"]}""")
        assertEquals(true, model.reasoningEffortSupported)
        assertEquals(true, model.reasoning)
        assertNull(model.reasoningWireFormat)
        assertNull(model.reasoningContentRequired)
        val rejected = parse("""
            {"reasoningEffortSupported":false,"supported_parameters":["reasoning_effort"]}
        """)
        assertEquals(false, rejected.reasoningEffortSupported)
    }

    @Test
    fun openRouterMandatoryReasoningCannotAdvertiseAnOffOption() {
        val model = parse("""
            {"reasoning":{"mandatory":true,"supported_efforts":["max","high","none"],
              "default_effort":"high","default_enabled":true},"supported_parameters":["reasoning"]}
        """)

        assertEquals(true, model.reasoning)
        assertEquals(listOf("max", "high"), model.supportedReasoningLevels)
        assertEquals("high", model.defaultReasoningLevel)
        assertEquals(false, model.reasoningCanDisable)
        assertEquals(true, model.reasoningDefaultEnabled)
    }

    @Test
    fun openRouterDefaultEffortAndDefaultEnabledRemainSeparate() {
        val model = parse("""
            {"reasoning":{"mandatory":false,"supported_efforts":["high","low"],
              "default_effort":"high","default_enabled":false}}
        """)

        assertEquals(true, model.reasoning)
        assertEquals("high", model.defaultReasoningLevel)
        assertEquals(true, model.reasoningCanDisable)
        assertEquals(false, model.reasoningDefaultEnabled)
    }

    @Test
    fun parameterSupportDoesNotInventDiscreteEffortsOrDisableSupport() {
        val model = parse("""
            {"supported_parameters":["reasoning","include_reasoning","thinking_budget"]}
        """)

        assertEquals(true, model.reasoning)
        assertTrue(model.supportedReasoningLevels.isEmpty())
        assertNull(model.reasoningCanDisable)
        assertNull(model.defaultReasoningLevel)
    }

    @Test
    fun openRouterNullAllowlistDoesNotInventAnExplicitEffortList() {
        val model = parse("""
            {"reasoning":{"mandatory":false,"supported_efforts":null,"default_enabled":true}}
        """)

        assertEquals(true, model.reasoning)
        assertTrue(model.supportedReasoningLevels.isEmpty())
        assertEquals(true, model.reasoningCanDisable)
    }

    @Test
    fun unknownModelMetadataStaysUnknownEvenOnAnOfficialRoute() {
        val model = parse("""{"id":"deepseek-v4-pro"}""", "https://api.deepseek.com")

        assertNull(model.reasoning)
        assertTrue(model.supportedReasoningLevels.isEmpty())
        assertNull(model.reasoningCanDisable)
        assertNull(model.reasoningDefaultEnabled)
        assertNull(model.reasoningCapabilitySource)
    }

    @Test
    fun invalidDefaultsAndNonStringEffortsAreNotAdvertised() {
        val model = parse("""
            {"supportedReasoningLevels":["low",42,null,{"effort":true},{"effort":"future"}],
             "defaultReasoningLevel":"high"}
        """)

        assertEquals(listOf("low", "future"), model.supportedReasoningLevels)
        assertNull(model.defaultReasoningLevel)
    }

    @Test
    fun oldCachedModelWithoutTheEffortFieldNormalizesWithoutLosingSupport() {
        val model = Gson().fromJson("""
            {"id":"legacy","displayName":"Legacy","inputModalities":[],"outputModalities":[],"reasoning":true}
        """, ProviderModelOption::class.java)

        val normalized = ProviderModelCapabilities.normalize(model)

        assertEquals(true, normalized.reasoning)
        assertTrue(normalized.supportedReasoningLevels.isEmpty())
        assertNull(normalized.reasoningCanDisable)
    }

    private fun anthropicEndpointWithPort(port: Int): String =
        URI("https", null, "api.deepseek.com", port, "/anthropic", null, null).toString()

    private fun parse(metadata: String, apiBase: String? = null): ProviderModelOption =
        ProviderModelCapabilities.applyDeclared(
            model = ProviderModelOption("fixture"),
            metadata = Json.parseToJsonElement(metadata.trimIndent()) as JsonObject,
            apiBase = apiBase,
        )
}
