package cn.com.omnimind.nativeui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.theme.LocalOmniPalette

/**
 * Agent identity mark (ui/lib/widgets/agent_brand_icon.dart): the brand
 * artwork for known Harnesses, the user's avatar for Xiaowan, and a robot
 * for custom Agents.
 */
@Composable
internal fun AgentBrandIcon(
    agentId: String,
    xiaowanAvatar: ImageBitmap?,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    description: String? = null,
    tint: Color? = null,
) {
    val palette = LocalOmniPalette.current
    when (normalizeAgentBrandId(agentId)) {
        "xiaowan-acp" -> if (xiaowanAvatar != null) {
            Image(xiaowanAvatar, description, modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            OmniIcon(R.drawable.omni_bot, description, modifier, size, tint ?: palette.accent)
        }
        "kimi-code-acp" -> OmniIcon(R.drawable.omni_brand_moonshot, description, modifier, size, tint ?: Color(0xFF1783FF))
        "claude-code-acp" -> OmniIcon(R.drawable.omni_brand_claude, description, modifier, size, tint ?: Color(0xFFD97757))
        "codex-acp" -> OmniIcon(R.drawable.omni_brand_codex, description, modifier, size, tint ?: palette.text)
        "opencode-acp" -> OmniIcon(R.drawable.omni_brand_opencode, description, modifier, size, tint ?: palette.text)
        "deepseek-harness-acp" -> OmniIcon(R.drawable.omni_brand_deepseek, description, modifier, size, tint ?: Color(0xFF4D6BFE))
        else -> OmniIcon(R.drawable.omni_bot, description, modifier, size, tint ?: palette.accent)
    }
}

/** `AgentBrandIcon.hasKnownBrand`: known marks render bare, custom Agents sit in a badge. */
internal fun hasKnownAgentBrand(agentId: String): Boolean =
    normalizeAgentBrandId(agentId) in setOf(
        "xiaowan-acp", "codex-acp", "kimi-code-acp", "claude-code-acp", "opencode-acp", "deepseek-harness-acp",
    )

internal fun normalizeAgentBrandId(agentId: String): String = when (agentId.trim().lowercase()) {
    "xiaowan", "xiaowan-acp" -> "xiaowan-acp"
    "codex", "codex-acp", "codex-remote" -> "codex-acp"
    "kimi", "kimi-code", "kimi-code-acp" -> "kimi-code-acp"
    "claude", "claude-code", "claude-code-acp" -> "claude-code-acp"
    "opencode", "open-code", "opencode-acp" -> "opencode-acp"
    "deepseek", "deepseek-acp", "deepseek-harness", "deepseek_harness", "deepseek-harness-acp" ->
        "deepseek-harness-acp"
    else -> agentId.trim().lowercase()
}
