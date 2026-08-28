package cn.com.omnimind.assists.controller.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpControllerSceneRoutingTest {
    @Test
    fun `VLM scene inherits the shared Agent model`() {
        assertEquals(
            "DeepSeek-V4-Pro",
            HttpController.resolveSceneDefaultModel(
                sceneId = "scene.vlm.operation.primary",
                sceneDefaultModel = "qwen3.5-plus",
                sharedAgentModel = "DeepSeek-V4-Pro",
            ),
        )
    }

    @Test
    fun `VLM scene has no implicit model when shared model is absent`() {
        assertNull(
            HttpController.resolveSceneDefaultModel(
                sceneId = "scene.vlm.operation.primary",
                sceneDefaultModel = "qwen3.5-plus",
                sharedAgentModel = null,
            ),
        )
    }

    @Test
    fun `other scenes are not redirected to the shared Agent model`() {
        assertEquals(
            "qwen3.5-plus",
            HttpController.resolveSceneDefaultModel(
                sceneId = "scene.dispatch.model",
                sceneDefaultModel = "qwen3.5-plus",
                sharedAgentModel = "DeepSeek-V4-Pro",
            ),
        )
        assertNull(
            HttpController.resolveSceneDefaultModel(
                sceneId = "scene.dispatch.model",
                sceneDefaultModel = " ",
                sharedAgentModel = "DeepSeek-V4-Pro",
            ),
        )
    }
}
