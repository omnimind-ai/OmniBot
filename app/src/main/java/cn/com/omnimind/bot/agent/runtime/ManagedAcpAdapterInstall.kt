package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

internal const val MANAGED_CODEX_ACP_PACKAGE_SPEC =
    "@agentclientprotocol/codex-acp@1.1.7"
internal const val MANAGED_CLAUDE_ACP_PACKAGE_SPEC =
    "@agentclientprotocol/claude-agent-acp@0.61.0"

private const val MANAGED_ACP_ROOT = "/root/.omnibot/acp-adapters"
internal const val EXPECTED_MANAGED_ACP_LOCK_SHA512 =
    "dea567257b8063c435b7ab135e7ec7a81d56fd624443761d2b435d28d0089ce247ae06529e651f7058c693ec9f80c070a95161d17292e22c4fe821d6eeef05e7"
internal const val MANAGED_ACP_INSTALL_DIR =
    "$MANAGED_ACP_ROOT/versions/$EXPECTED_MANAGED_ACP_LOCK_SHA512"
internal const val MANAGED_ACP_BIN_DIR = "$MANAGED_ACP_INSTALL_DIR/node_modules/.bin"
internal const val MANAGED_ACP_PATH_PREFIX =
    "PATH=\"$MANAGED_ACP_BIN_DIR:\$PATH\"; export PATH;"

internal const val AGENT_RUNTIME_ADAPTER_LOCK_INVALID =
    "AGENT_RUNTIME_ADAPTER_LOCK_INVALID"
internal const val AGENT_RUNTIME_ADAPTER_NPM_MISSING =
    "AGENT_RUNTIME_ADAPTER_NPM_MISSING"
internal const val AGENT_RUNTIME_ADAPTER_INSTALL_FAILED =
    "AGENT_RUNTIME_ADAPTER_INSTALL_FAILED"

private const val PACKAGE_ASSET_PATH = "agent_runtime/acp-adapters/package.json"
private const val LOCK_ASSET_PATH = "agent_runtime/acp-adapters/package-lock.json"
private const val MANIFEST_ASSET_PATH = "agent_runtime/acp-adapters/installed-manifest.json"
private const val INSTALLED_MANIFEST_FILE_NAME = ".omnibot-installed-manifest.json"
private const val MAX_MANAGED_INSTALL_INPUT_BYTES = 512 * 1024
private const val EXPECTED_PACKAGE_SHA512 =
    "db1ab1969abc22649f9a9ca751acc725c9332c985434ac7b48cc4c80b2e2ac4b922f2a90416e9e13b14c441dfa6f54073c317f047635c7c0341ca3544cfba0d6"
private const val EXPECTED_MANIFEST_SHA512 =
    "8bf3fc30f8f055e1017440864aab2033d8fce098f8d6e5a1931540ad4961fab48b7fc892926a270372428c41d72c03218a6d96a2de6feb93a34a4ea128412363"

private const val MANAGED_CODEX_ACP_VERSION = "1.1.7"
private const val MANAGED_CLAUDE_ACP_VERSION = "0.61.0"
private const val MANAGED_CODEX_VERSION = "0.145.0"
private const val MANAGED_CODEX_ARM64_VERSION = "0.145.0-linux-arm64"
private const val MANAGED_CLAUDE_SDK_VERSION = "0.3.217"
private const val SYSTEM_EXECUTABLE_PATH =
    "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

private val EXPECTED_DEPENDENCIES = linkedMapOf(
    "@agentclientprotocol/claude-agent-acp" to MANAGED_CLAUDE_ACP_VERSION,
    "@agentclientprotocol/codex-acp" to MANAGED_CODEX_ACP_VERSION
)

private val MANAGED_COMMAND_BY_PACKAGE_SPEC = mapOf(
    MANAGED_CODEX_ACP_PACKAGE_SPEC to "codex-acp",
    MANAGED_CLAUDE_ACP_PACKAGE_SPEC to "claude-agent-acp"
)

private val MANAGED_EXECUTABLE_BY_PACKAGE_SPEC = mapOf(
    MANAGED_CODEX_ACP_PACKAGE_SPEC to
        "node_modules/@agentclientprotocol/codex-acp/dist/index.js",
    MANAGED_CLAUDE_ACP_PACKAGE_SPEC to
        "node_modules/@agentclientprotocol/claude-agent-acp/dist/index.js"
)

private val REQUIRED_CRITICAL_PATHS = mapOf(
    "node_modules/@agentclientprotocol/codex-acp/dist/index.js" to "always",
    "node_modules/@agentclientprotocol/claude-agent-acp/dist/index.js" to "always",
    "node_modules/@openai/codex/bin/codex.js" to "always",
    "node_modules/@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl/bin/codex" to
        "always",
    "node_modules/@anthropic-ai/claude-agent-sdk/sdk.mjs" to "always",
    "node_modules/@anthropic-ai/claude-agent-sdk-linux-arm64/claude" to "glibc",
    "node_modules/@anthropic-ai/claude-agent-sdk-linux-arm64-musl/claude" to "musl"
)

private const val CODEX_ARM64_PACKAGE_PATH = "node_modules/@openai/codex-linux-arm64"
private const val CLAUDE_GLIBC_PACKAGE_PATH =
    "node_modules/@anthropic-ai/claude-agent-sdk-linux-arm64"
private const val CLAUDE_MUSL_PACKAGE_PATH =
    "node_modules/@anthropic-ai/claude-agent-sdk-linux-arm64-musl"

private val MANAGED_INSTALL_INPUT_WRITER_SOURCE = """
    const crypto = require('crypto');
    const fs = require('fs');
    const path = require('path');
    const fail = () => { throw new Error('invalid managed adapter input'); };
    try {
      const root = process.argv[1];
      const expectedPackage = process.argv[2];
      const expectedLock = process.argv[3];
      const expectedManifest = process.argv[4];
      const rootInfo = fs.lstatSync(root);
      if (!rootInfo.isDirectory() || rootInfo.isSymbolicLink() || fs.realpathSync(root) !== root) fail();
      const raw = fs.readFileSync(0);
      if (!raw.length || raw.length > $MAX_MANAGED_INSTALL_INPUT_BYTES) fail();
      const input = JSON.parse(raw.toString('utf8'));
      const keys = Object.keys(input).sort();
      if (keys.join(',') !== 'manifestJsonBase64,packageJsonBase64,packageLockBase64,schemaVersion') fail();
      if (input.schemaVersion !== 1) fail();
      const entries = [
        ['packageJsonBase64', 'package.json', expectedPackage],
        ['packageLockBase64', 'package-lock.json', expectedLock],
        ['manifestJsonBase64', '$INSTALLED_MANIFEST_FILE_NAME', expectedManifest]
      ];
      for (const [field, name, expected] of entries) {
        const encoded = input[field];
        if (typeof encoded !== 'string' || !encoded.length || encoded.length > $MAX_MANAGED_INSTALL_INPUT_BYTES) fail();
        const decoded = Buffer.from(encoded, 'base64');
        if (decoded.toString('base64') !== encoded) fail();
        const digest = crypto.createHash('sha512').update(decoded).digest('hex');
        if (!/^[0-9a-f]{128}$/.test(expected) || digest !== expected) fail();
        const destination = path.join(root, name);
        const flags = fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_NOFOLLOW;
        const fd = fs.openSync(destination, flags, 0o600);
        try {
          fs.fchmodSync(fd, 0o600);
          fs.writeFileSync(fd, decoded);
          fs.fsyncSync(fd);
        } finally {
          fs.closeSync(fd);
        }
      }
    } catch (_) {
      process.exit(1);
    }
""".trimIndent()

