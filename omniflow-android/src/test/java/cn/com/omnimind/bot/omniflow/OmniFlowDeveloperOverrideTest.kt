package cn.com.omnimind.bot.omniflow

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowDeveloperOverrideTest {
    @Test
    fun `paths remain inside the omniflow Python package`() {
        assertEquals("omniflow/vlm/planner.py", normalizedPythonPath("vlm/planner.py"))
        assertEquals("omniflow/runtime/engine.py", normalizedPythonPath("./omniflow/runtime/engine.py"))
        assertThrows(IllegalArgumentException::class.java) {
            normalizedPythonPath("../omnitransfer/runtime.py")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizedPythonPath("omniflow/vlm/prompt.txt")
        }
    }

    @Test
    fun `first edit copies full package and clear restores pinned mode`() {
        val temporary = Files.createTempDirectory("omniflow-override-test").toFile()
        val base = temporary.resolve("base/python")
        base.resolve("omniflow/vlm/planner.py").apply {
            parentFile.mkdirs()
            writeText("VALUE = 'stable'\n")
        }
        base.resolve("omniflow/runtime/engine.py").apply {
            parentFile.mkdirs()
            writeText("ENGINE = 'stable'\n")
        }
        base.resolve("schemas/oob/oob_canonical_actions.v1.json").apply {
            parentFile.mkdirs()
            writeText("{\"tools\":[]}")
        }
        val store = OmniFlowDeveloperOverrideStore(temporary.resolve("override"))

        store.apply(base, "runtime-v1", "vlm/planner.py", "VALUE = 'edited'\n")

        assertEquals("VALUE = 'edited'\n", store.read("vlm/planner.py"))
        assertEquals("ENGINE = 'stable'\n", store.read("runtime/engine.py"))
        assertEquals(
            "{\"tools\":[]}",
            temporary.resolve("override/python/schemas/oob/oob_canonical_actions.v1.json")
                .readText(),
        )
        assertEquals(listOf("omniflow/vlm/planner.py"), store.status("runtime-v1").modifiedFiles)
        assertTrue(store.clear())
        assertFalse(store.status("runtime-v1").enabled)
    }

    @Test
    fun `runtime rebase preserves only explicitly modified files`() {
        val temporary = Files.createTempDirectory("omniflow-rebase-test").toFile()
        val baseV1 = temporary.resolve("base-v1/python")
        val baseV2 = temporary.resolve("base-v2/python")
        for ((base, planner, engine) in listOf(
            Triple(baseV1, "PLANNER = 1\n", "ENGINE = 1\n"),
            Triple(baseV2, "PLANNER = 2\n", "ENGINE = 2\n"),
        )) {
            base.resolve("omniflow/vlm/planner.py").apply {
                parentFile.mkdirs()
                writeText(planner)
            }
            base.resolve("omniflow/runtime/engine.py").apply {
                parentFile.mkdirs()
                writeText(engine)
            }
        }
        val store = OmniFlowDeveloperOverrideStore(temporary.resolve("override"))
        store.apply(baseV1, "runtime-v1", "vlm/planner.py", "PLANNER = 99\n")

        store.rebaseIfPresent(baseV2, "runtime-v2")

        assertEquals("PLANNER = 99\n", store.read("vlm/planner.py"))
        assertEquals("ENGINE = 2\n", store.read("runtime/engine.py"))
        assertEquals(
            listOf("omniflow/vlm/planner.py"),
            store.status("runtime-v2").modifiedFiles,
        )
    }

    @Test
    fun `failed first edit can restore stable content without marking modification`() {
        val temporary = Files.createTempDirectory("omniflow-rollback-test").toFile()
        val base = temporary.resolve("base/python")
        base.resolve("omniflow/vlm/planner.py").apply {
            parentFile.mkdirs()
            writeText("VALUE = 'stable'\n")
        }
        val store = OmniFlowDeveloperOverrideStore(temporary.resolve("override"))
        val stable = base.resolve("omniflow/vlm/planner.py").readText()
        store.apply(base, "runtime-v1", "vlm/planner.py", "not valid Python")

        store.restore("vlm/planner.py", stable, keepModified = false)

        assertEquals(stable, store.read("vlm/planner.py"))
        assertTrue(store.status("runtime-v1").modifiedFiles.isEmpty())
        assertFalse(store.status("runtime-v1").enabled)
    }
}
