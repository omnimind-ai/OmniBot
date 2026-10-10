package cn.com.omnimind.nativeui.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.components.OmniSwitch
import cn.com.omnimind.nativeui.components.SectionTitle
import cn.com.omnimind.nativeui.settings.PermissionSetting
import cn.com.omnimind.nativeui.settings.PermissionsActions
import cn.com.omnimind.nativeui.settings.PermissionsState
import cn.com.omnimind.nativeui.settings.PreferenceDivider
import cn.com.omnimind.nativeui.settings.PreferenceRow
import cn.com.omnimind.nativeui.settings.labelResource
import cn.com.omnimind.nativeui.settings.guideResource
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayListPopup

/* Permissions, Provider, models, scenes and completion (batch 5f-1b). */

@Composable
internal fun PermissionsPage(permissions: PermissionsState, actions: PermissionsActions) {
    val palette = LocalOmniPalette.current
    PageColumn(R.drawable.omni_shield_check, stringResource(R.string.omni_onb_perm_title), stringResource(R.string.omni_onb_perm_desc)) {
        val ready = permissions.onboardingCoreReady
        val progress by animateFloatAsState(ready / ONBOARDING_CORE_PERMISSIONS.toFloat(), label = "permission-progress")
        PermissionCount(stringResource(R.string.omni_onb_perm_ready, ready, ONBOARDING_CORE_PERMISSIONS))
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(progress = if (permissions.loaded) progress else null, height = 6.dp,
            colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = palette.accent, backgroundColor = palette.border))
        Spacer(Modifier.height(24.dp))
        SectionTitle(stringResource(R.string.omni_permissions_core))
        Spacer(Modifier.height(4.dp))
        PermissionItem(PermissionSetting.Background, R.drawable.omni_battery_charging, R.string.omni_permission_background,
            R.string.omni_permission_background_summary, permissions.backgroundAllowed, permissions, actions)
        PreferenceDivider()
        PermissionItem(PermissionSetting.Overlay, R.drawable.omni_picture_in_picture_2, R.string.omni_permission_overlay,
            R.string.omni_permission_overlay_summary, permissions.overlayAllowed, permissions, actions)
        PreferenceDivider()
        PermissionItem(PermissionSetting.InstalledApps, R.drawable.omni_layout_grid, R.string.omni_permission_apps,
            R.string.omni_permission_apps_summary, permissions.installedAppsAllowed, permissions, actions, isLast = true)
        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.omni_permissions_advanced))
        Spacer(Modifier.height(4.dp))
        PermissionItem(PermissionSetting.PublicStorage, R.drawable.omni_folder_open, R.string.omni_permission_storage,
            R.string.omni_permission_storage_summary, permissions.publicStorageAllowed, permissions, actions)
        PreferenceDivider()
        PreferenceRow(stringResource(R.string.omni_permission_shizuku), stringResource(permissions.shizuku.guideResource()),
            icon = R.drawable.omni_usb, isLast = true, enabled = permissions.loaded && !permissions.busy,
            onClick = { actions.select(PermissionSetting.Shizuku) }) {
            GrantTrailing(stringResource(permissions.shizuku.labelResource()), permissions.shizuku.granted)
        }
        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.omni_permissions_notifications))
        Spacer(Modifier.height(4.dp))
        val label = stringResource(R.string.omni_authorize_receive_notifications)
        PreferenceRow(label, stringResource(R.string.omni_authorize_notifications_desc), icon = R.drawable.omni_bell, isLast = true,
            enabled = permissions.loaded && !permissions.busy,
            onClick = { actions.setNotificationsEnabled(!permissions.notificationsEnabled) }) {
            OmniSwitch(permissions.notificationsEnabled, actions.setNotificationsEnabled,
                enabled = permissions.loaded && !permissions.busy, contentDescription = label)
        }
    }
}

