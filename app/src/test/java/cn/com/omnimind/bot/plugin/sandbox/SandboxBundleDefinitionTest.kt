package cn.com.omnimind.bot.plugin.sandbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SandboxBundleDefinitionTest {
    @Test
    fun `bundle maps declared tools to generic check and publish executors`() {
        val bundle = SandboxBundleDefinition.parse(
            """
                {
                  "schemaVersion": 1,
                  "tools": [
                    {
                      "name": "project_check",
                      "displayName": "Check Project",
                      "description": "Check one project directory.",
                      "executor": "plugin.pool.check",
                      "parameters": {"type": "object", "properties": {}}
                    },
                    {
                      "name": "project_publish",
                      "displayName": "Publish Project",
                      "description": "Publish one project directory.",
                      "executor": "plugin.pool.publish",
                      "parameters": {"type": "object", "properties": {}}
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(
            listOf("project_check", "project_publish"),
            bundle.tools.map { it.definition().name },
        )
        assertEquals(
            listOf(
                SandboxBundleTool.CHECK_PROJECT_EXECUTOR,
                SandboxBundleTool.PUBLISH_PROJECT_EXECUTOR,
            ),
            bundle.tools.map(SandboxBundleTool::executor),
        )
    }

    @Test
    fun `packaged bundle exposes inline manifest schemas supported by tool validation`() {
        val bundle = SandboxBundleDefinition.parse(
            projectSource(
                "plugins/vibe-project/runtime-skill/vibe-project-builder/bundle.json",
            ),
        )

        assertEquals(
            listOf("project_contract", "project_check", "project_publish"),
            bundle.tools.map { it.name },
        )
        bundle.tools.forEach { tool ->
            if (tool.name == "project_contract") return@forEach
            val properties = tool.parameters["properties"] as JsonObject
            val manifest = properties["manifest"] as JsonObject
            assertEquals("object", manifest["type"]?.jsonPrimitive?.content)
            assertFalse("\$ref" in manifest)
            val required = manifest["required"].toString()
            assertTrue(required.contains("entry_path"))
            assertTrue(required.contains("icon_path"))
        }
        val contract = bundle.tools.single { it.name == "project_contract" }
        assertTrue(contract.description.contains("connectors[].id"))
        assertTrue(contract.description.contains("sqlite"))
        assertTrue(contract.description.contains("xiaowan"))
        assertTrue(contract.description.contains("window.omni.tools.call"))
        val check = bundle.tools.single { it.name == "project_check" }
        assertTrue(check.description.contains("direct window.omni.db calls are rejected"))
        val publish = bundle.tools.single { it.name == "project_publish" }
        assertTrue(publish.description.contains("publication re-runs this check"))
    }

    @Test
    fun `packaged vibe skill uses workspace direct routing and upgraded marker`() {
        val skill = projectSource(
            "plugins/vibe-project/runtime-skill/vibe-project-builder/SKILL.md",
        )
        val marker = projectSource(
            "plugins/vibe-project/runtime-skill/vibe-project-builder/PACKAGED_RUNTIME_SKILL",
        ).trim()

        assertTrue(skill.contains("tool-routing: workspace-direct"))
        assertTrue(skill.contains("\"做*工具\""))
        assertTrue(skill.contains("Data / Tool / Display consistency"))
        assertTrue(skill.contains("references/workflow-validation.md"))
        assertTrue(skill.contains("Start production state empty"))
        assertTrue(skill.contains("Never call an image-generation model"))
        assertTrue(skill.contains("icon.svg"))
        assertTrue(skill.contains("window.omni.tools.call"))
        assertFalse(skill.contains("window.omni.db"))
        val workflowValidation = projectSource(
            "plugins/vibe-project/runtime-skill/vibe-project-builder/references/workflow-validation.md",
        )
        assertTrue(workflowValidation.contains("AI event lifecycle"))
        assertTrue(workflowValidation.contains("Capability-source alignment"))
        assertTrue(workflowValidation.contains("No-Mock Audit"))
        assertEquals("vibe-project-builder-contract-v12", marker)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bundle rejects executor code not owned by the host`() {
        SandboxBundleDefinition.parse(
            """
                {
                  "schemaVersion": 1,
                  "tools": [
                    {
                      "name": "unsafe_execute",
                      "displayName": "Unsafe",
                      "description": "Must be rejected.",
                      "executor": "shell.exec",
                      "parameters": {}
                    }
                  ]
                }
            """.trimIndent(),
        )
    }

    private fun projectSource(path: String): String {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!current.resolve("settings.gradle.kts").isFile) {
            current = current.parentFile ?: error("Could not locate project root")
        }
        return current.resolve(path).readText()
    }
}
