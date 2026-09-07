package com.rk.libcommons

import android.content.Context
import android.net.ConnectivityManager
import android.util.AtomicFile
import java.net.InetAddress
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object OmnibotTerminalEnvironment {
    private const val PREFS_NAME = "omnibot_terminal_environment"
    private const val KEY_VARIABLES_JSON = "variables_json"
    private const val FLUTTER_PREFS_NAME = "FlutterSharedPreferences"
    private const val FLUTTER_ENV_KEY = "flutter.chat_terminal_environment_variables"
    private const val ENV_FILE_NAME = "omnibot-user-env.sh"
    private const val MT_PACKAGE_NAME = "bin.mt.plus"
    private const val MT_LOCAL_STORAGE_PATH = "/sdcard"
    private const val MT_EMULATED_STORAGE_PATH = "/storage/emulated/0"
    private const val MT_APP_SPECIFIC_STORAGE_PATH =
        "$MT_EMULATED_STORAGE_PATH/Android/data/$MT_PACKAGE_NAME/files"
    private const val MT_STORAGE_MOUNT_PATH = "/mnt/mt"
    private const val MT_STORAGE_SHORTCUT_PATH = "/mt"
    private val envKeyPattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun buildTerminalEnvironment(context: Context): Map<String, String> {
        val appContext = context.applicationContext
        val userVariables = loadUserVariables(appContext)
        return linkedMapOf<String, String>().apply {
            putAll(buildDefaultStorageEnvironment())
            activeResolverFile(appContext)?.let { put("OMNIBOT_DNS_FILE", it.absolutePath) }
            putAll(userVariables)
            val envFile = ensureUserEnvFile(appContext, userVariables)
            put("OMNIBOT_USER_ENV_FILE", envFile.absolutePath)
        }
    }

    // Use the phone's selected network (including its VPN), not a fixed public
    // resolver. Both interactive terminals and hidden Harness launches reuse
    // this existing environment boundary; no polling or parallel lifecycle.
    private fun activeResolverFile(context: Context): File? = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching null
        val network = connectivity.activeNetwork ?: return@runCatching null
        val addresses = connectivity.getLinkProperties(network)?.dnsServers.orEmpty()
        val content = buildResolverConfig(addresses)
        if (content.isEmpty()) return@runCatching null
        val file = File(context.filesDir.parentFile, "local/omnibot-resolv.conf")
        file.parentFile?.mkdirs()
        synchronized(this) {
            if (!file.exists() || file.readText() != content) {
                val atomic = AtomicFile(file)
                val output = atomic.startWrite()
                try {
                    output.write(content.toByteArray(Charsets.UTF_8))
                    atomic.finishWrite(output)
                } catch (error: Throwable) {
                    atomic.failWrite(output)
                    throw error
                }
            }
        }
        file
    }.getOrNull()

    internal fun buildResolverConfig(addresses: List<InetAddress>): String =
        addresses.mapNotNull { it.hostAddress }.distinct().take(3)
            .joinToString(separator = "", transform = { "nameserver $it\n" })

    fun loadUserVariables(context: Context): Map<String, String> {
        val appContext = context.applicationContext
        val nativeJson = prefs(appContext).getString(KEY_VARIABLES_JSON, null)
        val nativeVariables = parseVariablesJson(nativeJson)
        if (nativeVariables.isNotEmpty() || nativeJson != null) {
            return nativeVariables
        }

        val flutterJson = appContext
            .getSharedPreferences(FLUTTER_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(FLUTTER_ENV_KEY, null)
        val flutterVariables = parseVariablesJson(flutterJson)
        if (flutterVariables.isNotEmpty()) {
            saveUserVariables(appContext, flutterVariables)
        }
        return flutterVariables
    }

    fun saveUserVariables(context: Context, variables: Map<String, String>): Map<String, String> {
        val normalized = normalizeVariables(variables)
        val payload = JSONArray()
        normalized.forEach { (key, value) ->
            payload.put(JSONObject().apply {
                put("key", key)
                put("value", value)
            })
        }
        prefs(context.applicationContext)
            .edit()
            .putString(KEY_VARIABLES_JSON, payload.toString())
            .apply()
        ensureUserEnvFile(context.applicationContext, normalized)
        return normalized
    }

    fun normalizeVariables(variables: Map<String, String>): Map<String, String> {
        val normalized = linkedMapOf<String, String>()
        variables.forEach { (rawKey, value) ->
            val key = rawKey.trim()
            if (
                key.isEmpty() ||
                !envKeyPattern.matches(key)
            ) {
                return@forEach
            }
            normalized.remove(key)
            normalized[key] = value
        }
        return normalized
    }

    fun mtStorageHostPath(): String {
        return resolveMtStorageHostPath()
    }

    fun mtAppSpecificStorageHostPath(): String {
        return MT_APP_SPECIFIC_STORAGE_PATH
    }

    fun mtStorageMountPath(): String = MT_STORAGE_MOUNT_PATH

    fun mtStorageShortcutPath(): String = MT_STORAGE_SHORTCUT_PATH

    internal fun buildShellExportScript(variables: Map<String, String>): String {
        return buildString {
            appendLine("# Generated by Omnibot. Manual edits may be overwritten.")
            variables.forEach { (key, value) ->
                append("export ")
                append(key)
                append("=")
                append(quoteForShell(value))
                append('\n')
            }
        }
    }

    private fun buildDefaultStorageEnvironment(): Map<String, String> {
        val mtHostPath = mtStorageHostPath()
        return linkedMapOf(
            "OMNIBOT_MT_STORAGE_HOST" to mtHostPath,
            "OMNIBOT_MT_STORAGE_LEGACY_HOST" to MT_APP_SPECIFIC_STORAGE_PATH,
            "OMNIBOT_MT_STORAGE" to MT_STORAGE_MOUNT_PATH,
            "MT_STORAGE" to MT_STORAGE_MOUNT_PATH
        )
    }

    private fun ensureUserEnvFile(context: Context, variables: Map<String, String>): File {
        val scriptFile = File(context.filesDir.parentFile, "local/bin/$ENV_FILE_NAME")
        scriptFile.parentFile?.mkdirs()
        val content = buildShellExportScript(normalizeVariables(variables))
        if (!scriptFile.exists() || scriptFile.readText() != content) {
            scriptFile.writeText(content)
        }
        scriptFile.setReadable(true, false)
        scriptFile.setExecutable(false, false)
        return scriptFile
    }

    private fun parseVariablesJson(jsonText: String?): Map<String, String> {
        val raw = jsonText?.trim().orEmpty()
        if (raw.isEmpty()) {
            return emptyMap()
        }
        return runCatching {
            val items = JSONArray(raw)
            val parsed = linkedMapOf<String, String>()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val key = item.optString("key", "").trim()
                if (key.isEmpty() || !envKeyPattern.matches(key)) {
                    continue
                }
                parsed.remove(key)
                parsed[key] = item.optString("value", "")
            }
            parsed
        }.getOrDefault(emptyMap())
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun resolveMtStorageHostPath(): String {
        val candidates = linkedSetOf<String>().apply {
            add(MT_LOCAL_STORAGE_PATH)
            add(MT_EMULATED_STORAGE_PATH)
            add(MT_APP_SPECIFIC_STORAGE_PATH)
        }
        return candidates.firstOrNull(::isUsableDirectory) ?: MT_LOCAL_STORAGE_PATH
    }

    private fun isUsableDirectory(path: String): Boolean {
        return runCatching {
            val dir = File(path)
            dir.isDirectory && dir.canRead()
        }.getOrDefault(false)
    }

    private fun quoteForShell(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

}
