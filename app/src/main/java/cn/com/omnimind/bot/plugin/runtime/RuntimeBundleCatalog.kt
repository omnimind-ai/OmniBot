package cn.com.omnimind.bot.plugin.runtime

import android.content.res.AssetManager
import cn.com.omnimind.bot.plugin.OmniPluginContract
import cn.com.omnimind.bot.plugin.OmniPluginDescriptor
import cn.com.omnimind.bot.plugin.OmniPluginKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

data class RuntimeBundleDefinition(
    val descriptor: OmniPluginDescriptor,
    val adapterId: String,
    val runtimeSkill: RuntimeSkillSpec,
)

class RuntimeBundleCatalog private constructor(
    val bundles: List<RuntimeBundleDefinition>,
) {
    fun require(pluginId: String): RuntimeBundleDefinition =
        bundles.firstOrNull { it.descriptor.id == pluginId }
            ?: throw IllegalArgumentException("Runtime bundle is not declared: $pluginId")

    /**
     * Apply remote metadata to the host catalog while retaining the APK's
     * packaged baseline and locally declared plugin set.
     */
    internal fun mergeRemote(remote: RuntimeBundleCatalog): RuntimeBundleCatalog {
        val remoteById = remote.bundles.associateBy { it.descriptor.id }
        return RuntimeBundleCatalog(
            bundles.map { local ->
                val candidate = remoteById[local.descriptor.id]
                    ?: return@map local
                if (compareVersions(candidate.descriptor.version, local.descriptor.version) < 0) {
                    return@map local
                }
                local.copy(
                    descriptor = candidate.descriptor.copy(
                        required = local.descriptor.required || candidate.descriptor.required,
                        installByDefault =
                            local.descriptor.installByDefault || candidate.descriptor.installByDefault,
                        settingsSchema = candidate.descriptor.settingsSchema.takeUnless { it.isEmpty() }
                            ?: local.descriptor.settingsSchema,
                        presentation = candidate.descriptor.presentation.takeUnless { it.isEmpty() }
                            ?: local.descriptor.presentation,
                    ),
                    runtimeSkill = candidate.runtimeSkill.copy(
                        packagedAssetPath = local.runtimeSkill.packagedAssetPath
                            ?: candidate.runtimeSkill.packagedAssetPath,
                        packagedArchivePath = local.runtimeSkill.packagedArchivePath
                            ?: candidate.runtimeSkill.packagedArchivePath,
                        packagedArchiveSha256 = local.runtimeSkill.packagedArchiveSha256
                            ?: candidate.runtimeSkill.packagedArchiveSha256,
                    ).validated(),
                )
            },
        )
    }

    companion object {
        private const val ASSET_PATH = "catalog.v1.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun load(assets: AssetManager, profile: String): RuntimeBundleCatalog {
            val source = assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            return parse(source, profile)
        }

        internal fun parse(source: String, profile: String? = null): RuntimeBundleCatalog {
            val catalog = json.decodeFromString<RuntimeBundleCatalogWire>(source)
            require(catalog.schemaVersion == 1) {
                "Unsupported runtime bundle catalog schema: ${catalog.schemaVersion}"
            }
            catalog.plugins.forEach(RuntimeBundlePluginWire::validateProfiles)
            val bundles = catalog.plugins
                .filter { plugin -> profile == null || profile in plugin.profiles }
                .map(RuntimeBundlePluginWire::toDefinition)
            val duplicateId = bundles.groupBy { it.descriptor.id }
                .entries.firstOrNull { it.value.size > 1 }
                ?.key
            require(duplicateId == null) { "Duplicate runtime bundle id: $duplicateId" }
            return RuntimeBundleCatalog(bundles)
        }

        private fun compareVersions(left: String, right: String): Int {
            val leftParts = left.substringBefore('-').substringBefore('+')
                .split('.').map { it.toIntOrNull() ?: 0 }
            val rightParts = right.substringBefore('-').substringBefore('+')
                .split('.').map { it.toIntOrNull() ?: 0 }
            return (0 until maxOf(leftParts.size, rightParts.size))
                .asSequence()
                .map { index -> (leftParts.getOrNull(index) ?: 0).compareTo(rightParts.getOrNull(index) ?: 0) }
                .firstOrNull { it != 0 }
                ?: 0
        }
    }
}

