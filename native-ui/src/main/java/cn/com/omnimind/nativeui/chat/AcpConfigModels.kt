package cn.com.omnimind.nativeui.chat

import androidx.compose.runtime.Immutable

/*
 * The ACP config panel's model (batch 5e-3), ported from
 * `widgets/acp_config_button.dart`: every option the Agent declares, with
 * labels as presentation only; ids, values and types stay ACP-owned.
 */

@Immutable
data class AcpConfigChoice(val value: String, val label: String)

@Immutable
data class AcpConfigOption(
    val id: String,
    val label: String,
    val category: String?,
    /** `select`, `boolean`, or an unknown type shown read-only. */
    val type: String,
    val currentValue: Any?,
    val choices: List<AcpConfigChoice> = emptyList(),
) {
    val isBoolean: Boolean get() = type == "boolean"
    val isSelect: Boolean get() = type == "select" && choices.isNotEmpty()
    val currentLabel: String
        get() = when {
            isBoolean -> ""
            else -> choices.firstOrNull { it.value == currentValue?.toString() }?.label
                ?: currentValue?.toString() ?: "—"
        }
}

@Immutable
data class AcpConfigPanelState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    /** True while a turn runs: values are shown but cannot change. */
    val readOnly: Boolean = false,
    val options: List<AcpConfigOption> = emptyList(),
    val error: String? = null,
)

/** Dart `acpConfigLabel`: Chinese names for the known ids and categories. */
fun acpConfigLabel(id: String, name: String?, category: String?, english: Boolean): String {
    val fallback = name?.takeIf { it.isNotBlank() } ?: id
    if (english) return fallback
    when (category) {
        "thought_level" -> return "思考强度"
        "model" -> return "模型"
        "mode" -> return "运行模式"
    }
    return ZH_OPTION_LABELS[id] ?: fallback
}

private val ZH_OPTION_LABELS = mapOf(
    "reasoning_effort" to "思考强度",
    "thinking_budget" to "思考预算",
    "enable_thinking" to "启用思考",
    "temperature" to "回答随机性",
    "top_p" to "采样范围",
    "max_tokens" to "最大输出长度",
    "max_output_tokens" to "最大输出长度",
    "max_completion_tokens" to "最大输出长度",
    "model" to "模型",
    "mode" to "运行模式",
    "approval_policy" to "操作确认方式",
    "sandbox_mode" to "执行权限",
    "collaboration_mode" to "协作模式",
)

private val ZH_EFFORT_LABELS = mapOf(
    "default" to "模型默认", "none" to "关闭", "minimal" to "极低", "low" to "低",
    "medium" to "中", "high" to "高", "xhigh" to "极高", "max" to "最高",
)

/**
 * Parses `configOptions` of an ACP response (Dart `acpConfigOptions` and the
 * panel's value flattening): grouped choices are flattened, an option without
 * an id is dropped, and nothing is invented for an unknown type.
 */
fun parseAcpConfigOptions(raw: Any?, english: Boolean): List<AcpConfigOption> =
    (raw as? List<*>).orEmpty().mapNotNull { item ->
        val option = item as? Map<*, *> ?: return@mapNotNull null
        val id = (option["id"] ?: option["configId"])?.toString()?.trim()?.ifEmpty { null } ?: return@mapNotNull null
        val category = option["category"]?.toString()
        val thought = category == "thought_level" || id == "reasoning_effort"
        val choices = (option["options"] as? List<*>).orEmpty().flatMap { entry ->
            val map = entry as? Map<*, *> ?: return@flatMap emptyList()
            (map["options"] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: listOf(map)
        }.mapNotNull { choice ->
            val value = choice["value"]?.toString() ?: return@mapNotNull null
            val name = choice["name"]?.toString()?.takeIf { it.isNotBlank() }
            val label = if (!english && thought) ZH_EFFORT_LABELS[value] ?: name ?: value else name ?: value
            AcpConfigChoice(value, label)
        }
        AcpConfigOption(
            id = id,
            label = acpConfigLabel(id, option["name"]?.toString(), category, english),
            category = category,
            type = option["type"]?.toString().orEmpty(),
            currentValue = option["currentValue"],
            choices = choices,
        )
    }
