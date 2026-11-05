package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.bot.R
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

internal fun acpEventBridgeTestContext(locale: PromptLocale = PromptLocale.ZH_CN): Context {
    val context = mock(Context::class.java)
    val preferences = mock(SharedPreferences::class.java)
    `when`(context.applicationContext).thenReturn(context)
    `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
    `when`(preferences.getString("flutter.language_option", null))
        .thenReturn(if (locale == PromptLocale.EN_US) "en" else "zhHans")
    stubAcpTestResources(context, locale)
    return context
}

/** Supply Android resource IO while keeping ACP tests on the plain JVM. */
@Suppress("DEPRECATION")
internal fun stubAcpTestResources(context: Context, locale: PromptLocale = PromptLocale.ZH_CN) {
    val resources = mock(Resources::class.java)
    val configuration = mock(Configuration::class.java).apply { this.locale = locale.locale }
    val locales = mock(LocaleList::class.java)
    `when`(locales.isEmpty).thenReturn(false)
    `when`(locales[0]).thenReturn(locale.locale)
    `when`(configuration.locales).thenReturn(locales)
    `when`(resources.configuration).thenReturn(configuration)
    `when`(context.resources).thenReturn(resources)
    `when`(context.createConfigurationContext(any(Configuration::class.java))).thenReturn(context)
    `when`(context.getString(anyInt())).thenAnswer { call ->
        toolTitleStrings.getValue(locale).getValue(call.getArgument<Int>(0))
    }
}

private val toolTitleStrings: Map<PromptLocale, Map<Int, String>> by lazy {
    PromptLocale.entries.associateWith { locale ->
        val qualifier = if (locale == PromptLocale.EN_US) "values-en" else "values"
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$qualifier/strings.xml"))
        val strings = document.getElementsByTagName("string")
        (0 until strings.length).mapNotNull { index ->
            val node = strings.item(index)
            val name = node.attributes.getNamedItem("name").nodeValue
            if (name.startsWith("tool_")) {
                R.string::class.java.getField(name).getInt(null) to node.textContent
            } else {
                null
            }
        }.toMap()
    }
}
