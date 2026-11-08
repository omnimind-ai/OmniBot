import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/model_vendor_catalog.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/flclash/popup.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:ui/widgets/provider_vendor_icon.dart';
import 'package:ui/widgets/settings_ui.dart';

import 'chatgpt_account_section.dart';
import 'provider_setting_models.dart';

class ProviderConnectionTab extends StatelessWidget {
  const ProviderConnectionTab({
    super.key,
    required this.scrollController,
    required this.profile,
    required this.isNew,
    required this.readOnly,
    required this.isChatGptProvider,
    required this.selectedPreset,
    required this.selectedWireProtocol,
    required this.showProtocolSelector,
    required this.nameController,
    required this.baseUrlController,
    required this.apiKeyController,
    required this.nameFocusNode,
    required this.baseUrlFocusNode,
    required this.apiKeyFocusNode,
    required this.obscureApiKey,
    required this.onToggleObscureApiKey,
    required this.saveState,
    required this.saveErrorDiagnosis,
    required this.missingConfigItem,
    required this.advancedExpanded,
    required this.onToggleAdvancedExpanded,
    required this.customHeaderEntries,
    required this.customHeadersErrorText,
    required this.onAddCustomHeader,
    required this.onRemoveCustomHeader,
    required this.onSelectPreset,
    required this.onOpenPresetMenu,
    required this.onOpenWireProtocolMenu,
    required this.onSave,
    required this.onSaveAndManageModels,
    required this.beforeChatGptLogin,
    required this.onChatGptAccountChanged,
    required this.isBusy,
  });

  final ScrollController scrollController;
  final ModelProviderProfileSummary? profile;
  final bool isNew;
  final bool readOnly;
  final bool isChatGptProvider;
  final ProviderPresetOption selectedPreset;
  final ProviderWireProtocol selectedWireProtocol;
  final bool showProtocolSelector;
  final TextEditingController nameController;
  final TextEditingController baseUrlController;
  final TextEditingController apiKeyController;
  final FocusNode nameFocusNode;
  final FocusNode baseUrlFocusNode;
  final FocusNode apiKeyFocusNode;
  final bool obscureApiKey;
  final VoidCallback onToggleObscureApiKey;
  final ProviderSaveState saveState;
  final ProviderErrorDiagnosis? saveErrorDiagnosis;
  final String? missingConfigItem;
  final bool advancedExpanded;
  final VoidCallback onToggleAdvancedExpanded;
  final List<EditableHeaderEntry> customHeaderEntries;
  final String? customHeadersErrorText;
  final VoidCallback onAddCustomHeader;
  final ValueChanged<int> onRemoveCustomHeader;
  final ValueChanged<String> onSelectPreset;
  final Future<void> Function(BuildContext anchorContext) onOpenPresetMenu;
  final Future<void> Function(BuildContext anchorContext)
  onOpenWireProtocolMenu;
  final VoidCallback onSave;
  final VoidCallback onSaveAndManageModels;
  final Future<bool> Function() beforeChatGptLogin;
  final Future<void> Function(String profileId) onChatGptAccountChanged;
  final bool isBusy;

  bool _isEnglish(BuildContext context) =>
      Localizations.localeOf(context).languageCode == 'en';

  String _text(BuildContext context, String zh, String en) =>
      _isEnglish(context) ? en : zh;

