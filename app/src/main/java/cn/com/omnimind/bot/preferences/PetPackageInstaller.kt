package cn.com.omnimind.bot.preferences

import android.graphics.BitmapFactory
import cn.com.omnimind.uikit.loader.cat.PetSpriteAtlasSpec
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipInputStream

/** Validates the existing .codex-pet.zip contract before touching an installed package. */
internal class PetPackageInstaller {
    data class InstalledPet(val id: String, val name: String, val description: String,
        val atlas: File, val version: Int)

    /** Recover a replacement interrupted between moving the old and new directory. */
    fun recoverInterruptedInstall(petsRoot: File) {
        val root = petsRoot.takeIf { it.isDirectory }?.canonicalFile ?: return
        root.listFiles().orEmpty().sortedByDescending(File::lastModified).forEach { entry ->
            val name = entry.name
            val backup = name.startsWith(".previous-")
            val staged = name.startsWith(".import-")
            if (!backup && !staged) return@forEach
            val uuid = name.takeLast(36)
            if (runCatching { UUID.fromString(uuid) }.isFailure) return@forEach
            val id = name.removePrefix(if (backup) ".previous-" else ".import-")
                .removeSuffix("-$uuid")
            if (!PET_ID.matches(id)) return@forEach
            if (Files.isSymbolicLink(entry.toPath())) {
                entry.delete()
                return@forEach
            }
            val target = File(root, id)
            if (backup && !target.exists()) runCatching { move(entry, target) }
            else runCatching { removeTreeWithoutFollowingLinks(entry) }
        }
    }

