package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/**
 * Declarative ACP Harness catalog.
 *
 * A catalog entry describes how an official process is discovered and
 * prepared. The runtime only interprets this data and selects the shared ACP
 * capability seam; adding an entry must not require a new branch in the
 * conversation/session/turn lifecycle.
 */
internal object AcpAgentCatalog {
    private const val ASSET_PATH = "acp/agents.json"
    private val gson = Gson()

    fun load(context: Context): Catalog {
        val source = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        return parse(source) { assetPath ->
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        }
    }

    internal fun parse(
        source: String,
        assetReader: ((String) -> String)? = null,
    ): Catalog {
        val root = JsonParser.parseString(source).asJsonObject
        val version = root.get("version")?.asInt ?: 0
        require(version == 1) { "Unsupported ACP Agent catalog version: $version" }
        val retired = root.getAsJsonArray("retiredAgentIds")
            ?.mapNotNull { it.asString?.trim()?.takeIf(String::isNotEmpty) }
            ?.toSet()
            .orEmpty()
        val entries = root.getAsJsonArray("agents")?.map { it.asJsonObject } ?: emptyList()
        require(entries.isNotEmpty()) { "ACP Agent catalog is empty." }
        val profiles = entries.map { entry ->
            val runtime = entry.getAsJsonObject("runtime")?.toRuntime(assetReader)
            val profile = AcpAgentProfile(
                id = entry.requiredString("id"),
                name = entry.requiredString("name"),
                description = entry.string("description").orEmpty(),
                command = entry.requiredString("command"),
                arguments = entry.getAsJsonArray("arguments")
                    ?.map { it.asString }
                    .orEmpty(),
                enabled = entry.get("enabled")?.asBoolean ?: true,
                builtIn = entry.get("builtIn")?.asBoolean ?: true,
                officialRuntime = runtime,
            )
            require(profile.builtIn) { "Catalog Agent ${profile.id} must be built-in." }
            profile
        }
        require(profiles.map { it.id }.distinct().size == profiles.size) {
            "ACP Agent catalog contains duplicate ids."
        }
        return Catalog(profiles, retired)
    }

    internal data class Catalog(
        val agents: List<AcpAgentProfile>,
        val retiredAgentIds: Set<String>,
    ) {
        val officialIds: Set<String> = agents.mapTo(linkedSetOf()) { it.id }

        fun definition(id: String): AcpAgentProfile? = agents.firstOrNull { it.id == id }
    }

    private fun JsonObject.toRuntime(assetReader: ((String) -> String)?): AcpOfficialRuntime {
        val packages = getAsJsonArray("managedAdapterPackages")
            ?.map { it.asString }
            .orEmpty()
        val singlePackage = string("managedAdapterPackage")
        val resolvedManagedPackage = singlePackage ?: packages.lastOrNull()
        val installCommand = string("managedInstallCommand")
            ?: string("managedInstallCommandAsset")?.let { assetPath ->
                assetReader?.invoke(assetPath)
            }
        return AcpOfficialRuntime(
            discoveryCommand = requiredString("discoveryCommand"),
            managedAdapterPackage = resolvedManagedPackage,
            managedAdapterPackages = packages.ifEmpty {
                resolvedManagedPackage?.let(::listOf).orEmpty()
            },
            requiresNativeBuildTools = get("requiresNativeBuildTools")?.asBoolean ?: false,
            managedAdapterHealthCommand = string("managedAdapterHealthCommand"),
            harnessAdapter = AcpHarnessAdapters.forConfigAdapterId(string("configAdapterId")),
            usesSharedProvider = get("usesSharedProvider")?.asBoolean ?: false,
            terminalPackageId = string("terminalPackageId"),
            managedInstallScriptPath = string("managedInstallScriptPath"),
            managedInstallCommand = installCommand,
            preparationRevision = string("preparationRevision"),
            embedded = get("embedded")?.asBoolean ?: false,
            declaredCapabilities = getAsJsonObject("declaredCapabilities")?.let {
                gson.fromJson<Map<String, Any?>>(
                    it,
                    object : TypeToken<Map<String, Any?>>() {}.type,
                )
            }.orEmpty(),
        )
    }

    private fun JsonObject.requiredString(key: String): String =
        string(key) ?: throw IllegalArgumentException("ACP Agent catalog field '$key' is required.")

    private fun JsonObject.string(key: String): String? = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.trim()
        ?.takeIf(String::isNotEmpty)
}
