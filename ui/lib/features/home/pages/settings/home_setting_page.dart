import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/features/home/widgets/home_quick_prompt_icon.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/home_greeting_settings_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';
import 'package:ui/widgets/settings_section_title.dart';
import 'package:ui/widgets/settings_ui.dart';

class HomeSettingPage extends StatefulWidget {
  const HomeSettingPage({super.key});

  @override
  State<HomeSettingPage> createState() => _HomeSettingPageState();
}

class _HomeSettingPageState extends State<HomeSettingPage> {
  @override
  void initState() {
    super.initState();
    HomeGreetingSettingsService.notifier.addListener(_handleSettingsChanged);
    unawaited(HomeGreetingSettingsService.load());
  }

  @override
  void dispose() {
    HomeGreetingSettingsService.notifier.removeListener(_handleSettingsChanged);
    super.dispose();
  }

  void _handleSettingsChanged() {
    if (!mounted) return;
    setState(() {});
  }

  Future<void> _setGreetingEnabled(bool value) async {
    final saved = await HomeGreetingSettingsService.setGreetingEnabled(value);
    if (!saved && mounted) {
      showToast(context.trLegacy('设置失败'), type: ToastType.error);
    }
  }

  Future<void> _deletePrompt(HomeQuickPrompt prompt) async {
    final confirmed = await showSettingsDetailSheet<bool>(
      context: context,
      builder: (sheetContext) {
        return SettingsDetailSheet(
          title: context.trLegacy('删除快捷指令'),
          body: Text(context.trLegacy('删除后该快捷指令将不再显示在首页问候语下方。')),
          actions: [
            TextButton(
              style: settingsDetailSheetActionStyle(sheetContext),
              onPressed: () => Navigator.of(sheetContext).pop(false),
              child: Text(context.trLegacy('取消')),
            ),
            TextButton(
              style: settingsDetailSheetActionStyle(
                sheetContext,
                foregroundColor: const Color(0xFFE05252),
              ),
              onPressed: () => Navigator.of(sheetContext).pop(true),
              child: Text(context.trLegacy('删除')),
            ),
          ],
        );
      },
    );
    if (confirmed != true) {
      return;
    }
    final saved = await HomeGreetingSettingsService.deleteQuickPrompt(
      prompt.id,
    );
    if (!saved && mounted) {
      showToast(context.trLegacy('删除失败'), type: ToastType.error);
    }
  }

  Future<void> _resetPrompts() async {
    final saved = await HomeGreetingSettingsService.resetQuickPrompts();
    if (!saved && mounted) {
      showToast(context.trLegacy('设置失败'), type: ToastType.error);
    }
  }

  Future<void> _togglePinnedPrompt(
    HomeQuickPrompt prompt,
    HomeGreetingSettings settings,
  ) async {
    final isPinned = settings.pinnedQuickPromptIds.contains(prompt.id);
    if (!isPinned && settings.pinnedQuickPromptIds.length >= 2) {
      showToast(context.trLegacy('最多固定两个快捷指令'));
      return;
    }
    final saved = await HomeGreetingSettingsService.togglePinnedQuickPrompt(
      prompt.id,
    );
    if (!saved && mounted) {
      showToast(context.trLegacy('设置失败'), type: ToastType.error);
    }
  }