    fun install(input: InputStream, petsRoot: File): InstalledPet {
        val archive = readBounded(input, MAX_ARCHIVE_BYTES, "Pet package exceeds 32 MB")
        val files = readZip(archive)
        val manifestEntry = files.entries.singleOrNull { it.key.substringAfterLast('/') == "pet.json" }
            ?: throw PetPackageException("Package must contain exactly one pet.json")
        if (manifestEntry.value.size > 1024 * 1024) throw PetPackageException("pet.json is too large")
        require(files.keys.count { it.substringAfterLast('/') == "pet.json" } == 1) {
            "Package must contain exactly one pet.json"
        }
        val manifest = runCatching { JSONObject(manifestEntry.value.toString(Charsets.UTF_8)) }
            .getOrElse { throw PetPackageException("pet.json is invalid") }
        fun field(key: String): String = manifest.opt(key)
            ?.takeUnless { it == JSONObject.NULL }?.toString()?.trim().orEmpty()
        val id = field("id")
        if (!PET_ID.matches(id)) throw PetPackageException("Pet id must use lowercase words and hyphens")
        val name = field("displayName").ifEmpty { field("name").ifEmpty { id } }
        if (name.isEmpty()) throw PetPackageException("Pet displayName is required")
        val description = field("description")
        val atlasName = field("spritesheetPath")
        if (!SAFE_NAME.matches(atlasName) || !(atlasName.endsWith(".webp", true) || atlasName.endsWith(".png", true))) {
            throw PetPackageException("spritesheetPath must name a PNG or WebP file")
        }
        val prefix = manifestEntry.key.substringBeforeLast('/', "")
        val atlasKey = if (prefix.isEmpty()) atlasName else "$prefix/$atlasName"
        val atlasBytes = files[atlasKey] ?: throw PetPackageException("Package is missing $atlasName")
        checkImageHeader(atlasName, atlasBytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(atlasBytes, 0, atlasBytes.size, bounds)
        val spec = PetSpriteAtlasSpec.detect(bounds.outWidth, bounds.outHeight)
            ?: throw PetPackageException("Pet atlas must be 1536×1872 or 1536×2288")
        val rawVersion = manifest.opt("spriteVersionNumber")
        val version = when (rawVersion) {
            null, JSONObject.NULL -> 1
            is Number -> rawVersion.toInt()
            is String -> rawVersion.trim().toIntOrNull()
            else -> null
        } ?: throw PetPackageException("spriteVersionNumber must be 1 or 2")
        if (version != spec.spriteVersionNumber) {
            throw PetPackageException("Atlas version does not match spriteVersionNumber")
        }
        require(petsRoot.isDirectory || petsRoot.mkdirs()) { "Pet directory is unavailable" }
        val root = petsRoot.canonicalFile
        val target = File(root, id)
        if (target.exists() && target.canonicalFile.parentFile != root) {
            throw PetPackageException("Unsafe existing pet directory")
        }
        val staging = File(root, ".import-$id-${UUID.randomUUID()}")
        val backup = File(root, ".previous-$id-${UUID.randomUUID()}")
        require(staging.mkdir()) { "Unable to stage pet package" }
        var movedPrevious = false
        var installed = false
        try {
            File(staging, atlasName).writeBytes(atlasBytes)
            manifest.put("id", id)
            manifest.put("displayName", name)
            manifest.put("description", description)
            manifest.put("spritesheetPath", atlasName)
            manifest.put("spriteVersionNumber", version)
            File(staging, "pet.json").writeText(manifest.toString(2))
            if (target.exists()) {
                move(target, backup)
                movedPrevious = true
            }
            move(staging, target)
            installed = true
            if (movedPrevious) runCatching { removeTreeWithoutFollowingLinks(backup) }
            return InstalledPet(id, name, description, File(target, atlasName), version)
        } finally {
            if (!installed) {
                runCatching { removeTreeWithoutFollowingLinks(staging) }
                if (movedPrevious && !target.exists()) runCatching { move(backup, target) }
            }
        }
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var extracted = 0L
        var entries = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > MAX_ENTRIES) throw PetPackageException("Too many pet package entries")
                val name = normalizeEntry(entry.name)
                if (!entry.isDirectory) {
                    val limit = MAX_EXTRACTED_BYTES - extracted
                    val content = readBounded(zip, limit, "Pet package exceeds 64 MB after extraction")
                    extracted += content.size
                    if (files.put(name, content) != null) throw PetPackageException("Duplicate pet package entry")
                }
                zip.closeEntry() // Also verifies the ZIP entry checksum.
            }
        }
        if (files.isEmpty()) throw PetPackageException("Pet package is empty")
        return files
    }

    private fun normalizeEntry(raw: String): String {
        val name = raw.trim().trimEnd('/')
        if (name.isEmpty() || name.startsWith('/') || name.contains('\\') || name.contains(':')) {
            throw PetPackageException("Unsafe pet package path")
        }
        val segments = name.split('/')
        if (segments.any { it.isEmpty() || it == ".." }) throw PetPackageException("Unsafe pet package path")
        return segments.filterNot { it == "." }.joinToString("/")
    }

    private fun readBounded(input: InputStream, limit: Long, failure: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            size += count
            if (size > limit) throw PetPackageException(failure)
            output.write(buffer, 0, count)
        }
    }

    private fun checkImageHeader(name: String, bytes: ByteArray) {
        val valid = if (name.endsWith(".png", true)) bytes.size >= 8 &&
            bytes.copyOfRange(0, 8).contentEquals(PNG_HEADER)
        else bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
        if (!valid) throw PetPackageException("Atlas format does not match its extension")
    }

    private fun move(source: File, target: File) {
        runCatching { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { if (!source.renameTo(target)) throw PetPackageException("Unable to finish pet import") }
    }

    private fun removeTreeWithoutFollowingLinks(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            file.delete()
            return
        }
        if (file.isDirectory) file.listFiles()?.forEach(::removeTreeWithoutFollowingLinks)
        file.delete()
    }

    private companion object {
        const val MAX_ARCHIVE_BYTES = 32L * 1024 * 1024
        const val MAX_EXTRACTED_BYTES = 64L * 1024 * 1024
        const val MAX_ENTRIES = 64
        val PET_ID = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        val SAFE_NAME = Regex("^[^/\\\\:]+$")
        val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}

internal class PetPackageException(message: String) : IllegalArgumentException(message)
