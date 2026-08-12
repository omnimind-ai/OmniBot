import java.math.BigInteger
import java.net.URI
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val gitCommitHash: Provider<String> =
    providers.exec { commandLine("git", "rev-parse", "--short=8", "HEAD") }.standardOutput.asText.map { it.trim() }

val fullGitCommitHash: Provider<String> =
    providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim() }

val gitCommitDate: Provider<String> =
    providers.exec { commandLine("git", "show", "-s", "--format=%cI", "HEAD") }.standardOutput.asText.map { it.trim() }

val termuxPackageBaseUrl = "https://packages-cf.termux.dev/apt/termux-main"
val bundledRuntimeDir = layout.projectDirectory.dir("src/main/embedded-terminal-runtime")
val prootDebFileName = "proot_5.1.107.77_aarch64.deb"
val prootDebFile = bundledRuntimeDir.file(prootDebFileName)
val prootDebChecksum = "f2cd07bafbebf625c62931994120d469934a8925a831f6e049bb08f91889a00d"
val prootSourceCommit = "571a6c066639669ba7bef0cab9b70050c4fd60f5"
val prootSourceUrl = "https://github.com/termux/proot/archive/$prootSourceCommit.zip"
val prootSourceChecksum = "1976a149c86e72c23d230dd6f467648cecb2516971fbd6b63c93d652db543ab8"
val prootLoader32Checksum = "19e9a2dd9bca570bfd4c92cdfca3eecd792e91aa0ae21067cc06bf719fcf152c"
val embeddedRuntimeNdkVersion = "28.2.13676358"
val prootLoader32SourceDir = layout.projectDirectory.dir("src/main/proot-loader32-source")
val prootLoader32SourceChecksums = linkedMapOf(
    "COPYING" to "078ba767b29d17dd2d31bc07ad3bc010ebd6359543ee326354b4133e1dcaae0e",
    "src/arch.h" to "396ef015b644ee4bc39400e90f363f5771e2d52bd1fbf158ea8be20566715b43",
    "src/attribute.h" to "4b8c8849fbd1e39dc3f7d9cbad60ac37992aa4c48a1912d17baf1801891cc146",
    "src/compat.h" to "558482b3026456a902a6ff4826d89547b974037811bac829d980e1cfab9a0858",
    "src/loader/loader.c" to "5de0e2cbc5a478b8cd25301cf1ccfc84e04a9b6197c4124fb3fd98a5166c9578",
    "src/loader/assembly.S" to "bf400d539aa118942ebdfdadbe63037852233a4c40364e085ff2d15656db441b",
    "src/loader/script.h" to "ec9df4d2ce2eacac15685242257101215b1c81deef598d0010e08a97f2f927d5",
    "src/loader/assembly-arm.h" to "3858ffe0a7a8c1dd6c6059f45136d1d48156237d5e743c19210a8f684277c077"
)
val libtallocDebUrl = "$termuxPackageBaseUrl/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
val libtallocDebChecksum = "ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da"
val alpineMiniRootfsUrl =
    "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz"
val alpineMiniRootfsChecksum = "f31202c4070c4ef7de9e157e1bd01cb4da3a2150035d74ea5372c5e86f1efac1"
val ubuntuBaseRootfsUrl =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
val ubuntuBaseRootfsChecksum = "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"

val maxDebArchiveBytes = 16L * 1024L * 1024L
val maxProotSourceFileBytes = 1L * 1024L * 1024L
val maxRootfsArchiveBytes = 128L * 1024L * 1024L
val maxDebDataArchiveBytes = 64L * 1024L * 1024L
val maxTarListingBytes = 4L * 1024L * 1024L
val maxTarEntries = 4_096
val maxRuntimeFileBytes = 32L * 1024L * 1024L

android {
    namespace = "com.rk.terminal"
    android.buildFeatures.buildConfig = true
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs/embeddedTerminalRuntime"))
            assets.srcDir(layout.buildDirectory.dir("generated/assets/embeddedTerminalRuntime"))
        }
    }

    buildTypes {
        release {
            buildConfigField("String", "GIT_COMMIT_HASH", "\"${fullGitCommitHash.get()}\"")
            buildConfigField("String", "GIT_SHORT_COMMIT_HASH", "\"${gitCommitHash.get()}\"")
            buildConfigField("String", "GIT_COMMIT_DATE", "\"${gitCommitDate.get()}\"")

            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
        debug{
            buildConfigField("String", "GIT_COMMIT_HASH", "\"${fullGitCommitHash.get()}\"")
            buildConfigField("String", "GIT_SHORT_COMMIT_HASH", "\"${gitCommitHash.get()}\"")
            buildConfigField("String", "GIT_COMMIT_DATE", "\"${gitCommitDate.get()}\"")
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }


}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val readBytes = input.read(buffer)
            if (readBytes < 0) break
            digest.update(buffer, 0, readBytes)
        }
    }
    return BigInteger(1, digest.digest()).toString(16).padStart(64, '0')
}

