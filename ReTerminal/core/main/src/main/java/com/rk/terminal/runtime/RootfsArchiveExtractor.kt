package com.rk.terminal.runtime

import android.annotation.SuppressLint
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.util.zip.GZIPInputStream

/**
 * Extracts `.tar` and `.tar.gz` Linux rootfs archives directly in the host JVM
 * so rootfs bootstrap never depends on executing `/system/bin/tar` under PRoot
 * or Android's host Toybox symlink checks.
 *
 * Archive hardlinks (`typeflag == '1'`) are materialized as relative symbolic
 * links inside the target rootfs because Android `untrusted_app` SELinux denies
 * `link(2)`/`linkat(2)` on app data files.
 */
@SuppressLint("NewApi")
internal object RootfsArchiveExtractor {
    private const val TAR_BLOCK_SIZE = 512
    private const val IO_BUFFER_SIZE = 64 * 1024
    private const val MAX_METADATA_BYTES = 1024 * 1024L
    private const val MAX_SYMLINK_HOPS = 16

    private data class DeferredDirectoryMetadata(
        val path: Path,
        val mode: Int,
        val mtimeSeconds: Long
    )

    fun extract(
        rawInput: InputStream,
        rootfsDir: File,
        onCancelCheck: () -> Unit = {}
    ) {
        rootfsDir.mkdirs()
        val rootfsRoot = rootfsDir.canonicalFile.toPath()
        val tarInput = openTarStream(rawInput)
        val header = ByteArray(TAR_BLOCK_SIZE)
        val copyBuffer = ByteArray(IO_BUFFER_SIZE)
        val deferredDirs = ArrayList<DeferredDirectoryMetadata>()

        var consecutiveZeroBlocks = 0
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        val globalPaxHeaders = HashMap<String, String>()
        val entryPaxHeaders = HashMap<String, String>()

        while (true) {
            onCancelCheck()
            val headerBytes = readBlockOrEof(tarInput, header)
            if (headerBytes == 0) {
                break
            }
            if (isZeroBlock(header)) {
                consecutiveZeroBlocks += 1
                if (consecutiveZeroBlocks >= 2) {
                    break
                }
                continue
            }
            consecutiveZeroBlocks = 0

            verifyHeaderChecksum(header)

            val rawType = header[156].toInt() and 0xFF
            val typeFlag = if (rawType == 0) '0' else rawType.toChar()
            val headerSize = parseTarNumber(header, 124, 12)

            when (typeFlag) {
                'L' -> {
                    pendingLongName = readMetadataString(tarInput, headerSize, copyBuffer)
                    continue
                }
                'K' -> {
                    pendingLongLink = readMetadataString(tarInput, headerSize, copyBuffer)
                    continue
                }
                'x' -> {
                    parsePaxHeaders(
                        readMetadataBytes(tarInput, headerSize, copyBuffer),
                        entryPaxHeaders
                    )
                    continue
                }
                'g' -> {
                    parsePaxHeaders(
                        readMetadataBytes(tarInput, headerSize, copyBuffer),
                        globalPaxHeaders
                    )
                    continue
                }
            }

            val rawName = entryPaxHeaders["path"]
                ?: pendingLongName
                ?: globalPaxHeaders["path"]
                ?: readUstarName(header)
            val rawLinkName = entryPaxHeaders["linkpath"]
                ?: pendingLongLink
                ?: globalPaxHeaders["linkpath"]
                ?: readNullTerminatedString(header, 157, 100)
            val entrySize = entryPaxHeaders["size"]?.toLongOrNull()
                ?: globalPaxHeaders["size"]?.toLongOrNull()
                ?: headerSize
            val mtimeSeconds = entryPaxHeaders["mtime"]?.substringBefore('.')?.toLongOrNull()
                ?: globalPaxHeaders["mtime"]?.substringBefore('.')?.toLongOrNull()
                ?: parseTarNumber(header, 136, 12)
            val rawMode = parseTarNumber(header, 100, 8).toInt()

            pendingLongName = null
            pendingLongLink = null
            entryPaxHeaders.clear()

            if (entrySize < 0L) {
                throw IOException("Invalid negative tar entry size for '$rawName': $entrySize")
            }

            val isDirEntry = typeFlag == '5' || (typeFlag == '0' && rawName.endsWith("/"))
            val segments = normalizeArchivePath(rawName)
            if (segments.isEmpty()) {
                if (entrySize > 0L) {
                    skipFully(tarInput, paddedTarSize(entrySize), copyBuffer)
                }
                continue
            }

            val parentDir = resolveGuestDirectory(
                rootfsRoot = rootfsRoot,
                segments = segments.subList(0, segments.size - 1)
            )
            val leafPath = parentDir.resolve(segments.last()).normalize()
            ensureInsideRootfs(rootfsRoot, leafPath, rawName)

            when {
                isDirEntry -> {
                    if (entrySize > 0L) {
                        skipFully(tarInput, paddedTarSize(entrySize), copyBuffer)
                    }
                    if (Files.isSymbolicLink(leafPath)) {
                        val symlinkTarget = followSymlinkInGuest(rootfsRoot, leafPath, rawName)
                        Files.createDirectories(symlinkTarget)
                        applyPermissions(symlinkTarget, modeOrDefault(rawMode, 0b111_101_101), isDirectory = true)
                    } else {
                        if (Files.exists(leafPath, LinkOption.NOFOLLOW_LINKS) &&
                            !Files.isDirectory(leafPath, LinkOption.NOFOLLOW_LINKS)
                        ) {
                            Files.delete(leafPath)
                        }
                        Files.createDirectories(leafPath)
                        // Keep owner rwx during extraction so child entries can always be created.
                        applyPermissions(leafPath, 0b111_101_101, isDirectory = true)
                        deferredDirs.add(
                            DeferredDirectoryMetadata(
                                path = leafPath,
                                mode = modeOrDefault(rawMode, 0b111_101_101),
                                mtimeSeconds = mtimeSeconds
                            )
                        )
                    }
                }

                typeFlag == '0' || typeFlag == '7' -> {
                    deleteExistingNonDirectoryOrEmptyDir(leafPath)
                    writeRegularFile(tarInput, leafPath, entrySize, copyBuffer, onCancelCheck)
                    val padding = paddingBytes(entrySize)
                    if (padding > 0L) {
                        skipFully(tarInput, padding, copyBuffer)
                    }
                    applyPermissions(leafPath, modeOrDefault(rawMode, 0b110_100_100), isDirectory = false)
                    applyLastModifiedTime(leafPath, mtimeSeconds)
                }

                typeFlag == '2' -> {
                    if (entrySize > 0L) {
                        skipFully(tarInput, paddedTarSize(entrySize), copyBuffer)
                    }
                    if (rawLinkName.isEmpty()) {
                        throw IOException("Tar symlink entry '$rawName' has an empty link target.")
                    }
                    deleteExistingNonDirectoryOrEmptyDir(leafPath)
                    Files.createSymbolicLink(leafPath, Paths.get(rawLinkName))
                }

                typeFlag == '1' -> {
                    if (entrySize > 0L) {
                        skipFully(tarInput, paddedTarSize(entrySize), copyBuffer)
                    }
                    if (rawLinkName.isEmpty()) {
                        throw IOException("Tar hardlink entry '$rawName' has an empty link target.")
                    }
                    val targetSegments = normalizeArchivePath(rawLinkName)
                    if (targetSegments.isEmpty()) {
                        throw IOException("Tar hardlink entry '$rawName' points to root directory.")
                    }
                    val targetParent = resolveGuestDirectory(
                        rootfsRoot = rootfsRoot,
                        segments = targetSegments.subList(0, targetSegments.size - 1)
                    )
                    val targetPath = targetParent.resolve(targetSegments.last()).normalize()
                    ensureInsideRootfs(rootfsRoot, targetPath, rawLinkName)
                    if (targetPath != leafPath) {
                        deleteExistingNonDirectoryOrEmptyDir(leafPath)
                        val relativeTarget = leafPath.parent.relativize(targetPath)
                        Files.createSymbolicLink(leafPath, relativeTarget)
                    }
                }

                else -> {
                    // Skip special entries such as char/block device nodes or FIFOs that
                    // unprivileged Android app UIDs cannot mknod; PRoot binds host /dev.
                    if (entrySize > 0L) {
                        skipFully(tarInput, paddedTarSize(entrySize), copyBuffer)
                    }
                }
            }
        }

        for (index in deferredDirs.indices.reversed()) {
            val dir = deferredDirs[index]
            if (Files.isDirectory(dir.path, LinkOption.NOFOLLOW_LINKS)) {
                applyPermissions(dir.path, dir.mode, isDirectory = true)
                applyLastModifiedTime(dir.path, dir.mtimeSeconds)
            }
        }
    }