@Serializable
private data class RuntimeBundleCatalogWire(
    val schemaVersion: Int = 0,
    val plugins: List<RuntimeBundlePluginWire> = emptyList(),
)

@Serializable
private data class RuntimeBundlePluginWire(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val interfaceVersion: Int = OmniPluginContract.CURRENT_INTERFACE_VERSION,
    val description: String = "",
    val publisher: String = "",
    val kind: String = OmniPluginKind.RUNTIME_BUNDLE.wireName,
    val downloadSizeBytes: Long = 0,
    val capabilities: List<String> = emptyList(),
    val required: Boolean = false,
    val installByDefault: Boolean = false,
    val settingsSchema: JsonObject = JsonObject(emptyMap()),
    val presentation: JsonObject = JsonObject(emptyMap()),
    val adapter: String = "",
    val runtimeSkill: RuntimeSkillWire = RuntimeSkillWire(),
    val profiles: List<String> = listOf("main", "investor"),
) {
    fun validateProfiles() {
        require(profiles.isNotEmpty() && profiles.all(PROFILE::matches)) {
            "Runtime bundle $id declares invalid profiles"
        }
    }

    fun toDefinition(): RuntimeBundleDefinition {
        require(PLUGIN_ID.matches(id)) { "Invalid runtime bundle id: $id" }
        require(name.isNotBlank()) { "Runtime bundle $id has no name" }
        require(version.isNotBlank()) { "Runtime bundle $id has no version" }
        require(publisher.isNotBlank()) { "Runtime bundle $id has no publisher" }
        require(downloadSizeBytes >= 0) { "Runtime bundle $id has a negative download size" }
        require(kind == OmniPluginKind.RUNTIME_BUNDLE.wireName) {
            "Runtime bundle $id declares unsupported kind: $kind"
        }
        require(adapter.isNotBlank()) { "Runtime bundle $id has no adapter" }
        return RuntimeBundleDefinition(
            descriptor = OmniPluginDescriptor(
                id = id,
                name = name,
                version = version,
                interfaceVersion = interfaceVersion,
                description = description,
                publisher = publisher,
                kind = OmniPluginKind.RUNTIME_BUNDLE,
                downloadSizeBytes = downloadSizeBytes,
                capabilities = capabilities,
                required = required,
                installByDefault = installByDefault,
                settingsSchema = settingsSchema,
                presentation = presentation,
            ),
            adapterId = adapter,
            runtimeSkill = runtimeSkill.toSpec(id, version),
        )
    }

    private companion object {
        val PLUGIN_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+$")
        val PROFILE = Regex("^[a-z][a-z0-9-]*$")
    }
}

@Serializable
private data class RuntimeSkillWire(
    val id: String = "",
    val packagedAssetPath: String? = null,
    val packagedArchivePath: String? = null,
    val packagedArchiveSha256: String? = null,
    val markerFile: String = "PACKAGED_RUNTIME_SKILL",
    val componentArchiveUrl: String? = null,
    val componentArchiveSha256: String? = null,
    val installTimeoutSeconds: Int = 15 * 60,
) {
    fun toSpec(componentId: String, componentVersion: String): RuntimeSkillSpec = RuntimeSkillSpec(
        componentId = componentId,
        componentVersion = componentVersion,
        id = id,
        packagedAssetPath = packagedAssetPath,
        packagedArchivePath = packagedArchivePath,
        packagedArchiveSha256 = packagedArchiveSha256 ?: componentArchiveSha256
            .takeIf { !packagedArchivePath.isNullOrBlank() },
        markerFile = markerFile,
        componentArchiveUrl = componentArchiveUrl,
        componentArchiveSha256 = componentArchiveSha256,
        installTimeoutSeconds = installTimeoutSeconds,
    ).validated()
}
