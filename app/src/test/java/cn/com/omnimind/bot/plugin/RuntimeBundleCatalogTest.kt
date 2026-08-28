package cn.com.omnimind.bot.plugin

import cn.com.omnimind.bot.plugin.runtime.RuntimeBundleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBundleCatalogTest {
    @Test
    fun `remote catalog updates runtime while retaining the packaged baseline`() {
        val local = RuntimeBundleCatalog.parse(catalog("2.1.7", "old"))
        val remote = RuntimeBundleCatalog.parse(catalog("2.1.8", "new"))

        val merged = local.mergeRemoteForTest(remote).bundles.single()

        assertEquals("2.1.8", merged.descriptor.version)
        assertEquals("https://example.com/new.zip", merged.runtimeSkill.componentArchiveUrl)
        assertEquals("b".repeat(64), merged.runtimeSkill.componentArchiveSha256)
        assertEquals("runtime-components/baseline.zip", merged.runtimeSkill.packagedArchivePath)
        assertEquals("a".repeat(64), merged.runtimeSkill.packagedArchiveSha256)
        assertTrue(merged.descriptor.required)
        assertTrue(merged.descriptor.installByDefault)
    }

    private fun catalog(version: String, archiveLabel: String): String = """
        {
          "schemaVersion": 1,
          "plugins": [{
            "id": "com.omnimind.omni-vlm-lite",
            "name": "OmniFlow",
            "version": "$version",
            "interfaceVersion": 1,
            "description": "GUI runtime",
            "publisher": "OmniMind",
            "kind": "runtime_bundle",
            "required": true,
            "installByDefault": true,
            "adapter": "omniflow_android_gui",
            "runtimeSkill": {
              "id": "omniflow-gui-runtime",
              "packagedArchivePath": "runtime-components/${if (archiveLabel == "old") "baseline" else "remote"}.zip",
              "packagedArchiveSha256": "${if (archiveLabel == "old") "a".repeat(64) else "c".repeat(64)}",
              "componentArchiveUrl": "https://example.com/${archiveLabel}.zip",
              "componentArchiveSha256": "${if (archiveLabel == "old") "a".repeat(64) else "b".repeat(64)}"
            }
          }]
        }
    """.trimIndent()
}

private fun RuntimeBundleCatalog.mergeRemoteForTest(
    remote: RuntimeBundleCatalog,
): RuntimeBundleCatalog = mergeRemote(remote)