fun downloadRuntimeFile(
    localPath: String,
    remoteUrl: String,
    expectedChecksum: String? = null,
    maxBytes: Long
) {
    require(maxBytes > 0) { "The download size limit must be positive" }
    val file = file(localPath)
    if (file.exists()) {
        if (file.isFile && file.length() in 1..maxBytes) {
            val checksum = sha256(file)
            if (expectedChecksum == null || checksum == expectedChecksum) return
        }
        file.delete()
    }

    file.parentFile?.mkdirs()
    val digest = MessageDigest.getInstance("SHA-256")
    val connection = URI(remoteUrl).toURL().openConnection()
    try {
        connection.getInputStream().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var totalBytes = 0L
                while (true) {
                    val readBytes = input.read(buffer)
                    if (readBytes < 0) break
                    totalBytes += readBytes
                    check(totalBytes <= maxBytes) {
                        "Downloaded archive exceeds the ${maxBytes}-byte limit: $remoteUrl"
                    }
                    output.write(buffer, 0, readBytes)
                    digest.update(buffer, 0, readBytes)
                }
            }
        }
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
    var checksum = BigInteger(1, digest.digest()).toString(16)
    while (checksum.length < 64) checksum = "0$checksum"
    if (expectedChecksum != null && checksum != expectedChecksum) {
        file.delete()
        throw GradleException(
            "Wrong checksum for $remoteUrl:\nExpected: $expectedChecksum\nActual:   $checksum"
        )
    }
}

fun copyVerifiedRuntimeFile(source: File, target: File, expectedChecksum: String? = null) {
    check(source.isFile && source.length() > 0) { "Missing bundled runtime file: ${source.absolutePath}" }
    val checksum = sha256(source)
    if (expectedChecksum != null && checksum != expectedChecksum) {
        throw GradleException(
            "Wrong checksum for ${source.absolutePath}:\nExpected: $expectedChecksum\nActual:   $checksum"
        )
    }
    target.parentFile?.mkdirs()
    source.copyTo(target, overwrite = true)
}

fun extractDebMember(
    debFile: File,
    memberName: String,
    target: File,
    maxMemberBytes: Long
) {
    check(debFile.isFile && debFile.length() in 1..maxDebArchiveBytes) {
        "Deb archive is missing, empty, or exceeds the ${maxDebArchiveBytes}-byte limit: ${debFile.absolutePath}"
    }
    target.parentFile?.mkdirs()
    RandomAccessFile(debFile, "r").use { input ->
        val globalHeader = ByteArray(8)
        input.readFully(globalHeader)
        check(String(globalHeader) == "!<arch>\n") { "Invalid deb archive: ${debFile.absolutePath}" }

        var entryCount = 0
        while (input.filePointer < input.length()) {
            entryCount++
            check(entryCount <= maxTarEntries) {
                "Deb archive contains more than $maxTarEntries members: ${debFile.absolutePath}"
            }
            val header = ByteArray(60)
            input.readFully(header)
            val name = String(header, 0, 16).trim().removeSuffix("/")
            val size = String(header, 48, 10).trim().toLongOrNull()
                ?: error("Invalid member size in deb archive: ${debFile.absolutePath}")
            check(size >= 0) { "Negative member size in deb archive: ${debFile.absolutePath}" }
            val dataOffset = input.filePointer
            val paddedSize = size + (size % 2)
            check(paddedSize >= size && dataOffset <= input.length() - paddedSize) {
                "Truncated member $name in deb archive: ${debFile.absolutePath}"
            }
            if (name == memberName) {
                check(size <= maxMemberBytes) {
                    "$memberName exceeds the ${maxMemberBytes}-byte limit in ${debFile.absolutePath}"
                }
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var remaining = size
                    while (remaining > 0) {
                        val readBytes = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        check(readBytes >= 0) { "Unexpected EOF while reading $memberName from ${debFile.name}" }
                        output.write(buffer, 0, readBytes)
                        remaining -= readBytes
                    }
                }
                return
            }
            input.seek(dataOffset + paddedSize)
        }
    }
    error("Missing $memberName in ${debFile.absolutePath}")
}

