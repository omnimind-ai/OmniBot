package cn.com.omnimind.bot.agent.runtime

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

/**
 * Host-side state for the official DSH profile plugin surface.
 *
 * DSH itself still owns loading and composing plugins. This type only keeps
 * the profile manifest needed by the Android host; it is deliberately not a
 * second plugin protocol or a Flutter task model.
 */
internal data class DshPluginRecord(
    val id: String,
    val packageName: String,
    val specifier: String,
    val enabled: Boolean = true,
    val installedAt: Long? = null
)

internal object DshPluginManager {
    const val MANIFEST_PATH = "/root/.dsh/omnibot-acp/plugins.json"
    const val PROFILE_PATH = "/root/.dsh/omnibot-acp"

    private val packagePattern = Regex(
        "^(?:@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*(?:@(?![./])[a-zA-Z0-9][a-zA-Z0-9._~-]*)?$"
    )

    fun normalizeSpecifier(raw: String): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "Plugin package is required." }
        require(!value.startsWith("-")) { "Plugin package cannot start with '-'." }
        require(value.length <= 240) { "Plugin package specifier is too long." }
        require(value.none { it.isWhitespace() || it == ';' || it == '|' || it == '&' }) {
            "Plugin package specifier contains unsupported characters."
        }
        require(packagePattern.matches(value)) {
            "Only npm package names with an optional exact version are supported."
        }
        return value
    }

    fun packageName(specifier: String): String {
        val normalized = normalizeSpecifier(specifier)
        return if (normalized.startsWith("@")) {
            val versionSeparator = normalized.indexOf('@', startIndex = 1)
            if (versionSeparator > 0) normalized.substring(0, versionSeparator) else normalized
        } else {
            normalized.substringBefore('@')
        }
    }

    fun pluginId(packageName: String): String =
        packageName.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    fun parse(content: String): List<DshPluginRecord> {
        val root = runCatching {
            JsonParser.parseString(content).takeIf { it.isJsonArray }?.asJsonArray
        }.getOrNull() ?: return emptyList()
        return root.mapNotNull { element ->
            val objectValue = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val packageName = objectValue.get("packageName")?.asString?.trim().orEmpty()
            val specifier = objectValue.get("specifier")?.asString?.trim().orEmpty()
            if (packageName.isBlank() || specifier.isBlank()) return@mapNotNull null
            runCatching {
                DshPluginRecord(
                    id = objectValue.get("id")?.asString?.trim().takeUnless { it.isNullOrBlank() }
                        ?: pluginId(packageName),
                    packageName = packageName,
                    specifier = normalizeSpecifier(specifier),
                    enabled = objectValue.get("enabled")?.asBoolean ?: true,
                    installedAt = objectValue.get("installedAt")?.asLong
                )
            }.getOrNull()
        }.distinctBy { it.packageName }
    }

    fun encode(records: List<DshPluginRecord>): String =
        GsonBuilder().setPrettyPrinting().create().toJson(records) + "\n"

    fun toPayload(record: DshPluginRecord): Map<String, Any?> = linkedMapOf(
        "id" to record.id,
        "packageName" to record.packageName,
        "specifier" to record.specifier,
        "enabled" to record.enabled,
        "installedAt" to record.installedAt
    )

    /**
     * Official DSH modules are mounted by the host composition itself.  A
     * package with the same name in the user manifest must not be mounted a
     * second time: Cordis treats that as a separate loader entry, so a
     * user-installed copy without the official config can make the whole
     * plugin tree fail during startup.
     */
    fun cordisEntries(
        records: List<DshPluginRecord>,
        excludedPackageNames: Set<String> = emptySet()
    ): String = records
        .filter { it.enabled }
        .filterNot { it.packageName in excludedPackageNames }
        .joinToString(separator = "\n") { record ->
            // packageName has already passed the npm package validator, so it
            // cannot inject YAML or a shell command here.
            """    - id: user-${record.id}
      name: '${record.packageName}'"""
        }

    fun installCommand(specifier: String): String {
        val normalized = normalizeSpecifier(specifier)
        return """
            set -eu
            mkdir -p ${shellQuote(PROFILE_PATH)}
            if [ ! -f ${shellQuote("$PROFILE_PATH/package.json")} ]; then
              printf '%s\n' '{"private":true,"name":"omnibot-dsh-profile"}' > ${shellQuote("$PROFILE_PATH/package.json")}
            fi
            npm install --prefix ${shellQuote(PROFILE_PATH)} --save-exact --no-audit --no-fund ${shellQuote(normalized)}
            test -f ${shellQuote("$PROFILE_PATH/node_modules/${packageName(normalized)}/package.json")}
        """.trimIndent()
    }

    fun uninstallCommand(packageName: String): String {
        require(packageName.matches(Regex("^(?:@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*$"))) {
            "Invalid plugin package name."
        }
        return "npm uninstall --prefix ${shellQuote(PROFILE_PATH)} --no-audit --no-fund ${shellQuote(packageName)}"
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
