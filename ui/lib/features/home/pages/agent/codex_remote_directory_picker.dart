import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/theme/theme_context.dart';

Future<String?> showCodexRemoteDirectoryPicker({
  required BuildContext context,
  required String remoteBridgeUrl,
  required String remoteBridgeToken,
  String initialPath = '',
}) {
  return showOmniGlassBottomSheet<String>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    backgroundColor: Colors.transparent,
    builder: (context) {
      return _CodexRemoteDirectoryPickerSheet(
        remoteBridgeUrl: remoteBridgeUrl,
        remoteBridgeToken: remoteBridgeToken,
        initialPath: initialPath,
      );
    },
  );
}

class _CodexRemoteDirectoryPickerSheet extends StatefulWidget {
  const _CodexRemoteDirectoryPickerSheet({
    required this.remoteBridgeUrl,
    required this.remoteBridgeToken,
    required this.initialPath,
  });

  final String remoteBridgeUrl;
  final String remoteBridgeToken;
  final String initialPath;

  @override
  State<_CodexRemoteDirectoryPickerSheet> createState() =>
      _CodexRemoteDirectoryPickerSheetState();
}

class _CodexRemoteDirectoryPickerSheetState
    extends State<_CodexRemoteDirectoryPickerSheet> {
  CodexRemoteDirectoryList? _listing;
  String _currentPath = '';
  bool _isLoading = true;
  String? _error;

  bool get _isEnglish => Localizations.localeOf(context).languageCode == 'en';

  @override
  void initState() {
    super.initState();
    _currentPath = widget.initialPath.trim();
    unawaited(_loadDirectory(_currentPath));
  }

  Future<void> _loadDirectory(String path) async {
    if (!mounted) return;
    setState(() {
      _isLoading = true;
      _error = null;
    });
    try {
      final listing = await AgentRuntimeService.listRemoteDirectories(
        agentId: 'codex-remote',
        remoteBridgeUrl: widget.remoteBridgeUrl,
        remoteBridgeToken: widget.remoteBridgeToken,
        remoteCwd: widget.initialPath,
        path: path,
      );
      if (!mounted) return;
      setState(() {
        _listing = listing;
        _currentPath = listing.path.isNotEmpty ? listing.path : path;
        _isLoading = false;
        _error = listing.ok
            ? null
            : (listing.error ??
                  (_isEnglish ? 'Failed to read directory' : '读取目录失败'));
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _error = error.toString();
      });
    }
  }

  void _selectCurrentDirectory() {
    final path = (_listing?.path ?? _currentPath).trim();
    if (path.isEmpty) {
      return;
    }
    Navigator.of(context).pop(path);
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final directoryEntries = (_listing?.entries ?? const [])
        .where((entry) => entry.isDirectory)
        .toList(growable: false);
    final maxHeight = MediaQuery.sizeOf(context).height * 0.76;

    return ConstrainedBox(
      constraints: BoxConstraints(maxHeight: maxHeight),
      child: OmniGlassSheet(
        borderRadius: const BorderRadius.vertical(top: Radius.circular(22)),
        child: SafeArea(
          top: false,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const SizedBox(height: 10),
              Container(
                width: 36,
                height: 4,
                decoration: BoxDecoration(
                  color: palette.borderStrong,
                  borderRadius: BorderRadius.circular(99),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(18, 14, 10, 8),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        _isEnglish ? 'Remote Workspace' : '远程工作目录',
                        style: TextStyle(
                          color: palette.textPrimary,
                          fontSize: 17,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    OmniCircleButton(
                      tooltip: _isEnglish ? 'Close' : '关闭',
                      onPressed: () => Navigator.of(context).pop(),
                      icon: LucideIcons.x,
                      foregroundColor: palette.textSecondary,
                    ),
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(18, 0, 18, 12),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        _currentPath.isEmpty
                            ? (_isEnglish ? 'Bridge default' : 'Bridge 默认目录')
                            : _currentPath,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 12,
                          height: 1.4,
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    OmniCircleButton(
                      tooltip: _isEnglish ? 'Home' : '主目录',
                      onPressed: (_listing?.home ?? '').isEmpty || _isLoading
                          ? null
                          : () => unawaited(_loadDirectory(_listing!.home!)),
                      icon: LucideIcons.house,
                    ),
                    OmniCircleButton(
                      tooltip: _isEnglish ? 'Parent' : '上级目录',
                      onPressed: (_listing?.parent ?? '').isEmpty || _isLoading
                          ? null
                          : () => unawaited(_loadDirectory(_listing!.parent!)),
                      icon: LucideIcons.arrowUp,
                    ),
                    OmniCircleButton(
                      tooltip: _isEnglish ? 'Reload' : '刷新',
                      onPressed: _isLoading
                          ? null
                          : () => unawaited(_loadDirectory(_currentPath)),
                      icon: LucideIcons.refreshCw,
                      iconWidget: _isLoading
                          ? const SizedBox.square(
                              dimension: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : null,
                    ),
                  ],
                ),
              ),
              Divider(height: 1, color: palette.borderSubtle),
              Flexible(
                child: _isLoading
                    ? const Center(child: CircularProgressIndicator.adaptive())
                    : _error != null
                    ? _DirectoryError(
                        message: _error!,
                        onRetry: () => unawaited(_loadDirectory(_currentPath)),
                      )
                    : directoryEntries.isEmpty
                    ? _DirectoryEmpty(
                        message: _isEnglish ? 'No subdirectories' : '没有子目录',
                      )
                    : ListView.separated(
                        shrinkWrap: true,
                        padding: const EdgeInsets.symmetric(vertical: 6),
                        itemCount: directoryEntries.length,
                        separatorBuilder: (_, __) =>
                            Divider(height: 1, color: palette.borderSubtle),
                        itemBuilder: (context, index) {
                          final entry = directoryEntries[index];
                          return ListTile(
                            dense: true,
                            leading: Icon(
                              LucideIcons.folder,
                              size: 20,
                              color: palette.accentPrimary,
                            ),
                            title: Text(
                              entry.name,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: palette.textPrimary,
                                fontSize: 14,
                              ),
                            ),
                            subtitle: Text(
                              entry.path,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: palette.textTertiary,
                                fontSize: 11,
                              ),
                            ),
                            trailing: Icon(
                              LucideIcons.chevronRight,
                              color: palette.textTertiary,
                            ),
                            onTap: () => unawaited(_loadDirectory(entry.path)),
                          );
                        },
                      ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(18, 10, 18, 16),
                child: SizedBox(
                  width: double.infinity,
                  height: 44,
                  child: FilledButton.icon(
                    onPressed:
                        _isLoading ||
                            _error != null ||
                            _currentPath.trim().isEmpty
                        ? null
                        : _selectCurrentDirectory,
                    icon: const Icon(LucideIcons.check, size: 18),
                    label: Text(
                      LegacyTextLocalizer.localize('选择此目录'),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _DirectoryError extends StatelessWidget {
  const _DirectoryError({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              LucideIcons.circleAlert,
              color: Theme.of(context).colorScheme.error,
              size: 28,
            ),
            const SizedBox(height: 10),
            Text(
              message,
              textAlign: TextAlign.center,
              style: TextStyle(color: palette.textSecondary, fontSize: 13),
            ),
            const SizedBox(height: 12),
            TextButton.icon(
              onPressed: onRetry,
              icon: const Icon(LucideIcons.refreshCw, size: 17),
              label: Text(LegacyTextLocalizer.localize('重试')),
            ),
          ],
        ),
      ),
    );
  }
}

class _DirectoryEmpty extends StatelessWidget {
  const _DirectoryEmpty({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Text(
          message,
          style: TextStyle(color: palette.textSecondary, fontSize: 13),
        ),
      ),
    );
  }
}
