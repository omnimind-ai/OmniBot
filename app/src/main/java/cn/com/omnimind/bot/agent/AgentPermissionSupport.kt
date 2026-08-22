package cn.com.omnimind.bot.agent

/**
 * Maps human-readable permission names from tool handlers to the stable ids
 * consumed by the chat authorization UI.
 */
internal fun resolveAgentPermissionIds(missing: List<String>): List<String> {
    val aliases = mapOf(
        "无障碍权限" to "accessibility",
        "Android GUI 无障碍权限" to "accessibility",
        "Accessibility" to "accessibility",
        "Accessibility Permission" to "accessibility",
        "悬浮窗权限" to "overlay",
        "Overlay" to "overlay",
        "Overlay Permission" to "overlay",
        "应用列表读取权限" to "installed_apps",
        "Installed Apps Access" to "installed_apps",
        "Installed Apps Permission" to "installed_apps",
        "Shizuku 权限" to "shizuku",
        "Shizuku Permission" to "shizuku",
        "内置 workspace" to "workspace_storage",
        "Built-in workspace" to "workspace_storage",
        "公共文件访问" to "public_storage",
        "Public Storage Access" to "public_storage",
    )
    return missing.mapNotNull { aliases[it.trim()] }.distinct()
}