    fun clearIncompleteRootfs(rootfsDir: File) {
        if (!rootfsDir.exists()) {
            return
        }
        val children = rootfsDir.listFiles() ?: return
        for (child in children) {
            val name = child.name
            if (name == "root" || name == "tmp") {
                continue
            }
            deletePathRecursivelyNoFollow(child.toPath())
        }
    }

    fun markRootfsReadyAtomically(rootfsDir: File, markerName: String, distributionId: String) {
        rootfsDir.mkdirs()
        val markerPath = rootfsDir.toPath().resolve(markerName)
        val tempPath = rootfsDir.toPath().resolve("$markerName.tmp.${System.nanoTime()}")
        try {
            Files.write(tempPath, "$distributionId\n".toByteArray(StandardCharsets.UTF_8))
            applyPermissions(tempPath, 0b110_100_100, isDirectory = false)
            Files.move(
                tempPath,
                markerPath,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            runCatching { Files.deleteIfExists(tempPath) }
        }
    }

    private fun deletePathRecursivelyNoFollow(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            runCatching {
                val file = path.toFile()
                file.setReadable(true, true)
                file.setWritable(true, true)
                file.setExecutable(true, true)
            }
            Files.newDirectoryStream(path).use { entries ->
                for (entry in entries) {
                    deletePathRecursivelyNoFollow(entry)
                }
            }
        }
        if (!Files.deleteIfExists(path)) {
            throw IOException("Failed to remove incomplete rootfs entry: $path")
        }
    }

