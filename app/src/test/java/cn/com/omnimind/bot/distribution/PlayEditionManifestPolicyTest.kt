package cn.com.omnimind.bot.distribution

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayEditionManifestPolicyTest {
    @Test
    fun playManifestRemovesRestrictedPermissionsKeptByStandard() {
        val restricted = setOf(
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.REQUEST_INSTALL_PACKAGES",
        )
        val mainPermissions = permissionNodes(locate("app/src/main/AndroidManifest.xml"))
            .associateBy { it.name }
        val playPermissions = permissionNodes(locate("app/src/play/AndroidManifest.xml"))
            .associateBy { it.name }

        restricted.forEach { permission ->
            assertTrue("Standard source manifest must keep $permission", permission in mainPermissions)
            assertTrue("Play source manifest must remove $permission", permission in playPermissions)
            assertTrue(
                "Play removal for $permission must use tools:node=remove",
                playPermissions.getValue(permission).remove,
            )
        }
    }

    @Test
    fun reviewedPermissionBaselinesMatchEditionSplit() {
        val playBaseline = baselineNames(locate("scripts/production-play-permissions.txt"))
        val standardBaseline = baselineNames(locate("scripts/production-standard-permissions.txt"))
        val restricted = setOf(
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
        )

        restricted.forEach { permission ->
            assertFalse("Play baseline must exclude $permission", permission in playBaseline)
            assertTrue("Standard baseline must keep $permission", permission in standardBaseline)
        }
    }

    @Test
    fun buildFlavorsDeclareOppositeCapabilityFlags() {
        val buildFile = locate("app/build.gradle.kts").readText()
        val standard = flavorSection(buildFile, "standard", "play")
        val play = flavorSection(buildFile, "play", null)

        listOf("APP_CAN_QUERY_INSTALLED_APPS", "APP_CAN_MANAGE_PUBLIC_STORAGE")
            .forEach { flag ->
                assertTrue("Standard must enable $flag", standard.contains("\"$flag\", \"true\""))
                assertTrue("Play must disable $flag", play.contains("\"$flag\", \"false\""))
            }
    }

    @Test
    fun installedAppChannelQueriesPreserveCoroutineCancellation() {
        val source = locate(
            "app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt"
        ).readText()
        listOf(
            "fun getInstalledApplications(",
            "fun getInstalledApplicationsWithIconUpdate(",
        ).forEach { signature ->
            val methodStart = source.indexOf(signature)
            assertTrue("Missing $signature", methodStart >= 0)
            val nextMethod = source.indexOf("\n    fun ", methodStart + signature.length)
                .takeIf { it >= 0 }
                ?: source.length
            val method = source.substring(methodStart, nextMethod)
            val cancellationCatch = method.indexOf("catch (error: CancellationException)")
            val genericCatch = method.indexOf("catch (_: Exception)")
            assertTrue("$signature must rethrow CancellationException", cancellationCatch >= 0)
            assertTrue("$signature must catch cancellation before Exception", genericCatch > cancellationCatch)
            assertTrue(
                "$signature cancellation branch must rethrow",
                method.substring(cancellationCatch, genericCatch).contains("throw error"),
            )
        }

        val repositorySource = locate(
            "app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeContextRepository.kt"
        ).readText()
        val cancellationCatch = repositorySource.indexOf("catch (error: CancellationException)")
        val genericCatch = repositorySource.indexOf("catch (_: Exception)")
        assertTrue("Runtime app query must rethrow CancellationException", cancellationCatch >= 0)
        assertTrue(
            "Runtime app query must catch cancellation before Exception",
            genericCatch > cancellationCatch,
        )
        assertTrue(
            "Runtime app query cancellation branch must rethrow",
            repositorySource.substring(cancellationCatch, genericCatch).contains("throw error"),
        )
    }

    private fun permissionNodes(file: File): List<PermissionNode> {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("uses-permission")
        return buildList {
            for (index in 0 until nodes.length) {
                val attributes = nodes.item(index).attributes ?: continue
                val name = attributes.getNamedItemNS(ANDROID_NAMESPACE, "name")
                    ?.nodeValue ?: continue
                val remove = attributes.getNamedItemNS(TOOLS_NAMESPACE, "node")
                    ?.nodeValue == "remove"
                add(PermissionNode(name = name, remove = remove))
            }
        }
    }

    private fun baselineNames(file: File): Set<String> = file.readLines()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .mapNotNull { it.split('|').getOrNull(1) }
        .toSet()

    private fun flavorSection(source: String, flavor: String, nextFlavor: String?): String {
        val start = source.indexOf("create(\"$flavor\")")
        check(start >= 0) { "Missing $flavor flavor" }
        val end = nextFlavor?.let { source.indexOf("create(\"$it\")", start + 1) }
            ?.takeIf { it >= 0 }
            ?: source.length
        return source.substring(start, end)
    }

    private fun locate(relativePath: String): File = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)
        ?: error("Unable to locate $relativePath from ${File(".").absolutePath}")

    private data class PermissionNode(val name: String, val remove: Boolean)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NAMESPACE = "http://schemas.android.com/tools"
    }
}
