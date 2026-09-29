package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager

/**
 * Native adapter for the Remote PC Bridge settings page over the existing
 * `AgentRuntimeManager.handleMethod` boundary. The store, the probe and the
 * remote-session teardown after a config write stay in the runtime. The bridge
 * token is never logged and never appears in a string form.
 */
internal class NativeRemoteBridgeRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

    private fun runtime() = AgentRuntimeManager.getInstance(appContext)

    suspend fun read(): NativeRemoteBridgeConfig = parseConfig(
        runtime().handleMethod("config/remote/read", emptyMap())
    )

    /**
     * Writes the config and refreshes the `flutter.remote_bridge_enabled`
     * first-frame cache read by the Agents page, exactly as the Flutter page
     * does after `config/remote/read`.
     */
    suspend fun write(config: NativeRemoteBridgeConfig): NativeRemoteBridgeConfig {
        val saved = parseConfig(
            runtime().handleMethod(
                "config/remote/write",
                mapOf(
                    "remoteEnabled" to config.enabled,
                    "remoteBridgeUrl" to config.bridgeUrl.trim(),
                    "remoteBridgeToken" to config.token.trim(),
                    "remoteCwd" to config.cwd.trim(),
                ),
            )
        )
        preferences.edit().putBoolean(KEY_REMOTE_BRIDGE_ENABLED, saved.enabled).apply()
        return saved
    }

    /** Probes the bridge with the form's current values; nothing is persisted. */
    suspend fun test(config: NativeRemoteBridgeConfig): RemoteBridgeProbe {
        val root = runtime().handleMethod(
            "config/remote/test",
            mapOf(
                "remoteBridgeUrl" to config.bridgeUrl.trim(),
                "remoteBridgeToken" to config.token.trim(),
                "remoteCwd" to config.cwd.trim(),
            ),
        ) as? Map<*, *> ?: emptyMap<String, Any?>()
        return RemoteBridgeProbe(ok = root["ok"] == true || root["ready"] == true)
    }

    suspend fun listDirectories(
        config: NativeRemoteBridgeConfig,
        path: String,
    ): RemoteDirectoryListing {
        val root = runtime().handleMethod(
            "config/remote/fs/list",
            mapOf(
                "remoteBridgeUrl" to config.bridgeUrl.trim(),
                "remoteBridgeToken" to config.token.trim(),
                "remoteCwd" to config.cwd.trim(),
                "path" to path.trim(),
            ),
        ) as? Map<*, *> ?: emptyMap<String, Any?>()
        val entries = (root["entries"] as? List<*>).orEmpty().mapNotNull { raw ->
            val entry = raw as? Map<*, *> ?: return@mapNotNull null
            val name = (entry["name"] as? String).orEmpty()
            val entryPath = (entry["path"] as? String).orEmpty()
            if (name.isEmpty() || entryPath.isEmpty()) return@mapNotNull null
            if ((entry["type"] as? String) != "directory") return@mapNotNull null
            RemoteDirectoryEntry(name = name, path = entryPath)
        }
        return RemoteDirectoryListing(
            ok = root["ok"] == true,
            path = (root["path"] as? String).orEmpty(),
            parent = (root["parent"] as? String)?.takeIf(String::isNotBlank),
            home = (root["home"] as? String)?.takeIf(String::isNotBlank),
            entries = entries,
        )
    }

    private fun parseConfig(payload: Any?): NativeRemoteBridgeConfig {
        val root = payload as? Map<*, *> ?: emptyMap<String, Any?>()
        return NativeRemoteBridgeConfig(
            enabled = root["remoteEnabled"] == true,
            bridgeUrl = (root["remoteBridgeUrl"] as? String).orEmpty(),
            token = (root["remoteBridgeToken"] as? String).orEmpty(),
            cwd = (root["remoteCwd"] as? String).orEmpty(),
        )
    }

    private companion object {
        // Same SharedPreferences key the Flutter page maintains for its first frame.
        const val KEY_REMOTE_BRIDGE_ENABLED = "flutter.remote_bridge_enabled"
    }
}

internal data class NativeRemoteBridgeConfig(
    val enabled: Boolean,
    val bridgeUrl: String,
    val token: String,
    val cwd: String,
) {
    override fun toString(): String =
        "NativeRemoteBridgeConfig(enabled=$enabled, url=$bridgeUrl, cwd=$cwd)"
}

internal data class RemoteBridgeProbe(val ok: Boolean)

internal data class RemoteDirectoryEntry(val name: String, val path: String)

internal data class RemoteDirectoryListing(
    val ok: Boolean,
    val path: String,
    val parent: String?,
    val home: String?,
    val entries: List<RemoteDirectoryEntry>,
)