private val MANAGED_TREE_VERIFIER_SOURCE = """
    const crypto = require('crypto');
    const fs = require('fs');
    const path = require('path');
    const fail = () => { throw new Error('invalid managed adapter tree'); };
    const existsNoFollow = (name) => {
      try { fs.lstatSync(name); return true; } catch (error) {
        if (error && error.code === 'ENOENT') return false;
        throw error;
      }
    };
    const safeRelative = (value) =>
      typeof value === 'string' && value.length > 0 && !path.posix.isAbsolute(value) &&
      !value.split('/').some((part) => !part || part === '.' || part === '..') &&
      !/[\t\r\n\0]/.test(value);
    const byteSort = (left, right) => Buffer.compare(Buffer.from(left), Buffer.from(right));
    const hashFile = (name) => new Promise((resolve, reject) => {
      const digest = crypto.createHash('sha512');
      const stream = fs.createReadStream(name);
      stream.on('data', (chunk) => digest.update(chunk));
      stream.on('error', reject);
      stream.on('end', () => resolve(digest.digest('hex')));
    });
    const assertDirectory = (name, expectedReal) => {
      const info = fs.lstatSync(name);
      if (!info.isDirectory() || info.isSymbolicLink()) fail();
      if (fs.realpathSync(name) !== expectedReal) fail();
    };
    const root = process.argv[1];
    const manifestPath = process.argv[2];
    const expectedManifestSha512 = process.argv[3];
    const expectedPackageSha512 = process.argv[4];
    const expectedLockSha512 = process.argv[5];
    assertDirectory(root, root);
    if (manifestPath !== path.join(root, '$INSTALLED_MANIFEST_FILE_NAME')) fail();
    const readVerifiedControlFile = (filePath, expectedSha512, maxBytes) => {
      if (!/^[0-9a-f]{128}$/.test(expectedSha512)) fail();
      const fd = fs.openSync(filePath, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
      try {
        const info = fs.fstatSync(fd);
        if (!info.isFile() || info.isSymbolicLink() || info.nlink !== 1) fail();
        if ((info.mode & 0o777) !== 0o600 || info.size <= 0 || info.size > maxBytes) fail();
        const bytes = Buffer.alloc(info.size);
        let offset = 0;
        while (offset < bytes.length) {
          const count = fs.readSync(fd, bytes, offset, bytes.length - offset, offset);
          if (count <= 0) fail();
          offset += count;
        }
        if (crypto.createHash('sha512').update(bytes).digest('hex') !== expectedSha512) fail();
        return bytes;
      } finally {
        fs.closeSync(fd);
      }
    };
    const manifestBytes = readVerifiedControlFile(manifestPath, expectedManifestSha512, 256 * 1024);
    readVerifiedControlFile(path.join(root, 'package.json'), expectedPackageSha512, 64 * 1024);
    readVerifiedControlFile(path.join(root, 'package-lock.json'), expectedLockSha512, 256 * 1024);
    const manifest = JSON.parse(manifestBytes.toString('utf8'));
    if (manifest.schemaVersion !== 1 || !Array.isArray(manifest.packages)) fail();
    if (process.platform !== 'linux' || process.arch !== 'arm64') fail();
    const header = process.report && process.report.getReport().header;
    const libc = header && header.glibcVersionRuntime ? 'glibc' : 'musl';
    const packageMap = new Map();
    for (const item of manifest.packages) {
      if (!safeRelative(item.path) || !item.path.startsWith('node_modules/')) fail();
      if (packageMap.has(item.path) || !/^[0-9a-f]{128}$/.test(item.treeSha512)) fail();
      if (!Number.isSafeInteger(item.fileCount) || item.fileCount < 0) fail();
      if (!Number.isSafeInteger(item.symlinkCount) || item.symlinkCount < 0) fail();
      if (!item.bins || typeof item.bins !== 'object' || Array.isArray(item.bins)) fail();
      packageMap.set(item.path, item);
    }
    const requiredOptional = new Set([
      'node_modules/@openai/codex-linux-arm64',
      libc === 'glibc'
        ? 'node_modules/@anthropic-ai/claude-agent-sdk-linux-arm64'
        : 'node_modules/@anthropic-ai/claude-agent-sdk-linux-arm64-musl'
    ]);
    const installed = new Set();
    for (const item of manifest.packages) {
      const full = path.join(root, ...item.path.split('/'));
      if (existsNoFollow(full)) {
        assertDirectory(full, full);
        installed.add(item.path);
      } else if (!item.optional || requiredOptional.has(item.path)) {
        fail();
      }
    }
    const digestTree = async (item) => {
      const base = path.join(root, ...item.path.split('/'));
      const lines = [];
      let files = 0;
      let links = 0;
      const walk = async (directory, prefix) => {
        const names = fs.readdirSync(directory).sort(byteSort);
        for (const name of names) {
          const relative = prefix ? prefix + '/' + name : name;
          if (!safeRelative(relative)) fail();
          const full = path.join(directory, name);
          const info = fs.lstatSync(full);
          if (info.isDirectory() && !info.isSymbolicLink()) {
            if (name === 'node_modules') {
              if (prefix !== '') fail();
              continue;
            }
            await walk(full, relative);
          } else if (info.isFile() && !info.isSymbolicLink()) {
            if (info.nlink !== 1) fail();
            const digest = await hashFile(full);
            lines.push('F\t' + relative + '\t' + (info.mode & 0o777).toString(8) + '\t' + digest + '\n');
            files += 1;
          } else if (info.isSymbolicLink()) {
            const target = fs.readlinkSync(full);
            if (typeof target !== 'string' || !target || path.isAbsolute(target) || /[\t\r\n]/.test(target)) fail();
            const resolved = path.resolve(path.dirname(full), target);
            if (resolved !== base && !resolved.startsWith(base + path.sep)) fail();
            lines.push('L\t' + relative + '\t' + target + '\n');
            links += 1;
          } else {
            fail();
          }
        }
      };
      await walk(base, '');
      lines.sort(byteSort);
      const digest = crypto.createHash('sha512').update(lines.join(''), 'utf8').digest('hex');
      if (digest !== item.treeSha512 || files !== item.fileCount || links !== item.symlinkCount) fail();
    };
    const containingNodeModules = (packagePath) => {
      const marker = '/node_modules/';
      const index = packagePath.lastIndexOf(marker);
      return index < 0 ? 'node_modules' : packagePath.slice(0, index) + '/node_modules';
    };
    const allowedBins = new Map();
    for (const packagePath of installed) {
      const item = packageMap.get(packagePath);
      const container = containingNodeModules(packagePath);
      let byName = allowedBins.get(container);
      if (!byName) { byName = new Map(); allowedBins.set(container, byName); }
      for (const [name, target] of Object.entries(item.bins)) {
        if (!/^[A-Za-z0-9._-]+$/.test(name) || !safeRelative(target)) fail();
        let targets = byName.get(name);
        if (!targets) { targets = new Set(); byName.set(name, targets); }
        targets.add(path.join(root, ...packagePath.split('/'), ...target.split('/')));
      }
    }
    const scanBins = (nodeModulesPath, nodeModulesRelative) => {
      const binPath = path.join(nodeModulesPath, '.bin');
      const expected = allowedBins.get(nodeModulesRelative) || new Map();
      if (!existsNoFollow(binPath)) {
        if (expected.size) fail();
        return;
      }
      assertDirectory(binPath, binPath);
      const actualNames = fs.readdirSync(binPath).sort(byteSort);
      if (actualNames.length !== expected.size) fail();
      for (const name of actualNames) {
        if (!expected.has(name)) fail();
        const linkPath = path.join(binPath, name);
        const info = fs.lstatSync(linkPath);
        if (!info.isSymbolicLink()) fail();
        const target = fs.readlinkSync(linkPath);
        if (typeof target !== 'string' || !target || path.isAbsolute(target) || /[\t\r\n]/.test(target)) fail();
        const expectedTargets = new Set([...expected.get(name)].map((item) => path.relative(binPath, item)));
        if (!expectedTargets.has(target)) fail();
        const resolved = fs.realpathSync(linkPath);
        if (!expected.get(name).has(resolved)) fail();
        const targetInfo = fs.lstatSync(resolved);
        if (!targetInfo.isFile() || targetInfo.isSymbolicLink() || targetInfo.nlink !== 1 || !(targetInfo.mode & 0o111)) fail();
      }
    };
    const found = new Set();
    const registerPackage = (nodeModulesPath, nodeModulesRelative, relativeName) => {
      const packagePath = nodeModulesRelative + '/' + relativeName;
      if (!installed.has(packagePath)) fail();
      const packageRoot = path.join(nodeModulesPath, ...relativeName.split('/'));
      assertDirectory(packageRoot, packageRoot);
      found.add(packagePath);
      const nested = path.join(packageRoot, 'node_modules');
      if (existsNoFollow(nested)) scanNodeModules(nested, packagePath + '/node_modules');
    };
    const scanNodeModules = (nodeModulesPath, nodeModulesRelative) => {
      assertDirectory(nodeModulesPath, nodeModulesPath);
      const names = fs.readdirSync(nodeModulesPath).sort(byteSort);
      for (const name of names) {
        if (name === '.bin') continue;
        const full = path.join(nodeModulesPath, name);
        const info = fs.lstatSync(full);
        if (name.startsWith('@')) {
          if (!info.isDirectory() || info.isSymbolicLink()) fail();
          const children = fs.readdirSync(full).sort(byteSort);
          for (const child of children) registerPackage(nodeModulesPath, nodeModulesRelative, name + '/' + child);
        } else {
          registerPackage(nodeModulesPath, nodeModulesRelative, name);
        }
      }
      scanBins(nodeModulesPath, nodeModulesRelative);
    };
    (async () => {
      for (const packagePath of installed) await digestTree(packageMap.get(packagePath));
      scanNodeModules(path.join(root, 'node_modules'), 'node_modules');
      if (found.size !== installed.size) fail();
      for (const packagePath of installed) if (!found.has(packagePath)) fail();
    })().catch(() => process.exit(1));
""".trimIndent()

