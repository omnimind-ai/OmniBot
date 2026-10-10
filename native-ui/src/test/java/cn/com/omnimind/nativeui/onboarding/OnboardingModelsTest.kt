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
}
