package cn.com.omnimind.bot.update

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyConsentDisclosureResourceTest {
    @Test
    fun chineseAndEnglishDisclosuresMatchAutomaticStartupPolicy() {
        val chinese = readString("values", "privacy_consent_message")
        listOf("平台模型目录", "检查更新", "局域网 MCP", "后台 AI", "主动发起账号或 AI 请求", "随机安装标识").forEach {
            assertTrue("Chinese disclosure must mention $it", chinese.contains(it))
        }

        val english = readString("values-en", "privacy_consent_message").lowercase()
        listOf(
            "platform model catalog",
            "check for updates",
            "lan mcp listener",
            "background ai",
            "requests you start yourself",
            "random installation identifier",
        ).forEach {
            assertTrue("English disclosure must mention $it", english.contains(it))
        }
    }

    private fun readString(valuesDirectory: String, name: String): String {
        val file = sequenceOf(
            File("src/main/res/$valuesDirectory/strings.xml"),
            File("app/src/main/res/$valuesDirectory/strings.xml"),
        ).firstOrNull(File::isFile)
            ?: error("Unable to locate $valuesDirectory/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = document.getElementsByTagName("string")
        for (index in 0 until strings.length) {
            val node = strings.item(index)
            if (node.attributes?.getNamedItem("name")?.nodeValue == name) {
                return node.textContent
            }
        }
        error("Missing string resource $name")
    }
}