internal class ManagedAcpInstallException(
    val stableCode: String
) : IllegalStateException(stableCode)

internal data class ManagedAcpPackageManifest(
    val path: String,
    val version: String,
    val packageJsonSha512: String,
    val mode: Int,
    val optional: Boolean,
    val treeSha512: String,
    val fileCount: Int,
    val symlinkCount: Int,
    val bins: Map<String, String>
)

internal data class ManagedAcpCriticalFileManifest(
    val path: String,
    val sha512: String,
    val mode: Int,
    val executable: Boolean,
    val requiredWhen: String
)

internal data class ManagedAcpInstallManifest(
    val packages: List<ManagedAcpPackageManifest>,
    val criticalFiles: List<ManagedAcpCriticalFileManifest>
)

internal data class ManagedAcpInstallPayload(
    val packageJsonBase64: String,
    val packageLockBase64: String,
    val packageSha512: String,
    val lockSha512: String,
    val manifestSha512: String,
    val manifestJsonBase64: String,
    val manifest: ManagedAcpInstallManifest
)

internal fun managedAcpCommandMatchesPackage(
    packageSpec: String,
    command: String
): Boolean = MANAGED_COMMAND_BY_PACKAGE_SPEC[packageSpec] == command

internal fun managedAcpAbsoluteExecutable(packageSpec: String): String {
    val relative = MANAGED_EXECUTABLE_BY_PACKAGE_SPEC[packageSpec]
        ?: throw ManagedAcpInstallException(AGENT_RUNTIME_ADAPTER_LOCK_INVALID)
    return "$MANAGED_ACP_INSTALL_DIR/$relative"
}