  Future<void> _showPromptEditor({HomeQuickPrompt? prompt}) async {
    final result = await showOmniGlassBottomSheet<_PromptEditorResult>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: Colors.transparent,
      builder: (context) {
        return _PromptEditorSheet(
          initialTitle: prompt?.title ?? '',
          initialPrompt: prompt?.prompt ?? '',
          editing: prompt != null,
        );
      },
    );
    if (result == null) {
      return;
    }
    final saved = prompt == null
        ? await HomeGreetingSettingsService.addQuickPrompt(
            title: result.title,
            prompt: result.prompt,
          )
        : await HomeGreetingSettingsService.updateQuickPrompt(
            prompt.copyWith(title: result.title, prompt: result.prompt),
          );
    if (!saved && mounted) {
      showToast(context.trLegacy('保存失败'), type: ToastType.error);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final settings = HomeGreetingSettingsService.notifier.value;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(title: context.trLegacy('首页设置'), primary: true),
      body: OmniContentWidth(
        child: SafeArea(
          top: false,
          bottom: false,
          child: ListView(
            padding: edgeToEdgeScrollPadding(
              context,
              const EdgeInsets.fromLTRB(22, 12, 22, 32),
            ),
            children: [
              OmniSettingsGroup(
                title: context.trLegacy('首页问候'),
                children: [
                  OmniSettingsTile(
                    icon: LucideIcons.hand,
                    title: context.trLegacy('显示问候语'),
                    subtitle: context.trLegacy('关闭后，聊天首页不再显示问候语和快捷指令'),
                    trailing: OmniSettingsSwitch(
                      value: settings.greetingEnabled,
                      onToggle: _setGreetingEnabled,
                    ),
                    onTap: () => _setGreetingEnabled(!settings.greetingEnabled),
                  ),
                ],
              ),
              const SizedBox(height: 22),
              SettingsSectionTitle(
                label: context.trLegacy('快捷指令'),
                bottomPadding: 8,
                trailing: TextButton(
                  style: TextButton.styleFrom(
                    foregroundColor: palette.accentPrimary,
                    minimumSize: const Size(0, 30),
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                  onPressed: _resetPrompts,
                  child: Text(
                    context.trLegacy('恢复默认'),
                    style: const TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
              ),
              if (settings.quickPrompts.isEmpty)
                OmniSettingsCard(child: _buildEmptyPrompts())
              else
                OmniSettingsGroup(
                  dividerIndent: 56,
                  children: [
                    for (final prompt in settings.quickPrompts)
                      _buildPromptTile(prompt, settings),
                  ],
                ),
              const SizedBox(height: 14),
              _buildAddButton(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPromptTile(
    HomeQuickPrompt prompt,
    HomeGreetingSettings settings,
  ) {
    final palette = context.omniPalette;
    final pinnedIndex = settings.pinnedQuickPromptIds.indexOf(prompt.id);
    final isPinned = pinnedIndex >= 0;
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 12, 8, 12),
      child: Row(
        children: [
          Container(
            width: 32,
            height: 32,
            decoration: BoxDecoration(
              color: palette.surfaceSecondary,
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(
              homeQuickPromptIcon(prompt.iconKey),
              size: 16,
              color: palette.textPrimary.withValues(alpha: 0.78),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Flexible(
                      child: Text(
                        prompt.resolveTitle(context),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 14.5,
                          fontWeight: FontWeight.w500,
                          color: palette.textPrimary,
                          height: 1.35,
                        ),
                      ),
                    ),
                    const SizedBox(width: 6),
                    _PromptTypeBadge(builtIn: prompt.builtIn),
                    if (isPinned) ...[
                      const SizedBox(width: 6),
                      _PinnedBadge(index: pinnedIndex + 1),
                    ],
                  ],
                ),
                const SizedBox(height: 3),
                Text(
                  prompt.resolvePrompt(context),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.textSecondary,
                    fontSize: 12,
                    height: 1.45,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 4),
          OmniCircleButton(
            tooltip: context.trLegacy(isPinned ? '取消固定' : '固定到首页'),
            onPressed: () => _togglePinnedPrompt(prompt, settings),
            icon: isPinned ? LucideIcons.pinOff : LucideIcons.pin,
            size: 36,
            foregroundColor: isPinned
                ? palette.accentPrimary
                : palette.textTertiary,
          ),
          if (!prompt.builtIn)
            OmniCircleButton(
              tooltip: context.trLegacy('编辑'),
              onPressed: () => _showPromptEditor(prompt: prompt),
              icon: LucideIcons.pencil,
              size: 36,
              foregroundColor: palette.textTertiary,
            ),
          OmniCircleButton(
            tooltip: context.trLegacy('删除'),
            onPressed: () => _deletePrompt(prompt),
            icon: LucideIcons.trash2,
            size: 36,
            foregroundColor: palette.textTertiary,
          ),
        ],
      ),
    );
  }

  Widget _buildEmptyPrompts() {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 20),
      child: Center(
        child: Text(
          context.trLegacy('暂无快捷指令'),
          style: TextStyle(color: palette.textSecondary, fontSize: 13),
        ),
      ),
    );
  }

  Widget _buildAddButton() {
    final palette = context.omniPalette;
    return SizedBox(
      height: 44,
      child: OutlinedButton.icon(
        onPressed: () => _showPromptEditor(),
        icon: const Icon(LucideIcons.plus, size: 17),
        label: Text(context.trLegacy('新增快捷指令')),
        style: OutlinedButton.styleFrom(
          foregroundColor: palette.accentPrimary,
          backgroundColor: palette.surfacePrimary,
          side: BorderSide(
            color: palette.borderSubtle.withValues(
              alpha: context.isDarkTheme ? 0.65 : 0.85,
            ),
            width: 0.75,
          ),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
        ),
      ),
    );
  }
}

class _PromptTypeBadge extends StatelessWidget {
  final bool builtIn;

  const _PromptTypeBadge({required this.builtIn});

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary.withValues(
          alpha: context.isDarkTheme ? 0.8 : 1,
        ),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        context.trLegacy(builtIn ? '内置' : '用户'),
        style: TextStyle(
          color: palette.textTertiary,
          fontSize: 10,
          height: 1,
          fontWeight: FontWeight.w500,
        ),
      ),
    );
  }
}

class _PinnedBadge extends StatelessWidget {
  final int index;

  const _PinnedBadge({required this.index});

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: palette.accentPrimary.withValues(
          alpha: context.isDarkTheme ? 0.18 : 0.1,
        ),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        '${context.trLegacy('固定')} $index',
        style: TextStyle(
          color: palette.accentPrimary,
          fontSize: 10,
          height: 1,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _PromptEditorSheet extends StatefulWidget {
  final String initialTitle;
  final String initialPrompt;
  final bool editing;

  const _PromptEditorSheet({
    required this.initialTitle,
    required this.initialPrompt,
    required this.editing,
  });

  @override
  State<_PromptEditorSheet> createState() => _PromptEditorSheetState();
}

class _PromptEditorSheetState extends State<_PromptEditorSheet> {
  late final titleController = TextEditingController(text: widget.initialTitle);
  late final promptController = TextEditingController(
    text: widget.initialPrompt,
  );

  @override
  void dispose() {
    titleController.dispose();
    promptController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;
    return Padding(
      padding: EdgeInsets.only(bottom: bottomInset),
      child: OmniGlassSheet(
        child: SafeArea(
          top: false,
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 20),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Center(
                  child: Container(
                    width: 36,
                    height: 4,
                    margin: const EdgeInsets.only(bottom: 14),
                    decoration: BoxDecoration(
                      color: palette.borderStrong.withValues(alpha: 0.6),
                      borderRadius: BorderRadius.circular(999),
                    ),
                  ),
                ),
                Text(
                  context.trLegacy(widget.editing ? '编辑快捷指令' : '新增快捷指令'),
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 17,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: titleController,
                  maxLength: 12,
                  style: TextStyle(fontSize: 14, color: palette.textPrimary),
                  decoration: omniSettingsInputDecoration(
                    context,
                    labelText: context.trLegacy('指令名称'),
                  ),
                ),
                const SizedBox(height: 10),
                TextField(
                  controller: promptController,
                  minLines: 3,
                  maxLines: 5,
                  maxLength: 160,
                  style: TextStyle(fontSize: 14, color: palette.textPrimary),
                  decoration: omniSettingsInputDecoration(
                    context,
                    labelText: context.trLegacy('填充文本'),
                  ),
                ),
                const SizedBox(height: 14),
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton(
                        onPressed: () => Navigator.of(context).pop(),
                        style: OutlinedButton.styleFrom(
                          minimumSize: const Size.fromHeight(44),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                        ),
                        child: Text(context.trLegacy('取消')),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: FilledButton(
                        onPressed: () {
                          final title = titleController.text.trim();
                          final prompt = promptController.text.trim();
                          if (title.isEmpty || prompt.isEmpty) {
                            showToast(
                              context.trLegacy('请填写完整信息'),
                              type: ToastType.error,
                            );
                            return;
                          }
                          Navigator.of(context).pop(
                            _PromptEditorResult(title: title, prompt: prompt),
                          );
                        },
                        style: FilledButton.styleFrom(
                          backgroundColor: palette.accentPrimary,
                          minimumSize: const Size.fromHeight(44),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                        ),
                        child: Text(context.trLegacy('保存')),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _PromptEditorResult {
  final String title;
  final String prompt;

  const _PromptEditorResult({required this.title, required this.prompt});
}