    private fun openTarStream(rawInput: InputStream): InputStream {
        val buffered = if (rawInput is BufferedInputStream) {
            rawInput
        } else {
            BufferedInputStream(rawInput, IO_BUFFER_SIZE)
        }
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        return if (b0 == 0x1F && b1 == 0x8B) {
            GZIPInputStream(buffered, IO_BUFFER_SIZE)
        } else {
            buffered
        }
    }

    private fun readBlockOrEof(input: InputStream, block: ByteArray): Int {
        var offset = 0
        while (offset < TAR_BLOCK_SIZE) {
            val read = input.read(block, offset, TAR_BLOCK_SIZE - offset)
            if (read < 0) {
                if (offset == 0) {
                    return 0
                }
                throw IOException("Truncated tar header ($offset of $TAR_BLOCK_SIZE bytes).")
            }
            offset += read
        }
        return TAR_BLOCK_SIZE
    }

    private fun isZeroBlock(block: ByteArray): Boolean {
        for (byte in block) {
            if (byte.toInt() != 0) {
                return false
            }
        }
        return true
    }

    private fun verifyHeaderChecksum(block: ByteArray) {
        val storedChecksum = parseTarNumber(block, 148, 8)
        var unsignedSum = 0L
        var signedSum = 0L
        for (index in 0 until TAR_BLOCK_SIZE) {
            if (index in 148..155) {
                unsignedSum += 0x20L
                signedSum += 0x20L
            } else {
                val byteVal = block[index]
                unsignedSum += (byteVal.toInt() and 0xFF).toLong()
                signedSum += byteVal.toLong()
            }
        }
        if (storedChecksum != unsignedSum && storedChecksum != signedSum) {
            throw IOException("Invalid tar header checksum (expected $storedChecksum, actual $unsignedSum).")
        }
    }

    private fun parseTarNumber(block: ByteArray, offset: Int, length: Int): Long {
        if (length <= 0) return 0L
        val first = block[offset].toInt() and 0xFF
        if ((first and 0x80) != 0) {
            // GNU base-256 binary encoding (positive values).
            var value = (first and 0x7F).toLong()
            for (index in 1 until length) {
                value = (value shl 8) or ((block[offset + index].toInt() and 0xFF).toLong())
            }
            return value
        }

        var cursor = offset
        val end = offset + length
        while (cursor < end && (block[cursor] == 0.toByte() || block[cursor] == ' '.code.toByte())) {
            cursor += 1
        }
        var value = 0L
        while (cursor < end) {
            val ch = (block[cursor].toInt() and 0xFF).toChar()
            if (ch == '\u0000' || ch == ' ') {
                break
            }
            if (ch !in '0'..'7') {
                throw IOException("Invalid octal digit '$ch' in tar header.")
            }
            value = (value shl 3) + (ch - '0')
            cursor += 1
        }
        return value
    }

    private fun readUstarName(block: ByteArray): String {
        val name = readNullTerminatedString(block, 0, 100)
        val magic = readNullTerminatedString(block, 257, 6)
        if (magic.startsWith("ustar")) {
            val prefix = readNullTerminatedString(block, 345, 155)
            if (prefix.isNotEmpty()) {
                return if (prefix.endsWith("/")) "$prefix$name" else "$prefix/$name"
            }
        }
        return name
    }