internal fun loadManagedAcpInstallPayload(context: Context): ManagedAcpInstallPayload {
    return try {
        val packageBytes = context.assets.open(PACKAGE_ASSET_PATH).use { it.readBytes() }
        val lockBytes = context.assets.open(LOCK_ASSET_PATH).use { it.readBytes() }
        val manifestBytes = context.assets.open(MANIFEST_ASSET_PATH).use { it.readBytes() }
        auditManagedAcpInstallAssets(packageBytes, lockBytes, manifestBytes)
    } catch (error: ManagedAcpInstallException) {
        throw error
    } catch (_: Exception) {
        throw ManagedAcpInstallException(AGENT_RUNTIME_ADAPTER_LOCK_INVALID)
    }
}

internal fun auditManagedAcpInstallAssets(
    packageBytes: ByteArray,
    lockBytes: ByteArray,
    manifestBytes: ByteArray,
    expectedPackageSha512: String = EXPECTED_PACKAGE_SHA512,
    expectedLockSha512: String = EXPECTED_MANAGED_ACP_LOCK_SHA512,
    expectedManifestSha512: String = EXPECTED_MANIFEST_SHA512
): ManagedAcpInstallPayload {
    try {
        val normalizedPackage = normalizeJsonAsset(packageBytes)
        val normalizedLock = normalizeJsonAsset(lockBytes)
        val normalizedManifest = normalizeJsonAsset(manifestBytes)
        val packageSha512 = sha512Hex(normalizedPackage)
        val lockSha512 = sha512Hex(normalizedLock)
        val manifestSha512 = sha512Hex(normalizedManifest)
        require(packageSha512 == expectedPackageSha512)
        require(lockSha512 == expectedLockSha512)
        require(manifestSha512 == expectedManifestSha512)

        val packageRoot = JsonParser.parseString(normalizedPackage.toString(StandardCharsets.UTF_8))
            .asJsonObject
        require(packageRoot.get("private")?.asBoolean == true)
        require(!packageRoot.has("scripts"))
        require(readStringMap(packageRoot.getAsJsonObject("dependencies")) == EXPECTED_DEPENDENCIES)

        val lockRoot = JsonParser.parseString(normalizedLock.toString(StandardCharsets.UTF_8))
            .asJsonObject
        require(lockRoot.get("lockfileVersion")?.asInt == 3)
        val packages = lockRoot.getAsJsonObject("packages")
        require(packages.size() > EXPECTED_DEPENDENCIES.size)
        require(
            readStringMap(packages.getAsJsonObject("").getAsJsonObject("dependencies")) ==
                EXPECTED_DEPENDENCIES
        )
        packages.entrySet().forEach { (path, element) ->
            if (path.isEmpty()) return@forEach
            requireSafeInstalledPath(path)
            val entry = element.asJsonObject
            require(!entry.has("link") && !entry.has("hasInstallScript"))
            require(readRequiredString(entry, "version").isNotBlank())
            requireOfficialRegistryTarball(readRequiredString(entry, "resolved"))
            requireSha512Integrity(readRequiredString(entry, "integrity"))
        }
        requireManagedRuntimeGraph(packages)

        val parsedManifest = parseAndAuditManifest(
            root = JsonParser.parseString(
                normalizedManifest.toString(StandardCharsets.UTF_8)
            ).asJsonObject,
            lockPackages = packages,
            lockSha512 = lockSha512
        )
        return ManagedAcpInstallPayload(
            packageJsonBase64 = Base64.getEncoder().encodeToString(normalizedPackage),
            packageLockBase64 = Base64.getEncoder().encodeToString(normalizedLock),
            packageSha512 = packageSha512,
            lockSha512 = lockSha512,
            manifestSha512 = manifestSha512,
            manifestJsonBase64 = Base64.getEncoder().encodeToString(normalizedManifest),
            manifest = parsedManifest
        )
    } catch (_: Exception) {
        throw ManagedAcpInstallException(AGENT_RUNTIME_ADAPTER_LOCK_INVALID)
    }
}

internal fun buildManagedAcpInstallInput(payload: ManagedAcpInstallPayload): ByteArray {
    requireManagedPayloadAssets(payload)
    val input = JsonObject().apply {
        addProperty("schemaVersion", 1)
        addProperty("packageJsonBase64", payload.packageJsonBase64)
        addProperty("packageLockBase64", payload.packageLockBase64)
        addProperty("manifestJsonBase64", payload.manifestJsonBase64)
    }.toString().toByteArray(StandardCharsets.UTF_8)
    if (input.isEmpty() || input.size > MAX_MANAGED_INSTALL_INPUT_BYTES) {
        throw ManagedAcpInstallException(AGENT_RUNTIME_ADAPTER_LOCK_INVALID)
    }
    return input
}

internal fun buildManagedAcpReadyProbeCommand(
    payload: ManagedAcpInstallPayload,
    packageSpec: String,
    command: String
): String {
    requireManagedPayload(payload, packageSpec, command)
    return buildString {
        appendLine("set -eu")
        appendLine("install_dir=${shellQuoteManaged(MANAGED_ACP_INSTALL_DIR)}")
        appendManagedRootChecks()
        appendToolchainAndPlatformChecks(requireNpm = true)
        appendInstalledTreeVerification(payload)
        appendMarkerVerification(payload)
        appendImmediateExecutableVerification(packageSpec, payload.manifest)
    }.trimEnd()
}

