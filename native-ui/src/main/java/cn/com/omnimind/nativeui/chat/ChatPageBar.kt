package cn.com.omnimind.nativeui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.AgentBrandIcon
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayListPopup

/** One selectable Harness in the chat page's switcher (batch 5e-3). */
@Immutable
data class ChatHarnessOption(val id: String, val name: String)

/** The chat page's app bar state. Null [harness] hides the switcher (pure chat). */
@Immutable
data class ChatPageBarState(
    val harness: ChatHarnessOption? = null,
    val harnessChoices: List<ChatHarnessOption> = emptyList(),
    /** Switching is refused while any turn runs; the menu shows why. */
    val switchLocked: Boolean = false,
    val switching: Boolean = false,
    /** Null hides the config button (pure chat, remote Codex). */
    val config: AcpConfigPanelState? = null,
)

class ChatPageBarActions(
    val onSelectHarness: (agentId: String) -> Unit = {},
    val onNewConversation: () -> Unit = {},
    val onOpenConfig: () -> Unit = {},
    val onDismissConfig: () -> Unit = {},
    val onRefreshConfig: () -> Unit = {},
    val onSetConfig: (configId: String, value: Any) -> Unit = { _, _ -> },
)

/**
 * The Harness chip and the new-conversation action in the chat page's top
 * bar (Flutter `ChatAppBar`'s Agent switcher). Rendering only.
 */
@Composable
internal fun ChatPageBarActionsRow(
    state: ChatPageBarState,
    actions: ChatPageBarActions,
    agentAvatar: ImageBitmap?,
) {
    val palette = LocalOmniPalette.current
    val harness = state.harness
    if (harness != null) {
        var show by rememberSaveable { mutableStateOf(false) }
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = !state.switching && state.harnessChoices.size > 1, role = Role.Button) { show = true }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentBrandIcon(harness.id, agentAvatar, Modifier.padding(end = 6.dp))
                Text(
                    harness.name,
                    fontSize = 13.sp,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
                if (state.harnessChoices.size > 1) {
                    Spacer(Modifier.width(2.dp))
                    OmniIcon(R.drawable.omni_chevron_down, tint = palette.tertiaryText, size = 14.dp)
                }
            }
            OverlayListPopup(show = show, minWidth = 200.dp, maxHeight = 360.dp, onDismissRequest = { show = false }) {
                ListPopupColumn {
                    state.harnessChoices.forEachIndexed { index, choice ->
                        DropdownImpl(choice.name, state.harnessChoices.size, choice.id == harness.id, index, onSelectedIndexChange = {
                            show = false
                            if (choice.id != harness.id) actions.onSelectHarness(choice.id)
                        })
                    }
                }
            }
        }
    }
    state.config?.let { config ->
        OmniIconButton(R.drawable.omni_settings_2, stringResource(R.string.omni_acp_config_title), actions.onOpenConfig, size = 20.dp)
        AcpConfigSheet(config, actions.onDismissConfig, actions.onRefreshConfig, actions.onSetConfig)
    }
    OmniIconButton(
        R.drawable.omni_message_square,
        stringResource(R.string.omni_home_drawer_new_chat),
        actions.onNewConversation,
        size = 20.dp,
    )
}