data class RequiredTarFile(
    val archivePath: String,
    val outputPath: String,
    val maxBytes: Long = maxRuntimeFileBytes
)

class SizeLimitedOutputStream(
    output: OutputStream,
    private val maxBytes: Long
) : FilterOutputStream(output) {
    private var writtenBytes = 0L

    override fun write(value: Int) {
        ensureCapacity(1)
        out.write(value)
        writtenBytes++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
            "Invalid output buffer range"
        }
        ensureCapacity(length)
        out.write(buffer, offset, length)
        writtenBytes += length
    }

    private fun ensureCapacity(additionalBytes: Int) {
        check(additionalBytes.toLong() <= maxBytes - writtenBytes) {
            "Extracted file exceeds the ${maxBytes}-byte limit"
        }
    }
}

fun pipeTarStdout(
    arguments: List<String>,
    output: OutputStream,
    maxBytes: Long
) {
    val process = ProcessBuilder(listOf("tar") + arguments)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    try {
        process.inputStream.use { input ->
            val limitedOutput = SizeLimitedOutputStream(output, maxBytes)
            val buffer = ByteArray(8192)
            while (true) {
                val readBytes = input.read(buffer)
                if (readBytes < 0) break
                limitedOutput.write(buffer, 0, readBytes)
            }
            limitedOutput.flush()
        }
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "tar exited with code $exitCode while reading a verified runtime archive"
        }
    } catch (error: Throwable) {
        process.destroyForcibly()
        runCatching { process.waitFor() }
        throw error
    }
}

fun normalizeSafeTarEntry(rawName: String): String {
    check(rawName.isNotBlank()) { "Tar archive contains an empty entry name" }
    check(rawName.length <= 4_096) { "Tar entry name exceeds the 4096-character limit" }
    check(!rawName.contains('\u0000')) { "Tar entry name contains a NUL byte" }

    val portableName = rawName.replace('\\', '/')
    check(!portableName.startsWith('/')) { "Tar archive contains an absolute path: $rawName" }
    check(!Regex("^[A-Za-z]:").containsMatchIn(portableName)) {
        "Tar archive contains a drive-qualified path: $rawName"
    }
    check(!portableName.contains(':')) { "Tar archive contains a Windows alternate-data path: $rawName" }

    val segments = portableName.split('/').filter { it.isNotEmpty() && it != "." }
    check(segments.isNotEmpty()) { "Tar archive contains an empty root entry" }
    check(segments.none { it == ".." }) { "Tar archive contains path traversal: $rawName" }
    return segments.joinToString("/")
}

fun safeArchiveOutput(root: File, relativePath: String): File {
    val normalized = normalizeSafeTarEntry(relativePath)
    val canonicalRoot = root.canonicalFile
    val output = File(canonicalRoot, normalized).canonicalFile
    check(output.toPath().startsWith(canonicalRoot.toPath())) {
        "Archive output escapes its destination: $relativePath"
    }
    return output
}

fun resolveAndroidSdkDirectory(): File {
    val configured = sequenceOf(
        System.getenv("ANDROID_SDK_ROOT"),
        System.getenv("ANDROID_HOME")
    ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull()
        ?: run {
            val localProperties = rootProject.file("local.properties")
            check(localProperties.isFile) {
                "Android SDK is not configured through ANDROID_SDK_ROOT, ANDROID_HOME, or local.properties"
            }
            Properties().apply {
                localProperties.inputStream().use(::load)
            }.getProperty("sdk.dir")?.trim().orEmpty()
        }
    check(configured.isNotEmpty()) { "Android SDK path is empty" }
    return File(configured).canonicalFile.also { sdkDir ->
        check(sdkDir.isDirectory) { "Android SDK directory does not exist: ${sdkDir.absolutePath}" }
    }
}

fun resolvePinnedNdkToolchainBin(): File {
    val sdkNdk = resolveAndroidSdkDirectory().resolve("ndk/$embeddedRuntimeNdkVersion")
    val environmentCandidates = sequenceOf(
        System.getenv("ANDROID_NDK_HOME"),
        System.getenv("ANDROID_NDK_ROOT"),
        System.getenv("ANDROID_NDK_PATH")
    ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty)?.let(::File) }
    val ndkDir = (environmentCandidates + sequenceOf(sdkNdk))
        .map(File::getCanonicalFile)
        .firstOrNull { candidate ->
            val sourceProperties = candidate.resolve("source.properties")
            if (!sourceProperties.isFile) return@firstOrNull false
            val properties = Properties().apply {
                sourceProperties.inputStream().use(::load)
            }
            properties.getProperty("Pkg.Revision")?.trim() == embeddedRuntimeNdkVersion
        }
        ?: error("Android NDK $embeddedRuntimeNdkVersion is required to build the 16 KB PRoot loader32")

    val hostTag = when {
        System.getProperty("os.name").contains("Windows", ignoreCase = true) -> "windows-x86_64"
        System.getProperty("os.name").contains("Mac", ignoreCase = true) -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    return ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/bin").also { toolchainBin ->
        check(toolchainBin.isDirectory) {
            "Pinned NDK toolchain is missing for $hostTag: ${toolchainBin.absolutePath}"
        }
    }
}