@Composable
private fun PermissionCount(text: String) {
    val palette = LocalOmniPalette.current
    AnimatedContent(text, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "permission-count") {
        Text(it, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
    }
}

@Composable
private fun PermissionItem(setting: PermissionSetting, icon: Int, title: Int, summary: Int, granted: Boolean,
    state: PermissionsState, actions: PermissionsActions, isLast: Boolean = false) {
    PreferenceRow(stringResource(title), stringResource(summary), icon = icon, isLast = isLast,
        enabled = state.loaded && !state.busy && !granted, onClick = { actions.select(setting) }) {
        GrantTrailing(stringResource(if (granted) R.string.omni_permission_enabled else R.string.omni_permission_enable), granted)
    }
}

/** A filled check pops in when the grant lands; otherwise the action label. */
@Composable
private fun GrantTrailing(label: String, granted: Boolean) {
    val palette = LocalOmniPalette.current
    AnimatedContent(granted, transitionSpec = {
        (scaleIn(spring(dampingRatio = .55f, stiffness = Spring.StiffnessMedium)) + fadeIn()) togetherWith fadeOut()
    }, label = "grant") { done ->
        if (done) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                OmniIcon(R.drawable.omni_check, label, size = 14.dp, tint = Color.White)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = palette.accent)
                OmniIcon(R.drawable.omni_chevron_right, size = 16.dp, tint = palette.tertiaryText)
            }
        }
    }
}

private val ProviderOption.icon: Int
    get() = when (id) {
        "deepseek" -> R.drawable.omni_brand_deepseek
        "moonshot" -> R.drawable.omni_brand_moonshot
        "mimo" -> R.drawable.omni_brand_xiaomi
        "openai" -> R.drawable.omni_brand_openai
        "anthropic" -> R.drawable.omni_brand_anthropic
        else -> R.drawable.omni_plug_zap
    }

@Composable
private fun ProviderOption.tint(): Color = when (id) {
    "deepseek" -> Color(0xFF4D6BFE)
    "mimo" -> Color(0xFFFF6900)
    "custom" -> LocalOmniPalette.current.secondaryText
    else -> LocalOmniPalette.current.text
}

@Composable
internal fun ProviderPage(state: OnboardingState, actions: OnboardingActions) {
    val provider = state.provider
    val palette = LocalOmniPalette.current
    PageColumn(R.drawable.omni_brain, stringResource(R.string.omni_onb_provider_title), stringResource(R.string.omni_onb_provider_desc)) {
        if (state.accountAvailable) {
            OptionCard(false, actions.openAccount, { IconTile(R.drawable.omni_log_in, palette.accent, palette.accent.copy(alpha = .1f)) },
                stringResource(R.string.omni_onb_provider_account),
                description = stringResource(R.string.omni_onb_provider_account_desc), enabled = !provider.busy)
            Spacer(Modifier.height(10.dp))
        }
        if (provider.loading) {
            Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.omni_onb_provider_loading), fontSize = 13.sp, color = palette.secondaryText)
            }
        } else {
            PROVIDER_OPTIONS.forEach { option ->
                val connectedHere = provider.connected && provider.optionId == option.id
                OptionCard(connectedHere, { actions.chooseProvider(option.id) },
                    { IconTile(option.icon, option.tint()) },
                    if (option.id == "custom") stringResource(R.string.omni_onb_provider_custom) else option.label,
                    description = when {
                        connectedHere -> stringResource(R.string.omni_onb_provider_connected, provider.models.size)
                        option.id == "custom" -> stringResource(R.string.omni_onb_provider_custom_desc)
                        else -> option.baseUrl.removePrefix("https://")
                    },
                    enabled = !provider.busy)
            }
        }
        provider.notice?.let { NoticeText(it) }
    }
}