    private fun readNullTerminatedString(block: ByteArray, offset: Int, maxLength: Int): String {
        var end = offset
        val limit = offset + maxLength
        while (end < limit && block[end] != 0.toByte()) {
            end += 1
        }
        return String(block, offset, end - offset, StandardCharsets.UTF_8)
    }

    private fun readMetadataBytes(
        input: InputStream,
        size: Long,
        scratch: ByteArray
    ): ByteArray {
        if (size < 0L || size > MAX_METADATA_BYTES) {
            throw IOException("Invalid tar metadata block size: $size")
        }
        val payload = ByteArray(size.toInt())
        var offset = 0
        while (offset < payload.size) {
            val read = input.read(payload, offset, payload.size - offset)
            if (read < 0) {
                throw IOException("Unexpected EOF while reading tar metadata.")
            }
            offset += read
        }
        val padding = paddingBytes(size)
        if (padding > 0L) {
            skipFully(input, padding, scratch)
        }
        return payload
    }

    private fun readMetadataString(
        input: InputStream,
        size: Long,
        scratch: ByteArray
    ): String {
        val bytes = readMetadataBytes(input, size, scratch)
        var length = bytes.size
        while (length > 0 && bytes[length - 1] == 0.toByte()) {
            length -= 1
        }
        return String(bytes, 0, length, StandardCharsets.UTF_8)
    }

    private fun parsePaxHeaders(bytes: ByteArray, target: MutableMap<String, String>) {
        var cursor = 0
        while (cursor < bytes.size) {
            var spaceIdx = cursor
            while (spaceIdx < bytes.size && bytes[spaceIdx] != ' '.code.toByte()) {
                spaceIdx += 1
            }
            if (spaceIdx >= bytes.size) {
                break
            }
            val lengthStr = String(bytes, cursor, spaceIdx - cursor, StandardCharsets.US_ASCII).trim()
            val recordLen = lengthStr.toIntOrNull()
                ?: throw IOException("Invalid PAX header length '$lengthStr'.")
            if (recordLen <= (spaceIdx - cursor + 1) || cursor + recordLen > bytes.size) {
                throw IOException("Malformed PAX header record length: $recordLen")
            }
            var contentEnd = cursor + recordLen
            if (bytes[contentEnd - 1] == '\n'.code.toByte()) {
                contentEnd -= 1
            }
            val kv = String(
                bytes,
                spaceIdx + 1,
                contentEnd - (spaceIdx + 1),
                StandardCharsets.UTF_8
            )
            val eq = kv.indexOf('=')
            if (eq > 0) {
                val key = kv.substring(0, eq)
                val value = kv.substring(eq + 1)
                if (value.isEmpty()) {
                    target.remove(key)
                } else {
                    target[key] = value
                }
            }
            cursor += recordLen
        }
    }

    private fun normalizeArchivePath(rawPath: String): List<String> {
        val trimmed = rawPath.replace('\\', '/')
        val segments = ArrayList<String>()
        for (rawSegment in trimmed.split('/')) {
            when (rawSegment) {
                "", "." -> continue
                ".." -> {
                    if (segments.isEmpty()) {
                        throw IOException("Tar entry escapes target rootfs: '$rawPath'")
                    }
                    segments.removeAt(segments.size - 1)
                }
                else -> segments.add(rawSegment)
            }
        }
        return segments
    }

