package cn.com.omnimind.bot.omniflow

import android.content.Context
import android.content.ContextWrapper
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowRuntimeProviderTest {
    @Test
    fun `install keeps packaged runtime while update requests official refresh`() = runBlocking {
        val platform = RefreshRecordingPlatform()
        val provider = OmniFlowRuntimeProvider()

        runCatching { provider.install(TestContext, platform) }
        runCatching { provider.update(TestContext, platform) }

        assertEquals(listOf(false, true), platform.refreshRequests)
    }

    @Test
    fun `runtime upgrade preserves function store and removes stale temp file`() {
        val storeDirectory = Files.createTempDirectory("omniflow-store-test").toFile()
        val store = storeDirectory.resolve("omniflow.json").apply {
            writeText("{\"schema_version\":\"omniflow.store.v2\"}")
        }
        val staleTemp = storeDirectory.resolve("omniflow.json.tmp").apply {
            writeText("partial")
        }

        alignOmniFlowStoreWithRuntime(storeDirectory, "runtime-fingerprint")

        assertTrue(store.isFile)
        assertEquals(
            "{\"schema_version\":\"omniflow.store.v2\"}",
            store.readText(),
        )
        assertFalse(staleTemp.exists())
        assertEquals(
            "runtime-fingerprint",
            storeDirectory.resolve(".runtime_fingerprint").readText(),
        )
        storeDirectory.deleteRecursively()
    }

    @Test
    fun `bridge contract mismatch triggers packaged runtime recovery`() {
        assertTrue(
            isOmniFlowRuntimeCompatibilityFailure(
                IllegalStateException("omniflow_bridge_contract_mismatch:old")
            )
        )
        assertFalse(
            isOmniFlowRuntimeCompatibilityFailure(
                IllegalStateException("omniflow_tool_call_failed")
            )
        )
    }

    @Test
    fun `runtime completeness uses package owned runlog module`() {
        val manifest = OmniFlowRuntimeManifest(
            version = "test",
            protocol = "test",
            capabilities = setOf("initialize"),
            bridgeContractSha256 = "0".repeat(64),
            pythonVersion = "3.12",
            omniFlowCommit = "flow",
            omniFlowSourceSha256 = "1".repeat(64),
            omniTransferCommit = "transfer",
            omniTransferSourceSha256 = "2".repeat(64),
            omniTransferCheckpoint = "checkpoints/v9.npz",
            numpyVersion = "numpy",
            jsonRepairVersion = "json-repair",
        )

        val required = OmniFlowRuntimeProvider().requiredOmniFlowRuntimePaths(manifest)

        assertTrue("scripts/runtime/python/omniflow/runlog.py" in required)
        assertTrue(
            "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/page_embedding.py" in required
        )
        assertTrue(
            "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/unified_alignment.py" in required
        )
        assertTrue(
            "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/visual_descriptor.py" in required
        )
        assertFalse(
            "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/numpy_matcher.py" in required
        )
        assertFalse("scripts/runtime/python/src/integrations/runlog.py" in required)
    }

    private class RefreshRecordingPlatform : OmniFlowPlatform {
        val refreshRequests = mutableListOf<Boolean>()

        override suspend fun startProcess(
            context: Context,
            command: String,
            environment: Map<String, String>,
        ): Process = error("not used")

        override suspend fun ensurePython(context: Context, expectedVersion: String) = Unit

        override suspend fun resolveRuntimeSkill(
            context: Context,
            refresh: Boolean,
        ): OmniFlowSkillLocation {
            refreshRequests += refresh
            error("stop after refresh observation")
        }

        override suspend fun bootstrapRuntimeSkill(
            context: Context,
            location: OmniFlowSkillLocation,
        ): OmniFlowSkillLocation = location

        override suspend fun reclaimRuntimeSkill(context: Context) = Unit

        override suspend fun completeJson(
            request: cn.com.omnimind.baselib.llm.ChatCompletionRequest,
        ): String = error("not used")
    }

    private object TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
