import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/flclash/page_activity.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/workspace_memory_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';
import 'package:ui/widgets/settings_ui.dart';

class WorkspaceMemorySettingPage extends StatefulWidget {
  const WorkspaceMemorySettingPage({super.key});

  @override
  State<WorkspaceMemorySettingPage> createState() =>
      _WorkspaceMemorySettingPageState();
}

class _WorkspaceMemorySettingPageState extends State<WorkspaceMemorySettingPage>
    with FlClashPageLoadMixin {
  final TextEditingController _soulController = TextEditingController();
  final TextEditingController _chatController = TextEditingController();
  final TextEditingController _memoryController = TextEditingController();

  bool _loading = true;
  bool _savingSoul = false;
  bool _savingChat = false;
  bool _savingMemory = false;
  bool _embeddingEnabled = true;
  bool _rollupEnabled = false;
  WorkspaceMemoryEmbeddingConfig? _embeddingConfig;
  WorkspaceMemoryRollupStatus? _rollupStatus;

  @override
  void initState() {
    super.initState();
    _loadAll();
  }

  @override
  void dispose() {
    _soulController.dispose();
    _chatController.dispose();
    _memoryController.dispose();
    super.dispose();
  }

  Future<void> _loadAll() async {
    if (!await waitForPageReady() || !mounted) return;
    setState(() => _loading = true);
    try {
      final results = await Future.wait([
        WorkspaceMemoryService.getAgentSoulSetting(),
        WorkspaceMemoryService.getChatPromptSetting(),
        WorkspaceMemoryService.getLongMemory(),
        WorkspaceMemoryService.getEmbeddingConfig(),
        WorkspaceMemoryService.getRollupStatus(),
      ]);
      if (!await waitForPageReady() || !mounted) return;
      final soul = results[0] as String;
      final chatPrompt = results[1] as String;
      final memory = results[2] as String;
      final embedding = results[3] as WorkspaceMemoryEmbeddingConfig;
      final rollup = results[4] as WorkspaceMemoryRollupStatus;
      setState(() {
        _soulController.text = soul;
        _chatController.text = chatPrompt;
        _memoryController.text = memory;
        _embeddingConfig = embedding;
        _rollupStatus = rollup;
        _embeddingEnabled = embedding.enabled;
        _rollupEnabled = rollup.enabled;
      });
    } catch (e) {
      if (!await waitForPageReady() || !mounted) return;
      showToast(context.l10n.workspaceMemoryLoadFailed, type: ToastType.error);
    } finally {
      if (await waitForPageReady() && mounted) {
        setState(() => _loading = false);
      }
    }
  }

  Future<void> _saveSoul() async {
    setState(() => _savingSoul = true);
    try {
      final saved = await WorkspaceMemoryService.saveAgentSoulSetting(
        _soulController.text,
      );
      if (!mounted) return;
      _soulController.text = saved;
      showToast(context.l10n.agentSoulSaved, type: ToastType.success);
    } catch (e) {
      showToast(context.l10n.agentSoulSaveFailed, type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() => _savingSoul = false);
      }
    }
  }

  Future<void> _saveChatPrompt() async {
    setState(() => _savingChat = true);
    try {
      final saved = await WorkspaceMemoryService.saveChatPromptSetting(
        _chatController.text,
      );
      if (!mounted) return;
      _chatController.text = saved;
      showToast(context.l10n.chatPromptSaved, type: ToastType.success);
    } catch (e) {
      showToast(context.l10n.chatPromptSaveFailed, type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() => _savingChat = false);
      }
    }
  }

  Future<void> _saveMemory() async {
    setState(() => _savingMemory = true);
    try {
      final saved = await WorkspaceMemoryService.saveLongMemory(
        _memoryController.text,
      );
      if (!mounted) return;
      _memoryController.text = saved;
      showToast(context.l10n.workspaceMemorySaved, type: ToastType.success);
    } catch (e) {
      showToast(context.l10n.workspaceMemorySaveFailed, type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() => _savingMemory = false);
      }
    }
  }

  Future<void> _toggleEmbedding(bool enabled) async {
    setState(() => _embeddingEnabled = enabled);
    try {
      final config = await WorkspaceMemoryService.saveEmbeddingConfig(
        enabled: enabled,
      );
      if (!mounted) return;
      setState(() => _embeddingConfig = config);
    } catch (e) {
      if (!mounted) return;
      setState(() => _embeddingEnabled = !enabled);
      showToast(
        context.l10n.workspaceEmbeddingToggleFailed,
        type: ToastType.error,
      );
    }
  }

  Future<void> _toggleRollup(bool enabled) async {
    setState(() => _rollupEnabled = enabled);
    try {
      final status = await WorkspaceMemoryService.saveRollupEnabled(enabled);
      if (!mounted) return;
      setState(() => _rollupStatus = status);
    } catch (e) {
      if (!mounted) return;
      setState(() => _rollupEnabled = !enabled);
      showToast(
        context.l10n.workspaceRollupToggleFailed,
        type: ToastType.error,
      );
    }
  }

  Future<void> _runRollupNow() async {
    try {
      final result = await WorkspaceMemoryService.runRollupNow();
      if (!mounted) return;
      showToast(
        (result?['summary'] ?? context.l10n.workspaceRollupDone).toString(),
      );
      await _loadAll();
    } on PlatformException catch (e) {
      final message = e.message?.trim();
      final errorText = (message == null || message.isEmpty)
          ? context.l10n.workspaceRollupFailed
          : '${context.l10n.workspaceRollupFailed}：$message';
      showToast(errorText, type: ToastType.error);
    } catch (e) {
      showToast(
        '${context.l10n.workspaceRollupFailed}：$e',
        type: ToastType.error,
      );
    }
  }

  String _formatTime(int? millis) {
    if (millis == null || millis <= 0) return context.l10n.workspaceNone;
    final date = DateTime.fromMillisecondsSinceEpoch(millis);
    return '${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')} '
        '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: context.l10n.workspaceMemoryTitle,
        primary: true,
      ),
      body: OmniContentWidth(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: edgeToEdgeScrollPadding(
                  context,
                  const EdgeInsets.fromLTRB(22, 12, 22, 32),
                ),
                children: [
                  SettingsSectionTitle(
                    label: context.l10n.workspaceMemoryCapability,
                  ),
                  OmniSettingsGroup(
                    dividerIndent: 16,
                    children: [
                      _buildSwitchCard(
                        icon: LucideIcons.sparkles,
                        title: context.l10n.workspaceEmbeddingRetrieval,
                        subtitle: _embeddingConfig?.configured == true
                            ? context.l10n.workspaceEmbeddingReady
                            : context.l10n.workspaceEmbeddingNotReady,
                        value: _embeddingEnabled,
                        onChanged: _toggleEmbedding,
                        footer: _embeddingConfig?.usesPlatform == true
                            ? null
                            : TextButton(
                                style: TextButton.styleFrom(
                                  foregroundColor: palette.accentPrimary,
                                  padding: EdgeInsets.zero,
                                  minimumSize: const Size(0, 30),
                                  tapTargetSize:
                                      MaterialTapTargetSize.shrinkWrap,
                                ),
                                onPressed: () {
                                  GoRouterManager.push(
                                    '/home/scene_model_setting',
                                  );
                                },
                                child: Text(context.l10n.workspaceGoToConfig),
                              ),
                      ),
                      _buildSwitchCard(
                        icon: LucideIcons.moonStar,
                        title: context.l10n.workspaceNightlyRollup,
                        subtitle:
                            '${context.l10n.workspaceLastRun(_formatTime(_rollupStatus?.lastRunAtMillis))}\n${context.l10n.workspaceNextRun(_formatTime(_rollupStatus?.nextRunAtMillis))}',
                        value: _rollupEnabled,
                        onChanged: _toggleRollup,
                        footer: Align(
                          alignment: Alignment.centerLeft,
                          child: TextButton(
                            style: TextButton.styleFrom(
                              foregroundColor: palette.accentPrimary,
                              padding: EdgeInsets.zero,
                              minimumSize: const Size(0, 30),
                              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                            ),
                            onPressed: _runRollupNow,
                            child: Text(context.l10n.workspaceRollupNow),
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 22),
                  SettingsSectionTitle(
                    label: context.l10n.workspaceSettingsAndMemory,
                  ),
                  _buildEditorCard(
                    title: context.l10n.agentSoulSetting,
                    controller: _soulController,
                    saving: _savingSoul,
                    onSave: _saveSoul,
                  ),
                  const SizedBox(height: 14),
                  _buildEditorCard(
                    title: context.l10n.chatPromptSetting,
                    controller: _chatController,
                    saving: _savingChat,
                    onSave: _saveChatPrompt,
                  ),
                  const SizedBox(height: 14),
                  _buildEditorCard(
                    title: context.l10n.workspaceMemoryMd,
                    controller: _memoryController,
                    saving: _savingMemory,
                    onSave: _saveMemory,
                  ),
                ],
              ),
      ),
    );
  }

  Widget _buildSwitchCard({
    required IconData icon,
    required String title,
    required String subtitle,
    required bool value,
    required ValueChanged<bool> onChanged,
    Widget? footer,
  }) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                icon,
                size: 18,
                color: palette.textPrimary.withValues(alpha: 0.78),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  title,
                  style: TextStyle(
                    fontSize: 14.5,
                    fontWeight: FontWeight.w500,
                    color: palette.textPrimary,
                  ),
                ),
              ),
              OmniSettingsSwitch(value: value, onToggle: onChanged),
            ],
          ),
          const SizedBox(height: 6),
          Padding(
            padding: const EdgeInsets.only(left: 28),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  subtitle,
                  style: TextStyle(
                    fontSize: 12,
                    height: 1.48,
                    color: palette.textSecondary,
                  ),
                ),
                if (footer != null) ...[const SizedBox(height: 6), footer],
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildEditorCard({
    required String title,
    required TextEditingController controller,
    required bool saving,
    required Future<void> Function() onSave,
  }) {
    final palette = context.omniPalette;
    return OmniSettingsCard(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: TextStyle(
              fontSize: 14.5,
              fontWeight: FontWeight.w600,
              color: palette.textPrimary,
            ),
          ),
          const SizedBox(height: 10),
          TextField(
            controller: controller,
            maxLines: 12,
            minLines: 7,
            style: TextStyle(
              fontSize: 13.5,
              height: 1.5,
              color: palette.textPrimary,
            ),
            decoration: omniSettingsInputDecoration(context),
          ),
          const SizedBox(height: 12),
          Align(
            alignment: Alignment.centerRight,
            child: ElevatedButton(
              onPressed: saving ? null : onSave,
              style: ElevatedButton.styleFrom(
                backgroundColor: palette.accentPrimary,
                foregroundColor: Theme.of(context).colorScheme.onPrimary,
                elevation: 0,
                minimumSize: const Size(84, 38),
                padding: const EdgeInsets.symmetric(horizontal: 18),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(10),
                ),
              ),
              child: saving
                  ? SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Theme.of(context).colorScheme.onPrimary,
                      ),
                    )
                  : Text(context.trLegacy('保存')),
            ),
          ),
        ],
      ),
    );
  }
}