fun ndkTool(toolchainBin: File, name: String): File {
    val suffix = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) ".exe" else ""
    return toolchainBin.resolve("$name$suffix").also { executable ->
        check(executable.isFile) { "Pinned NDK tool is missing: ${executable.absolutePath}" }
    }
}

fun runCheckedCommand(command: List<String>, workingDir: File): String {
    check(command.isNotEmpty()) { "Command must not be empty" }
    val process = ProcessBuilder(command)
        .directory(workingDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    if (output.isNotBlank()) println(output.trimEnd())
    check(exitCode == 0) {
        "Pinned toolchain command failed with exit code $exitCode: ${command.first()}"
    }
    return output
}

fun verifyVendoredProotLoader32Source(sourceRoot: File) {
    val canonicalSourceRoot = sourceRoot.canonicalFile
    check(canonicalSourceRoot.isDirectory) {
        "Vendored PRoot loader32 source is missing: ${canonicalSourceRoot.absolutePath}"
    }
    val expectedFiles = prootLoader32SourceChecksums.keys + "SOURCE.md"
    val actualFiles = canonicalSourceRoot.walkTopDown()
        .filter(File::isFile)
        .map { file -> file.relativeTo(canonicalSourceRoot).invariantSeparatorsPath }
        .toSet()
    check(actualFiles == expectedFiles) {
        "Vendored PRoot loader32 source file set does not match the fixed allow-list"
    }
    prootLoader32SourceChecksums.forEach { (relativePath, expectedChecksum) ->
        val sourceFile = safeArchiveOutput(canonicalSourceRoot, relativePath)
        check(sourceFile.isFile && sourceFile.length() in 1..maxProotSourceFileBytes) {
            "Vendored PRoot source is missing, empty, or too large: $relativePath"
        }
        val actualChecksum = sha256(sourceFile)
        check(actualChecksum == expectedChecksum) {
            "Vendored PRoot source checksum mismatch for $relativePath"
        }
    }

    val protocolHeader = safeArchiveOutput(canonicalSourceRoot, "src/loader/script.h").readText()
    listOf(
        "#define LOAD_ACTION_OPEN_NEXT\t\t0",
        "#define LOAD_ACTION_OPEN\t\t1",
        "#define LOAD_ACTION_MMAP_FILE\t\t2",
        "#define LOAD_ACTION_MMAP_ANON\t\t3",
        "#define LOAD_ACTION_MAKE_STACK_EXEC\t4",
        "#define LOAD_ACTION_START_TRACED\t5",
        "#define LOAD_ACTION_START\t\t6",
        "typedef struct load_statement LoadStatement;"
    ).forEach { protocolMarker ->
        check(protocolHeader.contains(protocolMarker)) {
            "Vendored PRoot loader protocol marker is missing: $protocolMarker"
        }
    }
}

fun verifyElf32ArmLoader(file: File) {
    check(file.isFile && file.length() in 1..maxProotSourceFileBytes) {
        "PRoot loader32 output is missing, empty, or too large: ${file.absolutePath}"
    }
    check(sha256(file) == prootLoader32Checksum) {
        "PRoot loader32 output does not match the fixed reproducible SHA-256"
    }

    RandomAccessFile(file, "r").use { input ->
        val elfHeader = ByteArray(52)
        input.readFully(elfHeader)
        check(elfHeader.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))) {
            "PRoot loader32 is not an ELF file"
        }
        check(elfHeader[4].toInt() == 1 && elfHeader[5].toInt() == 1) {
            "PRoot loader32 must be little-endian ELF32"
        }
        val header = ByteBuffer.wrap(elfHeader).order(ByteOrder.LITTLE_ENDIAN)
        check(header.getShort(16).toInt() == 2) { "PRoot loader32 must be ET_EXEC" }
        check(header.getShort(18).toInt() == 40) { "PRoot loader32 must target ARM32" }
        check(Integer.toUnsignedLong(header.getInt(24)) == 0x20000000L) {
            "PRoot loader32 entry point must remain compatible with the PRoot loader protocol"
        }
        check(Integer.toUnsignedLong(header.getInt(36)) == 0x05000200L) {
            "PRoot loader32 must retain the expected ARM EABI flags"
        }
        val programHeaderOffset = Integer.toUnsignedLong(header.getInt(28))
        val programHeaderSize = header.getShort(42).toInt() and 0xffff
        val programHeaderCount = header.getShort(44).toInt() and 0xffff
        check(programHeaderSize >= 32 && programHeaderCount in 1..64) {
            "PRoot loader32 has an invalid program-header table"
        }
        check(programHeaderOffset <= input.length() - programHeaderSize.toLong() * programHeaderCount) {
            "PRoot loader32 has a truncated program-header table"
        }

        var loadSegmentCount = 0
        for (index in 0 until programHeaderCount) {
            input.seek(programHeaderOffset + index.toLong() * programHeaderSize)
            val bytes = ByteArray(programHeaderSize)
            input.readFully(bytes)
            val programHeader = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val type = programHeader.getInt(0)
            check(type != 2 && type != 3) {
                "PRoot loader32 must remain static and must not contain PT_DYNAMIC/PT_INTERP"
            }
            if (type != 1) continue
            loadSegmentCount++
            val offset = Integer.toUnsignedLong(programHeader.getInt(4))
            val virtualAddress = Integer.toUnsignedLong(programHeader.getInt(8))
            val fileSize = Integer.toUnsignedLong(programHeader.getInt(16))
            val alignment = Integer.toUnsignedLong(programHeader.getInt(28))
            check(alignment >= 16_384L) {
                "PRoot loader32 contains a PT_LOAD segment below 16 KB alignment"
            }
            check(offset % 16_384L == virtualAddress % 16_384L) {
                "PRoot loader32 PT_LOAD offset/address congruence is invalid for 16 KB pages"
            }
            check(offset <= input.length() - fileSize) {
                "PRoot loader32 contains a truncated PT_LOAD segment"
            }
        }
        check(loadSegmentCount == 3) {
            "PRoot loader32 program layout changed unexpectedly: $loadSegmentCount PT_LOAD segments"
        }
    }
}