internal fun buildManagedAcpInstallCommand(
    payload: ManagedAcpInstallPayload,
    packageSpec: String,
    command: String
): String {
    requireManagedPayload(payload, packageSpec, command)
    return buildString {
        appendLine("set -eu")
        appendLine("umask 077")
        appendLine("managed_root=${shellQuoteManaged(MANAGED_ACP_ROOT)}")
        appendLine("versions_dir=\"\$managed_root/versions\"")
        appendLine("final_install_dir=${shellQuoteManaged(MANAGED_ACP_INSTALL_DIR)}")
        appendLine("test ! -L /root/.omnibot || exit 1")
        appendLine("mkdir -p /root/.omnibot \"\$managed_root\" \"\$versions_dir\"")
        appendLine("for safe_dir in /root/.omnibot \"\$managed_root\" \"\$versions_dir\"; do")
        appendLine("  test ! -L \"\$safe_dir\" && test -d \"\$safe_dir\"")
        appendLine("done")
        appendLine("test \"\$(readlink -f \"\$managed_root\")\" = \"\$managed_root\"")
        appendLine("test \"\$(readlink -f \"\$versions_dir\")\" = \"\$versions_dir\"")
        appendLine("staging='' work_dir='' previous_dir=''")
        appendLine("guarded_remove() {")
        appendLine("  target=\"\$1\"")
        appendLine("  test -n \"\$target\" && test \"\$target\" != / || exit 1")
        appendLine("  case \"\$target\" in")
        appendLine("    \"\$versions_dir\"/.staging.*|\"\$versions_dir\"/.previous.*|\"\$managed_root\"/.work.*) rm -rf -- \"\$target\" ;;")
        appendLine("    *) exit 1 ;;")
        appendLine("  esac")
        appendLine("}")
        appendLine("cleanup() {")
        appendLine("  test -z \"\$staging\" || guarded_remove \"\$staging\"")
        appendLine("  test -z \"\$work_dir\" || guarded_remove \"\$work_dir\"")
        appendLine("  test -z \"\$previous_dir\" || guarded_remove \"\$previous_dir\"")
        appendLine("}")
        appendLine("trap cleanup EXIT")
        appendLine("trap 'exit 1' HUP INT TERM")
        appendLine("staging=\"\$(mktemp -d \"\$versions_dir/.staging.XXXXXXXX\")\"")
        appendLine("work_dir=\"\$(mktemp -d \"\$managed_root/.work.XXXXXXXX\")\"")
        appendLine("test ! -L \"\$staging\" && test ! -L \"\$work_dir\"")
        appendLine("case \"\$(readlink -f \"\$staging\")\" in \"\$versions_dir\"/.staging.*) ;; *) exit 1 ;; esac")
        appendLine("case \"\$(readlink -f \"\$work_dir\")\" in \"\$managed_root\"/.work.*) ;; *) exit 1 ;; esac")
        appendLine("npm_home=\"\$work_dir/home\"")
        appendLine("npm_cache=\"\$work_dir/cache\"")
        appendLine("npm_user_config=\"\$work_dir/user.npmrc\"")
        appendLine("npm_global_config=\"\$work_dir/global.npmrc\"")
        appendLine("mkdir -p \"\$npm_home\" \"\$npm_cache\"")
        appendLine(": > \"\$npm_user_config\"")
        appendLine(": > \"\$npm_global_config\"")
        appendLine("chmod 600 \"\$npm_user_config\" \"\$npm_global_config\"")
        appendLine("system_path=${shellQuoteManaged(SYSTEM_EXECUTABLE_PATH)}")
        appendLine("node_bin=\"\$(PATH=\"\$system_path\" command -v node)\"")
        appendLine("npm_bin=\"\$(PATH=\"\$system_path\" command -v npm)\"")
        appendLine("node_major=\"\$(env -i HOME=\"\$npm_home\" PATH=\"\$system_path\" \"\$node_bin\" -p 'Number(process.versions.node.split(`.`)[0])' 2>/dev/null)\"")
        appendLine("test \"\$node_major\" -ge 22")
        appendLine(
            "env -i HOME=\"\$npm_home\" PATH=\"\$system_path\" \"\$node_bin\" -e " +
                "${shellQuoteManaged(MANAGED_INSTALL_INPUT_WRITER_SOURCE)} \"\$staging\" " +
                "${shellQuoteManaged(payload.packageSha512)} " +
                "${shellQuoteManaged(payload.lockSha512)} " +
                "${shellQuoteManaged(payload.manifestSha512)} >/dev/null 2>&1"
        )
        appendLine("test \"\$(sha512sum \"\$staging/package.json\" | awk '{print \$1}')\" = ${shellQuoteManaged(payload.packageSha512)}")
        appendLine("test \"\$(sha512sum \"\$staging/package-lock.json\" | awk '{print \$1}')\" = ${shellQuoteManaged(payload.lockSha512)}")
        appendLine("test \"\$(sha512sum \"\$staging/$INSTALLED_MANIFEST_FILE_NAME\" | awk '{print \$1}')\" = ${shellQuoteManaged(payload.manifestSha512)}")
        appendLine("test ! -e \"\$staging/.npmrc\" && test ! -L \"\$staging/.npmrc\"")
        appendLine("test ! -e \"\$staging/npm-shrinkwrap.json\" && test ! -L \"\$staging/npm-shrinkwrap.json\"")
        appendTopLevelAllowlist(
            "\$staging",
            listOf("package.json", "package-lock.json", INSTALLED_MANIFEST_FILE_NAME)
        )
        appendLine("cd \"\$staging\"")
        appendLine("umask 022")
        appendLine(
            "env -i HOME=\"\$npm_home\" PATH=\"\$system_path\" " +
                "NPM_CONFIG_REGISTRY=https://registry.npmjs.org/ " +
                "NPM_CONFIG_USERCONFIG=\"\$npm_user_config\" " +
                "NPM_CONFIG_GLOBALCONFIG=\"\$npm_global_config\" " +
                "NPM_CONFIG_CACHE=\"\$npm_cache\" " +
                "NPM_CONFIG_AUDIT=false NPM_CONFIG_FUND=false " +
                "NPM_CONFIG_IGNORE_SCRIPTS=true NPM_CONFIG_ENGINE_STRICT=true " +
                "NPM_CONFIG_UMASK=0022 " +
                "NPM_CONFIG_UPDATE_NOTIFIER=false NPM_CONFIG_PROGRESS=false " +
                "NPM_CONFIG_LOGLEVEL=silent NPM_CONFIG_LOGS_MAX=0 " +
                "\"\$npm_bin\" ci --ignore-scripts --omit=dev " +
                "--registry=https://registry.npmjs.org/ --no-audit --no-fund " +
                "--loglevel=silent >/dev/null 2>&1"
        )
        appendLine("umask 077")
        appendLine("rm -f -- \"\$staging/node_modules/.package-lock.json\"")
        appendLine("test ! -e \"\$staging/.npmrc\" && test ! -L \"\$staging/.npmrc\"")
        appendLine("test ! -e \"\$staging/npm-shrinkwrap.json\" && test ! -L \"\$staging/npm-shrinkwrap.json\"")
        appendTopLevelAllowlist(
            "\$staging",
            listOf(
                "package.json",
                "package-lock.json",
                INSTALLED_MANIFEST_FILE_NAME,
                "node_modules"
            )
        )
        appendLine("install_dir=\"\$staging\"")
        appendToolchainAndPlatformChecks(requireNpm = true, isolatedWorkDir = true)
        appendInstalledTreeVerification(payload)
        appendMarkerCreation(payload)
        appendLine("if test -e \"\$final_install_dir\" || test -L \"\$final_install_dir\"; then")
        appendLine("  previous_dir=\"\$(mktemp -d \"\$versions_dir/.previous.XXXXXXXX\")\"")
        appendLine("  mv -- \"\$final_install_dir\" \"\$previous_dir/install\"")
        appendLine("fi")
        appendLine("if ! mv -- \"\$staging\" \"\$final_install_dir\"; then")
        appendLine("  if test -n \"\$previous_dir\" && test -e \"\$previous_dir/install\"; then mv -- \"\$previous_dir/install\" \"\$final_install_dir\"; fi")
        appendLine("  exit 1")
        appendLine("fi")
        appendLine("staging=''")
        appendLine("if test -n \"\$previous_dir\"; then guarded_remove \"\$previous_dir\"; previous_dir=''; fi")
        appendLine("install_dir=\"\$final_install_dir\"")
        appendManagedRootChecks()
        appendToolchainAndPlatformChecks(requireNpm = true, isolatedWorkDir = true)
        appendInstalledTreeVerification(payload)
        appendMarkerVerification(payload)
        appendImmediateExecutableVerification(packageSpec, payload.manifest)
    }.trimEnd()
}