    private fun resolveGuestDirectory(rootfsRoot: Path, segments: List<String>): Path {
        var current = rootfsRoot
        for (segment in segments) {
            val candidate = current.resolve(segment).normalize()
            ensureInsideRootfs(rootfsRoot, candidate, segment)
            current = if (Files.isSymbolicLink(candidate)) {
                val resolved = followSymlinkInGuest(rootfsRoot, candidate, segment)
                Files.createDirectories(resolved)
                applyPermissions(resolved, 0b111_101_101, isDirectory = true)
                resolved
            } else {
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                ) {
                    Files.delete(candidate)
                }
                Files.createDirectories(candidate)
                applyPermissions(candidate, 0b111_101_101, isDirectory = true)
                candidate
            }
        }
        return current
    }

    private fun followSymlinkInGuest(rootfsRoot: Path, startLink: Path, contextName: String): Path {
        var current = startLink
        var hops = 0
        while (Files.isSymbolicLink(current)) {
            hops += 1
            if (hops > MAX_SYMLINK_HOPS) {
                throw IOException("Too many symbolic link hops while extracting '$contextName'.")
            }
            val linkTarget = Files.readSymbolicLink(current)
            val rawTarget = linkTarget.toString()
            current = if (linkTarget.isAbsolute || rawTarget.startsWith("/")) {
                val guestSegments = normalizeArchivePath(rawTarget)
                var guestPath = rootfsRoot
                for (seg in guestSegments) {
                    guestPath = guestPath.resolve(seg)
                }
                guestPath.normalize()
            } else {
                val base = current.parent ?: rootfsRoot
                base.resolve(linkTarget).normalize()
            }
            ensureInsideRootfs(rootfsRoot, current, contextName)
        }
        return current
    }

    private fun ensureInsideRootfs(rootfsRoot: Path, candidate: Path, rawName: String) {
        if (!candidate.normalize().startsWith(rootfsRoot)) {
            throw IOException("Tar entry resolves outside target rootfs: '$rawName'")
        }
    }

    private fun deleteExistingNonDirectoryOrEmptyDir(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            deletePathRecursivelyNoFollow(path)
        } else {
            Files.delete(path)
        }
    }

    private fun writeRegularFile(
        input: InputStream,
        target: Path,
        size: Long,
        buffer: ByteArray,
        onCancelCheck: () -> Unit
    ) {
        Files.newOutputStream(target).use { output ->
            var remaining = size
            while (remaining > 0L) {
                onCancelCheck()
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read < 0) {
                    throw IOException("Unexpected EOF while extracting '$target' ($remaining bytes remaining).")
                }
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long, scratch: ByteArray) {
        var remaining = bytesToSkip
        while (remaining > 0L) {
            val toRead = minOf(scratch.size.toLong(), remaining).toInt()
            val read = input.read(scratch, 0, toRead)
            if (read < 0) {
                throw IOException("Unexpected EOF while advancing tar stream ($remaining bytes remaining).")
            }
            remaining -= read
        }
    }

    private fun paddingBytes(size: Long): Long {
        val rem = size % TAR_BLOCK_SIZE
        return if (rem == 0L) 0L else TAR_BLOCK_SIZE - rem
    }

    private fun paddedTarSize(size: Long): Long = size + paddingBytes(size)

    private fun modeOrDefault(rawMode: Int, defaultMode: Int): Int {
        val masked = rawMode and 0b111_111_111
        return if (masked == 0) defaultMode else masked
    }

    private fun applyPermissions(path: Path, rawMode: Int, isDirectory: Boolean) {
        // Always ensure owner read+write (and owner execute on directories) so the
        // single-UID Android app sandbox can create, update, and clean up files.
        val effective = if (isDirectory) {
            (rawMode and 0b111_111_111) or 0b111_000_000
        } else {
            (rawMode and 0b111_111_111) or 0b110_000_000
        }

        val posixView = Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        if (posixView != null) {
            val perms = EnumSet.noneOf(PosixFilePermission::class.java)
            if ((effective and 0b100_000_000) != 0) perms.add(PosixFilePermission.OWNER_READ)
            if ((effective and 0b010_000_000) != 0) perms.add(PosixFilePermission.OWNER_WRITE)
            if ((effective and 0b001_000_000) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE)
            if ((effective and 0b000_100_000) != 0) perms.add(PosixFilePermission.GROUP_READ)
            if ((effective and 0b000_010_000) != 0) perms.add(PosixFilePermission.GROUP_WRITE)
            if ((effective and 0b000_001_000) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE)
            if ((effective and 0b000_000_100) != 0) perms.add(PosixFilePermission.OTHERS_READ)
            if ((effective and 0b000_000_010) != 0) perms.add(PosixFilePermission.OTHERS_WRITE)
            if ((effective and 0b000_000_001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE)
            runCatching { posixView.setPermissions(perms) }
            return
        }

        val file = path.toFile()
        file.setReadable(true, (effective and 0b000_100_100) == 0)
        file.setWritable(true, (effective and 0b000_010_010) == 0)
        if ((effective and 0b001_001_001) != 0) {
            file.setExecutable(true, (effective and 0b000_001_001) == 0)
        }
    }

    private fun applyLastModifiedTime(path: Path, mtimeSeconds: Long) {
        if (mtimeSeconds <= 0L) return
        runCatching {
            Files.setLastModifiedTime(path, FileTime.fromMillis(mtimeSeconds * 1000L))
        }
    }
}
