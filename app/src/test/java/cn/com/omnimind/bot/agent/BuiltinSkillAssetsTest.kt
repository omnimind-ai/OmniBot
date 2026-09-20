package cn.com.omnimind.bot.agent

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class BuiltinSkillAssetsTest {
    @Test fun `refresh and reinstall retain learned repairs and remove obsolete code`() {
        val root = Files.createTempDirectory("builtin-learning").toFile()
        try {
            val data = root.resolve("data").apply { mkdirs() }
            val errors = data.resolve("ERRORS.md").apply { writeText("GLM failure: pending") }
            val lessons = data.resolve("nested/verified.json").apply {
                parentFile.mkdirs(); writeText("{\"verified\":true}")
            }
            root.resolve("obsolete.sh").writeText("old")
            repeat(3) { version ->
                refreshBuiltinSkillAssets(root) { root.resolve("SKILL.md").writeText("v$version") }
                assertEquals("v$version", root.resolve("SKILL.md").readText())
                assertEquals("GLM failure: pending", errors.readText())
                assertEquals("{\"verified\":true}", lessons.readText())
                assertFalse(root.resolve("obsolete.sh").exists())
            }
            runCatching { refreshBuiltinSkillAssets(root) { error("asset read failed") } }
            assertEquals("GLM failure: pending", errors.readText())
        } finally { root.deleteRecursively() }
    }
}