internal fun buildManagedAcpLaunchCommand(
    payload: ManagedAcpInstallPayload,
    packageSpec: String,
    command: String,
    arguments: List<String>
): String {
    val executable = managedAcpAbsoluteExecutable(packageSpec)
    return buildString {
        appendLine(buildManagedAcpReadyProbeCommand(payload, packageSpec, command))
        appendLine("PATH=\"\$install_dir/node_modules/.bin:\$system_path\"; export PATH")
        append("exec \"\$node_bin\" ${shellQuoteManaged(executable)}")
        arguments.forEach { append(" ${shellQuoteManaged(it)}") }
    }
}

private fun StringBuilder.appendManagedRootChecks() {
    appendLine("managed_root=${shellQuoteManaged(MANAGED_ACP_ROOT)}")
    appendLine("versions_dir=\"\$managed_root/versions\"")
    appendLine("test ! -L /root/.omnibot && test -d /root/.omnibot")
    appendLine("test ! -L \"\$managed_root\" && test -d \"\$managed_root\"")
    appendLine("test ! -L \"\$versions_dir\" && test -d \"\$versions_dir\"")
    appendLine("test ! -L \"\$install_dir\" && test -d \"\$install_dir\"")
    appendLine("test \"\$(readlink -f \"\$managed_root\")\" = \"\$managed_root\"")
    appendLine("test \"\$(readlink -f \"\$install_dir\")\" = \"\$install_dir\"")
}

private fun StringBuilder.appendToolchainAndPlatformChecks(
    requireNpm: Boolean,
    isolatedWorkDir: Boolean = false
) {
    appendLine("system_path=${shellQuoteManaged(SYSTEM_EXECUTABLE_PATH)}")
    appendLine("node_bin=\"\$(PATH=\"\$system_path\" command -v node)\"")
    if (requireNpm) {
        appendLine("npm_bin=\"\$(PATH=\"\$system_path\" command -v npm)\"")
    }
    val home = if (isolatedWorkDir) "\$npm_home" else "/nonexistent"
    appendLine("node_version=\"\$(env -i HOME=\"$home\" PATH=\"\$system_path\" \"\$node_bin\" --version 2>/dev/null)\"")
    appendLine("node_major=\"\$(env -i HOME=\"$home\" PATH=\"\$system_path\" \"\$node_bin\" -p 'Number(process.versions.node.split(`.`)[0])' 2>/dev/null)\"")
    appendLine("test \"\$node_major\" -ge 22")
    appendLine("test \"\$(env -i HOME=\"$home\" PATH=\"\$system_path\" \"\$node_bin\" -p 'process.platform+`/`+process.arch' 2>/dev/null)\" = linux/arm64")
    appendLine("libc_kind=\"\$(env -i HOME=\"$home\" PATH=\"\$system_path\" \"\$node_bin\" -p 'const h=process.report&&process.report.getReport().header;h&&h.glibcVersionRuntime?`glibc`:`musl`' 2>/dev/null)\"")
    appendLine("case \"\$libc_kind\" in glibc|musl) ;; *) exit 1 ;; esac")
    if (requireNpm) {
        val config = if (isolatedWorkDir) {
            "NPM_CONFIG_USERCONFIG=\"\$npm_user_config\" NPM_CONFIG_GLOBALCONFIG=\"\$npm_global_config\""
        } else {
            "NPM_CONFIG_USERCONFIG=/dev/null NPM_CONFIG_GLOBALCONFIG=/dev/null"
        }
        appendLine("npm_version=\"\$(env -i HOME=\"$home\" PATH=\"\$system_path\" $config \"\$npm_bin\" --version 2>/dev/null)\"")
    }
}

private fun StringBuilder.appendInstalledTreeVerification(
    payload: ManagedAcpInstallPayload
) {
    val manifest = payload.manifest
    appendLine("test ! -e \"\$install_dir/.npmrc\" && test ! -L \"\$install_dir/.npmrc\"")
    appendLine("test ! -e \"\$install_dir/npm-shrinkwrap.json\" && test ! -L \"\$install_dir/npm-shrinkwrap.json\"")
    appendTopLevelAllowlist(
        "\$install_dir",
        listOf(
            "package.json",
            "package-lock.json",
            INSTALLED_MANIFEST_FILE_NAME,
            "node_modules",
            ".install-marker"
        )
    )
    appendLine("installed_manifest=\"\$install_dir/$INSTALLED_MANIFEST_FILE_NAME\"")
    appendLine(
        "env -i HOME=/nonexistent PATH=\"\$system_path\" \"\$node_bin\" -e " +
            "${shellQuoteManaged(MANAGED_TREE_VERIFIER_SOURCE)} \"\$install_dir\" " +
            "\"\$installed_manifest\" ${shellQuoteManaged(payload.manifestSha512)} " +
            "${shellQuoteManaged(payload.packageSha512)} " +
            "${shellQuoteManaged(payload.lockSha512)} " +
            ">/dev/null 2>&1"
    )
    appendLine("verify_managed_file() {")
    appendLine("  file=\"\$1\" expected_hash=\"\$2\" expected_mode=\"\$3\" executable=\"\$4\"")
    appendLine("  test -f \"\$file\" && test ! -L \"\$file\"")
    appendLine("  case \"\$(readlink -f \"\$file\")\" in \"\$install_dir\"/node_modules/*) ;; *) exit 1 ;; esac")
    appendLine("  test \"\$(stat -c '%h' \"\$file\")\" = 1")
    appendLine("  test \"\$(stat -c '%a' \"\$file\")\" = \"\$expected_mode\"")
    appendLine("  test \"\$(sha512sum \"\$file\" | awk '{print \$1}')\" = \"\$expected_hash\"")
    appendLine("  test \"\$executable\" = 0 || test -x \"\$file\"")
    appendLine("}")
    manifest.criticalFiles.forEach { entry ->
        val invocation = managedFileVerificationInvocation(
            file = "\$install_dir/${entry.path}",
            sha512 = entry.sha512,
            mode = entry.mode,
            executable = entry.executable
        )
        when (entry.requiredWhen) {
            "always" -> appendLine(invocation)
            "glibc" -> appendLine("if test \"\$libc_kind\" = glibc; then $invocation; fi")
            "musl" -> appendLine("if test \"\$libc_kind\" = musl; then $invocation; fi")
        }
    }
}

