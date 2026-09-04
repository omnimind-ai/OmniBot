import 'dart:async';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/services/workspace_mount_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

class _EnvironmentDefinition {
  const _EnvironmentDefinition({
    required this.id,
    required this.title,
    required this.descriptionKey,
    required this.groupKey,
  });

  final String id;
  final String title;
  final String descriptionKey;
  final String groupKey;
}

class _EnvironmentViewModel {
  const _EnvironmentViewModel({
    required this.definition,
    required this.ready,
    required this.version,
  });

  final _EnvironmentDefinition definition;
  final bool ready;
  final String? version;
}

const List<_EnvironmentDefinition> _environmentDefinitions =
    <_EnvironmentDefinition>[
      _EnvironmentDefinition(
        id: 'nodejs',
        title: 'nodejs',
        descriptionKey: 'alpineNodeJs',
        groupKey: 'alpineDevEnv',
      ),
      _EnvironmentDefinition(
        id: 'npm',
        title: 'npm',
        descriptionKey: 'alpineNpm',
        groupKey: 'alpineDevEnv',
      ),
      _EnvironmentDefinition(
        id: 'git',
        title: 'git',
        descriptionKey: 'alpineGit',
        groupKey: 'alpineDevEnv',
      ),
      _EnvironmentDefinition(
        id: 'python',
        title: 'python',
        descriptionKey: 'alpinePython',
        groupKey: 'alpineDevEnv',
      ),
      _EnvironmentDefinition(
        id: 'uv',
        title: 'uv',
        descriptionKey: 'alpinePip',
        groupKey: 'alpineDevEnv',
      ),
      _EnvironmentDefinition(
        id: 'pip',
        title: 'pip',
        descriptionKey: 'alpinePipInstall',
        groupKey: 'alpineDevEnv',
      ),
      _EnvironmentDefinition(
        id: 'codex',
        title: 'codex',
        descriptionKey: 'alpineCodex',
        groupKey: 'alpineAiAgent',
      ),
      _EnvironmentDefinition(
        id: 'claude_code',
        title: 'Claude Code',
        descriptionKey: 'alpineClaudeCode',
        groupKey: 'alpineAiAgent',
      ),
      _EnvironmentDefinition(
        id: 'opencode',
        title: 'OpenCode',
        descriptionKey: 'alpineOpenCode',
        groupKey: 'alpineAiAgent',
      ),
      _EnvironmentDefinition(
        id: 'deepseek_harness',
        title: 'DeepSeek Harness',
        descriptionKey: 'alpineDeepSeekHarness',
        groupKey: 'alpineAiAgent',
      ),
      _EnvironmentDefinition(
        id: 'kimi',
        title: 'Kimi Code',
        descriptionKey: 'alpineKimiCode',
        groupKey: 'alpineAiAgent',
      ),
      _EnvironmentDefinition(
        id: 'ssh_client',
        title: 'ssh',
        descriptionKey: 'alpineSshClient',
        groupKey: 'alpineSsh',
      ),
      _EnvironmentDefinition(
        id: 'sshpass',
        title: 'sshpass',
        descriptionKey: 'alpineSshpass',
        groupKey: 'alpineSsh',
      ),
      _EnvironmentDefinition(
        id: 'openssh_server',
        title: 'sshd',
        descriptionKey: 'alpineOpenSshServer',
        groupKey: 'alpineSsh',
      ),
    ];

class TermuxSettingPage extends StatefulWidget {
  const TermuxSettingPage({super.key, this.focusPackageId});

  final String? focusPackageId;

  @override
  State<TermuxSettingPage> createState() => _TermuxSettingPageState();
}