fun buildProotLoader32(sourceRoot: File, buildDir: File, outputFile: File) {
    verifyVendoredProotLoader32Source(sourceRoot)
    buildDir.deleteRecursively()
    check(buildDir.mkdirs()) { "Unable to create PRoot loader32 build directory: ${buildDir.absolutePath}" }
    outputFile.parentFile?.mkdirs()

    val toolchainBin = resolvePinnedNdkToolchainBin()
    val clang = ndkTool(toolchainBin, "clang")
    val strip = ndkTool(toolchainBin, "llvm-strip")
    val nm = ndkTool(toolchainBin, "llvm-nm")
    val sourceDir = sourceRoot.resolve("src")
    val loaderObject = buildDir.resolve("loader.o")
    val assemblyObject = buildDir.resolve("assembly.o")
    val unstrippedLoader = buildDir.resolve("loader32.unstripped")

    val commonCompileArguments = listOf(
        clang.absolutePath,
        "--target=armv7a-linux-androideabi24",
        "-D_FILE_OFFSET_BITS=64",
        "-D_GNU_SOURCE",
        "-I${sourceRoot.absolutePath}",
        "-I${sourceDir.absolutePath}",
        "-Wall",
        "-Wextra",
        "-O2",
        "-fPIC",
        "-ffreestanding",
        "-c"
    )
    runCheckedCommand(
        commonCompileArguments + listOf(
            sourceDir.resolve("loader/loader.c").absolutePath,
            "-o",
            loaderObject.absolutePath
        ),
        buildDir
    )
    runCheckedCommand(
        commonCompileArguments + listOf(
            sourceDir.resolve("loader/assembly.S").absolutePath,
            "-o",
            assemblyObject.absolutePath
        ),
        buildDir
    )
    runCheckedCommand(
        listOf(
            clang.absolutePath,
            "--target=armv7a-linux-androideabi24",
            "-static",
            "-nostdlib",
            "-Wl,--build-id=none,-Ttext=0x20000000,--rosegment,-z,noexecstack,-z,max-page-size=16384,-z,common-page-size=16384",
            "-o",
            unstrippedLoader.absolutePath,
            loaderObject.absolutePath,
            assemblyObject.absolutePath
        ),
        buildDir
    )

    val symbols = runCheckedCommand(
        listOf(nm.absolutePath, "--defined-only", "--numeric-sort", unstrippedLoader.absolutePath),
        buildDir
    )
    check(Regex("(?m)^20000000 [Tt] _start$").containsMatchIn(symbols)) {
        "PRoot loader32 must export _start at the protocol entry address"
    }
    val undefinedSymbols = runCheckedCommand(
        listOf(nm.absolutePath, "--undefined-only", unstrippedLoader.absolutePath),
        buildDir
    )
    check(undefinedSymbols.isBlank()) { "PRoot loader32 must not depend on external symbols" }

    runCheckedCommand(
        listOf(strip.absolutePath, "--strip-all", "-o", outputFile.absolutePath, unstrippedLoader.absolutePath),
        buildDir
    )
    verifyElf32ArmLoader(outputFile)
}

