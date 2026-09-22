package cn.com.omnimind.nativeui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.LegacyDestination
import cn.com.omnimind.nativeui.NativeHomeState
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text

/** The landing surface never starts a prompt. Input is handed to the existing chat owner. */
@Composable
internal fun HomeScreen(
    state: NativeHomeState,
    onDrawer: () -> Unit,
    onOpen: (LegacyDestination) -> Unit,
) {
    val palette = LocalOmniPalette.current
    val openChat = { onOpen(LegacyDestination.NewConversation()) }
    Scaffold(
        containerColor = palette.page,
        topBar = { HomeTopBar(onDrawer, onOpen) },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                // This is an entry point, not a second composer or send pipeline.
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(palette.surface)
                    .clickable(role = Role.Button, onClick = openChat).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(stringResource(R.string.omni_composer_hint), fontSize = 15.sp, color = palette.tertiaryText,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OmniIconButton(R.drawable.omni_plus, stringResource(R.string.omni_input_tools), openChat, size = 20.dp)
                        OmniIconButton(R.drawable.omni_square_terminal, stringResource(R.string.omni_settings_alpine_title),
                            { onOpen(LegacyDestination.Page.Terminal) }, size = 20.dp)
                        Spacer(Modifier.weight(1f))
                        OmniIconButton(R.drawable.omni_mic, stringResource(R.string.omni_voice), openChat, size = 20.dp)
                    }
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = BiasAlignment(0f, -.18f)) {
            if (state.greetingEnabled) {
                Column(Modifier.widthIn(max = 520.dp).padding(horizontal = 28.dp)) {
                    Text(stringResource(R.string.omni_greeting), fontSize = 19.sp, lineHeight = 24.7.sp, color = palette.text)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(stringResource(R.string.omni_greeting_help), fontSize = 19.sp, lineHeight = 24.7.sp, color = palette.secondaryText)
                        Text(stringResource(R.string.omni_greeting_verb), fontSize = 19.sp, lineHeight = 24.7.sp,
                            color = palette.accent, fontFamily = FontFamily.Serif)
                    }
                    if (state.quickPrompts.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.quickPrompts.take(2).forEach { prompt ->
                                Text(prompt.title, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    color = if (palette.dark) palette.accent else palette.text,
                                    modifier = Modifier.clip(CircleShape)
                                        .background(palette.accent.copy(alpha = if (palette.dark) .13f else .09f))
                                        .clickable(role = Role.Button) { onOpen(LegacyDestination.NewConversation(prompt.prompt)) }
                                        .padding(horizontal = 13.dp, vertical = 9.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(onDrawer: () -> Unit, onOpen: (LegacyDestination) -> Unit) {
    val palette = LocalOmniPalette.current
    BoxWithConstraints(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp).height(50.dp)) {
        // Matches ChatAppBar's symmetric reservation so the island stays screen-centered.
        val islandWidth = (maxWidth - 228.dp).coerceIn(0.dp, 176.dp)
        val islandLeft = (maxWidth - islandWidth) / 2
        val accessoryLeft = 62.dp + ((islandLeft - 114.dp) / 2).coerceAtLeast(0.dp)
        OmniIconButton(R.drawable.omni_menu, stringResource(R.string.omni_open_drawer), onDrawer,
            Modifier.align(Alignment.CenterStart).width(50.dp))
        // The pet and agent controls still belong to the chat feature during this slice.
        OmniIconButton(R.drawable.omni_paw_print, stringResource(R.string.omni_pet),
            { onOpen(LegacyDestination.NewConversation()) }, Modifier.offset(x = accessoryLeft).align(Alignment.CenterStart).width(40.dp))
        Row(Modifier.align(Alignment.Center).width(islandWidth).height(34.dp).clip(CircleShape).background(palette.surface)) {
            val gradient = if (palette.dark) listOf(Color(0xFFAA9774), Color(0xFF8FA38A))
                else listOf(Color(0xFF00AEFF), Color(0xFF4658FF))
            Box(Modifier.weight(1f).fillMaxHeight().padding(2.dp).clip(CircleShape)
                .background(Brush.horizontalGradient(gradient)), contentAlignment = Alignment.Center) {
                OmniIcon(R.drawable.omni_bot, stringResource(R.string.omni_chat), tint = Color.White, size = 19.dp)
            }
            Box(Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Button) { onOpen(LegacyDestination.Page.Workspace) },
                contentAlignment = Alignment.Center) {
                OmniIcon(R.drawable.omni_folders, stringResource(R.string.omni_workspace), tint = palette.tertiaryText, size = 19.dp)
            }
        }
        OmniIconButton(R.drawable.omni_circle_chevron_down, stringResource(R.string.omni_agent_select),
            { onOpen(LegacyDestination.Page.Agents) }, Modifier.align(Alignment.CenterEnd).width(50.dp))
    }
}