class _TermuxSettingPageState extends State<TermuxSettingPage>
    with WidgetsBindingObserver {
  bool _isOpeningSetup = false;
  bool _isDetecting = true;
  bool _isDistributionLoading = true;
  bool _isDistributionSwitching = false;
  bool _isDistributionCancelling = false;
  bool _isAutoStartLoading = true;
  bool _isAutoStartBusy = false;
  bool _isMountsLoading = true;
  bool _isMountsBusy = false;
  bool _hasInitializedSelection = false;
  String? _detectError;
  String? _autoStartError;
  String? _mountsError;
  Map<String, EmbeddedTerminalSetupInventoryItem> _inventory =
      const <String, EmbeddedTerminalSetupInventoryItem>{};
  List<EmbeddedTerminalAutoStartTask> _autoStartTasks =
      const <EmbeddedTerminalAutoStartTask>[];
  List<WorkspaceMountEntry> _workspaceMounts = const <WorkspaceMountEntry>[];
  Set<String> _selectedPackageIds = <String>{};
  EmbeddedTerminalDistribution _selectedDistribution =
      EmbeddedTerminalDistribution.alpine;
  EmbeddedTerminalDistribution? _switchingDistribution;
  StreamSubscription<EmbeddedTerminalInitProgress>?
  _terminalInitProgressSubscription;
  double? _distributionSwitchProgress;
  String? _distributionSwitchStage;

  List<_EnvironmentViewModel> get _items {
    return _environmentDefinitions
        .map((definition) {
          final item = _inventory[definition.id];
          return _EnvironmentViewModel(
            definition: definition,
            ready: item?.ready == true,
            version: item?.version,
          );
        })
        .toList(growable: false);
  }

  int get _selectedLostCount {
    return _items
        .where(
          (item) =>
              !item.ready && _selectedPackageIds.contains(item.definition.id),
        )
        .length;
  }

  bool get _canStartSetup =>
      !_isDetecting && !_isDistributionLoading && _selectedLostCount > 0;

  bool get _isDarkTheme => context.isDarkTheme;
  bool get _isEnglish => Localizations.localeOf(context).languageCode == 'en';
  Color get _pageBackground => _isDarkTheme
      ? context.omniPalette.pageBackground
      : const Color(0xFFF6F8FA);
  Color get _primaryTextColor =>
      _isDarkTheme ? context.omniPalette.textPrimary : AppColors.text;
  Color get _secondaryTextColor => _isDarkTheme
      ? context.omniPalette.textSecondary
      : const Color(0xFF64748B);
  Color get _tertiaryTextColor =>
      _isDarkTheme ? context.omniPalette.textTertiary : const Color(0xFF475569);
  Color get _mutedSurfaceColor => _isDarkTheme
      ? context.omniPalette.surfaceSecondary
      : const Color(0xFFF8FAFC);

  String get _workspaceMountSectionTitle =>
      _isEnglish ? 'Workspace mounts' : 'Workspace 挂载';
  String get _workspaceMountSectionDesc => _isEnglish
      ? 'Link a readable host directory into `/workspace`. The selected terminal system, chat resource previews, and the workspace browser will all see the same files. Unmount only removes the mount entry and never deletes the real directory.'
      : '把一个可访问的宿主目录挂到 `/workspace` 下。当前终端系统、聊天资源预览和文件浏览器都会看到同一份文件。卸载只会删除挂载入口，不会删除真实目录。';
  String get _workspaceMountAddLabel => _isEnglish ? 'Add mount' : '新增挂载';
  String get _workspaceMountEmptyDesc =>
      _isEnglish ? 'No mounted directories yet.' : '还没有挂载任何宿主目录。';
  String get _workspaceMountPickTitle =>
      _isEnglish ? 'Select a host directory' : '选择宿主目录';
  String get _workspaceMountAliasTitle => _isEnglish ? 'Mount name' : '挂载名称';
  String get _workspaceMountAliasHint => _isEnglish
      ? 'This will become `/workspace/<name>`'
      : '会显示为 `/workspace/<名称>`';
  String get _workspaceMountAliasSave => _isEnglish ? 'Mount' : '挂载';
  String get _workspaceMountUnmountTitle =>
      _isEnglish ? 'Unmount directory' : '卸载挂载';
  String _workspaceMountUnmountMessage(String alias) => _isEnglish
      ? 'Unmount `/workspace/$alias`? The original host directory and its files will remain untouched.'
      : '确认卸载 `/workspace/$alias` 吗？原始宿主目录和里面的文件都不会被删除。';
  String _workspaceMountBrokenMessage(String sourcePath) => _isEnglish
      ? 'Source is missing or unreadable: $sourcePath'
      : '原始目录不存在或当前不可读：$sourcePath';
  String _workspaceMountedToast(String alias) =>
      _isEnglish ? 'Mounted as /workspace/$alias' : '已挂载到 /workspace/$alias';
  String _workspaceUnmountedToast(String alias) =>
      _isEnglish ? 'Unmounted /workspace/$alias' : '已卸载 /workspace/$alias';

  String _resolveL10nKey(String key) {
    // Returns the localized string for a given ARB key.
    final l10n = context.l10n;
    switch (key) {
      case 'alpineNodeJs':
        return l10n.alpineNodeJs;
      case 'alpineNpm':
        return l10n.alpineNpm;
      case 'alpineGit':
        return l10n.alpineGit;
      case 'alpinePython':
        return l10n.alpinePython;
      case 'alpinePip':
        return l10n.alpinePip;
      case 'alpinePipInstall':
        return l10n.alpinePipInstall;
      case 'alpineCodex':
        return l10n.alpineCodex;
      case 'alpineClaudeCode':
        return l10n.alpineClaudeCode;
      case 'alpineOpenCode':
        return l10n.alpineOpenCode;
      case 'alpineDeepSeekHarness':
        return l10n.alpineDeepSeekHarness;
      case 'alpineKimiCode':
        return l10n.alpineKimiCode;
      case 'alpineSshClient':
        return l10n.alpineSshClient;
      case 'alpineSshpass':
        return l10n.alpineSshpass;
      case 'alpineOpenSshServer':
        return l10n.alpineOpenSshServer;
      case 'alpineDevEnv':
        return l10n.alpineDevEnv;
      case 'alpineAiAgent':
        return l10n.alpineAiAgent;
      case 'alpineSsh':
        return 'SSH';
      default:
        return key;
    }
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _terminalInitProgressSubscription = embeddedTerminalInitProgressStream
        .listen(_handleTerminalInitProgress);
    unawaited(_loadDistributionAndInventory(selectMissingByDefault: true));
    unawaited(_refreshAutoStartTasks());
    unawaited(_refreshWorkspaceMounts());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    unawaited(_terminalInitProgressSubscription?.cancel());
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      if (!_isDistributionSwitching) {
        unawaited(_loadDistributionAndInventory());
      }
      unawaited(_refreshAutoStartTasks());
      unawaited(_refreshWorkspaceMounts());
    }
  }

  void _handleTerminalInitProgress(EmbeddedTerminalInitProgress progress) {
    if (!mounted || !_isDistributionSwitching) return;
    final switchingDistribution = _switchingDistribution;
    if (progress.distribution != null &&
        progress.distribution != switchingDistribution) {
      return;
    }
    setState(() {
      _distributionSwitchProgress = progress.progress;
      if (progress.message.isNotEmpty) {
        _distributionSwitchStage = progress.message;
      }
    });
  }

  Future<void> _cancelDistributionSwitch() async {
    if (!_isDistributionSwitching || _isDistributionCancelling) return;
    setState(() {
      _isDistributionCancelling = true;
      _distributionSwitchStage = _isEnglish
          ? 'Cancelling download…'
          : '正在取消下载…';
    });
    try {
      await cancelEmbeddedTerminalInit();
    } finally {
      if (mounted) {
        setState(() {
          _isDistributionCancelling = false;
        });
      }
    }
  }

  Future<void> _loadDistributionAndInventory({
    bool selectMissingByDefault = false,
  }) async {
    try {
      final distribution = await getEmbeddedTerminalDistribution();
      if (!mounted) return;
      final changed = distribution != _selectedDistribution;
      setState(() {
        _selectedDistribution = distribution;
        _isDistributionLoading = false;
        if (changed) {
          _hasInitializedSelection = false;
          _inventory = const <String, EmbeddedTerminalSetupInventoryItem>{};
          _selectedPackageIds = <String>{};
        }
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _isDistributionLoading = false;
      });
    }
    await _refreshInventory(selectMissingByDefault: selectMissingByDefault);
  }

  Future<void> _switchDistribution(
    EmbeddedTerminalDistribution distribution,
  ) async {
    if (_isDistributionLoading ||
        _isDistributionSwitching ||
        distribution == _selectedDistribution) {
      return;
    }
    final previous = _selectedDistribution;
    setState(() {
      _selectedDistribution = distribution;
      _isDistributionSwitching = true;
      _isDistributionCancelling = false;
      _switchingDistribution = distribution;
      _distributionSwitchProgress = null;
      _distributionSwitchStage = _isEnglish
          ? 'Preparing ${distribution == EmbeddedTerminalDistribution.ubuntu ? 'Ubuntu' : 'Alpine'}…'
          : '正在准备 ${distribution == EmbeddedTerminalDistribution.ubuntu ? 'Ubuntu' : 'Alpine'}…';
      _hasInitializedSelection = false;
      _inventory = const <String, EmbeddedTerminalSetupInventoryItem>{};
      _selectedPackageIds = <String>{};
    });
    try {
      final saved = await switchEmbeddedTerminalDistribution(distribution);
      if (!mounted) return;
      setState(() {
        _selectedDistribution = saved;
      });
      await _refreshInventory(selectMissingByDefault: true);
      await _refreshAutoStartTasks();
      if (!mounted) return;
      showToast(
        _isEnglish
            ? 'Terminal system switched to ${saved == EmbeddedTerminalDistribution.ubuntu ? 'Ubuntu' : 'Alpine'}'
            : '终端系统已切换为 ${saved == EmbeddedTerminalDistribution.ubuntu ? 'Ubuntu' : 'Alpine'}',
      );
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() {
        _selectedDistribution = previous;
      });
      showToast(
        error.message ??
            (_isEnglish ? 'Failed to switch terminal system' : '切换终端系统失败'),
        type: ToastType.error,
      );
      await _refreshInventory();
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _selectedDistribution = previous;
      });
      showToast(
        _isEnglish ? 'Failed to switch terminal system' : '切换终端系统失败',
        type: ToastType.error,
      );
      await _refreshInventory();
    } finally {
      if (mounted) {
        setState(() {
          _isDistributionSwitching = false;
          _isDistributionCancelling = false;
          _switchingDistribution = null;
          _distributionSwitchProgress = null;
          _distributionSwitchStage = null;
        });
      }
    }
  }

  Future<void> _refreshInventory({bool selectMissingByDefault = false}) async {
    if (mounted) {
      setState(() {
        _isDetecting = true;
        _detectError = null;
      });
    }
    try {
      final inventory = await getEmbeddedTerminalSetupInventory();
      final nextSelected = <String>{};
      final focusPackageId = widget.focusPackageId?.trim();
      final shouldSelectMissing =
          selectMissingByDefault || !_hasInitializedSelection;
      for (final definition in _environmentDefinitions) {
        final item = inventory.packages[definition.id];
        final ready = item?.ready == true;
        if (ready) {
          continue;
        }
        if (_selectedPackageIds.contains(definition.id) ||
            shouldSelectMissing ||
            focusPackageId == definition.id) {
          nextSelected.add(definition.id);
        }
      }
      if (!mounted) {
        return;
      }
      setState(() {
        _inventory = inventory.packages;
        _selectedPackageIds = nextSelected;
        _hasInitializedSelection = true;
        _isDetecting = false;
      });
    } on PlatformException catch (e) {
      if (!mounted) {
        return;
      }
      setState(() {
        _inventory = const <String, EmbeddedTerminalSetupInventoryItem>{};
        _selectedPackageIds = _environmentDefinitions
            .map((definition) => definition.id)
            .toSet();
        _hasInitializedSelection = true;
        _isDetecting = false;
        _detectError = e.message ?? context.l10n.alpineDetectFailed;
      });
    } catch (_) {
      if (!mounted) {
        return;
      }
      setState(() {
        _inventory = const <String, EmbeddedTerminalSetupInventoryItem>{};
        _selectedPackageIds = _environmentDefinitions
            .map((definition) => definition.id)
            .toSet();
        _hasInitializedSelection = true;
        _isDetecting = false;
        _detectError = context.l10n.alpineDetectFailed;
      });
    }
  }

  Future<void> _refreshAutoStartTasks() async {
    if (mounted) {
      setState(() {
        _isAutoStartLoading = true;
        _autoStartError = null;
      });
    }
    try {
      final tasks = await getEmbeddedTerminalAutoStartTasks();
      if (!mounted) {
        return;
      }
      setState(() {
        _autoStartTasks = tasks.tasks;
        _isAutoStartLoading = false;
      });
    } on PlatformException catch (e) {
      if (!mounted) {
        return;
      }
      setState(() {
        _autoStartTasks = const <EmbeddedTerminalAutoStartTask>[];
        _isAutoStartLoading = false;
        _autoStartError = e.message ?? context.l10n.alpineBootTasksLoadFailed;
      });
    } catch (_) {
      if (!mounted) {
        return;
      }
      setState(() {
        _autoStartTasks = const <EmbeddedTerminalAutoStartTask>[];
        _isAutoStartLoading = false;
        _autoStartError = context.l10n.alpineBootTasksLoadFailed;
      });
    }
  }

  Future<void> _refreshWorkspaceMounts() async {
    if (mounted) {
      setState(() {
        _isMountsLoading = true;
        _mountsError = null;
      });
    }
    try {
      final mounts = await WorkspaceMountService.listMountedDirectories();
      if (!mounted) {
        return;
      }
      setState(() {
        _workspaceMounts = mounts;
        _isMountsLoading = false;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _workspaceMounts = const <WorkspaceMountEntry>[];
        _isMountsLoading = false;
        _mountsError = error.toString();
      });
    }
  }

  Future<void> _pickAndMountWorkspaceDirectory() async {
    if (_isMountsBusy) {
      return;
    }
    try {
      await OmnibotResourceService.ensureWorkspacePathsLoaded();
      final selectedDirectoryPath = await FilePicker.platform.getDirectoryPath(
        dialogTitle: _workspaceMountPickTitle,
      );
      if (!mounted ||
          selectedDirectoryPath == null ||
          selectedDirectoryPath.trim().isEmpty) {
        return;
      }
      final suggestedAlias = WorkspaceMountService.suggestUniqueAlias(
        selectedDirectoryPath,
        rootPath: OmnibotResourceService.rootPath,
      );
      final alias = (await AppDialog.input(
        context,
        title: _workspaceMountAliasTitle,
        hintText: _workspaceMountAliasHint,
        initialValue: suggestedAlias,
        confirmText: _workspaceMountAliasSave,
        cancelText: context.trLegacy('取消'),
      ))?.trim();
      if (!mounted || alias == null) {
        return;
      }
      final aliasValidationError = WorkspaceMountService.validateAlias(alias);
      if (aliasValidationError != null) {
        showToast(aliasValidationError, type: ToastType.warning);
        return;
      }
      setState(() {
        _isMountsBusy = true;
      });
      final mountedEntry = await WorkspaceMountService.mountDirectory(
        sourcePath: selectedDirectoryPath,
        alias: alias,
      );
      await _refreshWorkspaceMounts();
      if (!mounted) return;
      showToast(_workspaceMountedToast(mountedEntry.alias));
    } catch (error) {
      if (!mounted) {
        return;
      }
      showToast(error.toString(), type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() {
          _isMountsBusy = false;
        });
      }
    }
  }

  Future<void> _unmountWorkspaceDirectory(WorkspaceMountEntry entry) async {
    if (_isMountsBusy) {
      return;
    }
    final confirmed = await AppDialog.confirm(
      context,
      title: _workspaceMountUnmountTitle,
      content: _workspaceMountUnmountMessage(entry.alias),
      cancelText: context.trLegacy('取消'),
      confirmText: _isEnglish ? 'Unmount' : '卸载',
    );
    if (confirmed != true || _isMountsBusy) {
      return;
    }
    setState(() {
      _isMountsBusy = true;
    });
    try {
      await WorkspaceMountService.unmountDirectory(entry);
      await _refreshWorkspaceMounts();
      if (!mounted) return;
      showToast(_workspaceUnmountedToast(entry.alias));
    } catch (error) {
      if (!mounted) {
        return;
      }
      showToast(error.toString(), type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() {
          _isMountsBusy = false;
        });
      }
    }
  }

  Future<void> _handleOpenSetupPage() async {
    if (_isOpeningSetup || !_canStartSetup) {
      return;
    }
    final configOpenFailedMessage = context.l10n.alpineConfigOpenFailed;
    setState(() {
      _isOpeningSetup = true;
    });
    try {
      await openNativeTerminal(
        openSetup: true,
        setupPackageIds: _selectedPackageIds.toList(growable: false),
      );
    } on PlatformException catch (e) {
      showToast(e.message ?? configOpenFailedMessage, type: ToastType.error);
    } catch (_) {
      showToast(configOpenFailedMessage, type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() {
          _isOpeningSetup = false;
        });
      }
    }
  }

  Future<void> _openAutoStartTaskDialog({
    EmbeddedTerminalAutoStartTask? task,
  }) async {
    final saveFailedMessage = context.l10n.alpineBootTaskSaveFailed;
    final addedMessage = context.l10n.alpineBootTaskAdded;
    final updatedMessage = context.l10n.alpineBootTaskUpdated;
    final formResult = await showDialog<_AutoStartTaskFormResult>(
      context: context,
      builder: (dialogContext) => _AutoStartTaskDialog(task: task),
    );
    if (formResult == null || _isAutoStartBusy) {
      return;
    }
    setState(() {
      _isAutoStartBusy = true;
    });
    try {
      await saveEmbeddedTerminalAutoStartTask(
        id: task?.id,
        name: formResult.name,
        command: formResult.command,
        workingDirectory: formResult.workingDirectory,
        enabled: formResult.enabled,
      );
      await _refreshAutoStartTasks();
      if (!mounted) return;
      showToast(task == null ? addedMessage : updatedMessage);
    } on PlatformException catch (e) {
      showToast(e.message ?? saveFailedMessage, type: ToastType.error);
    } catch (_) {
      showToast(saveFailedMessage, type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() {
          _isAutoStartBusy = false;
        });
      }
    }
  }

  Future<void> _toggleAutoStartTask(
    EmbeddedTerminalAutoStartTask task,
    bool enabled,
  ) async {
    if (_isAutoStartBusy) {
      return;
    }
    final updateFailedMessage = context.l10n.alpineBootTaskUpdateFailed;
    final enabledMessage = context.l10n.alpineBootEnabled;
    final disabledMessage = context.l10n.alpineBootDisabled;
    setState(() {
      _isAutoStartBusy = true;
    });
    try {
      await saveEmbeddedTerminalAutoStartTask(
        id: task.id,
        name: task.name,
        command: task.command,
        workingDirectory: task.workingDirectory,
        enabled: enabled,
      );
      await _refreshAutoStartTasks();
      if (!mounted) return;
      showToast(enabled ? enabledMessage : disabledMessage);
    } on PlatformException catch (e) {
      showToast(e.message ?? updateFailedMessage, type: ToastType.error);
    } catch (_) {
      showToast(updateFailedMessage, type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() {
          _isAutoStartBusy = false;
        });
      }
    }
  }

  Future<void> _deleteAutoStartTask(EmbeddedTerminalAutoStartTask task) async {
    if (_isAutoStartBusy) {
      return;
    }
    final deleteFailedMessage = context.l10n.alpineBootTaskDeleteFailed;
    final deletedMessage = context.l10n.alpineBootTaskDeleted;
    final confirmed = await AppDialog.confirm(
      context,
      title: context.l10n.alpineDeleteBootTask,
      content: context.l10n.alpineDeleteBootTaskMsg(task.name),
      cancelText: context.trLegacy('取消'),
      confirmText: context.trLegacy('删除'),
    );
    if (confirmed != true || _isAutoStartBusy) {
      return;
    }
    setState(() {
      _isAutoStartBusy = true;
    });
    try {
      await deleteEmbeddedTerminalAutoStartTask(task.id);
      await _refreshAutoStartTasks();
      if (!mounted) return;
      showToast(deletedMessage);
    } on PlatformException catch (e) {
      showToast(e.message ?? deleteFailedMessage, type: ToastType.error);
    } catch (_) {
      showToast(deleteFailedMessage, type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() {
          _isAutoStartBusy = false;
        });
      }
    }
  }

  Future<void> _runAutoStartTask(EmbeddedTerminalAutoStartTask task) async {
    if (_isAutoStartBusy) {
      return;
    }
    setState(() {
      _isAutoStartBusy = true;
    });
    try {
      final result = await runEmbeddedTerminalAutoStartTask(task.id);
      await _refreshAutoStartTasks();
      if (!mounted) return;
      showToast(
        result.message.isNotEmpty
            ? result.message
            : context.l10n.alpineCommandSent,
      );
    } on PlatformException catch (e) {
      showToast(
        e.message ?? context.l10n.alpineStartFailed,
        type: ToastType.error,
      );
    } catch (_) {
      showToast(context.l10n.alpineStartFailed, type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() {
          _isAutoStartBusy = false;
        });
      }
    }
  }

  void _togglePackage(String packageId, bool? value) {
    setState(() {
      if (value == true) {
        _selectedPackageIds = <String>{..._selectedPackageIds, packageId};
      } else {
        _selectedPackageIds = _selectedPackageIds
            .where((id) => id != packageId)
            .toSet();
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final groupedItems = <String, List<_EnvironmentViewModel>>{};
    for (final item in _items) {
      final group = _resolveL10nKey(item.definition.groupKey);
      groupedItems.putIfAbsent(group, () => <_EnvironmentViewModel>[]);
      groupedItems[group]!.add(item);
    }

    return Scaffold(
      backgroundColor: _pageBackground,
      appBar: CommonAppBar(
        title: context.l10n.settingsAlpineTitle,
        primary: true,
      ),
      body: SafeArea(
        top: false,
        bottom: false,
        child: RefreshIndicator(
          onRefresh: () async {
            await _refreshInventory();
            await _refreshAutoStartTasks();
          },
          child: ListView(
            padding: edgeToEdgeScrollPadding(
              context,
              const EdgeInsets.fromLTRB(16, 12, 16, 24),
            ),
            children: [
              _buildDistributionCard(),
              const SizedBox(height: 14),
              _buildIntroCard(),
              const SizedBox(height: 14),
              if (_detectError != null) ...[
                _buildErrorCard(_detectError!),
                const SizedBox(height: 14),
              ],
              for (final entry in groupedItems.entries) ...[
                _buildSectionCard(
                  title: entry.key,
                  child: Column(
                    children: [
                      for (
                        int index = 0;
                        index < entry.value.length;
                        index++
                      ) ...[
                        _buildEnvironmentTile(entry.value[index]),
                        if (index != entry.value.length - 1)
                          const Divider(height: 20, thickness: 0.6),
                      ],
                    ],
                  ),
                ),
                const SizedBox(height: 14),
              ],
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: _canStartSetup && !_isOpeningSetup
                      ? _handleOpenSetupPage
                      : null,
                  icon: _isOpeningSetup
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(
                            strokeWidth: 2.2,
                            color: Colors.white,
                          ),
                        )
                      : const Icon(LucideIcons.squareTerminal),
                  label: Text(
                    _isDetecting
                        ? context.l10n.alpineDetecting
                        : _selectedLostCount > 0
                        ? context.l10n.alpineStartConfig(_selectedLostCount)
                        : context.l10n.alpineAllReady,
                  ),
                ),
              ),
              const SizedBox(height: 14),
              _buildAutoStartSection(),
              const SizedBox(height: 14),
              _buildWorkspaceMountSection(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildDistributionCard() {
    final busy = _isDistributionLoading || _isDistributionSwitching;
    return _buildSectionCard(
      title: _isEnglish ? 'Terminal system' : '终端系统',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            _isEnglish
                ? 'Choose the Linux rootfs used by environment detection, commands, Agent tools, and ReTerminal.'
                : '选择环境检测、命令执行、Agent 工具与 ReTerminal 共用的 Linux rootfs。',
            style: TextStyle(
              color: _secondaryTextColor,
              fontSize: 13,
              fontWeight: FontWeight.w500,
              height: 1.6,
            ),
          ),
          const SizedBox(height: 14),
          SizedBox(
            width: double.infinity,
            child: SegmentedButton<EmbeddedTerminalDistribution>(
              segments: const [
                ButtonSegment(
                  value: EmbeddedTerminalDistribution.alpine,
                  label: Text('Alpine'),
                ),
                ButtonSegment(
                  value: EmbeddedTerminalDistribution.ubuntu,
                  label: Text('Ubuntu'),
                ),
              ],
              selected: <EmbeddedTerminalDistribution>{_selectedDistribution},
              onSelectionChanged: busy
                  ? null
                  : (selection) {
                      if (selection.isNotEmpty) {
                        unawaited(_switchDistribution(selection.first));
                      }
                    },
            ),
          ),
          if (busy) ...[
            const SizedBox(height: 12),
            LinearProgressIndicator(
              minHeight: 2,
              value: _isDistributionSwitching
                  ? _distributionSwitchProgress
                  : null,
            ),
            if (_isDistributionSwitching) ...[
              const SizedBox(height: 8),
              Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  Expanded(
                    child: Text(
                      _distributionSwitchStage ??
                          (_isEnglish
                              ? 'Preparing terminal system…'
                              : '正在准备终端系统…'),
                      style: TextStyle(
                        color: _secondaryTextColor,
                        fontSize: 12,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  TextButton(
                    onPressed: _isDistributionCancelling
                        ? null
                        : () => unawaited(_cancelDistributionSwitch()),
                    child: Text(_isEnglish ? 'Cancel' : '取消下载'),
                  ),
                ],
              ),
            ],
          ],
        ],
      ),
    );
  }

  Widget _buildIntroCard() {
    final readyCount = _items.where((item) => item.ready).length;
    return _buildSectionCard(
      title: context.l10n.alpineEnvConfig,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            _isDetecting
                ? context.l10n.alpineDetectingDesc
                : context.l10n.alpineReadyCount(readyCount, _items.length),
            style: TextStyle(
              color: _secondaryTextColor,
              fontSize: 13,
              fontWeight: FontWeight.w500,
              height: 1.6,
            ),
          ),
          const SizedBox(height: 12),
        ],
      ),
    );
  }

  Widget _buildWorkspaceMountSection() {
    return _buildSectionCard(
      title: _workspaceMountSectionTitle,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            _workspaceMountSectionDesc,
            style: TextStyle(
              color: _secondaryTextColor,
              fontSize: 13,
              fontWeight: FontWeight.w500,
              height: 1.6,
            ),
          ),
          const SizedBox(height: 14),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: _isMountsBusy ? null : _pickAndMountWorkspaceDirectory,
              icon: _isMountsBusy
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2.2),
                    )
                  : const Icon(LucideIcons.folderPlus),
              label: Text(_workspaceMountAddLabel),
            ),
          ),
          const SizedBox(height: 14),
          if (_mountsError != null) ...[
            _buildErrorCard(_mountsError!),
          ] else if (_isMountsLoading) ...[
            const Center(
              child: Padding(
                padding: EdgeInsets.symmetric(vertical: 16),
                child: CircularProgressIndicator(strokeWidth: 2.2),
              ),
            ),
          ] else if (_workspaceMounts.isEmpty) ...[
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: _mutedSurfaceColor,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                  color: _isDarkTheme
                      ? context.omniPalette.borderSubtle
                      : const Color(0xFFE2E8F0),
                ),
              ),
              child: Text(
                _workspaceMountEmptyDesc,
                style: TextStyle(
                  color: _secondaryTextColor,
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  height: 1.6,
                ),
              ),
            ),
          ] else ...[
            for (int index = 0; index < _workspaceMounts.length; index++) ...[
              _buildWorkspaceMountTile(_workspaceMounts[index]),
              if (index != _workspaceMounts.length - 1)
                const Divider(height: 20, thickness: 0.6),
            ],
          ],
        ],
      ),
    );
  }

  Widget _buildErrorCard(String message) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xFFFFFBEB),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: const Color(0xFFFCD34D)),
      ),
      child: Text(
        message,
        style: const TextStyle(
          color: Color(0xFF92400E),
          fontSize: 13,
          fontWeight: FontWeight.w600,
          height: 1.5,
        ),
      ),
    );
  }

  Widget _buildWorkspaceMountTile(WorkspaceMountEntry entry) {
    final stateLabel = entry.isBroken
        ? (_isEnglish ? 'broken' : '异常')
        : (_isEnglish ? 'mounted' : '已挂载');
    final stateBackground = entry.isBroken
        ? const Color(0xFFFFF7ED)
        : const Color(0xFFEAF2FF);
    final stateForeground = entry.isBroken
        ? const Color(0xFFC2410C)
        : const Color(0xFF2563EB);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    entry.shellPath,
                    style: TextStyle(
                      color: _primaryTextColor,
                      fontSize: 15,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    entry.sourcePath,
                    style: TextStyle(
                      color: _secondaryTextColor,
                      fontSize: 12,
                      fontWeight: FontWeight.w500,
                      height: 1.5,
                    ),
                  ),
                  if (entry.isBroken) ...[
                    const SizedBox(height: 6),
                    Text(
                      _workspaceMountBrokenMessage(entry.sourcePath),
                      style: const TextStyle(
                        color: Color(0xFFC2410C),
                        fontSize: 11,
                        fontWeight: FontWeight.w600,
                        height: 1.5,
                      ),
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(width: 10),
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                _buildLegendTag(
                  label: stateLabel,
                  backgroundColor: stateBackground,
                  foregroundColor: stateForeground,
                  darkBackgroundColor: context.omniPalette.surfaceSecondary,
                  darkForegroundColor: entry.isBroken
                      ? const Color(0xFFE7D2B6)
                      : const Color(0xFFD7DADF),
                ),
                const SizedBox(height: 10),
                TextButton.icon(
                  onPressed: _isMountsBusy
                      ? null
                      : () => _unmountWorkspaceDirectory(entry),
                  icon: const Icon(LucideIcons.unlink, size: 18),
                  label: Text(_isEnglish ? 'Unmount' : '卸载'),
                ),
              ],
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildAutoStartSection() {
    return _buildSectionCard(
      title: context.l10n.alpineBootTasks,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            context.l10n.alpineBootTasksDesc,
            style: TextStyle(
              color: _secondaryTextColor,
              fontSize: 13,
              fontWeight: FontWeight.w500,
              height: 1.6,
            ),
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: _isAutoStartBusy
                      ? null
                      : () => _openAutoStartTaskDialog(),
                  icon: const Icon(LucideIcons.plus),
                  label: Text(context.l10n.alpineAddTask),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: _isAutoStartBusy
                      ? null
                      : () => openNativeTerminal(),
                  icon: const Icon(LucideIcons.squareTerminal),
                  label: Text(context.l10n.alpineOpenTerminal),
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          if (_autoStartError != null) ...[
            _buildErrorCard(_autoStartError!),
          ] else if (_isAutoStartLoading) ...[
            const Center(
              child: Padding(
                padding: EdgeInsets.symmetric(vertical: 16),
                child: CircularProgressIndicator(strokeWidth: 2.2),
              ),
            ),
          ] else if (_autoStartTasks.isEmpty) ...[
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: _mutedSurfaceColor,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                  color: _isDarkTheme
                      ? context.omniPalette.borderSubtle
                      : const Color(0xFFE2E8F0),
                ),
              ),
              child: Text(
                context.l10n.alpineNoTasksDesc,
                style: TextStyle(
                  color: _secondaryTextColor,
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  height: 1.6,
                ),
              ),
            ),
          ] else ...[
            for (int index = 0; index < _autoStartTasks.length; index++) ...[
              _buildAutoStartTaskTile(_autoStartTasks[index]),
              if (index != _autoStartTasks.length - 1)
                const Divider(height: 20, thickness: 0.6),
            ],
          ],
        ],
      ),
    );
  }

  Widget _buildAutoStartTaskTile(EmbeddedTerminalAutoStartTask task) {
    final workingDirectory = task.workingDirectory?.trim();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    task.name,
                    style: TextStyle(
                      color: _primaryTextColor,
                      fontSize: 15,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      _buildLegendTag(
                        label: task.enabled
                            ? context.l10n.alpineBootOnAppOpen
                            : context.l10n.alpineNotEnabled,
                        backgroundColor: task.enabled
                            ? const Color(0xFFEAF2FF)
                            : const Color(0xFFF1F5F9),
                        foregroundColor: task.enabled
                            ? const Color(0xFF2563EB)
                            : const Color(0xFF64748B),
                        darkBackgroundColor: task.enabled
                            ? Color.lerp(
                                context.omniPalette.surfaceSecondary,
                                context.omniPalette.accentPrimary,
                                0.14,
                              )
                            : context.omniPalette.surfaceSecondary,
                        darkForegroundColor: task.enabled
                            ? Color.lerp(
                                context.omniPalette.textPrimary,
                                context.omniPalette.accentPrimary,
                                0.38,
                              )
                            : context.omniPalette.textSecondary,
                      ),
                      _buildLegendTag(
                        label: task.running ? 'running' : 'idle',
                        backgroundColor: task.running
                            ? const Color(0xFFE8F7EE)
                            : const Color(0xFFFFF7ED),
                        foregroundColor: task.running
                            ? const Color(0xFF17803D)
                            : const Color(0xFFC2410C),
                        darkBackgroundColor: task.running
                            ? Color.lerp(
                                context.omniPalette.surfaceSecondary,
                                const Color(0xFF72A778),
                                0.22,
                              )
                            : Color.lerp(
                                context.omniPalette.surfaceSecondary,
                                const Color(0xFFB88B61),
                                0.18,
                              ),
                        darkForegroundColor: task.running
                            ? const Color(0xFFD6E7D6)
                            : const Color(0xFFE7D2B6),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            Switch.adaptive(
              value: task.enabled,
              onChanged: _isAutoStartBusy
                  ? null
                  : (value) => _toggleAutoStartTask(task, value),
              activeTrackColor: const Color(0xFF93C5FD),
              activeThumbColor: const Color(0xFF2563EB),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: _mutedSurfaceColor,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: _isDarkTheme
                  ? context.omniPalette.borderSubtle
                  : const Color(0xFFE2E8F0),
            ),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                task.command,
                style: TextStyle(
                  color: _primaryTextColor,
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  height: 1.6,
                ),
              ),
              if (workingDirectory != null && workingDirectory.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text(
                  context.l10n.alpineWorkDirValue(workingDirectory),
                  style: TextStyle(
                    color: _secondaryTextColor,
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ],
          ),
        ),
        const SizedBox(height: 10),
        Row(
          children: [
            TextButton.icon(
              onPressed: _isAutoStartBusy
                  ? null
                  : () => _runAutoStartTask(task),
              icon: const Icon(LucideIcons.play, size: 18),
              label: Text(
                task.running
                    ? context.l10n.alpineRunning
                    : context.l10n.alpineStartNow,
              ),
            ),
            TextButton.icon(
              onPressed: _isAutoStartBusy
                  ? null
                  : () => _openAutoStartTaskDialog(task: task),
              icon: const Icon(LucideIcons.pencil, size: 18),
              label: Text(context.l10n.alpineEdit),
            ),
            TextButton.icon(
              onPressed: _isAutoStartBusy
                  ? null
                  : () => _deleteAutoStartTask(task),
              icon: const Icon(LucideIcons.trash2, size: 18),
              label: Text(context.trLegacy('删除')),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildEnvironmentTile(_EnvironmentViewModel item) {
    final selected = _selectedPackageIds.contains(item.definition.id);
    final versionText = item.version?.trim().isNotEmpty == true
        ? item.version!.trim()
        : (item.ready
              ? context.l10n.alpineVersionDetected
              : context.l10n.alpineVersionNotFound);

    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 2),
          child: item.ready
              ? const Icon(
                  LucideIcons.squareCheckBig,
                  color: Color(0xFF16A34A),
                  size: 22,
                )
              : Checkbox(
                  value: selected,
                  activeColor: const Color(0xFF2563EB),
                  onChanged: _isDetecting
                      ? null
                      : (value) => _togglePackage(item.definition.id, value),
                ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                item.definition.title,
                style: TextStyle(
                  color: _primaryTextColor,
                  fontSize: 15,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                _resolveL10nKey(item.definition.descriptionKey),
                style: TextStyle(
                  color: _secondaryTextColor,
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(width: 10),
        ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 150),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              if (_isDetecting && !_inventory.containsKey(item.definition.id))
                const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              else
                _buildLegendTag(
                  label: item.ready ? 'ready' : 'lost',
                  backgroundColor: item.ready
                      ? const Color(0xFFE8F7EE)
                      : const Color(0xFFEAF2FF),
                  foregroundColor: item.ready
                      ? const Color(0xFF17803D)
                      : const Color(0xFF2563EB),
                  darkBackgroundColor: item.ready
                      ? Color.lerp(
                          context.omniPalette.surfaceSecondary,
                          const Color(0xFF72A778),
                          0.22,
                        )
                      : Color.lerp(
                          context.omniPalette.surfaceSecondary,
                          const Color(0xFF79808A),
                          0.16,
                        ),
                  darkForegroundColor: item.ready
                      ? const Color(0xFFD6E7D6)
                      : const Color(0xFFD7DADF),
                ),
              const SizedBox(height: 6),
              Text(
                versionText,
                textAlign: TextAlign.right,
                style: TextStyle(
                  color: _tertiaryTextColor,
                  fontSize: 11,
                  fontWeight: FontWeight.w600,
                  height: 1.4,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildLegendTag({
    required String label,
    required Color backgroundColor,
    required Color foregroundColor,
    Color? darkBackgroundColor,
    Color? darkForegroundColor,
  }) {
    final resolvedBackgroundColor = context.isDarkTheme
        ? (darkBackgroundColor ?? context.omniPalette.surfaceSecondary)
        : backgroundColor;
    final resolvedForegroundColor = context.isDarkTheme
        ? (darkForegroundColor ?? context.omniPalette.textSecondary)
        : foregroundColor;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: resolvedBackgroundColor,
        borderRadius: BorderRadius.circular(999),
        border: context.isDarkTheme
            ? Border.all(color: resolvedForegroundColor.withValues(alpha: 0.16))
            : null,
      ),
      child: Text(
        label,
        style: TextStyle(
          color: resolvedForegroundColor,
          fontSize: 11,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }

  Widget _buildSectionCard({required String title, required Widget child}) {
    return SizedBox(
      width: double.infinity,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SettingsSectionTitle(label: title, bottomPadding: 10),
          child,
        ],
      ),
    );
  }
}

class _AutoStartTaskFormResult {
  const _AutoStartTaskFormResult({
    required this.name,
    required this.command,
    required this.workingDirectory,
    required this.enabled,
  });

  final String name;
  final String command;
  final String? workingDirectory;
  final bool enabled;
}

class _AutoStartTaskDialog extends StatefulWidget {
  const _AutoStartTaskDialog({this.task});

  final EmbeddedTerminalAutoStartTask? task;

  @override
  State<_AutoStartTaskDialog> createState() => _AutoStartTaskDialogState();
}

class _AutoStartTaskDialogState extends State<_AutoStartTaskDialog> {
  late final TextEditingController _nameController;
  late final TextEditingController _commandController;
  late final TextEditingController _workingDirectoryController;
  late bool _enabled;

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(text: widget.task?.name ?? '');
    _commandController = TextEditingController(
      text: widget.task?.command ?? '',
    );
    _workingDirectoryController = TextEditingController(
      text: widget.task?.workingDirectory ?? '/workspace',
    );
    _enabled = widget.task?.enabled ?? true;
  }

  @override
  void dispose() {
    _nameController.dispose();
    _commandController.dispose();
    _workingDirectoryController.dispose();
    super.dispose();
  }

  void _submit() {
    final name = _nameController.text.trim();
    final command = _commandController.text.trim();
    final workingDirectory = _workingDirectoryController.text.trim();
    if (name.isEmpty) {
      showToast(context.l10n.alpineTaskNameHint, type: ToastType.error);
      return;
    }
    if (command.isEmpty) {
      showToast(context.l10n.alpineCommandHint, type: ToastType.error);
      return;
    }
    Navigator.of(context).pop(
      _AutoStartTaskFormResult(
        name: name,
        command: command,
        workingDirectory: workingDirectory.isEmpty ? null : workingDirectory,
        enabled: _enabled,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final editing = widget.task != null;
    return AlertDialog(
      title: Text(
        editing
            ? context.l10n.alpineEditBootTask
            : context.l10n.alpineAddBootTask,
      ),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            TextField(
              controller: _nameController,
              textInputAction: TextInputAction.next,
              decoration: InputDecoration(
                labelText: context.l10n.alpineTaskName,
                hintText: context.l10n.alpineTaskNameExample,
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _commandController,
              minLines: 3,
              maxLines: 5,
              decoration: InputDecoration(
                labelText: context.l10n.alpineStartCommand,
                hintText: context.l10n.alpineCommandExample,
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _workingDirectoryController,
              textInputAction: TextInputAction.done,
              decoration: InputDecoration(
                labelText: context.l10n.alpineWorkDir,
                hintText: '/workspace',
              ),
            ),
            const SizedBox(height: 12),
            SwitchListTile.adaptive(
              contentPadding: EdgeInsets.zero,
              value: _enabled,
              onChanged: (value) {
                setState(() {
                  _enabled = value;
                });
              },
              title: Text(context.l10n.alpineBootAutoStart),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(context.trLegacy('取消')),
        ),
        FilledButton(
          onPressed: _submit,
          child: Text(
            editing ? context.trLegacy('保存') : context.trLegacy('创建'),
          ),
        ),
      ],
    );
  }
}
