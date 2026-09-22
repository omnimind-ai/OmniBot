package cn.com.omnimind.nativeui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.LegacyDestination.Page
import cn.com.omnimind.nativeui.NativeHomeActions
import cn.com.omnimind.nativeui.NativeHomeState
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniTopBar
import cn.com.omnimind.nativeui.components.SectionTitle
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text

private data class SettingItem(
    @DrawableRes val icon: Int,
    @StringRes val title: Int,
    @StringRes val subtitle: Int? = null,
    val destination: SettingDestination,
)

private sealed interface SettingDestination {
    data class Legacy(val page: Page) : SettingDestination
    data object LocalService : SettingDestination
    data object About : SettingDestination
    data object Permissions : SettingDestination
}

private data class SettingSection(@StringRes val title: Int, val items: List<SettingItem>)

// Destination ownership is explicit here; no page interprets arbitrary route strings.
private val sections = listOf(
    SettingSection(R.string.omni_account_section, listOf(
        SettingItem(R.drawable.omni_user_round_cog, R.string.omni_account_title, R.string.omni_account_subtitle, SettingDestination.Legacy(Page.Account)),
    )),
    SettingSection(R.string.omni_settings_section_model_memory, listOf(
        SettingItem(R.drawable.omni_box, R.string.omni_settings_model_provider_title, R.string.omni_settings_model_provider_subtitle, SettingDestination.Legacy(Page.ModelProviders)),
        SettingItem(R.drawable.omni_file_box, R.string.omni_settings_scene_model_title, R.string.omni_settings_scene_model_subtitle, SettingDestination.Legacy(Page.SceneModels)),
        SettingItem(R.drawable.omni_database, R.string.omni_settings_workspace_memory_title, destination = SettingDestination.Legacy(Page.WorkspaceMemory)),
    )),
    SettingSection(R.string.omni_settings_section_service_environment, listOf(
        SettingItem(R.drawable.omni_bot, R.string.omni_agents_title, R.string.omni_agents_subtitle, SettingDestination.Legacy(Page.Agents)),
        SettingItem(R.drawable.omni_square_terminal, R.string.omni_settings_alpine_title, R.string.omni_settings_alpine_subtitle, SettingDestination.Legacy(Page.Terminal)),
        SettingItem(R.drawable.omni_monitor_smartphone, R.string.omni_settings_local_service_title, R.string.omni_settings_local_service_subtitle, SettingDestination.LocalService),
        SettingItem(R.drawable.omni_hammer, R.string.omni_settings_mcp_tools_title, R.string.omni_settings_mcp_tools_subtitle, SettingDestination.Legacy(Page.McpTools)),
    )),
    SettingSection(R.string.omni_settings_section_experience_appearance, listOf(
        SettingItem(R.drawable.omni_palette, R.string.omni_settings_appearance_title, R.string.omni_settings_appearance_subtitle, SettingDestination.Legacy(Page.Appearance)),
        SettingItem(R.drawable.omni_settings_2, R.string.omni_misc_title, R.string.omni_misc_subtitle, SettingDestination.Legacy(Page.Miscellaneous)),
    )),
    SettingSection(R.string.omni_settings_section_permission_info, listOf(
        SettingItem(R.drawable.omni_shield_check, R.string.omni_authorize_page_title, R.string.omni_permissions_subtitle, SettingDestination.Permissions),
        SettingItem(R.drawable.omni_hard_drive, R.string.omni_storage_usage_title, R.string.omni_storage_usage_subtitle, SettingDestination.Legacy(Page.Storage)),
        SettingItem(R.drawable.omni_info, R.string.omni_settings_about_title, destination = SettingDestination.About),
    )),
)

@Composable
internal fun SettingsScreen(
    state: NativeHomeState,
    onBack: () -> Unit,
    actions: NativeHomeActions,
    onAbout: () -> Unit,
    onPermissions: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    var showLocalService by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        containerColor = palette.page,
        topBar = { OmniTopBar(stringResource(R.string.omni_settings_title), onBack) },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(sections.size, key = { sections[it].title }) { index ->
                val section = sections[index]
                Column {
                    SectionTitle(stringResource(section.title), Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp))
                    section.items.forEachIndexed { itemIndex, item ->
                        SettingRow(item, itemIndex == section.items.lastIndex, state, actions, onAbout, onPermissions) { showLocalService = true }
                        if (itemIndex < section.items.lastIndex) {
                            Box(Modifier.padding(start = 30.dp).fillMaxWidth().height(1.dp)
                                .background(palette.border.copy(alpha = if (palette.dark) .5f else .78f)))
                        }
                    }
                }
            }
        }
        LocalServiceSheet(
            show = showLocalService && state.localServiceEnabled,
            state = state,
            onDismiss = { showLocalService = false },
            onRefreshToken = actions.refreshLocalServiceToken,
        )
    }
}

@Composable
private fun SettingRow(
    item: SettingItem,
    isLast: Boolean,
    state: NativeHomeState,
    actions: NativeHomeActions,
    onAbout: () -> Unit,
    onPermissions: () -> Unit,
    onLocalServiceDetails: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val title = stringResource(item.title)
    val subtitle = if (item.destination == SettingDestination.Legacy(Page.WorkspaceMemory)) {
        stringResource(if (state.workspaceMemoryConfigured) R.string.omni_memory_configured else R.string.omni_memory_unconfigured)
    } else item.subtitle?.let { stringResource(it) }
    val localService = item.destination == SettingDestination.LocalService
    val canOpen = !localService || (state.localServiceEnabled && state.localService.endpoint.isNotBlank())
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .then(if (canOpen) Modifier.clickable(role = Role.Button) {
                when (val destination = item.destination) {
                    SettingDestination.LocalService -> onLocalServiceDetails()
                    SettingDestination.About -> onAbout()
                    SettingDestination.Permissions -> onPermissions()
                    is SettingDestination.Legacy -> actions.open(destination.page)
                }
            } else Modifier)
            .padding(start = 4.dp, top = 14.dp, end = 2.dp, bottom = if (isLast) 14.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OmniIcon(item.icon, tint = if (item.destination == SettingDestination.Legacy(Page.Terminal)) Color(0xFF2C7FEB) else palette.text)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = palette.text)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 11.sp, lineHeight = 17.05.sp, color = palette.secondaryText)
            }
        }
        if (localService) {
            CompactSwitch(title, state.localServiceEnabled, !state.localServiceBusy, actions.setLocalServiceEnabled)
        } else {
            OmniIcon(R.drawable.omni_chevron_right, modifier = Modifier.padding(start = 12.dp), tint = palette.tertiaryText)
        }
    }
}

/** Existing switch geometry; foundation toggleable expands its minimum touch bounds. */
@Composable
private fun CompactSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalOmniPalette.current
    val offset by animateDpAsState(if (checked) 17.7.dp else 3.dp, label = "switch thumb")
    Box(
        Modifier.size(44.dp, 40.dp).semantics { contentDescription = label }
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(Modifier.size(32.dp, 18.67.dp).clip(CircleShape)
            .background((if (checked) palette.accent else palette.strongBorder).copy(alpha = if (enabled) 1f else .5f))) {
            Box(Modifier.align(Alignment.CenterStart).offset(x = offset).size(11.3.dp).background(Color.White, CircleShape))
        }
    }
}
