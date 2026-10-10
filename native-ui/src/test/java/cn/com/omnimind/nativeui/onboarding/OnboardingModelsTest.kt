package cn.com.omnimind.nativeui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingModelsTest {
    @Test fun flowPushesHistoryAndMarksOnlyFooterPagesVisited() {
        val flow = OnboardingFlow().goTo(OnboardingPage.Development).goTo(OnboardingPage.Tools)
            .goTo(OnboardingPage.EnvironmentProgress)
        assertEquals(OnboardingPage.EnvironmentProgress, flow.page)
        assertEquals(listOf(OnboardingPage.System, OnboardingPage.Development, OnboardingPage.Tools), flow.history)
        assertFalse(OnboardingPage.EnvironmentProgress in flow.visited)
        assertFalse(flow.onNavigationPage)
        assertSame(flow, flow.goTo(OnboardingPage.EnvironmentProgress))
    }

    @Test fun backPopsAndStopsAtTheStart() {
        val flow = OnboardingFlow().goTo(OnboardingPage.Development)
        val back = flow.goBack()!!
        assertEquals(OnboardingPage.System, back.page)
        assertEquals(-1, back.direction)
        assertNull(back.goBack())
    }

    @Test fun jumpingBackToAVisitedPageTrimsTheHistory() {
        val flow = OnboardingFlow().goTo(OnboardingPage.Development).goTo(OnboardingPage.Tools)
            .goTo(OnboardingPage.Permissions)
        val jumped = flow.jumpToVisited(OnboardingPage.Development)
        assertEquals(OnboardingPage.Development, jumped.page)
        assertEquals(listOf(OnboardingPage.System), jumped.history)
        assertEquals(-1, jumped.direction)
        // Unvisited pages cannot be jumped to.
        assertSame(flow, flow.jumpToVisited(OnboardingPage.MemoryScenes))
    }

    @Test fun packagesComeFromThePresetThenTheTools() {
        assertEquals(listOf("python", "pip", "uv", "git", "codex"), onboardingPackageIds("python", setOf("codex", "git")))
        assertEquals(ENVIRONMENT_PRESETS.first().packageIds, onboardingPackageIds("unknown", emptySet()))
    }

    @Test fun sceneDefaultsPreferBoundThenGeneralAndEmbeddingModels() {
        val models = listOf("text-embedding-3", "deepseek-chat", "bge-m3")
        val picks = defaultSceneSelections(models, bindings = mapOf("scene.memory.rollup" to "bge-m3", "scene.dispatch.model" to "gone"))
        assertEquals("deepseek-chat", picks["scene.dispatch.model"])
        assertEquals("deepseek-chat", picks["scene.compactor.context.chat"])
        assertEquals("text-embedding-3", picks["scene.memory.embedding"])
        assertEquals("bge-m3", picks["scene.memory.rollup"])
        assertTrue(defaultSceneSelections(emptyList()).isEmpty())
        // Only embedding-looking models: they also serve the text scenes.
        assertEquals("bge-m3", defaultSceneSelections(listOf("bge-m3"))["scene.dispatch.model"])
    }

    @Test fun phaseFollowsTheFurtherOfStageTextAndProgress() {
        assertEquals(0, environmentPhase("正在保存你的选择…", 0.02f, ready = false))
        assertEquals(1, environmentPhase("正在准备 Alpine 系统…", 0.05f, ready = false))
        assertEquals(2, environmentPhase("正在准备 Alpine 系统…", 0.6f, ready = false))
        assertEquals(3, environmentPhase("正在验证安装结果", 0.2f, ready = false))
        assertEquals(4, environmentPhase("", 0f, ready = true))
    }

    @Test fun progressNeverGoesBackwardsOrCompletesByItself() {
        var progress = 0.02f
        repeat(2_000) {
            val next = nextEnvironmentProgress(progress, nativeProgress = 0.3f, phase = 1)
            assertTrue(next >= progress)
            progress = next
        }
        assertTrue(progress <= 0.50f)
        assertTrue(progress >= 0.30f)
        assertEquals(0.99f, (1..5_000).fold(0.95f) { p, _ -> nextEnvironmentProgress(p, 0.95f, 4) }, 0.0001f)
    }

    @Test fun providerFormRequiresAKeyExceptForCompatibleApis() {
        val deepseek = PROVIDER_OPTIONS.first()
        val custom = PROVIDER_OPTIONS.last()
        val valid: (String) -> Boolean = { it.startsWith("http") }
        assertEquals(ProviderFormError.MissingName, validateProviderForm(deepseek, " ", "https://a", "k", valid))
        assertEquals(ProviderFormError.InvalidBaseUrl, validateProviderForm(deepseek, "n", "ftp://a", "k", valid))
        assertEquals(ProviderFormError.MissingApiKey, validateProviderForm(deepseek, "n", "https://a", "", valid))
        assertNull(validateProviderForm(custom, "n", "http://192.168.1.10:8000/v1", "", valid))
    }

    @Test fun nextFollowsThePageAndWaitsForInput() {
        assertEquals(OnboardingPage.Permissions, onboardingNextPage(OnboardingPage.Tools, connected = false, hasModels = false))
        assertNull(onboardingNextPage(OnboardingPage.ProviderConnection, connected = false, hasModels = false))
        assertNull(onboardingNextPage(OnboardingPage.ModelInventory, connected = true, hasModels = false))
        assertEquals(OnboardingPage.PrimaryScenes, onboardingNextPage(OnboardingPage.ModelInventory, connected = true, hasModels = true))
        assertNull(onboardingNextPage(OnboardingPage.MemoryScenes, connected = true, hasModels = true))
    }

    /** Fix (5f-1b): Dart always skipped from Provider, even with a Provider already connected. */
    @Test fun providerContinuesToTheModelsOnceConnected() {
        assertEquals(OnboardingPage.Completion, onboardingNextPage(OnboardingPage.Provider, connected = false, hasModels = false))
        assertEquals(OnboardingPage.ModelInventory, onboardingNextPage(OnboardingPage.Provider, connected = true, hasModels = false))
    }

    private fun profile(id: String, key: String = "", source: String = "custom", builtIn: Boolean = false, base: String = "https://x/v1") =
        OnboardingProfile(id, id, base, key, source, builtIn)

    /**
     * Fix (5f-1b): every install seeds keyless built-in profiles, and Dart
     * resumed with the first one that had a Base URL, so a fresh install
     * showed the Provider page "connected" to DeepSeek without a key.
     */
    @Test fun keylessBuiltInProfilesAreNotResumed() {
        val seeded = listOf(profile("deepseek-official", source = "deepseek", builtIn = true),
            profile("moonshot-official", source = "moonshot", builtIn = true))
        assertNull(resumableOnboardingProfile(seeded, editingId = "deepseek-official"))
        val keyed = seeded + profile("moonshot-official-2", key = "sk", source = "moonshot")
        assertEquals("moonshot-official-2", resumableOnboardingProfile(keyed, editingId = null)?.id)
        // A compatible endpoint of the user's own needs no key; the editing one wins.
        val local = keyed + profile("lan", base = "http://192.168.1.10:8000/v1")
        assertEquals("lan", resumableOnboardingProfile(local, editingId = "lan")?.id)
        assertNull(resumableOnboardingProfile(listOf(profile("blank", key = "k", base = "")), null))
    }

    @Test fun storedProfilesMapBackToTheirOption() {
        val same: (String, String) -> Boolean = { a, b -> a.trimEnd('/') == b.trimEnd('/') }
        assertEquals("moonshot", providerOptionFor(profile("p", source = "moonshot"), same).id)
        assertEquals("openai", providerOptionFor(profile("p", base = "https://api.openai.com/v1/"), same).id)
        assertEquals("custom", providerOptionFor(profile("p", base = "http://lan/v1"), same).id)
    }

    @Test fun connectingOverwritesTheMatchingProfile() {
        val same: (String, String) -> Boolean = { a, b -> a == b }
        val profiles = listOf(profile("ds", source = "deepseek"), profile("lan", base = "http://lan/v1"))
        val deepseek = PROVIDER_OPTIONS.first { it.id == "deepseek" }
        val custom = PROVIDER_OPTIONS.last()
        assertEquals("ds", profileToOverwrite(deepseek, deepseek.baseUrl, profiles, same)?.id)
        assertEquals("lan", profileToOverwrite(custom, "http://lan/v1", profiles, same)?.id)
        assertNull(profileToOverwrite(custom, "http://other/v1", profiles, same))
    }

    @Test fun flowSurvivesEncodingAndBadValuesStartOver() {
        val flow = OnboardingFlow().goTo(OnboardingPage.Development).goTo(OnboardingPage.EnvironmentProgress)
        assertEquals(flow.copy(direction = 1), decodeOnboardingFlow(flow.encode()))
        assertEquals(OnboardingFlow(), decodeOnboardingFlow("Gone||System"))
        assertEquals(OnboardingFlow(), decodeOnboardingFlow(null))
    }

    @Test fun stagesTranslateForEnglishOnly() {
        assertEquals("Checking runtime resources", localizedEnvironmentStage("正在校验终端环境运行资源…", english = true))
        assertEquals("正在校验终端环境运行资源", localizedEnvironmentStage("正在校验终端环境运行资源", english = false))
        assertEquals("custom error", localizedEnvironmentStage("custom error", english = true))
        assertNull(localizedEnvironmentStage("基础 Agent CLI 包尚未完成预装", english = false))
    }

    /** Fix (5f-1b): adding a model reset hand-made picks in Dart, or would have left a new embedding model unused. */
    @Test fun addingAModelKeepsHandPicksAndAdoptsANewEmbedding() {
        val before = defaultSceneSelections(listOf("qwen3-32b", "glm-4"))
        val current = before + ("scene.compactor.context.chat" to "glm-4")
        val after = rebalancedSceneSelections(listOf("qwen3-32b", "glm-4", "bge-m3"), current, picked = setOf("scene.compactor.context.chat"))
        assertEquals("glm-4", after["scene.compactor.context.chat"])
        assertEquals("bge-m3", after["scene.memory.embedding"])
        assertEquals("qwen3-32b", after["scene.dispatch.model"])
    }
}
