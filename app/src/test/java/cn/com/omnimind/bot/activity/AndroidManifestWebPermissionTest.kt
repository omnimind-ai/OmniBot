package cn.com.omnimind.bot.activity

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidManifestWebPermissionTest {
    @Test
    fun declaresEveryRuntimePermissionUsedByWebPermissionPolicy() {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(locateManifest())
        val permissionNodes = document.getElementsByTagName("uses-permission")
        val permissions = buildSet {
            for (index in 0 until permissionNodes.length) {
                permissionNodes.item(index).attributes
                    ?.getNamedItemNS(ANDROID_NAMESPACE, "name")
                    ?.nodeValue
                    ?.let(::add)
            }
        }

        setOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
        ).forEach { permission ->
            assertTrue("Manifest must declare $permission", permission in permissions)
        }
    }

    @Test
    fun reviewedProductionPermissionBaselinesIncludeWebRuntimePermissions() {
        val expectedEntries = setOf(
            "uses-permission|android.permission.CAMERA||",
            "uses-permission|android.permission.RECORD_AUDIO||",
            "uses-permission|android.permission.ACCESS_COARSE_LOCATION||",
            "uses-permission|android.permission.ACCESS_FINE_LOCATION||",
        )

        listOf(
            locateRepositoryFile("scripts/production-standard-permissions.txt"),
            locateRepositoryFile("scripts/production-play-permissions.txt"),
        ).forEach { baseline ->
            val entries = baseline.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith('#') }
            expectedEntries.forEach { entry ->
                assertTrue("${baseline.name} must include $entry", entry in entries)
            }
        }
    }

    private fun locateManifest(): File {
        return sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull(File::isFile)
            ?: error("Unable to locate app AndroidManifest.xml from ${File(".").absolutePath}")
    }

    private fun locateRepositoryFile(relativePath: String): File {
        return sequenceOf(
            File(relativePath),
            File("../$relativePath"),
        ).firstOrNull(File::isFile)
            ?: error("Unable to locate $relativePath from ${File(".").absolutePath}")
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
