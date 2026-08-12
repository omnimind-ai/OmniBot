package cn.com.omnimind.bot.agent.runtime

import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ManagedAcpAdapterInstallTest {
    @Test
    fun bundledLockAndFullTreeManifest_areAuditedAndStableAcrossLineEndings() {
        val assets = bundledAssets()
        val payload = auditManagedAcpInstallAssets(
            assets.packageBytes,
            assets.lockBytes,
            assets.manifestBytes
        )
        val crlfPayload = auditManagedAcpInstallAssets(
            assets.packageBytes.toCrlf(),
            assets.lockBytes.toCrlf(),
            assets.manifestBytes.toCrlf()
        )

        assertEquals(EXPECTED_MANAGED_ACP_LOCK_SHA512, payload.lockSha512)
        assertEquals(133, payload.manifest.packages.size)
        assertEquals(7, payload.manifest.criticalFiles.size)
        assertTrue(payload.manifest.packages.sumOf { it.fileCount } > 6_000)
        assertTrue(payload.manifest.packages.all { it.treeSha512.length == 128 })
        assertEquals(payload, crlfPayload)
    }

    @Test
    fun lockWithMissingIntegrity_failsEvenWhenWholeFileHashIsUpdated() {
        val assets = bundledAssets()
        val lock = JsonParser.parseString(assets.lockBytes.toUtf8()).asJsonObject
        val firstDependency = lock.getAsJsonObject("packages").entrySet()
            .first { it.key.isNotEmpty() }.value.asJsonObject
        firstDependency.remove("integrity")
        val modified = lock.toString().toByteArray(StandardCharsets.UTF_8)

        assertStableLockFailure {
            auditManagedAcpInstallAssets(
                assets.packageBytes,
                modified,
                assets.manifestBytes,
                expectedLockSha512 = sha512Hex(modified)
            )
        }
    }

    @Test
    fun lockWithPrivateRegistry_failsWithoutLeakingCanary() {
        val assets = bundledAssets()
        val lock = JsonParser.parseString(assets.lockBytes.toUtf8()).asJsonObject
        val firstDependency = lock.getAsJsonObject("packages").entrySet()
            .first { it.key.isNotEmpty() }.value.asJsonObject
        firstDependency.addProperty(
            "resolved",
            "https://private-registry.example.invalid/token-canary-do-not-leak.tgz"
        )
        val modified = lock.toString().toByteArray(StandardCharsets.UTF_8)

        val error = runCatching {
            auditManagedAcpInstallAssets(
                assets.packageBytes,
                modified,
                assets.manifestBytes,
                expectedLockSha512 = sha512Hex(modified)
            )
        }.exceptionOrNull()
        assertEquals(AGENT_RUNTIME_ADAPTER_LOCK_INVALID, error?.message)
        assertFalse(error?.message.orEmpty().contains("token-canary"))
    }

    @Test
    fun missingCurrentPlatformRuntime_failsEvenWithRehashedLock() {
        val assets = bundledAssets()
        val lock = JsonParser.parseString(assets.lockBytes.toUtf8()).asJsonObject
        lock.getAsJsonObject("packages")
            .remove("node_modules/@openai/codex-linux-arm64")
        val modified = lock.toString().toByteArray(StandardCharsets.UTF_8)

        assertStableLockFailure {
            auditManagedAcpInstallAssets(
                assets.packageBytes,
                modified,
                assets.manifestBytes,
                expectedLockSha512 = sha512Hex(modified)
            )
        }
    }

    @Test
    fun malformedManifestTreeDigest_failsEvenWithRehashedManifest() {
        val assets = bundledAssets()
        val manifest = JsonParser.parseString(assets.manifestBytes.toUtf8()).asJsonObject
        manifest.getAsJsonArray("packages")[0].asJsonObject
            .addProperty("treeSha512", "not-a-sha512")
        val modified = manifest.toString().toByteArray(StandardCharsets.UTF_8)

        assertStableLockFailure {
            auditManagedAcpInstallAssets(
                assets.packageBytes,
                assets.lockBytes,
                modified,
                expectedManifestSha512 = sha512Hex(modified)
            )
        }
    }

    @Test
    fun installCommand_usesFreshStagingIsolatedEngineStrictNpmCi() {
        val payload = auditedPayload()
        val command = buildManagedAcpInstallCommand(
            payload,
            MANAGED_CODEX_ACP_PACKAGE_SPEC,
            "codex-acp"
        )

        assertTrue(command.contains("mktemp -d \"\$versions_dir/.staging."))
        assertTrue(command.contains("cd \"\$staging\""))
        assertTrue(command.contains("test ! -e \"\$staging/.npmrc\""))
        assertTrue(command.contains("test ! -e \"\$staging/npm-shrinkwrap.json\""))
        assertTrue(command.contains("NPM_CONFIG_ENGINE_STRICT=true"))
        assertTrue(command.contains("NPM_CONFIG_UMASK=0022"))
        assertTrue(command.contains("\"\$npm_bin\" ci --ignore-scripts --omit=dev"))
        assertTrue(command.contains("--registry=https://registry.npmjs.org/"))
        assertTrue(command.contains("env -i HOME=\"\$npm_home\""))
        assertTrue(command.contains("test \"\$node_major\" -ge 22"))
        assertTrue(command.contains("guarded_remove"))
        assertTrue(command.contains(".previous.XXXXXXXX"))
        assertTrue(command.contains("rm -f -- \"\$staging/node_modules/.package-lock.json\""))
        assertTrue(command.contains("fs.readFileSync(0)"))
        assertTrue(command.contains("fs.constants.O_NOFOLLOW"))
        assertTrue(command.contains(".omnibot-installed-manifest.json"))
        assertTrue(command.length < 120_000)
        assertFalse(command.contains(payload.packageLockBase64.take(64)))
        assertFalse(command.contains(payload.manifestJsonBase64.take(64)))
        assertFalse(command.contains("npm install"))
        assertFalse(command.contains(" install -g"))
        assertFalse(command.contains("/root/.npmrc"))
        assertFalse(command.contains("NODE_AUTH_TOKEN"))
        assertFalse(command.contains("NPM_TOKEN"))
        assertFalse(command.contains("token-canary"))
    }

    @Test
    fun installAssets_areDeliveredOnlyThroughBoundedStdinPayload() {
        val payload = auditedPayload()
        val input = buildManagedAcpInstallInput(payload)
        val root = JsonParser.parseString(input.toUtf8()).asJsonObject

        assertTrue(input.size < 512 * 1024)
        assertEquals(1, root.get("schemaVersion").asInt)
        assertEquals(payload.packageJsonBase64, root.get("packageJsonBase64").asString)
        assertEquals(payload.packageLockBase64, root.get("packageLockBase64").asString)
        assertEquals(payload.manifestJsonBase64, root.get("manifestJsonBase64").asString)

        val tampered = payload.copy(
            packageLockBase64 = payload.packageLockBase64.dropLast(4) + "AAAA"
        )
        assertStableLockFailure { buildManagedAcpInstallInput(tampered) }
    }

    @Test
    fun readyAndLaunch_rehashFullTreeAndUseVerifiedAbsoluteExecutable() {
        val payload = auditedPayload()
        val ready = buildManagedAcpReadyProbeCommand(
            payload,
            MANAGED_CLAUDE_ACP_PACKAGE_SPEC,
            "claude-agent-acp"
        )
        val launch = buildManagedAcpLaunchCommand(
            payload,
            MANAGED_CLAUDE_ACP_PACKAGE_SPEC,
            "claude-agent-acp",
            listOf("--flag")
        )

        listOf(ready, launch).forEach { command ->
            assertTrue(command.contains("invalid managed adapter tree"))
            assertTrue(command.contains("info.nlink !== 1"))
            assertTrue(command.contains("digest !== item.treeSha512"))
            assertTrue(command.contains("if (name ==="))
            assertTrue(command.contains("actualNames.length !== expected.size"))
            assertTrue(command.contains("expectedTargets.has(target)"))
            assertTrue(command.contains("fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW"))
            assertTrue(command.contains("readVerifiedControlFile"))
            assertTrue(command.contains("info.nlink !== 1"))
            assertTrue(command.contains(".omnibot-installed-manifest.json"))
            assertTrue(command.contains("test \"\$node_major\" -ge 22"))
            assertFalse(command.contains("/root/.npm-global"))
        }
        assertTrue(
            launch.contains(
                "exec \"\$node_bin\" '/root/.omnibot/acp-adapters/versions/" +
                    EXPECTED_MANAGED_ACP_LOCK_SHA512 +
                    "/node_modules/@agentclientprotocol/claude-agent-acp/dist/index.js'"
            )
        )
        assertTrue(
            launch.contains(
                "PATH=\"\$install_dir/node_modules/.bin:\$system_path\"; export PATH"
            )
        )
        assertTrue(launch.endsWith(" '--flag'"))
    }

    @Test
    fun invalidManagedPackageMapping_returnsOnlyStableCode() {
        val error = runCatching {
            buildManagedAcpReadyProbeCommand(
                auditedPayload(),
                "@private/adapter@1.0.0",
                "https://private-registry.example.invalid/token-canary-do-not-leak"
            )
        }.exceptionOrNull()

        assertTrue(error is ManagedAcpInstallException)
        assertEquals(AGENT_RUNTIME_ADAPTER_LOCK_INVALID, error?.message)
        assertFalse(error?.message.orEmpty().contains("private-registry"))
        assertFalse(error?.message.orEmpty().contains("token-canary"))
    }

    private fun auditedPayload(): ManagedAcpInstallPayload {
        val assets = bundledAssets()
        return auditManagedAcpInstallAssets(
            assets.packageBytes,
            assets.lockBytes,
            assets.manifestBytes
        )
    }

    private fun bundledAssets(): Assets {
        val root = File("src/main/assets/agent_runtime/acp-adapters")
        return Assets(
            root.resolve("package.json").readBytes(),
            root.resolve("package-lock.json").readBytes(),
            root.resolve("installed-manifest.json").readBytes()
        )
    }

    private fun assertStableLockFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected managed ACP audit to fail")
        } catch (error: ManagedAcpInstallException) {
            assertEquals(AGENT_RUNTIME_ADAPTER_LOCK_INVALID, error.message)
        }
    }

    private fun ByteArray.toUtf8(): String = toString(StandardCharsets.UTF_8)
    private fun ByteArray.toCrlf(): ByteArray =
        toUtf8().replace("\n", "\r\n").toByteArray(StandardCharsets.UTF_8)

    private fun sha512Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-512").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class Assets(
        val packageBytes: ByteArray,
        val lockBytes: ByteArray,
        val manifestBytes: ByteArray
    )
}