@Composable
internal fun ConnectionPage(provider: ProviderSetup, actions: OnboardingActions) {
    val palette = LocalOmniPalette.current
    val custom = provider.option.id == "custom"
    val label = if (custom) stringResource(R.string.omni_onb_provider_custom) else provider.option.label
    PageColumn(provider.option.icon, stringResource(R.string.omni_onb_connect_title, label), stringResource(R.string.omni_onb_connect_desc)) {
        if (custom) {
            FieldLabel(stringResource(R.string.omni_onb_connect_name))
            TextField(provider.name, actions.updateName, Modifier.fillMaxWidth(), singleLine = true, enabled = !provider.busy,
                label = stringResource(R.string.omni_onb_connect_name_hint), useLabelAsPlaceholder = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            Spacer(Modifier.height(16.dp))
        }
        FieldLabel(stringResource(R.string.omni_onb_connect_base_url))
        TextField(provider.baseUrl, actions.updateBaseUrl, Modifier.fillMaxWidth(), singleLine = true, enabled = !provider.busy,
            label = "https://api.example.com/v1", useLabelAsPlaceholder = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(if (custom) R.string.omni_onb_connect_base_url_custom else R.string.omni_onb_connect_base_url_preset),
            fontSize = 12.sp, lineHeight = 17.sp, color = palette.tertiaryText)
        Spacer(Modifier.height(16.dp))
        FieldLabel("API Key")
        var reveal by rememberSaveable { mutableStateOf(false) }
        TextField(provider.apiKey, actions.updateApiKey, Modifier.fillMaxWidth(), singleLine = true, enabled = !provider.busy,
            label = if (custom) stringResource(R.string.omni_onb_connect_key_optional) else "sk-…", useLabelAsPlaceholder = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            trailingIcon = {
                OmniIconButton(if (reveal) R.drawable.omni_eye else R.drawable.omni_eye_off,
                    stringResource(if (reveal) R.string.omni_onb_key_hide else R.string.omni_onb_key_show),
                    { reveal = !reveal }, size = 18.dp, tint = palette.tertiaryText)
            })
        provider.notice?.let { NoticeText(it) }
    }
}




private const val MODEL_PREVIEW = 8

@Composable
internal fun ModelInventoryPage(provider: ProviderSetup, actions: OnboardingActions) {
    val palette = LocalOmniPalette.current
    PageColumn(R.drawable.omni_layout_grid, stringResource(R.string.omni_onb_models_title), stringResource(R.string.omni_onb_models_desc)) {
        if (provider.models.isEmpty()) {
            Text(stringResource(R.string.omni_onb_models_empty), fontSize = 13.sp, lineHeight = 19.sp, color = palette.secondaryText)
        } else {
            Text(stringResource(R.string.omni_onb_models_count, provider.models.size), fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, color = palette.text)
            Spacer(Modifier.height(10.dp))
            var expanded by rememberSaveable { mutableStateOf(false) }
            val shown = if (expanded) provider.models else provider.models.take(MODEL_PREVIEW)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.surface)) {
                shown.forEachIndexed { index, model ->
                    if (index > 0) Box(Modifier.padding(start = 14.dp).fillMaxWidth().height(1.dp).background(palette.border.copy(alpha = .6f)))
                    Text(model, fontSize = 13.sp, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
                }
            }
            if (provider.models.size > MODEL_PREVIEW) {
                TextButton(stringResource(if (expanded) R.string.omni_onb_models_less
                    else R.string.omni_onb_models_more, provider.models.size - MODEL_PREVIEW), { expanded = !expanded })
            }
        }
        Spacer(Modifier.height(18.dp))
        var manual by rememberSaveable { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(manual, { manual = it }, Modifier.weight(1f), singleLine = true,
                label = stringResource(R.string.omni_onb_models_add_hint), useLabelAsPlaceholder = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
            Spacer(Modifier.width(10.dp))
            TextButton(stringResource(R.string.omni_onb_models_add), {
                actions.addModel(manual)
                manual = ""
            }, enabled = manual.isNotBlank() && provider.connected, colors = ButtonDefaults.textButtonColorsPrimary())
        }
        provider.notice?.let { NoticeText(it) }
    }
}

@Composable
internal fun ScenesPage(provider: ProviderSetup, actions: OnboardingActions, memory: Boolean) {
    val english = isEnglish()
    PageColumn(
        if (memory) R.drawable.omni_database else R.drawable.omni_route,
        stringResource(if (memory) R.string.omni_onb_memory_title else R.string.omni_onb_scenes_title),
        stringResource(if (memory) R.string.omni_onb_memory_desc else R.string.omni_onb_scenes_desc),
    ) {
        ONBOARDING_SCENES.filter { it.memory == memory }.forEach { scene ->
            SceneCard(scene, if (english) scene.descriptionEn else scene.descriptionZh, provider, actions)
        }
        if (memory) provider.notice?.let { NoticeText(it) }
    }
}

@Composable
private fun SceneCard(scene: OnboardingScene, description: String, provider: ProviderSetup, actions: OnboardingActions) {
    val palette = LocalOmniPalette.current
    var show by remember { mutableStateOf(false) }
    val selected = provider.sceneSelections[scene.id]
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(palette.surface).padding(14.dp)) {
        Text(scene.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
        Spacer(Modifier.height(4.dp))
        Text(description, fontSize = 13.sp, lineHeight = 19.sp, color = palette.secondaryText)
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = maxWidth
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.secondarySurface)
                .clickable(enabled = provider.models.isNotEmpty() && !provider.savingScenes, role = Role.DropdownList) { show = true }
                .heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.omni_onb_scene_model), fontSize = 11.sp, color = palette.tertiaryText)
                    Text(selected ?: stringResource(R.string.omni_onb_choose_model), fontSize = 14.sp,
                        color = if (selected == null) palette.tertiaryText else palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OmniIcon(R.drawable.omni_chevron_down, size = 18.dp, tint = palette.tertiaryText)
            }
            OverlayListPopup(show = show, popupModifier = Modifier.width(width), minWidth = width, maxHeight = 360.dp,
                onDismissRequest = { show = false }) {
                ListPopupColumn {
                    provider.models.forEachIndexed { index, model ->
                        DropdownImpl(model, provider.models.size, model == selected, index, onSelectedIndexChange = {
                            show = false
                            actions.selectSceneModel(scene.id, model)
                        })
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompletionPage(state: OnboardingState) {
    val palette = LocalOmniPalette.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else .6f, spring(dampingRatio = .5f, stiffness = Spring.StiffnessLow), label = "done-scale")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(96.dp).scale(scale).clip(CircleShape).background(SUCCESS.copy(alpha = .14f)),
            contentAlignment = Alignment.Center) {
            OmniIcon(R.drawable.omni_check, size = 44.dp, tint = SUCCESS)
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.omni_onb_done_title), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = palette.text)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.omni_onb_done_desc), fontSize = 14.sp, lineHeight = 21.sp, color = palette.secondaryText,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(palette.surface).padding(vertical = 4.dp)) {
            DoneRow(R.drawable.omni_square_terminal, stringResource(R.string.omni_onb_done_environment), state.environment.ready)
            DoneRow(R.drawable.omni_brain, stringResource(R.string.omni_onb_done_models), state.provider.connected)
            DoneRow(R.drawable.omni_message_circle, stringResource(R.string.omni_onb_done_chat), true)
        }
    }
}


@Composable
private fun DoneRow(@DrawableRes icon: Int, label: String, done: Boolean) {
    val palette = LocalOmniPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        OmniIcon(icon, size = 18.dp, tint = palette.secondaryText)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 14.sp, color = palette.text, modifier = Modifier.weight(1f))
        Text(stringResource(if (done) R.string.omni_onb_done_configured else R.string.omni_onb_done_later),
            fontSize = 12.sp, color = if (done) SUCCESS else palette.tertiaryText, fontWeight = FontWeight.Medium)
    }
}
