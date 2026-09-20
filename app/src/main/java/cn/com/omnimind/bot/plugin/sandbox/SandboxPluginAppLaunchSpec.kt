package cn.com.omnimind.bot.plugin.sandbox

object SandboxPluginAppLaunchSpec {
    private const val PLUGIN_ID_PREFIX = "local.project."
    private const val SHORTCUT_ID_PREFIX = "vibe-app:"
    private const val URI_PREFIX = "omnibot://plugin-app/"
    private val pluginIdPattern = Regex(
        "^local\\.project\\.[a-z0-9]+(?:-[a-z0-9]+)*$",
    )

    fun shortcutId(pluginId: String): String =
        "$SHORTCUT_ID_PREFIX${requirePluginId(pluginId)}"

    fun uri(pluginId: String): String = "$URI_PREFIX${requirePluginId(pluginId)}"

    fun pluginId(uri: String?): String? = uri
        ?.takeIf { it.startsWith(URI_PREFIX) }
        ?.removePrefix(URI_PREFIX)
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.takeIf(pluginIdPattern::matches)

    private fun requirePluginId(pluginId: String): String {
        require(pluginId.startsWith(PLUGIN_ID_PREFIX) && pluginIdPattern.matches(pluginId)) {
            "Invalid Vibe plugin id: $pluginId"
        }
        return pluginId
    }
}