fun listValidatedTarEntries(dataArchive: File): Map<String, String> {
    val listing = ByteArrayOutputStream()
    pipeTarStdout(
        arguments = listOf("-tJf", dataArchive.absolutePath),
        output = listing,
        maxBytes = maxTarListingBytes
    )

    val entries = listing.toString(Charsets.UTF_8.name())
        .lineSequence()
        .filter { it.isNotBlank() }
        .filterNot { it == "." || it == "./" }
        .toList()
    return indexValidatedTarEntries(entries)
}

fun indexValidatedTarEntries(entries: List<String>): Map<String, String> {
    check(entries.size <= maxTarEntries) {
        "Tar archive contains more than $maxTarEntries entries"
    }

    val indexedEntries = linkedMapOf<String, String>()
    entries.forEach { rawName ->
        val normalizedName = normalizeSafeTarEntry(rawName)
        check(indexedEntries.put(normalizedName, rawName) == null) {
            "Tar archive contains a duplicate entry: $normalizedName"
        }
    }
    return indexedEntries
}

fun unpackDebData(
    debFile: File,
    targetDir: File,
    requiredFiles: List<RequiredTarFile>
) {
    check(requiredFiles.isNotEmpty()) { "At least one runtime file must be requested" }
    val dataArchive = File(targetDir.parentFile, "${debFile.name}.data.tar.xz")
    extractDebMember(
        debFile = debFile,
        memberName = "data.tar.xz",
        target = dataArchive,
        maxMemberBytes = maxDebDataArchiveBytes
    )
    val archiveEntries = listValidatedTarEntries(dataArchive)

    targetDir.deleteRecursively()
    check(targetDir.mkdirs()) { "Unable to create runtime extraction directory: ${targetDir.absolutePath}" }

    requiredFiles.forEach { requiredFile ->
        check(requiredFile.maxBytes > 0) { "Runtime file size limit must be positive" }
        val normalizedArchivePath = normalizeSafeTarEntry(requiredFile.archivePath)
        val rawArchivePath = archiveEntries[normalizedArchivePath]
            ?: error("Missing $normalizedArchivePath in ${dataArchive.absolutePath}")
        val output = safeArchiveOutput(targetDir, requiredFile.outputPath)
        output.parentFile?.mkdirs()
        try {
            output.outputStream().buffered().use { fileOutput ->
                // Stream only the explicitly allow-listed regular file. This avoids creating package
                // symlinks (which Windows bsdtar cannot create without extra privileges) and never
                // lets archive names choose a filesystem destination.
                pipeTarStdout(
                    arguments = listOf("-xJOf", dataArchive.absolutePath, rawArchivePath),
                    output = fileOutput,
                    maxBytes = requiredFile.maxBytes
                )
            }
            check(output.isFile && output.length() in 1..requiredFile.maxBytes) {
                "Extracted runtime file is empty or too large: ${output.absolutePath}"
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }
}

val verifyEmbeddedTerminalArchiveSafety by tasks.registering {
    group = "verification"
    description = "Checks embedded terminal archive path validation rules."
    doLast {
        check(normalizeSafeTarEntry("./data/data/com.termux/files/usr/bin/proot") ==
            "data/data/com.termux/files/usr/bin/proot")
        listOf(
            "../outside",
            "data/../../outside",
            "/absolute/path",
            "C:/outside",
            "data\\..\\outside",
            "data:stream"
        ).forEach { unsafePath ->
            check(runCatching { normalizeSafeTarEntry(unsafePath) }.isFailure) {
                "Unsafe tar entry was accepted: $unsafePath"
            }
        }
        check(runCatching {
            indexValidatedTarEntries(listOf("./runtime/file", "runtime/file"))
        }.isFailure) { "Duplicate normalized tar entries were accepted" }
        check(runCatching {
            indexValidatedTarEntries((0..maxTarEntries).map { "runtime/file-$it" })
        }.isFailure) { "A tar archive exceeding the entry limit was accepted" }
        check(runCatching {
            SizeLimitedOutputStream(ByteArrayOutputStream(), 1).use { output ->
                output.write(byteArrayOf(1, 2))
            }
        }.isFailure) { "An extracted file exceeding its byte limit was accepted" }
    }
}

fun copyRuntimeFile(source: File, target: File, executable: Boolean) {
    check(source.isFile && source.length() > 0) { "Missing runtime file: ${source.absolutePath}" }
    target.parentFile?.mkdirs()
    source.copyTo(target, overwrite = true)
    target.setReadable(true, false)
    target.setWritable(true, true)
    if (executable) {
        target.setExecutable(true, false)
    }
}

val prootLoader32Output =
    layout.buildDirectory.file("generated/prootLoader32/arm64-v8a/libproot-loader32.so")

val buildProotLoader32For16Kb by tasks.registering {
    group = "build"
    description = "Builds the fixed PRoot v5.1.107.77 ARM32 loader with 16 KB ELF alignment."
    inputs.files(
        prootLoader32SourceChecksums.keys.map { relativePath ->
            prootLoader32SourceDir.file(relativePath)
        }
    ).withPropertyName("prootLoader32Sources")
    inputs.file(prootLoader32SourceDir.file("SOURCE.md")).withPropertyName("prootLoader32Provenance")
    inputs.property("prootSourceCommit", prootSourceCommit)
    inputs.property("prootSourceUrl", prootSourceUrl)
    inputs.property("prootSourceChecksum", prootSourceChecksum)
    inputs.property("prootLoader32Checksum", prootLoader32Checksum)
    inputs.property("embeddedRuntimeNdkVersion", embeddedRuntimeNdkVersion)
    inputs.property(
        "loader32LinkFlags",
        "--build-id=none,-Ttext=0x20000000,--rosegment,-z,noexecstack,-z,max-page-size=16384,-z,common-page-size=16384"
    )
    outputs.file(prootLoader32Output)
    outputs.upToDateWhen {
        runCatching {
            verifyVendoredProotLoader32Source(prootLoader32SourceDir.asFile)
            verifyElf32ArmLoader(prootLoader32Output.get().asFile)
        }.isSuccess
    }
    doLast {
        buildProotLoader32(
            sourceRoot = prootLoader32SourceDir.asFile,
            buildDir = temporaryDir.resolve("build"),
            outputFile = prootLoader32Output.get().asFile
        )
    }
}

val prepareEmbeddedTerminalRuntime by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/assets/embeddedTerminalRuntime/embedded-terminal-runtime")
    val jniOutputDir = layout.buildDirectory.dir("generated/jniLibs/embeddedTerminalRuntime")
    dependsOn(buildProotLoader32For16Kb)
    inputs.file(prootDebFile).withPropertyName("prootDebFile")
    inputs.file(prootLoader32Output).withPropertyName("prootLoader32")
    inputs.property("prootDebChecksum", prootDebChecksum)
    inputs.property("libtallocDebUrl", libtallocDebUrl)
    inputs.property("libtallocDebChecksum", libtallocDebChecksum)
    inputs.property("alpineMiniRootfsUrl", alpineMiniRootfsUrl)
    inputs.property("alpineMiniRootfsChecksum", alpineMiniRootfsChecksum)
    inputs.property("ubuntuBaseRootfsUrl", ubuntuBaseRootfsUrl)
    inputs.property("ubuntuBaseRootfsChecksum", ubuntuBaseRootfsChecksum)
    outputs.dir(outputDir)
    outputs.dir(jniOutputDir)
    doLast {
        val root = outputDir.get().asFile
        val jniRoot = jniOutputDir.get().asFile
        root.mkdirs()
        jniRoot.mkdirs()
        val workDir = temporaryDir.apply {
            deleteRecursively()
            mkdirs()
        }

        val prootDeb = workDir.resolve("proot.deb")
        copyVerifiedRuntimeFile(
            source = prootDebFile.asFile,
            target = prootDeb,
            expectedChecksum = prootDebChecksum
        )
        val prootPackageRoot = workDir.resolve("proot")
        unpackDebData(
            debFile = prootDeb,
            targetDir = prootPackageRoot,
            requiredFiles = listOf(
                RequiredTarFile("data/data/com.termux/files/usr/bin/proot", "data/data/com.termux/files/usr/bin/proot"),
                RequiredTarFile("data/data/com.termux/files/usr/libexec/proot/loader", "data/data/com.termux/files/usr/libexec/proot/loader")
            )
        )
        val prootPrefix = prootPackageRoot.resolve("data/data/com.termux/files/usr")
        copyRuntimeFile(
            source = prootPrefix.resolve("bin/proot"),
            target = root.resolve("proot"),
            executable = true
        )
        copyRuntimeFile(
            source = prootPrefix.resolve("libexec/proot/loader"),
            target = jniRoot.resolve("arm64-v8a/libproot-loader.so"),
            executable = true
        )
        copyRuntimeFile(
            source = prootLoader32Output.get().asFile,
            target = jniRoot.resolve("arm64-v8a/libproot-loader32.so"),
            executable = true
        )

        val libtallocDeb = workDir.resolve("libtalloc.deb")
        downloadRuntimeFile(
            localPath = libtallocDeb.absolutePath,
            remoteUrl = libtallocDebUrl,
            expectedChecksum = libtallocDebChecksum,
            maxBytes = maxDebArchiveBytes
        )
        val libtallocPackageRoot = workDir.resolve("libtalloc")
        unpackDebData(
            debFile = libtallocDeb,
            targetDir = libtallocPackageRoot,
            requiredFiles = listOf(
                RequiredTarFile(
                    "data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3",
                    "data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3"
                )
            )
        )
        copyRuntimeFile(
            source = libtallocPackageRoot.resolve("data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3"),
            target = root.resolve("libtalloc.so.2"),
            executable = false
        )

        downloadRuntimeFile(
            localPath = root.resolve("alpine.tar.gz").absolutePath,
            remoteUrl = alpineMiniRootfsUrl,
            expectedChecksum = alpineMiniRootfsChecksum,
            maxBytes = maxRootfsArchiveBytes
        )
        downloadRuntimeFile(
            localPath = root.resolve("ubuntu.tar.gz").absolutePath,
            remoteUrl = ubuntuBaseRootfsUrl,
            expectedChecksum = ubuntuBaseRootfsChecksum,
            maxBytes = maxRootfsArchiveBytes
        )
    }
}

tasks.named("preBuild") {
    dependsOn(prepareEmbeddedTerminalRuntime)
}


dependencies {
    api(libs.appcompat)
    api(libs.material)
    api(libs.constraintlayout)
    api(libs.navigation.fragment)
    api(libs.navigation.ui)
    api(libs.navigation.fragment.ktx)
    api(libs.navigation.ui.ktx)
    api(libs.activity)
    api(libs.lifecycle.viewmodel.ktx)
    api(libs.lifecycle.runtime.ktx)
    api(libs.activity.compose)
    api(platform(libs.compose.bom))
    api(libs.ui)
    api(libs.ui.graphics)
    api(libs.material3)
    api(libs.navigation.compose)
    api(project(":core:terminal-view"))
    api(project(":core:terminal-emulator"))
    api(libs.utilcode)
    //api(libs.commons.net)
    api(libs.okhttp)
    api(libs.anrwatchdog)
    api(libs.androidx.material.icons.core)
    api(libs.androidx.palette)
    api(libs.accompanist.systemuicontroller)
//    api(libs.termux.shared)

    api(project(":core:resources"))
    api(project(":core:components"))
}