  Widget _buildStatusBanner(BuildContext context) {
    final palette = context.omniPalette;
    final english = _isEnglish(context);

    IconData icon;
    Color accent;
    String title;
    String? detail;

    if (saveState == ProviderSaveState.saving) {
      icon = LucideIcons.loader;
      accent = palette.accentPrimary;
      title = _text(context, '正在保存连接配置…', 'Saving connection settings…');
    } else if (saveState == ProviderSaveState.failed &&
        saveErrorDiagnosis != null) {
      icon = LucideIcons.circleAlert;
      accent = AppColors.alertRed;
      title =
          '${saveErrorDiagnosis!.categoryLabel}：${saveErrorDiagnosis!.summary}';
      detail = saveErrorDiagnosis!.hint;
    } else if (missingConfigItem != null && missingConfigItem!.isNotEmpty) {
      icon = LucideIcons.info;
      accent = const Color(0xFFD97706);
      title = _text(
        context,
        '配置尚未完成：$missingConfigItem',
        'Incomplete configuration: $missingConfigItem',
      );
    } else if (saveState == ProviderSaveState.dirty) {
      icon = LucideIcons.circleDot;
      accent = palette.accentPrimary;
      title = _text(
        context,
        '有未保存的修改，失焦后将自动保存或点击下方按钮立即保存',
        'Unsaved changes. Will auto-save when unfocused, or tap Save below.',
      );
    } else if (saveState == ProviderSaveState.saved || !isNew) {
      icon = LucideIcons.circleCheck;
      accent = const Color(0xFF2EAF67);
      title = _text(
        context,
        '连接配置已保存',
        'Connection settings saved',
      );
    } else {
      icon = LucideIcons.sparkles;
      accent = palette.accentPrimary;
      title = _text(
        context,
        '选择服务商预设可自动填充默认地址与接口协议',
        'Select a provider preset to pre-fill the default API URL and protocol',
      );
    }

    return Container(
      key: const Key('provider-connection-status-banner'),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
      decoration: BoxDecoration(
        color: accent.withValues(alpha: context.isDarkTheme ? 0.14 : 0.08),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: accent.withValues(alpha: context.isDarkTheme ? 0.32 : 0.22),
          width: 0.75,
        ),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 1),
            child: saveState == ProviderSaveState.saving
                ? SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: accent,
                    ),
                  )
                : Icon(icon, size: 16, color: accent),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: palette.textPrimary,
                    height: 1.35,
                  ),
                ),
                if (detail != null && detail.isNotEmpty) ...[
                  const SizedBox(height: 3),
                  Text(
                    detail,
                    style: TextStyle(
                      fontSize: 12,
                      color: palette.textSecondary,
                      height: 1.4,
                    ),
                  ),
                ],
                if (saveState == ProviderSaveState.failed &&
                    saveErrorDiagnosis?.technicalDetail != null) ...[
                  const SizedBox(height: 4),
                  Text(
                    saveErrorDiagnosis!.technicalDetail!,
                    style: TextStyle(
                      fontSize: 11,
                      color: palette.textTertiary,
                    ),
                  ),
                ],
              ],
            ),
          ),
          if (!english && saveState == ProviderSaveState.dirty)
            Padding(
              padding: const EdgeInsets.only(left: 8),
              child: GestureDetector(
                onTap: isBusy ? null : onSave,
                child: Text(
                  '立即保存',
                  style: TextStyle(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w600,
                    color: palette.accentPrimary,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildQuickPresetStrip(BuildContext context) {
    final palette = context.omniPalette;
    final english = _isEnglish(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          _text(context, '快速选择服务商', 'Quick Select Provider'),
          style: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w500,
            color: palette.textSecondary,
          ),
        ),
        const SizedBox(height: 6),
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(
            children: [
              for (var i = 0; i < kProviderPresets.length; i++) ...[
                if (i > 0) const SizedBox(width: 8),
                _PresetChip(
                  preset: kProviderPresets[i],
                  selected: kProviderPresets[i].value == selectedPreset.value,
                  english: english,
                  enabled: !readOnly && !isBusy,
                  onTap: () => onSelectPreset(kProviderPresets[i].value),
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildPresetSelectorRow(BuildContext context) {
    final palette = context.omniPalette;
    final english = _isEnglish(context);
    final enabled = !readOnly && !isBusy;
    final vendor = selectedPreset.vendorKey != null
        ? ModelVendorCatalog.byKey(selectedPreset.vendorKey)
        : null;

    return Row(
      children: [
        Text(
          _text(context, '服务商预设', 'Provider Preset'),
          style: TextStyle(
            color: palette.textSecondary,
            fontSize: 13.5,
            fontWeight: FontWeight.w500,
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Align(
            alignment: Alignment.centerRight,
            child: FlClashPopupSource(
              glass: false,
              color: Colors.transparent,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(10),
              ),
              builder: (anchorContext) {
                return Opacity(
                  opacity: enabled ? 1 : 0.65,
                  child: InkWell(
                    key: const Key('provider-protocol-type-button'),
                    onTap: enabled
                        ? () => unawaited(onOpenPresetMenu(anchorContext))
                        : null,
                    borderRadius: BorderRadius.circular(10),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 10,
                        vertical: 6,
                      ),
                      decoration: BoxDecoration(
                        color: palette.surfaceSecondary,
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(
                          color: palette.borderSubtle,
                          width: 0.75,
                        ),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          ProviderVendorIcon(vendor: vendor, size: 16),
                          const SizedBox(width: 6),
                          Flexible(
                            child: Text(
                              selectedPreset.displayLabel(english: english),
                              key: const Key('provider-protocol-type-text'),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: palette.textPrimary,
                                fontSize: 14,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                          const SizedBox(width: 4),
                          Icon(
                            LucideIcons.chevronDown,
                            size: 16,
                            color: palette.textSecondary,
                          ),
                        ],
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildWireProtocolField(BuildContext context) {
    final palette = context.omniPalette;
    final enabled = !readOnly && !isBusy;
    final decoration = omniSettingsInputDecoration(
      context,
      labelText: _text(context, '接口协议', 'API Protocol'),
    ).applyDefaults(Theme.of(context).inputDecorationTheme);

    return FlClashPopupSource(
      glass: false,
      color: decoration.filled == true
          ? decoration.fillColor ?? Colors.transparent
          : Colors.transparent,
      shape: decoration.enabledBorder ?? decoration.border ?? InputBorder.none,
      builder: (anchorContext) {
        return Opacity(
          opacity: enabled ? 1 : 0.68,
          child: Material(
            color: Colors.transparent,
            borderRadius: BorderRadius.circular(12),
            child: InkWell(
              key: const Key('provider-wire-api-button'),
              onTap: enabled
                  ? () => unawaited(onOpenWireProtocolMenu(anchorContext))
                  : null,
              borderRadius: BorderRadius.circular(12),
              child: InputDecorator(
                decoration: decoration,
                isEmpty: false,
                isFocused: false,
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        selectedWireProtocol.label,
                        key: const Key('provider-wire-api-text'),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: context.omniInputTextStyle,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Icon(
                      LucideIcons.chevronDown,
                      size: 18,
                      color: palette.textSecondary,
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildAdvancedSection(BuildContext context) {
    final palette = context.omniPalette;
    final headerCount = customHeaderEntries
        .where((e) => e.nameController.text.trim().isNotEmpty)
        .length;
    final summary = headerCount > 0
        ? _text(
            context,
            '已配置 $headerCount 项请求头 · 完整请求地址',
            '$headerCount custom headers · Request endpoint',
          )
        : _text(
            context,
            '完整请求地址、自定义请求头',
            'Full request endpoint & custom headers',
          );

    return OmniSettingsCard(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Material(
            color: Colors.transparent,
            borderRadius: BorderRadius.circular(8),
            child: InkWell(
              key: const Key('provider-advanced-settings-toggle'),
              borderRadius: BorderRadius.circular(8),
              onTap: onToggleAdvancedExpanded,
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            _text(context, '高级设置', 'Advanced Settings'),
                            key: const Key('provider-custom-headers-toggle'),
                            style: TextStyle(
                              color: palette.textPrimary,
                              fontSize: 14.5,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            summary,
                            style: TextStyle(
                              color: palette.textTertiary,
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                    ),
                    AnimatedRotation(
                      turns: advancedExpanded ? 0.5 : 0,
                      duration: const Duration(milliseconds: 180),
                      curve: Curves.easeOutCubic,
                      child: Icon(
                        LucideIcons.chevronDown,
                        size: 18,
                        color: palette.textTertiary,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
          if (advancedExpanded) ...[
            const SizedBox(height: 12),
            Divider(height: 1, thickness: 0.6, color: palette.borderSubtle),
            const SizedBox(height: 12),
            ValueListenableBuilder<TextEditingValue>(
              valueListenable: baseUrlController,
              builder: (context, value, _) {
                final endpoint = buildRequestEndpointPreview(
                  rawBaseUrl: value.text,
                  protocolType: selectedPreset.protocolType == 'deepseek'
                      ? 'deepseek'
                      : selectedWireProtocol.protocolType,
                  wireApi: selectedWireProtocol.wireApi,
                );
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _text(context, '完整请求地址预览', 'Full Request Endpoint'),
                      style: TextStyle(
                        fontSize: 12.5,
                        fontWeight: FontWeight.w600,
                        color: palette.textSecondary,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Container(
                      width: double.infinity,
                      padding: const EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 10,
                      ),
                      decoration: BoxDecoration(
                        color: palette.surfaceSecondary,
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(
                          color: palette.borderSubtle,
                          width: 0.75,
                        ),
                      ),
                      child: SelectableText(
                        endpoint ??
                            _text(
                              context,
                              '填写有效 API 地址后在此显示完整请求地址',
                              'Enter a valid API URL to preview the full endpoint',
                            ),
                        key: const Key('provider-full-request-url-preview'),
                        style: TextStyle(
                          fontSize: 12,
                          color: endpoint != null
                              ? palette.textPrimary
                              : palette.textTertiary,
                          fontFamily: 'monospace',
                        ),
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      _text(
                        context,
                        '默认根据接口协议自动补全路径；在 API 地址末尾加 # 可直接使用原地址。',
                        'Path is appended automatically based on the protocol; append # to the API URL to use it verbatim.',
                      ),
                      style: TextStyle(
                        fontSize: 11.5,
                        color: palette.textTertiary,
                        height: 1.35,
                      ),
                    ),
                  ],
                );
              },
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _text(context, '自定义请求头', 'Custom Headers'),
                        style: TextStyle(
                          fontSize: 12.5,
                          fontWeight: FontWeight.w600,
                          color: palette.textSecondary,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        _text(
                          context,
                          '如 HTTP-Referer、X-Title 等额外请求头（禁止覆盖 Host、Content-Length 等系统头）',
                          'Extra headers such as HTTP-Referer or X-Title (Host, Content-Length, etc. are blocked)',
                        ),
                        style: TextStyle(
                          fontSize: 11.5,
                          color: palette.textTertiary,
                        ),
                      ),
                    ],
                  ),
                ),
                if (!readOnly)
                  TextButton.icon(
                    key: const Key('provider-add-custom-header-button'),
                    onPressed: onAddCustomHeader,
                    icon: const Icon(LucideIcons.plus, size: 15),
                    label: Text(_text(context, '添加请求头', 'Add Header')),
                  ),
              ],
            ),
            const SizedBox(height: 8),
            if (customHeaderEntries.isEmpty)
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 12,
                ),
                decoration: BoxDecoration(
                  color: palette.surfaceSecondary,
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(
                    color: palette.borderSubtle,
                    width: 0.75,
                  ),
                ),
                child: Text(
                  _text(context, '未设置自定义请求头', 'No custom headers configured'),
                  style: TextStyle(
                    color: palette.textTertiary,
                    fontSize: 12,
                  ),
                ),
              )
            else
              Column(
                children: [
                  for (var i = 0; i < customHeaderEntries.length; i++) ...[
                    _CustomHeaderEntryRow(
                      entry: customHeaderEntries[i],
                      readOnly: readOnly,
                      onRemove: () =>
                          onRemoveCustomHeader(customHeaderEntries[i].id),
                    ),
                    if (i < customHeaderEntries.length - 1)
                      const SizedBox(height: 10),
                  ],
                ],
              ),
            if (customHeadersErrorText != null) ...[
              const SizedBox(height: 8),
              Text(
                customHeadersErrorText!,
                style: const TextStyle(
                  color: AppColors.alertRed,
                  fontSize: 12,
                ),
              ),
            ],
          ],
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return ListView(
      key: const PageStorageKey<String>('provider-connection-tab-scroll'),
      controller: scrollController,
      padding: edgeToEdgeScrollPadding(
        context,
        const EdgeInsets.fromLTRB(18, 12, 18, 28),
      ),
      children: [
        _buildStatusBanner(context),
        const SizedBox(height: 12),
        OmniSettingsCard(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (isNew) ...[
                _buildQuickPresetStrip(context),
                const SizedBox(height: 14),
                Divider(height: 1, thickness: 0.6, color: palette.borderSubtle),
                const SizedBox(height: 12),
              ],
              _buildPresetSelectorRow(context),
              const SizedBox(height: 14),
              TextField(
                key: const Key('provider-name-field'),
                controller: nameController,
                focusNode: nameFocusNode,
                enabled: !readOnly,
                style: context.omniInputTextStyle,
                decoration: omniSettingsInputDecoration(
                  context,
                  labelText: _text(context, '名称', 'Name'),
                  hintText: _text(
                    context,
                    '默认使用服务商名称，可自定义区分',
                    'Defaults to brand name; customize as needed',
                  ),
                ),
              ),
              if (isChatGptProvider) ...[
                const SizedBox(height: 16),
                ChatGptAccountSection(
                  key: ValueKey('chatgpt-account-${profile?.id ?? 'new'}'),
                  profileId: profile?.id ?? '',
                  enabled: !isBusy,
                  beforeLogin: beforeChatGptLogin,
                  onAccountChanged: onChatGptAccountChanged,
                ),
              ] else ...[
                const SizedBox(height: 12),
                _buildWireProtocolField(context),
                const SizedBox(height: 12),
                TextField(
                  key: const Key('provider-base-url-field'),
                  controller: baseUrlController,
                  focusNode: baseUrlFocusNode,
                  enabled: !readOnly,
                  keyboardType: TextInputType.url,
                  style: context.omniInputTextStyle,
                  decoration: omniSettingsInputDecoration(
                    context,
                    labelText: _text(context, 'API 地址', 'API URL'),
                    hintText: _text(
                      context,
                      '例如 https://api.openai.com/v1（末尾加 # 禁用自动补全）',
                      'e.g. https://api.openai.com/v1 (append # to disable suffix)',
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  key: const Key('provider-api-key-field'),
                  controller: apiKeyController,
                  focusNode: apiKeyFocusNode,
                  enabled: !readOnly,
                  style: context.omniInputTextStyle,
                  obscureText: obscureApiKey,
                  decoration: omniSettingsInputDecoration(
                    context,
                    labelText: _text(context, 'API 密钥', 'API Key'),
                    hintText: 'e.g., sk-xxxx',
                    suffixIcon: IconButton(
                      key: const Key('provider-api-key-visibility-button'),
                      splashRadius: 18,
                      tooltip: obscureApiKey
                          ? _text(context, '显示密钥', 'Show API Key')
                          : _text(context, '隐藏密钥', 'Hide API Key'),
                      onPressed: onToggleObscureApiKey,
                      icon: Icon(
                        obscureApiKey ? LucideIcons.eyeOff : LucideIcons.eye,
                        color: palette.textTertiary,
                        size: 18,
                      ),
                    ),
                  ),
                ),
                if (selectedPreset.isCustom ||
                    selectedPreset.value == 'openai_compatible') ...[
                  const SizedBox(height: 6),
                  Text(
                    _text(
                      context,
                      '本地或免鉴权服务可留空 API 密钥。',
                      'Leave API Key blank for local or unauthenticated endpoints.',
                    ),
                    style: TextStyle(
                      color: palette.textTertiary,
                      fontSize: 11.5,
                    ),
                  ),
                ],
              ],
              const SizedBox(height: 16),
              Row(
                children: [
                  Expanded(
                    child: FilledButton(
                      key: const Key('provider-save-button'),
                      onPressed: readOnly || isBusy ? null : onSave,
                      style: FilledButton.styleFrom(
                        backgroundColor: palette.accentPrimary,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(horizontal: 10),
                        minimumSize: const Size.fromHeight(42),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                        ),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(
                            saveState == ProviderSaveState.saved
                                ? LucideIcons.check
                                : LucideIcons.save,
                            size: 15,
                          ),
                          const SizedBox(width: 6),
                          Flexible(
                            child: Text(
                              saveState == ProviderSaveState.saving
                                  ? _text(context, '保存中…', 'Saving…')
                                  : _text(context, '保存配置', 'Save'),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 13.5,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: OutlinedButton(
                      key: const Key('provider-save-and-models-button'),
                      onPressed: isBusy ? null : onSaveAndManageModels,
                      style: OutlinedButton.styleFrom(
                        foregroundColor: palette.textPrimary,
                        side: BorderSide(color: palette.borderSubtle),
                        backgroundColor: palette.surfaceSecondary,
                        padding: const EdgeInsets.symmetric(horizontal: 10),
                        minimumSize: const Size.fromHeight(42),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                        ),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(LucideIcons.boxes, size: 15),
                          const SizedBox(width: 6),
                          Flexible(
                            child: Text(
                              isNew
                                  ? _text(
                                      context,
                                      '保存并获取模型',
                                      'Save & Models',
                                    )
                                  : _text(context, '去管理模型', 'Models'),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 13.5,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
        if (!isChatGptProvider) ...[
          const SizedBox(height: 12),
          _buildAdvancedSection(context),
        ],
      ],
    );
  }
}

class _PresetChip extends StatelessWidget {
  const _PresetChip({
    required this.preset,
    required this.selected,
    required this.english,
    required this.enabled,
    required this.onTap,
  });

  final ProviderPresetOption preset;
  final bool selected;
  final bool english;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final vendor = preset.vendorKey != null
        ? ModelVendorCatalog.byKey(preset.vendorKey)
        : null;
    return Material(
      color: selected
          ? palette.accentPrimary.withValues(
              alpha: context.isDarkTheme ? 0.22 : 0.12,
            )
          : palette.surfaceSecondary,
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        key: Key('provider-preset-chip-${preset.value}'),
        onTap: enabled ? onTap : null,
        borderRadius: BorderRadius.circular(10),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(10),
            border: Border.all(
              color: selected
                  ? palette.accentPrimary.withValues(alpha: 0.7)
                  : palette.borderSubtle,
              width: selected ? 1.1 : 0.75,
            ),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              ProviderVendorIcon(vendor: vendor, size: 14),
              const SizedBox(width: 6),
              Text(
                preset.displayLabel(english: english),
                style: TextStyle(
                  fontSize: 12.5,
                  fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                  color: selected ? palette.accentPrimary : palette.textPrimary,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CustomHeaderEntryRow extends StatelessWidget {
  const _CustomHeaderEntryRow({
    required this.entry,
    required this.readOnly,
    required this.onRemove,
  });

  final EditableHeaderEntry entry;
  final bool readOnly;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final english = Localizations.localeOf(context).languageCode == 'en';
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 12, 8, 10),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: palette.borderSubtle, width: 0.75),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              children: [
                TextField(
                  controller: entry.nameController,
                  focusNode: entry.nameFocusNode,
                  enabled: !readOnly,
                  style: context.omniInputTextStyle,
                  decoration: omniSettingsInputDecoration(
                    context,
                    labelText: english ? 'Header Name' : '请求头名称',
                    hintText: 'HTTP-Referer',
                  ),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: entry.valueController,
                  focusNode: entry.valueFocusNode,
                  enabled: !readOnly,
                  style: context.omniInputTextStyle,
                  decoration: omniSettingsInputDecoration(
                    context,
                    labelText: english ? 'Header Value' : '请求头值',
                    hintText: 'https://example.com',
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          OmniCircleButton(
            tooltip: english ? 'Remove Header' : '移除请求头',
            onPressed: readOnly ? null : onRemove,
            icon: LucideIcons.trash2,
            size: 34,
            foregroundColor: readOnly
                ? palette.textTertiary
                : AppColors.alertRed,
          ),
        ],
      ),
    );
  }
}