private fun managedFileVerificationInvocation(
    file: String,
    sha512: String,
    mode: Int,
    executable: Boolean
): String = "verify_managed_file \"$file\" ${shellQuoteManaged(sha512)} " +
    "${shellQuoteManaged(mode.toString(8))} ${if (executable) 1 else 0}"

private fun StringBuilder.appendMarkerCreation(payload: ManagedAcpInstallPayload) {
    appendLine("marker_tmp=\"\$install_dir/.install-marker.tmp.\$\$\"")
    appendLine("printf '%s\\n' \\")
    appendLine("  ${shellQuoteManaged("package_sha512=${payload.packageSha512}")} \\")
    appendLine("  ${shellQuoteManaged("lock_sha512=${payload.lockSha512}")} \\")
    appendLine("  ${shellQuoteManaged("manifest_sha512=${payload.manifestSha512}")} \\")
    appendLine("  \"node_version=\$node_version\" \\")
    appendLine("  \"npm_version=\$npm_version\" \\")
    appendLine("  \"libc=\$libc_kind\" > \"\$marker_tmp\"")
    appendLine("chmod 600 \"\$marker_tmp\"")
    appendLine("mv -f -- \"\$marker_tmp\" \"\$install_dir/.install-marker\"")
}

private fun StringBuilder.appendMarkerVerification(payload: ManagedAcpInstallPayload) {
    appendLine("marker=\"\$install_dir/.install-marker\"")
    appendLine("test -f \"\$marker\" && test ! -L \"\$marker\"")
    appendLine("test \"\$(stat -c '%h' \"\$marker\")\" = 1")
    appendLine("expected_marker=\"\$(printf '%s\\n' \\")
    appendLine("  ${shellQuoteManaged("package_sha512=${payload.packageSha512}")} \\")
    appendLine("  ${shellQuoteManaged("lock_sha512=${payload.lockSha512}")} \\")
    appendLine("  ${shellQuoteManaged("manifest_sha512=${payload.manifestSha512}")} \\")
    appendLine("  \"node_version=\$node_version\" \\")
    appendLine("  \"npm_version=\$npm_version\" \\")
    appendLine("  \"libc=\$libc_kind\")\"")
    appendLine("test \"\$(cat \"\$marker\")\" = \"\$expected_marker\"")
}

private fun StringBuilder.appendImmediateExecutableVerification(
    packageSpec: String,
    manifest: ManagedAcpInstallManifest
) {
    val relative = MANAGED_EXECUTABLE_BY_PACKAGE_SPEC.getValue(packageSpec)
    val entry = manifest.criticalFiles.single { it.path == relative }
    appendLine(
        managedFileVerificationInvocation(
            file = "\$install_dir/${entry.path}",
            sha512 = entry.sha512,
            mode = entry.mode,
            executable = true
        )
    )
}

private fun StringBuilder.appendTopLevelAllowlist(
    root: String,
    allowed: List<String>
) {
    appendLine("for control_entry in \"$root\"/* \"$root\"/.[!.]* \"$root\"/..?*; do")
    appendLine("  if test ! -e \"\$control_entry\" && test ! -L \"\$control_entry\"; then continue; fi")
    appendLine("  control_name=\"\${control_entry##*/}\"")
    appendLine("  case \"\$control_name\" in")
    appendLine("    ${allowed.joinToString("|")}) ;;")
    appendLine("    *) exit 1 ;;")
    appendLine("  esac")
    appendLine("done")
}

private fun requireManagedPayload(
    payload: ManagedAcpInstallPayload,
    packageSpec: String,
    command: String
) {
    requireManagedCommand(packageSpec, command)
    requireManagedPayloadAssets(payload)
}

private fun requireManagedPayloadAssets(payload: ManagedAcpInstallPayload) {
    try {
        require(payload.packageSha512 == EXPECTED_PACKAGE_SHA512)
        require(payload.lockSha512 == EXPECTED_MANAGED_ACP_LOCK_SHA512)
        require(payload.manifestSha512 == EXPECTED_MANIFEST_SHA512)
        listOf(
            payload.packageJsonBase64 to payload.packageSha512,
            payload.packageLockBase64 to payload.lockSha512,
            payload.manifestJsonBase64 to payload.manifestSha512
        ).forEach { (encoded, expectedSha512) ->
            val decoded = Base64.getDecoder().decode(encoded)
            require(Base64.getEncoder().encodeToString(decoded) == encoded)
            require(sha512Hex(decoded) == expectedSha512)
        }
    } catch (error: ManagedAcpInstallException) {
        throw error
    } catch (_: Exception) {
        throw ManagedAcpInstallException(AGENT_RUNTIME_ADAPTER_LOCK_INVALID)
    }
}

