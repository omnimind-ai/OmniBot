package cn.com.omnimind.bot.omniflow

import android.content.Context
import java.io.File
import java.security.MessageDigest

data class OmniFlowDeveloperOverrideStatus(
    val enabled: Boolean,
    val androidRoot: String,
    val shellRoot: String,
    val runtimeVersion: String,
    val modifiedFiles: List<String>,
)

internal class OmniFlowDeveloperOverrideStore(
    private val root: File,
) {
    private val pythonRoot = File(root, "python")
    private val packageRoot = File(pythonRoot, "omniflow")
    private val versionFile = File(root, ".runtime_version")
    private val modifiedFile = File(root, ".modified_files")

    fun status(runtimeVersion: String): OmniFlowDeveloperOverrideStatus {
        val modifiedPaths = modifiedPaths()
        return OmniFlowDeveloperOverrideStatus(
            enabled = packageRoot.isDirectory && modifiedPaths.isNotEmpty(),
            androidRoot = pythonRoot.absolutePath,
            shellRoot = SHELL_ROOT,
            runtimeVersion = runtimeVersion,
            modifiedFiles = modifiedPaths.toList(),
        )
    }

    fun read(relativePath: String): String {
        val path = normalizedPythonPath(relativePath)
        val file = File(pythonRoot, path)
        require(file.isFile) { "omniflow_override_file_missing:$path" }
        return file.readText()
    }

    fun apply(
        basePythonRoot: File,
        runtimeVersion: String,
        relativePath: String,
        content: String,
    ): File {
        require(content.toByteArray().size <= MAX_FILE_BYTES) {
            "omniflow_override_file_too_large"
        }
        val path = normalizedPythonPath(relativePath)
        ensureBase(basePythonRoot, runtimeVersion)
        val target = File(pythonRoot, path)
        require(target.canonicalPath.startsWith(packageRoot.canonicalPath + File.separator)) {
            "omniflow_override_path_escape"
        }
        target.parentFile?.mkdirs()
        atomicWrite(target, content)
        writeModifiedPaths(modifiedPaths() + path)
        return target
    }

    fun rebaseIfPresent(basePythonRoot: File, runtimeVersion: String) {
        if (root.exists()) ensureBase(basePythonRoot, runtimeVersion)
    }

    fun restore(relativePath: String, previous: String?, keepModified: Boolean) {
        val path = normalizedPythonPath(relativePath)
        val target = File(pythonRoot, path)
        if (previous == null) {
            target.delete()
        } else {
            atomicWrite(target, previous)
        }
        writeModifiedPaths(
            if (keepModified) modifiedPaths() + path else modifiedPaths() - path,
        )
    }

    fun clear(): Boolean = !root.exists() || root.deleteRecursively()

    private fun ensureBase(basePythonRoot: File, runtimeVersion: String) {
        val sourcePackage = File(basePythonRoot, "omniflow")
        val sourceSchemas = File(basePythonRoot, "schemas")
        require(sourcePackage.isDirectory) { "omniflow_override_base_missing" }
        if (
            packageRoot.isDirectory &&
            versionFile.takeIf(File::isFile)?.readText()?.trim() == runtimeVersion &&
            (!sourceSchemas.isDirectory || File(pythonRoot, "schemas").isDirectory)
        ) {
            return
        }
        val preserved = modifiedPaths().associateWith { path ->
            File(pythonRoot, path).takeIf(File::isFile)?.readBytes()
        }
        val temporary = File(root.parentFile, "${root.name}.tmp-${System.nanoTime()}")
        temporary.deleteRecursively()
        val temporaryPackage = File(temporary, "python/omniflow")
        require(sourcePackage.copyRecursively(temporaryPackage, overwrite = true)) {
            "omniflow_override_base_copy_failed"
        }
        if (sourceSchemas.isDirectory) {
            require(
                sourceSchemas.copyRecursively(
                    File(temporary, "python/schemas"),
                    overwrite = true,
                ),
            ) { "omniflow_override_schema_copy_failed" }
        }
        preserved.forEach { (path, bytes) ->
            bytes ?: return@forEach
            File(temporary, "python/$path").apply {
                parentFile?.mkdirs()
                writeBytes(bytes)
            }
        }
        File(temporary, ".runtime_version").writeText(runtimeVersion)
        File(temporary, ".modified_files").writeText(
            preserved.keys.sorted().joinToString(separator = "\n", postfix = "\n"),
        )
        root.deleteRecursively()
        require(temporary.renameTo(root)) { "omniflow_override_install_failed" }
    }

    private fun modifiedPaths(): Set<String> = modifiedFile.takeIf(File::isFile)
        ?.readLines()
        .orEmpty()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSortedSet()

    private fun writeModifiedPaths(paths: Set<String>) {
        root.mkdirs()
        modifiedFile.writeText(
            paths.sorted().joinToString(separator = "\n", postfix = "\n"),
        )
    }

    companion object {
        const val SHELL_ROOT = "/workspace/.omnibot/omniflow-developer/python"
        private const val MAX_FILE_BYTES = 256 * 1024
    }
}

internal fun normalizedPythonPath(value: String): String {
    val normalized = value.trim().replace('\\', '/').removePrefix("./")
    val path = if (normalized.startsWith("omniflow/")) normalized else "omniflow/$normalized"
    require(path.endsWith(".py")) { "omniflow_override_python_file_required" }
    require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "omniflow_override_path_invalid"
    }
    require(path.matches(Regex("omniflow/[A-Za-z0-9_./-]+\\.py"))) {
        "omniflow_override_path_invalid"
    }
    return path
}

private fun atomicWrite(target: File, content: String) {
    val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
    temporary.writeText(content)
    require(temporary.renameTo(target)) { "omniflow_override_atomic_write_failed" }
}

internal fun developerOverrideRoot(context: Context): File =
    File(omniFlowInternalRoot(context), "omniflow-developer")

internal fun sha256Text(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
