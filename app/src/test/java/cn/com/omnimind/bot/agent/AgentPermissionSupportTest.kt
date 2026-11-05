package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AgentPermissionSupportTest {
    @Test
    fun `maps accessibility aliases to the stable authorization id`() {
        assertEquals(
            listOf("accessibility"),
            resolveAgentPermissionIds(
                listOf("无障碍权限", "Android GUI 无障碍权限", "Accessibility")
            )
        )
    }

    @Test
    fun `maps the other shared permission aliases`() {
        assertEquals(
            listOf("overlay", "installed_apps", "shizuku", "public_storage"),
            resolveAgentPermissionIds(
                listOf(
                    "Overlay",
                    "Installed Apps Access",
                    "Shizuku Permission",
                    "Public Storage Access",
                )
            )
        )
    }

    @Test
    fun `localized automation permission labels preserve the missing permission scope`() {
        for (resourceDirectory in listOf("values", "values-en")) {
            val strings = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(File("src/main/res/$resourceDirectory/strings.xml"))
                .getElementsByTagName("string")
            val labels = (0 until strings.length).associate { index ->
                val element = strings.item(index)
                element.attributes.getNamedItem("name").nodeValue to element.textContent
            }
            val accessibility = labels.getValue("permission_accessibility")
            val overlay = labels.getValue("permission_overlay")

            assertEquals(
                "$resourceDirectory accessibility only",
                listOf("accessibility"),
                resolveAgentPermissionIds(listOf(accessibility)),
            )
            assertEquals(
                "$resourceDirectory overlay only",
                listOf("overlay"),
                resolveAgentPermissionIds(listOf(overlay)),
            )
            assertEquals(
                "$resourceDirectory both permissions",
                listOf("accessibility", "overlay"),
                resolveAgentPermissionIds(listOf(accessibility, overlay)),
            )
        }
    }
}