private fun parseAndAuditManifest(
    root: JsonObject,
    lockPackages: JsonObject,
    lockSha512: String
): ManagedAcpInstallManifest {
    require(root.get("schemaVersion")?.asInt == 1)
    require(readRequiredString(root, "lockSha512") == lockSha512)
    val manifestPackages = root.getAsJsonArray("packages").mapObjects { entry ->
        ManagedAcpPackageManifest(
            path = readRequiredString(entry, "path").also(::requireSafeInstalledPath),
            version = readRequiredString(entry, "version"),
            packageJsonSha512 = readSha512Hex(entry, "packageJsonSha512"),
            mode = readMode(entry, "mode"),
            optional = entry.get("optional")?.asBoolean == true,
            treeSha512 = readSha512Hex(entry, "treeSha512"),
            fileCount = readNonNegativeInt(entry, "fileCount"),
            symlinkCount = readNonNegativeInt(entry, "symlinkCount"),
            bins = readStringMap(entry.getAsJsonObject("bins")).also { bins ->
                bins.forEach { (name, target) ->
                    require(name.matches(Regex("[A-Za-z0-9._-]+")))
                    requireSafePackageRelativePath(target)
                }
            }
        )
    }
    val expectedPaths = lockPackages.entrySet().mapNotNull { (path, _) ->
        path.takeIf(String::isNotEmpty)
    }.toSet()
    require(manifestPackages.map { it.path }.toSet() == expectedPaths)
    require(manifestPackages.size == expectedPaths.size)
    manifestPackages.forEach { entry ->
        val locked = lockPackages.getAsJsonObject(entry.path)
        require(entry.version == readRequiredString(locked, "version"))
        require(entry.optional == (locked.get("optional")?.asBoolean == true))
    }

    val criticalFiles = root.getAsJsonArray("criticalFiles").mapObjects { entry ->
        ManagedAcpCriticalFileManifest(
            path = readRequiredString(entry, "path").also(::requireSafeInstalledPath),
            sha512 = readSha512Hex(entry, "sha512"),
            mode = readMode(entry, "mode"),
            executable = entry.get("executable")?.asBoolean == true,
            requiredWhen = readRequiredString(entry, "requiredWhen")
        )
    }
    require(criticalFiles.associate { it.path to it.requiredWhen } == REQUIRED_CRITICAL_PATHS)
    require(criticalFiles.size == REQUIRED_CRITICAL_PATHS.size)
    REQUIRED_CRITICAL_PATHS.keys.forEach { path ->
        val owner = expectedPaths.filter { path == it || path.startsWith("$it/") }.maxByOrNull(String::length)
        requireNotNull(owner)
    }
    return ManagedAcpInstallManifest(manifestPackages, criticalFiles)
}

private fun requireManagedRuntimeGraph(packages: JsonObject) {
    fun version(path: String, expected: String) {
        require(readRequiredString(packages.getAsJsonObject(path), "version") == expected)
    }
    version("node_modules/@agentclientprotocol/codex-acp", MANAGED_CODEX_ACP_VERSION)
    version("node_modules/@agentclientprotocol/claude-agent-acp", MANAGED_CLAUDE_ACP_VERSION)
    version("node_modules/@openai/codex", MANAGED_CODEX_VERSION)
    version(CODEX_ARM64_PACKAGE_PATH, MANAGED_CODEX_ARM64_VERSION)
    version("node_modules/@anthropic-ai/claude-agent-sdk", MANAGED_CLAUDE_SDK_VERSION)
    version(CLAUDE_GLIBC_PACKAGE_PATH, MANAGED_CLAUDE_SDK_VERSION)
    version(CLAUDE_MUSL_PACKAGE_PATH, MANAGED_CLAUDE_SDK_VERSION)

    val codexOptional = readStringMap(
        packages.getAsJsonObject("node_modules/@openai/codex")
            .getAsJsonObject("optionalDependencies")
    )
    // npm records alias dependencies using the canonical "npm:<package>@<version>"
    // syntax. Require that exact audited alias; accepting only the bare version
    // makes the valid lock fail closed at runtime even though the target package
    // and its integrity entry are both present and verified.
    require(
        codexOptional["@openai/codex-linux-arm64"] ==
            "npm:@openai/codex@$MANAGED_CODEX_ARM64_VERSION"
    )
    val claudeOptional = readStringMap(
        packages.getAsJsonObject("node_modules/@anthropic-ai/claude-agent-sdk")
            .getAsJsonObject("optionalDependencies")
    )
    require(claudeOptional["@anthropic-ai/claude-agent-sdk-linux-arm64"] == MANAGED_CLAUDE_SDK_VERSION)
    require(claudeOptional["@anthropic-ai/claude-agent-sdk-linux-arm64-musl"] == MANAGED_CLAUDE_SDK_VERSION)
    require(
        packages.getAsJsonObject("node_modules/@agentclientprotocol/claude-agent-acp")
            .getAsJsonObject("engines").get("node")?.asString == ">=22"
    )
}

private fun <T> JsonArray.mapObjects(block: (JsonObject) -> T): List<T> =
    map { element -> block(element.asJsonObject) }

private fun normalizeJsonAsset(bytes: ByteArray): ByteArray =
    bytes.toString(StandardCharsets.UTF_8)
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .toByteArray(StandardCharsets.UTF_8)

private fun readStringMap(value: JsonObject?): Map<String, String> {
    requireNotNull(value)
    return value.entrySet().associate { (key, element) ->
        require(element.isJsonPrimitive && element.asJsonPrimitive.isString)
        key to element.asString
    }
}

private fun readRequiredString(value: JsonObject, name: String): String {
    val element = value.get(name)
    require(element?.isJsonPrimitive == true && element.asJsonPrimitive.isString)
    return element.asString
}

private fun readSha512Hex(value: JsonObject, name: String): String =
    readRequiredString(value, name).also { require(it.matches(Regex("[0-9a-f]{128}"))) }

private fun readMode(value: JsonObject, name: String): Int {
    val mode = value.get(name)?.asInt ?: error("missing mode")
    require(mode in 0..0x1ff)
    return mode
}

private fun readNonNegativeInt(value: JsonObject, name: String): Int {
    val result = value.get(name)?.asInt ?: error("missing integer")
    require(result >= 0)
    return result
}

private fun requireSafePackageRelativePath(path: String) {
    require(path.isNotEmpty() && !path.startsWith('/'))
    require(path.none { it == '\n' || it == '\r' || it == '\t' || it == '\u0000' })
    require(path.split('/').none { it.isEmpty() || it == "." || it == ".." })
    require(path != "node_modules" && !path.startsWith("node_modules/") && "/node_modules/" !in path)
}

private fun requireSafeInstalledPath(path: String) {
    require(path.startsWith("node_modules/"))
    require(path.none { it == '\n' || it == '\r' || it == '\u0000' })
    require(path.split('/').none { it.isEmpty() || it == "." || it == ".." })
}

private fun requireOfficialRegistryTarball(rawUrl: String) {
    val uri = URI(rawUrl)
    require(uri.scheme == "https" && uri.host == "registry.npmjs.org")
    require(uri.userInfo == null && uri.query == null && uri.fragment == null)
    require(uri.path?.endsWith(".tgz") == true)
}

private fun requireSha512Integrity(integrity: String) {
    require(integrity.isNotBlank() && !integrity.contains(' '))
    require(integrity.startsWith("sha512-"))
    require(Base64.getDecoder().decode(integrity.removePrefix("sha512-")).size == 64)
}

private fun sha512Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-512").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun requireManagedCommand(packageSpec: String, command: String) {
    if (!managedAcpCommandMatchesPackage(packageSpec, command)) {
        throw ManagedAcpInstallException(AGENT_RUNTIME_ADAPTER_LOCK_INVALID)
    }
}

private fun shellQuoteManaged(value: String): String =
    "'${value.replace("'", "'\\''")}'"
