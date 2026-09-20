package cn.com.omnimind.bot.omniflow

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream

/** Public package boundary; dependencies and internal data formats belong to the package. */
data class OmniFlowRuntimeManifest(
    val interfaceVersion: Int,
    val version: String,
    val protocol: String,
    val entrypoint: String,
    val prepareEntrypoint: String,
    val sourceRoot: String,
    val tools: List<RuntimeTool>,
)

data class RuntimeTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
    val interactive: Boolean = false,
    val agentVisible: Boolean = true,
    val hostAction: String? = null,
)

fun parseOmniFlowRuntimeManifest(input: InputStream): OmniFlowRuntimeManifest {
    val json = input.bufferedReader().use { JsonParser.parseReader(it) }
    require(json.isJsonObject) { "runtime_manifest_invalid" }
    val manifest = Gson().fromJson(json, OmniFlowRuntimeManifest::class.java)
    require(manifest.interfaceVersion == 1) { "runtime_host_interface_unsupported" }
    require(!manifest.version.isNullOrBlank() && !manifest.protocol.isNullOrBlank()) {
        "runtime_identity_missing"
    }
    listOf(manifest.entrypoint, manifest.prepareEntrypoint, manifest.sourceRoot)
        .forEach(::validateRuntimeRelativePath)
    require(!manifest.tools.isNullOrEmpty()) { "runtime_tools_missing" }
    require(manifest.tools.map { it.name }.distinct().size == manifest.tools.size) {
        "runtime_tools_duplicate"
    }
    manifest.tools.forEachIndexed { index, tool ->
        val schema = json.asJsonObject.getAsJsonArray("tools")[index].asJsonObject.get("inputSchema")
        require(!tool.name.isNullOrBlank() && !tool.description.isNullOrBlank() && schema?.isJsonObject == true) {
            "runtime_tool_invalid"
        }
    }
    // Gson bypasses Kotlin constructor defaults when a boolean field is absent.
    return manifest.copy(tools = manifest.tools.mapIndexed { index, tool ->
        val definition = json.asJsonObject.getAsJsonArray("tools")[index].asJsonObject
        tool.copy(agentVisible = definition.get("agentVisible")?.asBoolean ?: true)
    })
}

internal fun validateRuntimeRelativePath(value: String?) {
    require(!value.isNullOrBlank() && !File(value).isAbsolute &&
        value.matches(Regex("[A-Za-z0-9_./-]+")) &&
        value.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
        "runtime_relative_path_invalid"
    }
}

internal fun runtimeFile(root: File, path: String): File {
    validateRuntimeRelativePath(path)
    return File(root, path).canonicalFile.also {
        require(it.path.startsWith(root.canonicalPath + File.separator)) { "runtime_path_escape" }
    }
}

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
