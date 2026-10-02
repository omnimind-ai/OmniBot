package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniConfirmDialog
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.components.OmniPage
import cn.com.omnimind.nativeui.components.OmniSwitch
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TooltipBox

/**
 * Skill store. Presentation only; the registry/workspace store and the official
 * repository sync stay with SkillIndexService behind the host ViewModel.
 */
@Composable
fun SkillStoreScreen(
    state: SkillStoreState,
    actions: SkillStoreActions,
    onBack: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val notice = state.notice?.let {
        if (state.noticeArg != null) stringResource(it, state.noticeArg) else stringResource(it)
    }
    val syncTooltip = stringResource(R.string.omni_skill_sync_tooltip)
    OmniPage(
        stringResource(R.string.omni_skill_store_title), onBack,
        notice = notice, onNoticeShown = actions.dismissNotice,
        actions = {
            if (state.syncing) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                }
            } else {
                OmniIconButton(R.drawable.omni_hard_drive_download, syncTooltip, actions.syncOfficial)
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            SkillSearchField(state.query, actions.setQuery,
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp))
            if (!state.loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val query = state.query.trim().lowercase()
                val visible = state.skills.filter {
                    query.isEmpty() || it.name.lowercase().contains(query) ||
                        it.description.lowercase().contains(query)
                }
                when {
                    state.skills.isEmpty() -> EmptyState(R.drawable.omni_puzzle,
                        stringResource(R.string.omni_skill_empty))
                    visible.isEmpty() -> EmptyState(R.drawable.omni_search,
                        stringResource(R.string.omni_skill_search_empty))
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 24.dp),
                    ) {
                        items(visible.size, key = { visible[it].id }) { index ->
                            SkillRow(visible[index], visible[index].id in state.busyIds, actions)
                            if (index < visible.lastIndex) {
                                Box(Modifier.padding(start = 28.dp).fillMaxWidth().height(1.dp)
                                    .background(palette.border.copy(alpha = if (palette.dark) .5f else .78f)))
                            }
                        }
                    }
                }
            }
        }
    }
    val deleting = state.skills.firstOrNull { it.id == state.deletingId }
    OmniConfirmDialog(
        show = deleting != null,
        title = stringResource(R.string.omni_skill_delete_title),
        summary = stringResource(R.string.omni_skill_delete_confirm, deleting?.name.orEmpty()),
        confirmText = stringResource(R.string.omni_skill_delete),
        onConfirm = actions.deleteConfirmed,
        onDismiss = { actions.confirmDelete(null) },
    )
}

@Composable
private fun SkillSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalOmniPalette.current
    val hint = stringResource(R.string.omni_skill_search_hint)
    BasicTextField(
        value = query, onValueChange = onQuery, singleLine = true,
        textStyle = TextStyle(fontSize = 13.sp, color = palette.text),
        cursorBrush = SolidColor(palette.accent),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = hint },
        decorationBox = { field ->
            Row(
                Modifier.height(36.dp).clip(CircleShape).background(palette.secondarySurface)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OmniIcon(R.drawable.omni_search, tint = palette.secondaryText)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(hint, fontSize = 13.sp, color = palette.tertiaryText,
                        maxLines = 1)
                    field()
                }
            }
        },
    )
}

@Composable
private fun EmptyState(icon: Int, text: String) {
    val palette = LocalOmniPalette.current
    Column(
        Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OmniIcon(icon, size = 48.dp, tint = palette.tertiaryText)
        Spacer(Modifier.height(12.dp))
        Text(text, fontSize = 16.sp, color = palette.secondaryText)
    }
}

@Composable
private fun SkillRow(skill: SkillItem, busy: Boolean, actions: SkillStoreActions) {
    val palette = LocalOmniPalette.current
    val statusLabels = buildList {
        if (skill.isBuiltin) add(stringResource(R.string.omni_skill_builtin))
        else if (skill.isOfficial) add(stringResource(R.string.omni_skill_official))
        else if (skill.installed) add(stringResource(R.string.omni_skill_installed))
        if (skill.installed) {
            add(stringResource(if (skill.enabled) R.string.omni_skill_enabled
                else R.string.omni_skill_disabled))
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp, end = 2.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(skill.name, fontSize = 14.sp, lineHeight = 21.sp,
                        fontWeight = FontWeight.Medium, color = palette.text)
                    if (skill.isBuiltin || skill.isOfficial) {
                        Spacer(Modifier.width(6.dp))
                        val badge = stringResource(if (skill.isBuiltin) R.string.omni_skill_builtin
                            else R.string.omni_skill_official)
                        TooltipBox(badge) {
                            OmniIcon(R.drawable.omni_badge_check, badge, size = 16.dp,
                                tint = palette.accent)
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    skill.description.trim().ifEmpty { stringResource(R.string.omni_skill_no_description) },
                    fontSize = 11.sp, lineHeight = 17.05.sp, color = palette.secondaryText,
                )
                if (statusLabels.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        statusLabels.joinToString(" · "),
                        fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
                        color = if (skill.installed && skill.enabled) palette.accent
                            else palette.tertiaryText,
                    )
                }
                if (!skill.installed && skill.isBuiltin) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.omni_skill_builtin_removed_desc), fontSize = 11.sp,
                        lineHeight = 16.5.sp, color = palette.tertiaryText)
                }
                if (skill.installed) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            skill.shellSkillFilePath, fontSize = 11.sp, lineHeight = 16.sp,
                            color = palette.tertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (!skill.isOfficial) {
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.omni_skill_delete),
                                fontSize = 12.sp, color = Color(0xFFE05252),
                                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = !busy, role = Role.Button) {
                                        actions.confirmDelete(skill.id)
                                    }
                                    .padding(vertical = 3.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(64.dp).height(28.dp), contentAlignment = Alignment.TopEnd) {
                if (busy) {
                    CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp,
                        modifier = Modifier.padding(top = 4.dp))
                } else if (skill.installed) {
                    OmniSwitch(skill.enabled, { actions.toggle(skill.id, it) },
                        contentDescription = skill.name)
                } else {
                    Text(
                        stringResource(R.string.omni_skill_install),
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = palette.accent.copy(alpha = if (skill.isBuiltin) 1f else .4f),
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = skill.isBuiltin, role = Role.Button) {
                                actions.installBuiltin(skill.id)
                            }
                            .padding(horizontal = 4.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}
