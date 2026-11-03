package cn.com.omnimind.bot.agent.runtime

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Package inventory only. ACP continues to own sessions, turns and process connections. */
internal data class ManagedAgentVersion(
    val installedVersion: String? = null,
    val latestVersion: String? = null,
    val targetVersion: String? = null,
    val checkedAt: Long? = null,
    val error: String? = null,
    val pinned: Boolean = false,
) {
    val updateAvailable: Boolean get() = error == null &&
        installedVersion != null && targetVersion != null &&
        compareAgentVersions(installedVersion, targetVersion)?.let { it < 0 } == true

    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "installedVersion" to installedVersion,
        "latestVersion" to latestVersion,
        "targetVersion" to targetVersion,
        "checkedAt" to checkedAt,
        "error" to error,
        "pinned" to pinned,
        "updateAvailable" to updateAvailable,
    )
}

/** SemVer precedence, including prereleases; unknown output never means up to date. */
internal fun compareAgentVersions(left: String, right: String): Int? {
    val pattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")
    val a = pattern.matchEntire(left.trim()) ?: return null
    val b = pattern.matchEntire(right.trim()) ?: return null
    for (index in 1..3) {
        val compared = a.groupValues[index].toBigInteger().compareTo(b.groupValues[index].toBigInteger())
        if (compared != 0) return compared
    }
    val ap = a.groupValues[4]
    val bp = b.groupValues[4]
    if (ap == bp) return 0
    if (ap.isEmpty()) return 1
    if (bp.isEmpty()) return -1
    val aa = ap.split('.')
    val bb = bp.split('.')
    for (index in 0 until minOf(aa.size, bb.size)) {
        val an = aa[index].toBigIntegerOrNull()
        val bn = bb[index].toBigIntegerOrNull()
        val compared = when {
            an != null && bn != null -> an.compareTo(bn)
            an != null -> -1
            bn != null -> 1
            else -> aa[index].compareTo(bb[index])
        }
        if (compared != 0) return compared
    }
    return aa.size.compareTo(bb.size)
}

internal fun parseAgentVersion(output: String?): String? = output?.let {
    // Older Pi launchers report both the adapter and SDK versions. The catalog
    // manages the SDK package, so never compare the adapter version to its pin.
    val legacyPi = it.lineSequence().map(String::trim)
        .firstOrNull { line -> line.startsWith("omnibot-pi-acp ") }
    if (legacyPi != null) {
        val sdkVersion = Regex("^omnibot-pi-acp \\S+ \\(Pi (\\S+)\\)$")
            .matchEntire(legacyPi)?.groupValues?.get(1)
        return sdkVersion?.removePrefix("v")
            ?.takeIf { version -> compareAgentVersions(version, version) != null }
    }
    Regex("(?<![0-9A-Za-z])v?(\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?)")
        .find(it)?.groupValues?.get(1)
}

internal class ManagedAgentUpdates(
    private val probe: suspend (List<AcpAgentProfile>, String) -> Map<String, String?>,
    private val read: (String, String) -> ManagedAgentVersion?,
    private val save: (String, String, ManagedAgentVersion) -> Unit,
    private val latest: suspend (String) -> String = NpmAgentVersions::latest,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun check(profiles: List<AcpAgentProfile>, distributionId: String, remote: Boolean = true) = mutex.withLock {
        val managed = profiles.filter { it.officialRuntime?.managedAdapterPackage != null }
        if (managed.isEmpty()) return@withLock
        val localResult = try {
            Result.success(probe(managed, distributionId))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Result.failure(error)
        }
        coroutineScope {
            managed.map { profile -> async {
                val spec = requireNotNull(profile.officialRuntime?.managedAdapterPackage)
                val packageName = npmPackageName(spec)
                val selector = spec.removePrefix(packageName).removePrefix("@").ifEmpty { "latest" }
                val pinned = compareAgentVersions(selector, selector) != null
                val previous = read(distributionId, profile.id)
                var error: String? = if (localResult.isFailure) "local_probe_failed" else null
                val versions = localResult.getOrNull()
                val installed = versions?.get(profile.id)
                if (versions != null && !versions.containsKey(profile.id)) error = "local_probe_failed"
                val upstream = if (remote) try {
                    latest(packageName)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = error ?: "registry_unavailable"
                    null
                } else previous?.latestVersion
                val target = if (pinned) selector else upstream
                save(distributionId, profile.id, ManagedAgentVersion(
                    installedVersion = installed,
                    latestVersion = upstream,
                    targetVersion = target,
                    checkedAt = if (remote) now() else previous?.checkedAt,
                    // A fresh local probe replaces a previous local failure.
                    // Only a registry error needs another remote check to clear.
                    error = error ?: if (!remote) previous?.error?.takeIf {
                        it == "registry_unavailable"
                    } else null,
                    pinned = pinned,
                ))
            } }.awaitAll()
        }
    }
}

/** Small dist-tags response rather than downloading the full npm version history. */
internal object NpmAgentVersions {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun latest(packageName: String): String = withContext(Dispatchers.IO) {
        val url = "https://registry.npmjs.org/-/package/${packageName.replace("/", "%2f")}/dist-tags"
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "npm registry HTTP ${response.code}" }
            val version = JsonParser.parseString(response.body?.string().orEmpty())
                .asJsonObject.get("latest")?.asString
            requireNotNull(version?.takeIf { compareAgentVersions(it, it) != null }) {
                "npm registry returned no valid release version"
            }
        }
    }
}

/** Runs bounded, read-only CLI --version probes inside the selected terminal distribution. */
internal fun managedAgentVersionProbeCommand(profiles: List<AcpAgentProfile>): String {
    val targets = profiles.map { mapOf("id" to it.id, "command" to it.officialRuntime!!.discoveryCommand) }
    val script = """
        const {execFile} = require('node:child_process');
        const targets = ${Gson().toJson(targets)};
        Promise.all(targets.map(t => new Promise(resolve => {
          execFile(t.command, ['--version'], {timeout: 8000, maxBuffer: 32768}, (error, stdout, stderr) => {
            if (!error) return resolve([t.id, (stdout || stderr).trim()]);
            if (error.code === 'ENOENT') return resolve([t.id, null]);
            resolve(null);
          });
        }))).then(rows => console.log('__OMNI_AGENT_VERSIONS__' + JSON.stringify(Object.fromEntries(rows.filter(Boolean)))));
    """.trimIndent()
    return "export PATH=/root/.npm-global/bin:${'$'}PATH\nnode -e '" + script.replace("'", "'\\''") + "'"
}

internal fun parseManagedAgentVersionProbe(output: String): Map<String, String?> {
    val row = output.lineSequence().firstOrNull { it.startsWith("__OMNI_AGENT_VERSIONS__") }
        ?: error("Agent version probe did not complete")
    return JsonParser.parseString(row.removePrefix("__OMNI_AGENT_VERSIONS__")).asJsonObject
        .entrySet().mapNotNull { (id, value) ->
            if (value.isJsonNull) id to null
            else parseAgentVersion(value.asString)?.let { id to it }
        }.toMap()
}
