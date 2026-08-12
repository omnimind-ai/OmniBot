package cn.com.omnimind.bot.activity

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidManifestComponentExposureTest {
    @Test
    fun onlyIntendedExternalActivityEntrypointsRemainExported() {
        val manifest = locateManifest()
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)
        val activities = document.getElementsByTagName("activity")
        val exportedByName = buildMap {
            for (index in 0 until activities.length) {
                val activity = activities.item(index)
                val attributes = activity.attributes
                val name = attributes.getNamedItemNS(ANDROID_NAMESPACE, "name")?.nodeValue ?: continue
                val exported = attributes.getNamedItemNS(ANDROID_NAMESPACE, "exported")?.nodeValue
                put(name, exported)
            }
        }

        listOf(
            ".activity.MainActivity",
            ".activity.QuickLogEntryActivity",
            ".activity.QuickLogWidgetSettingsActivity",
            ".activity.QuickLogWidgetBridgeActivity",
        ).forEach { componentName ->
            assertEquals("$componentName must remain internal", "false", exportedByName[componentName])
        }
        listOf(
            ".activity.LauncherActivity",
            ".activity.McpFileReceiverActivity",
        ).forEach { componentName ->
            assertEquals("$componentName is an intended external entrypoint", "true", exportedByName[componentName])
        }
    }

    private fun locateManifest(): File {
        return sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull(File::isFile)
            ?: error("Unable to locate app AndroidManifest.xml from ${File(".").absolutePath}")
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
