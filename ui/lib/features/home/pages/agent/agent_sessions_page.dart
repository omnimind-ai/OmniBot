import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/home/pages/agent/codex_remote_directory_picker.dart';
import 'package:ui/features/home/pages/agent/codex_remote_workspace_browser.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

class AgentSessionsPage extends StatefulWidget {
  const AgentSessionsPage({super.key});

  @override
  State<AgentSessionsPage> createState() => _AgentSessionsPageState();
}

class _AgentSessionsPageState extends State<AgentSessionsPage> {
  List<_AgentSessionSummary> _sessions = const <_AgentSessionSummary>[];
  AgentRuntimeStatus _status = AgentRuntimeStatus.disconnected;
  String? _error;
  bool _isLoading = true;
  bool _isStartingSession = false;
  bool _isSwitchingWorkspace = false;
  String? _openingThreadId;
  _AgentSessionFilter _filter = _AgentSessionFilter.all;
  StreamSubscription<Map<String, dynamic>>? _agentEventSubscription;
  Timer? _remotePollTimer;
  Timer? _eventRefreshDebounce;

  bool get _isEnglish => Localizations.localeOf(context).languageCode == 'en';

  @override
  void initState() {
    super.initState();
    _agentEventSubscription = AgentRuntimeService.events.listen(
      _handleAgentEvent,
    );
    _remotePollTimer = Timer.periodic(const Duration(seconds: 3), (_) {
      if (!mounted || !_isRemoteRuntime) {
        return;
      }
      unawaited(_loadSessions(showLoading: false));
    });
    unawaited(_loadSessions());
  }

  @override
  void dispose() {
    _agentEventSubscription?.cancel();
    _remotePollTimer?.cancel();
    _eventRefreshDebounce?.cancel();
    super.dispose();
  }

  bool get _isRemoteRuntime =>
      _status.runtime == 'remote' || _status.remoteEnabled;

  void _handleAgentEvent(Map<String, dynamic> event) {
    final method =
        (event['method'] ??
                (event['message'] is Map
                    ? (event['message'] as Map)['method']
                    : null) ??
                event['type'] ??
                (event['message'] is Map
                    ? (event['message'] as Map)['type']
                    : null))
            ?.toString()
            .replaceAll('.', '/')
            .trim() ??
        '';
    if (method.isEmpty || !_isRemoteRuntime) {
      return;
    }
    if (!method.startsWith('thread/') &&
        !method.startsWith('turn/') &&
        !method.startsWith('item/') &&
        !method.startsWith('rawResponseItem/')) {
      return;
    }
    _eventRefreshDebounce?.cancel();
    _eventRefreshDebounce = Timer(const Duration(milliseconds: 450), () {
      if (!mounted) return;
      unawaited(_loadSessions(showLoading: false));
    });
  }

  Future<void> _loadSessions({bool showLoading = true}) async {
    if (mounted && showLoading) {
      setState(() {
        _isLoading = true;
        _error = null;
      });
    }
    AgentRuntimeStatus? lastStatus;
    try {
      var status = await AgentRuntimeService.status();
      lastStatus = status;
      if (status.ready && !status.connected) {
        status = await AgentRuntimeService.connect();
        lastStatus = status;
      }
      if (!status.ready) {
        if (!mounted) return;
        setState(() {
          _status = status;
          if (showLoading) {
            _sessions = const <_AgentSessionSummary>[];
          }
          _isLoading = false;
          _error =
              status.error ??
              (_isEnglish ? 'Agent runtime is unavailable' : 'Agent 运行时不可用');
        });
        return;
      }
      final payloads = <Map<String, dynamic>>[];
      if (status.runtime == 'remote' || status.remoteEnabled) {
        final loadedPayload = await _listLoadedThreadsIfSupported();
        if (loadedPayload != null) {
          payloads.add(loadedPayload);
        }
      }
      String? cursor;
      final seenCursors = <String>{};
      while (true) {
        final payload = await AgentRuntimeService.listSessions(
          cursor: cursor,
        );
        payloads.add(payload);
        final nextCursor = _stringValue(
          payload['nextCursor'] ??
              payload['next_cursor'] ??
              payload['nextPageCursor'] ??
              payload['next_page_cursor'],
        );
        if (nextCursor == null || !seenCursors.add(nextCursor)) {
          break;
        }
        cursor = nextCursor;
      }
      final sessions = _extractAgentSessions(payloads)
        ..sort((a, b) {
          if (a.active != b.active) {
            return a.active ? -1 : 1;
          }
          if (a.loaded != b.loaded) {
            return a.loaded ? -1 : 1;
          }
          if (a.archived != b.archived) {
            return a.archived ? 1 : -1;
          }
          final byUpdatedAt = (b.updatedAtMs ?? 0).compareTo(
            a.updatedAtMs ?? 0,
          );
          if (byUpdatedAt != 0) return byUpdatedAt;
          return a.title.compareTo(b.title);
        });
      if (!mounted) return;
      setState(() {
        _status = status;
        _sessions = sessions;
        _isLoading = false;
        _error = null;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        if (lastStatus != null) {
          _status = lastStatus;
        }
        _isLoading = false;
        _error = error.toString();
      });
    }
  }

  Future<Map<String, dynamic>?> _listLoadedThreadsIfSupported() async {
    try {
      return await AgentRuntimeService.listLoadedSessions();
    } catch (error) {
      debugPrint('Codex loaded thread list unavailable: $error');
      return null;
    }
  }

  Future<void> _openSession(_AgentSessionSummary session) async {
    if (_openingThreadId != null) {
      return;
    }
    setState(() {
      _openingThreadId = session.threadId;
    });
    try {
      if (_status.runtime == 'remote' || _status.remoteEnabled) {
        if (!mounted) return;
        GoRouterManager.push(
          '/home/chat',
          extra: ConversationThreadTarget.agentSession(
            sessionId: session.threadId,
            runtime: 'remote',
            agentSessionActive: session.active,
            requestKey: DateTime.now().microsecondsSinceEpoch.toString(),
          ),
        );
        return;
      }
      final response = await AgentRuntimeService.loadSession(
        sessionId: session.threadId,
        conversationMode: ConversationMode.agent.storageValue,
      );
      final conversationId = _intValue(response['conversationId']);
      if (conversationId == null) {
        throw StateError(
          _isEnglish
              ? 'Agent session did not return a local conversation'
              : 'Agent session 未返回本地对话',
        );
      }
      if (!mounted) return;
      GoRouterManager.push(
        '/home/chat',
        extra: ConversationThreadTarget.existing(
          conversationId: conversationId,
          mode: ConversationMode.agent,
          requestKey: DateTime.now().microsecondsSinceEpoch.toString(),
        ),
      );
    } catch (error) {
      if (!mounted) return;
      showToast(
        _isEnglish ? 'Failed to open session: $error' : '打开 session 失败：$error',
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() {
          _openingThreadId = null;
        });
      }
    }
  }

  Future<void> _startRemoteSession() async {
    if (_isStartingSession || !_isRemoteRuntime) {
      return;
    }
    setState(() {
      _isStartingSession = true;
    });
    try {
      AgentRuntimeStatus status = _status;
      if (!status.connected) {
        status = await AgentRuntimeService.connect();
      }
      final cwd = _workspacePathForStatus(status);
      final response = await AgentRuntimeService.newSession(
        cwd: cwd.isEmpty ? null : cwd,
      );
      final threadId = _threadIdFromResponse(response);
      if (threadId == null) {
        throw StateError(
          _isEnglish
              ? 'Agent did not return a session id'
              : 'Agent 未返回 session id',
        );
      }
      if (!mounted) return;
      setState(() {
        _status = status;
      });
      GoRouterManager.push(
        '/home/chat',
        extra: ConversationThreadTarget.agentSession(
          sessionId: threadId,
          runtime: 'remote',
          requestKey: DateTime.now().microsecondsSinceEpoch.toString(),
        ),
      );
      unawaited(_loadSessions(showLoading: false));
    } catch (error) {
      if (!mounted) return;
      showToast(
        _isEnglish
            ? 'Failed to start remote session: $error'
            : '创建远程 session 失败：$error',
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() {
          _isStartingSession = false;
        });
      }
    }
  }

  Future<void> _switchRemoteWorkspace() async {
    if (_isSwitchingWorkspace || !_isRemoteRuntime) {
      return;
    }
    setState(() {
      _isSwitchingWorkspace = true;
    });
    try {
      final config = await AgentRuntimeService.readRemoteBridgeConfig();
      if (!mounted) return;
      if (!config.remoteEnabled || config.remoteBridgeUrl.trim().isEmpty) {
        showToast(
          _isEnglish
              ? 'Remote Codex Bridge is not configured'
              : '远程 Codex Bridge 尚未配置',
          type: ToastType.warning,
        );
        return;
      }
      final selected = await showCodexRemoteDirectoryPicker(
        context: context,
        remoteBridgeUrl: config.remoteBridgeUrl,
        remoteBridgeToken: config.remoteBridgeToken,
        initialPath: config.remoteCwd,
      );
      if (!mounted || selected == null || selected.trim().isEmpty) {
        return;
      }
      final nextCwd = selected.trim();
      if (nextCwd == config.remoteCwd.trim()) {
        return;
      }
      await AgentRuntimeService.writeRemoteBridgeConfig(
        remoteEnabled: true,
        remoteBridgeUrl: config.remoteBridgeUrl,
        remoteBridgeToken: config.remoteBridgeToken,
        remoteCwd: nextCwd,
      );
      final status = await AgentRuntimeService.status();
      if (!mounted) return;
      setState(() {
        _status = status;
      });
      showToast(
        _isEnglish
            ? 'Workspace switched to ${_lastPathSegment(nextCwd) ?? nextCwd}'
            : '已切换到 ${_lastPathSegment(nextCwd) ?? nextCwd}',
        type: ToastType.success,
      );
      unawaited(_loadSessions(showLoading: false));
    } catch (error) {
      if (!mounted) return;
      showToast(
        _isEnglish ? 'Failed to switch workspace: $error' : '切换工作目录失败：$error',
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() {
          _isSwitchingWorkspace = false;
        });
      }
    }
  }

  Future<void> _showSessionActions(_AgentSessionSummary session) async {
    final action = await showModalBottomSheet<_AgentSessionAction>(
      context: context,
      isScrollControlled: true,
      backgroundColor: context.omniPalette.surfacePrimary,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (sheetContext) {
        final palette = context.omniPalette;
        final mediaQuery = MediaQuery.of(sheetContext);
        final availableHeight = math.max(
          0.0,
          mediaQuery.size.height - mediaQuery.viewPadding.vertical,
        );
        final maxHeight = math.min(
          availableHeight,
          mediaQuery.size.height * 0.82,
        );
        return SafeArea(
          top: false,
          child: ConstrainedBox(
            constraints: BoxConstraints(maxHeight: maxHeight),
            child: SingleChildScrollView(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Container(
                      width: 36,
                      height: 4,
                      decoration: BoxDecoration(
                        color: palette.borderStrong,
                        borderRadius: BorderRadius.circular(999),
                      ),
                    ),
                    const SizedBox(height: 12),
                    Text(
                      session.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        fontFamily: 'PingFang SC',
                      ),
                    ),
                    const SizedBox(height: 12),
                    _buildSessionActionTile(
                      sheetContext,
                      icon: Icons.open_in_new_rounded,
                      label: _isEnglish ? 'Open session' : '打开 Session',
                      action: _AgentSessionAction.open,
                    ),
                    const SizedBox(height: 8),
                    _buildSessionActionTile(
                      sheetContext,
                      icon: Icons.folder_open_rounded,
                      label: _isEnglish ? 'Open workspace' : '打开工作区',
                      action: _AgentSessionAction.workspace,
                    ),
                    const SizedBox(height: 8),
                    _buildSessionActionTile(
                      sheetContext,
                      icon: Icons.drive_file_rename_outline_rounded,
                      label: _isEnglish ? 'Rename' : '重命名',
                      action: _AgentSessionAction.rename,
                    ),
                    const SizedBox(height: 8),
                    _buildSessionActionTile(
                      sheetContext,
                      icon: session.archived
                          ? Icons.unarchive_outlined
                          : Icons.archive_outlined,
                      label: session.archived
                          ? (_isEnglish ? 'Unarchive' : '取消归档')
                          : (_isEnglish ? 'Archive' : '归档'),
                      action: session.archived
                          ? _AgentSessionAction.unarchive
                          : _AgentSessionAction.archive,
                    ),
                    const SizedBox(height: 8),
                    _buildSessionActionTile(
                      sheetContext,
                      icon: Icons.copy_rounded,
                      label: _isEnglish ? 'Copy thread id' : '复制 Thread ID',
                      action: _AgentSessionAction.copyThreadId,
                    ),
                    if (session.cwd.trim().isNotEmpty) ...[
                      const SizedBox(height: 8),
                      _buildSessionActionTile(
                        sheetContext,
                        icon: Icons.content_copy_rounded,
                        label: _isEnglish ? 'Copy workspace path' : '复制工作区路径',
                        action: _AgentSessionAction.copyCwd,
                      ),
                    ],
                    const SizedBox(height: 6),
                    Divider(height: 1, color: palette.borderSubtle),
                    const SizedBox(height: 6),
                    Material(
                      color: Colors.transparent,
                      borderRadius: BorderRadius.circular(10),
                      clipBehavior: Clip.antiAlias,
                      child: InkWell(
                        onTap: () => Navigator.of(sheetContext).pop(),
                        splashColor: palette.accentPrimary.withValues(
                          alpha: 0.08,
                        ),
                        highlightColor: Colors.transparent,
                        child: Padding(
                          padding: const EdgeInsets.fromLTRB(6, 12, 4, 12),
                          child: Row(
                            children: [
                              Icon(
                                Icons.close_rounded,
                                size: 19,
                                color: palette.textPrimary,
                              ),
                              const SizedBox(width: 12),
                              Text(
                                _isEnglish ? 'Cancel' : '取消',
                                style: TextStyle(
                                  color: palette.textPrimary,
                                  fontSize: 14,
                                  fontWeight: FontWeight.w600,
                                  fontFamily: 'PingFang SC',
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
    if (!mounted || action == null) return;
    switch (action) {
      case _AgentSessionAction.open:
        await _openSession(session);
      case _AgentSessionAction.workspace:
        await _openSessionWorkspace(session);
      case _AgentSessionAction.rename:
        await _renameSession(session);
      case _AgentSessionAction.archive:
        await _setArchived(session, archived: true);
      case _AgentSessionAction.unarchive:
        await _setArchived(session, archived: false);
      case _AgentSessionAction.copyThreadId:
        await _copyText(
          session.threadId,
          _isEnglish ? 'Thread id copied' : '已复制 Thread ID',
        );
      case _AgentSessionAction.copyCwd:
        await _copyText(
          session.cwd,
          _isEnglish ? 'Workspace path copied' : '已复制工作区路径',
        );
    }
  }

  Widget _buildSessionActionTile(
    BuildContext sheetContext, {
    required IconData icon,
    required String label,
    required _AgentSessionAction action,
    bool destructive = false,
  }) {
    final palette = context.omniPalette;
    final color = destructive ? const Color(0xFFE53935) : palette.textPrimary;
    return Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(10),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => Navigator.of(sheetContext).pop(action),
        splashColor: palette.accentPrimary.withValues(alpha: 0.08),
        highlightColor: Colors.transparent,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(6, 12, 4, 12),
          child: Row(
            children: [
              Icon(icon, color: color, size: 19),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  label,
                  style: TextStyle(
                    color: color,
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              ),
              Icon(
                Icons.chevron_right_rounded,
                color: palette.textTertiary,
                size: 18,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _renameSession(_AgentSessionSummary session) async {
    final nextName = (await AppDialog.input(
      context,
      title: _isEnglish ? 'Rename session' : '重命名 Session',
      hintText: _isEnglish ? 'Session name' : 'Session 名称',
      initialValue: session.title,
      confirmText: _isEnglish ? 'Save' : '保存',
      cancelText: _isEnglish ? 'Cancel' : '取消',
    ))?.trim();
    if (!mounted || nextName == null) return;
    if (nextName.isEmpty) {
      showToast(
        _isEnglish ? 'Name is required' : '名称不能为空',
        type: ToastType.warning,
      );
      return;
    }
    if (nextName == session.title) {
      return;
    }
    try {
      await AgentRuntimeService.setSessionName(
        sessionId: session.threadId,
        name: nextName,
      );
      if (!mounted) return;
      setState(() {
        _sessions = _sessions
            .map(
              (entry) => entry.threadId == session.threadId
                  ? entry.copyWith(title: nextName)
                  : entry,
            )
            .toList(growable: false);
      });
      showToast(
        _isEnglish ? 'Session renamed' : 'Session 已重命名',
        type: ToastType.success,
      );
      unawaited(_loadSessions(showLoading: false));
    } catch (error) {
      if (!mounted) return;
      showToast(
        _isEnglish ? 'Rename failed: $error' : '重命名失败：$error',
        type: ToastType.error,
      );
    }
  }

  Future<void> _setArchived(
    _AgentSessionSummary session, {
    required bool archived,
  }) async {
    try {
      if (archived) {
        await AgentRuntimeService.archiveSession(sessionId: session.threadId);
      } else {
        await AgentRuntimeService.unarchiveSession(sessionId: session.threadId);
      }
      if (!mounted) return;
      setState(() {
        _sessions = _sessions
            .map(
              (entry) => entry.threadId == session.threadId
                  ? entry.copyWith(archived: archived)
                  : entry,
            )
            .toList(growable: false);
      });
      showToast(
        archived
            ? (_isEnglish ? 'Session archived' : 'Session 已归档')
            : (_isEnglish ? 'Session restored' : 'Session 已恢复'),
        type: ToastType.success,
      );
      unawaited(_loadSessions(showLoading: false));
    } catch (error) {
      if (!mounted) return;
      showToast(
        archived
            ? (_isEnglish ? 'Archive failed: $error' : '归档失败：$error')
            : (_isEnglish ? 'Unarchive failed: $error' : '取消归档失败：$error'),
        type: ToastType.error,
      );
    }
  }

  Future<void> _copyText(String text, String successMessage) async {
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    showToast(successMessage, type: ToastType.success);
  }

  Future<void> _openSessionWorkspace(_AgentSessionSummary session) async {
    try {
      final config = await AgentRuntimeService.readRemoteBridgeConfig();
      if (!mounted) return;
      final workspacePath = session.cwd.trim().isNotEmpty
          ? session.cwd.trim()
          : config.remoteCwd.trim();
      if (config.remoteBridgeUrl.trim().isEmpty || workspacePath.isEmpty) {
        showToast(
          _isEnglish ? 'Remote workspace is not configured' : '远程工作区尚未配置',
          type: ToastType.warning,
        );
        return;
      }
      await Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => _AgentSessionWorkspacePage(
            title: session.cwdLabel.isNotEmpty
                ? session.cwdLabel
                : (_isEnglish ? 'Remote workspace' : '远程工作区'),
            workspacePath: workspacePath,
            remoteBridgeUrl: config.remoteBridgeUrl,
            remoteBridgeToken: config.remoteBridgeToken,
          ),
        ),
      );
    } catch (error) {
      if (!mounted) return;
      showToast(
        _isEnglish ? 'Failed to open workspace: $error' : '打开工作区失败：$error',
        type: ToastType.error,
      );
    }
  }

  String get _title => _status.runtime == 'remote'
      ? (_isEnglish ? 'Remote Codex Sessions' : '远程 Codex Sessions')
      : (_isEnglish ? 'Local Agent Sessions' : '本地 Agent Sessions');

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(title: _title, primary: true),
      body: SafeArea(
        top: false,
        bottom: false,
        child: RefreshIndicator(onRefresh: _loadSessions, child: _buildBody()),
      ),
    );
  }

  Widget _buildBody() {
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator.adaptive());
    }
    if (_error != null && !_isRemoteRuntime && _sessions.isEmpty) {
      return _AgentSessionsStateView(
        icon: Icons.error_outline_rounded,
        title: _isEnglish ? 'Unable to load sessions' : '无法加载 Sessions',
        subtitle: _error!,
        actionLabel: _isEnglish ? 'Retry' : '重试',
        onAction: () => unawaited(_loadSessions()),
      );
    }
    final visibleSessions = _filteredSessions;
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: edgeToEdgeScrollPadding(
        context,
        const EdgeInsets.fromLTRB(18, 12, 18, 32),
      ),
      children: [
        _buildOverviewPanel(),
        if (_error != null) ...[
          const SizedBox(height: 18),
          _buildInlineState(
            icon: Icons.error_outline_rounded,
            title: _isEnglish ? 'Bridge unavailable' : 'Bridge 不可用',
            subtitle: _error!,
            actionLabel: _isEnglish ? 'Retry' : '重试',
            onAction: () => unawaited(_loadSessions()),
          ),
        ],
        const SizedBox(height: 22),
        _buildFilterBar(),
        const SizedBox(height: 6),
        if (_sessions.isEmpty && _error == null)
          _buildInlineState(
            icon: Icons.history_rounded,
            title: _isEnglish ? 'No Agent sessions' : '暂无 Agent Sessions',
            subtitle: _status.runtime == 'remote'
                ? (_isEnglish
                      ? 'The remote PC Bridge returned no sessions.'
                      : '远程 PC Bridge 暂无可用 session。')
                : (_isEnglish
                      ? 'Local Agent returned no sessions.'
                      : '本地 Agent 暂无可用 session。'),
          )
        else if (_sessions.isNotEmpty && visibleSessions.isEmpty)
          _buildInlineState(
            icon: Icons.filter_alt_off_rounded,
            title: _isEnglish
                ? 'No sessions in this filter'
                : '当前筛选下暂无 Session',
            subtitle: _isEnglish
                ? 'Switch filters or refresh after the bridge syncs.'
                : '切换筛选，或等待 Bridge 同步后刷新。',
          )
        else
          for (var index = 0; index < visibleSessions.length; index++) ...[
            _buildSessionRow(
              session: visibleSessions[index],
              isLast: index == visibleSessions.length - 1,
            ),
          ],
      ],
    );
  }

  List<_AgentSessionSummary> get _filteredSessions {
    return _sessions
        .where((session) {
          return switch (_filter) {
            _AgentSessionFilter.all => true,
            _AgentSessionFilter.active =>
              !session.archived && (session.active || session.loaded),
            _AgentSessionFilter.recent =>
              !session.archived && !session.active && !session.loaded,
            _AgentSessionFilter.archived => session.archived,
          };
        })
        .toList(growable: false);
  }

  Widget _buildOverviewPanel() {
    final palette = context.omniPalette;
    final stats = _AgentSessionStats.from(_sessions);
    final isRemote = _isRemoteRuntime;
    final ready = _status.ready;
    final workspacePath = _workspacePathForStatus(_status);
    final bridgeLabel = _bridgeLabel(_status.remoteBridgeUrl);
    final transport = _status.remoteTransport?.trim() ?? '';
    final activeConnections = _status.remoteActiveConnections;
    final uptimeLabel = _formatUptime(_status.remoteUptimeMs);
    final statusLines = [
      ready ? (_isEnglish ? 'Ready' : '可用') : (_isEnglish ? 'Offline' : '离线'),
      if (_status.connected) _isEnglish ? 'connected' : '已连接',
      if (bridgeLabel.isNotEmpty) bridgeLabel,
      if (transport.isNotEmpty) transport,
      if (activeConnections != null)
        _isEnglish ? '$activeConnections clients' : '$activeConnections 个连接',
      if (uptimeLabel.isNotEmpty) uptimeLabel,
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SettingsSectionTitle(
          label: _isEnglish ? 'Runtime' : '运行时',
          bottomPadding: 10,
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(4, 2, 2, 0),
          child: Row(
            children: [
              Icon(
                Icons.hub_rounded,
                size: 20,
                color: isRemote ? palette.accentPrimary : palette.textPrimary,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      isRemote
                          ? (_isEnglish ? 'Remote PC Bridge' : '远程 PC Bridge')
                          : (_isEnglish ? 'Local ACP Agent' : '本地 ACP Agent'),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                        height: 1.25,
                        fontFamily: 'PingFang SC',
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      statusLines.join(' · '),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.textSecondary,
                        fontSize: 11.5,
                        height: 1.35,
                        fontFamily: 'PingFang SC',
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 10),
              _StatusPill(
                label: ready
                    ? (_isEnglish ? 'Live' : '在线')
                    : (_isEnglish ? 'Down' : '不可用'),
                color: ready
                    ? const Color(0xFF1F9D55)
                    : const Color(0xFFE53935),
              ),
            ],
          ),
        ),
        if (workspacePath.isNotEmpty) ...[
          const SizedBox(height: 14),
          _buildWorkspaceLine(workspacePath),
        ],
        const SizedBox(height: 16),
        _buildMetricRail(stats),
        const SizedBox(height: 16),
        _buildRuntimeActions(isRemote: isRemote, ready: ready),
      ],
    );
  }

  Widget _buildMetricRail(_AgentSessionStats stats) {
    final palette = context.omniPalette;
    return IntrinsicHeight(
      child: Row(
        children: [
          Expanded(
            child: _MetricChip(
              label: _isEnglish ? 'Sessions' : '总数',
              value: '${stats.total}',
            ),
          ),
          _MetricDivider(color: palette.borderSubtle),
          Expanded(
            child: _MetricChip(
              label: _isEnglish ? 'Running' : '运行中',
              value: '${stats.active}',
            ),
          ),
          _MetricDivider(color: palette.borderSubtle),
          Expanded(
            child: _MetricChip(
              label: _isEnglish ? 'Loaded' : '已载入',
              value: '${stats.loaded}',
            ),
          ),
          _MetricDivider(color: palette.borderSubtle),
          Expanded(
            child: _MetricChip(
              label: _isEnglish ? 'Archived' : '已归档',
              value: '${stats.archived}',
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildRuntimeActions({required bool isRemote, required bool ready}) {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        if (isRemote)
          FilledButton.icon(
            key: const Key('agent-sessions-new-remote-session-button'),
            onPressed: ready && !_isStartingSession
                ? () => unawaited(_startRemoteSession())
                : null,
            icon: _isStartingSession
                ? const SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.add_rounded, size: 17),
            label: Text(_isEnglish ? 'New' : '新建'),
          ),
        if (isRemote)
          TextButton.icon(
            key: const Key('agent-sessions-switch-workspace-button'),
            onPressed: ready && !_isSwitchingWorkspace
                ? () => unawaited(_switchRemoteWorkspace())
                : null,
            icon: _isSwitchingWorkspace
                ? const SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.folder_open_rounded, size: 17),
            label: Text(_isEnglish ? 'Workspace' : '工作区'),
          ),
        TextButton.icon(
          onPressed: () => GoRouterManager.push('/home/remote_codex_setting'),
          icon: const Icon(Icons.tune_rounded, size: 17),
          label: Text(_isEnglish ? 'Settings' : '设置'),
        ),
      ],
    );
  }

  Widget _buildWorkspaceLine(String workspacePath) {
    final palette = context.omniPalette;
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(4, 8, 0, 9),
          child: Row(
            children: [
              Icon(
                Icons.folder_outlined,
                size: 16,
                color: palette.textTertiary,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  workspacePath,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.textSecondary,
                    fontSize: 11.5,
                    height: 1.3,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              ),
              IconButton(
                visualDensity: VisualDensity.compact,
                constraints: const BoxConstraints.tightFor(
                  width: 28,
                  height: 28,
                ),
                padding: EdgeInsets.zero,
                tooltip: _isEnglish ? 'Copy path' : '复制路径',
                onPressed: () => unawaited(
                  _copyText(
                    workspacePath,
                    _isEnglish ? 'Workspace path copied' : '已复制工作区路径',
                  ),
                ),
                icon: Icon(
                  Icons.copy_rounded,
                  size: 15,
                  color: palette.textTertiary,
                ),
              ),
            ],
          ),
        ),
        Divider(
          height: 1,
          color: palette.borderSubtle.withValues(
            alpha: context.isDarkTheme ? 0.56 : 0.8,
          ),
        ),
      ],
    );
  }

  Widget _buildFilterBar() {
    final stats = _AgentSessionStats.from(_sessions);
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SettingsSectionTitle(
          label: _isEnglish ? 'Sessions' : '会话',
          bottomPadding: 8,
        ),
        DecoratedBox(
          decoration: BoxDecoration(
            border: Border(
              bottom: BorderSide(
                color: palette.borderSubtle.withValues(
                  alpha: context.isDarkTheme ? 0.56 : 0.8,
                ),
              ),
            ),
          ),
          child: SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: [
                _buildFilterChip(
                  filter: _AgentSessionFilter.all,
                  label: _isEnglish
                      ? 'All ${stats.total}'
                      : '全部 ${stats.total}',
                ),
                _buildFilterChip(
                  filter: _AgentSessionFilter.active,
                  label: _isEnglish
                      ? 'Live ${stats.active + stats.loaded}'
                      : '在线 ${stats.active + stats.loaded}',
                ),
                _buildFilterChip(
                  filter: _AgentSessionFilter.recent,
                  label: _isEnglish
                      ? 'Recent ${stats.recent}'
                      : '最近 ${stats.recent}',
                ),
                _buildFilterChip(
                  filter: _AgentSessionFilter.archived,
                  label: _isEnglish
                      ? 'Archived ${stats.archived}'
                      : '已归档 ${stats.archived}',
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildFilterChip({
    required _AgentSessionFilter filter,
    required String label,
  }) {
    final palette = context.omniPalette;
    final selected = _filter == filter;
    final color = selected ? palette.accentPrimary : palette.textSecondary;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () {
          setState(() {
            _filter = filter;
          });
        },
        splashColor: palette.accentPrimary.withValues(alpha: 0.08),
        highlightColor: Colors.transparent,
        child: Padding(
          padding: const EdgeInsets.only(right: 18),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(0, 8, 0, 9),
                child: Text(
                  label,
                  style: TextStyle(
                    color: color,
                    fontSize: 12.5,
                    fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                    height: 1.2,
                    fontFamily: 'PingFang SC',
                  ),
                ),
              ),
              AnimatedContainer(
                duration: const Duration(milliseconds: 180),
                curve: Curves.easeOutCubic,
                width: selected ? 22 : 0,
                height: 2,
                decoration: BoxDecoration(
                  color: palette.accentPrimary,
                  borderRadius: BorderRadius.circular(999),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSessionRow({
    required _AgentSessionSummary session,
    required bool isLast,
  }) {
    final palette = context.omniPalette;
    final opening = _openingThreadId == session.threadId;
    final statusColor = _statusColorForSession(session);
    return Column(
      children: [
        Material(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(12),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            borderRadius: BorderRadius.circular(12),
            onTap: opening ? null : () => unawaited(_openSession(session)),
            onLongPress: () => unawaited(_showSessionActions(session)),
            splashColor: palette.accentPrimary.withValues(alpha: 0.08),
            highlightColor: Colors.transparent,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(4, 14, 0, 14),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Padding(
                    padding: const EdgeInsets.only(top: 7),
                    child: Container(
                      width: 8,
                      height: 8,
                      decoration: BoxDecoration(
                        color: statusColor,
                        shape: BoxShape.circle,
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.center,
                          children: [
                            Expanded(
                              child: Text(
                                session.title,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  color: palette.textPrimary,
                                  fontSize: 14,
                                  fontWeight: FontWeight.w600,
                                  height: 1.35,
                                  fontFamily: 'PingFang SC',
                                ),
                              ),
                            ),
                            const SizedBox(width: 8),
                            _StatusPill(
                              label: session.statusLabel,
                              color: statusColor,
                              compact: true,
                            ),
                          ],
                        ),
                        const SizedBox(height: 4),
                        Text(
                          _sessionMetaLine(session),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: palette.textSecondary,
                            fontSize: 11,
                            height: 1.35,
                            fontFamily: 'PingFang SC',
                          ),
                        ),
                        if (session.preview.isNotEmpty) ...[
                          const SizedBox(height: 7),
                          Text(
                            session.preview,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: palette.textSecondary,
                              fontSize: 12,
                              height: 1.42,
                              fontFamily: 'PingFang SC',
                            ),
                          ),
                        ],
                        if (session.cwdLabel.isNotEmpty ||
                            session.branch.isNotEmpty ||
                            session.model.isNotEmpty ||
                            session.timeLabel.isNotEmpty) ...[
                          const SizedBox(height: 8),
                          Wrap(
                            spacing: 10,
                            runSpacing: 6,
                            children: [
                              if (session.cwdLabel.isNotEmpty)
                                _SessionTag(
                                  icon: Icons.folder_outlined,
                                  label: session.cwdLabel,
                                ),
                              if (session.branch.isNotEmpty)
                                _SessionTag(
                                  icon: Icons.account_tree_outlined,
                                  label: session.branch,
                                ),
                              if (session.model.isNotEmpty)
                                _SessionTag(
                                  icon: Icons.memory_rounded,
                                  label: session.model,
                                ),
                              if (session.timeLabel.isNotEmpty)
                                _SessionTag(
                                  icon: Icons.schedule_rounded,
                                  label: session.timeLabel,
                                ),
                            ],
                          ),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(width: 8),
                  opening
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : IconButton(
                          visualDensity: VisualDensity.compact,
                          constraints: const BoxConstraints.tightFor(
                            width: 34,
                            height: 34,
                          ),
                          padding: EdgeInsets.zero,
                          tooltip: _isEnglish
                              ? 'Session actions'
                              : 'Session 操作',
                          onPressed: () =>
                              unawaited(_showSessionActions(session)),
                          icon: Icon(
                            Icons.more_horiz_rounded,
                            color: palette.textTertiary,
                          ),
                        ),
                ],
              ),
            ),
          ),
        ),
        if (!isLast)
          Padding(
            padding: const EdgeInsets.only(left: 24),
            child: Divider(
              height: 1,
              color: palette.borderSubtle.withValues(
                alpha: context.isDarkTheme ? 0.5 : 0.78,
              ),
            ),
          ),
      ],
    );
  }

  Widget _buildInlineState({
    required IconData icon,
    required String title,
    required String subtitle,
    String? actionLabel,
    VoidCallback? onAction,
  }) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 36),
      child: Column(
        children: [
          Icon(icon, size: 30, color: palette.textTertiary),
          const SizedBox(height: 10),
          Text(
            title,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: 14,
              fontWeight: FontWeight.w600,
              fontFamily: 'PingFang SC',
            ),
          ),
          const SizedBox(height: 5),
          Text(
            subtitle,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: 12,
              height: 1.45,
              fontFamily: 'PingFang SC',
            ),
          ),
          if (actionLabel != null && onAction != null) ...[
            const SizedBox(height: 14),
            OutlinedButton.icon(
              onPressed: onAction,
              icon: const Icon(Icons.refresh_rounded, size: 16),
              label: Text(actionLabel),
            ),
          ],
        ],
      ),
    );
  }

  String _sessionMetaLine(_AgentSessionSummary session) {
    return [
      if (session.archived) LegacyTextLocalizer.localize('已归档'),
      if (session.cwd.isNotEmpty) session.cwd,
      if (session.timeLabel.isNotEmpty) session.timeLabel,
      if (session.threadId.isNotEmpty) _shortThreadId(session.threadId),
    ].join(' · ');
  }

  Color _statusColorForSession(_AgentSessionSummary session) {
    if (session.archived) {
      return context.omniPalette.textTertiary;
    }
    if (session.active) {
      return const Color(0xFF1F9D55);
    }
    if (session.loaded) {
      return context.omniPalette.accentPrimary;
    }
    return context.omniPalette.textSecondary;
  }
}

class _AgentSessionsStateView extends StatelessWidget {
  const _AgentSessionsStateView({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.actionLabel,
    required this.onAction,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final String actionLabel;
  final VoidCallback onAction;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      children: [
        SizedBox(height: MediaQuery.sizeOf(context).height * 0.18),
        Icon(icon, size: 34, color: palette.textTertiary),
        const SizedBox(height: 12),
        Text(
          title,
          textAlign: TextAlign.center,
          style: TextStyle(
            color: palette.textPrimary,
            fontSize: 15,
            fontWeight: FontWeight.w600,
          ),
        ),
        const SizedBox(height: 6),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32),
          child: Text(
            subtitle,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: 12,
              height: 1.45,
            ),
          ),
        ),
        const SizedBox(height: 14),
        Center(
          child: OutlinedButton.icon(
            onPressed: onAction,
            icon: const Icon(Icons.refresh_rounded, size: 17),
            label: Text(actionLabel),
          ),
        ),
      ],
    );
  }
}

class _AgentSessionWorkspacePage extends StatelessWidget {
  const _AgentSessionWorkspacePage({
    required this.title,
    required this.workspacePath,
    required this.remoteBridgeUrl,
    required this.remoteBridgeToken,
  });

  final String title;
  final String workspacePath;
  final String remoteBridgeUrl;
  final String remoteBridgeToken;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(title: title, primary: true),
      body: SafeArea(
        top: false,
        bottom: false,
        child: CodexRemoteWorkspaceBrowser(
          workspacePath: workspacePath,
          remoteBridgeUrl: remoteBridgeUrl,
          remoteBridgeToken: remoteBridgeToken,
        ),
      ),
    );
  }
}

class _MetricChip extends StatelessWidget {
  const _MetricChip({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            value,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: 16,
              fontWeight: FontWeight.w700,
              height: 1.15,
              fontFamily: 'PingFang SC',
            ),
          ),
          const SizedBox(height: 3),
          Text(
            label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: 11,
              fontWeight: FontWeight.w500,
              height: 1.2,
              fontFamily: 'PingFang SC',
            ),
          ),
        ],
      ),
    );
  }
}

class _MetricDivider extends StatelessWidget {
  const _MetricDivider({required this.color});

  final Color color;

  @override
  Widget build(BuildContext context) {
    return VerticalDivider(
      width: 1,
      thickness: 1,
      color: color.withValues(alpha: context.isDarkTheme ? 0.56 : 0.8),
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({
    required this.label,
    required this.color,
    this.compact = false,
  });

  final String label;
  final Color color;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: compact ? 5 : 6,
          height: compact ? 5 : 6,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        SizedBox(width: compact ? 5 : 6),
        Text(
          label,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            color: color,
            fontSize: compact ? 10.5 : 11.5,
            fontWeight: FontWeight.w700,
            height: 1.2,
            fontFamily: 'PingFang SC',
          ),
        ),
      ],
    );
  }
}

class _SessionTag extends StatelessWidget {
  const _SessionTag({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 180),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 12, color: palette.textTertiary),
          const SizedBox(width: 5),
          Flexible(
            child: Text(
              label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 10.5,
                height: 1.2,
                fontWeight: FontWeight.w500,
                fontFamily: 'PingFang SC',
              ),
            ),
          ),
        ],
      ),
    );
  }
}

enum _AgentSessionFilter { all, active, recent, archived }

enum _AgentSessionAction {
  open,
  workspace,
  rename,
  archive,
  unarchive,
  copyThreadId,
  copyCwd,
}

class _AgentSessionStats {
  const _AgentSessionStats({
    required this.total,
    required this.active,
    required this.loaded,
    required this.archived,
    required this.recent,
  });

  final int total;
  final int active;
  final int loaded;
  final int archived;
  final int recent;

  factory _AgentSessionStats.from(List<_AgentSessionSummary> sessions) {
    final active = sessions
        .where((session) => !session.archived && session.active)
        .length;
    final loaded = sessions
        .where(
          (session) => !session.archived && session.loaded && !session.active,
        )
        .length;
    final archived = sessions.where((session) => session.archived).length;
    final recent = sessions
        .where(
          (session) => !session.archived && !session.active && !session.loaded,
        )
        .length;
    return _AgentSessionStats(
      total: sessions.length,
      active: active,
      loaded: loaded,
      archived: archived,
      recent: recent,
    );
  }
}

class _AgentSessionSummary {
  const _AgentSessionSummary({
    required this.threadId,
    required this.title,
    required this.cwd,
    required this.updatedAtMs,
    required this.archived,
    required this.status,
    required this.active,
    required this.loaded,
    this.preview = '',
    this.model = '',
    this.branch = '',
    this.sourceKind = '',
  });

  final String threadId;
  final String title;
  final String cwd;
  final int? updatedAtMs;
  final bool archived;
  final String status;
  final bool active;
  final bool loaded;
  final String preview;
  final String model;
  final String branch;
  final String sourceKind;

  _AgentSessionSummary copyWith({
    String? title,
    String? cwd,
    int? updatedAtMs,
    bool? archived,
    String? status,
    bool? active,
    bool? loaded,
    String? preview,
    String? model,
    String? branch,
    String? sourceKind,
  }) {
    return _AgentSessionSummary(
      threadId: threadId,
      title: title ?? this.title,
      cwd: cwd ?? this.cwd,
      updatedAtMs: updatedAtMs ?? this.updatedAtMs,
      archived: archived ?? this.archived,
      status: status ?? this.status,
      active: active ?? this.active,
      loaded: loaded ?? this.loaded,
      preview: preview ?? this.preview,
      model: model ?? this.model,
      branch: branch ?? this.branch,
      sourceKind: sourceKind ?? this.sourceKind,
    );
  }

  String get cwdLabel {
    final segment = _lastPathSegment(cwd);
    if (segment == null) {
      return '';
    }
    return segment;
  }

  String get timeLabel => _formatSessionTime(updatedAtMs);

  String get statusLabel {
    if (archived) {
      return LegacyTextLocalizer.localize('已归档');
    }
    if (active) {
      return LegacyTextLocalizer.localize('活动中');
    }
    if (loaded) {
      return LegacyTextLocalizer.localize('已载入');
    }
    if (status.isEmpty) {
      return LegacyTextLocalizer.localize('空闲');
    }
    final normalized = status.toLowerCase();
    return switch (normalized) {
      'running' || 'active' || 'busy' => LegacyTextLocalizer.localize('活动中'),
      'loaded' => LegacyTextLocalizer.localize('已载入'),
      'idle' => LegacyTextLocalizer.localize('空闲'),
      _ => status,
    };
  }

  Map<String, dynamic> toDebugMap() {
    return <String, dynamic>{
      'threadId': threadId,
      'title': title,
      'cwd': cwd,
      'updatedAtMs': updatedAtMs,
      'archived': archived,
      'status': status,
      'active': active,
      'loaded': loaded,
      'preview': preview,
      'model': model,
      'branch': branch,
      'sourceKind': sourceKind,
    };
  }
}

List<_AgentSessionSummary> _extractAgentSessions(List<dynamic> payloads) {
  final sessionsById = <String, _AgentSessionSummary>{};

  void visit(dynamic value, [String? parentKey]) {
    if (value is List) {
      for (final item in value) {
        visit(item, parentKey);
      }
      return;
    }
    if (value is String && _looksLikeLoadedThreadId(value, parentKey)) {
      final threadId = value.trim();
      _mergeAgentSession(
        sessionsById,
        _AgentSessionSummary(
          threadId: threadId,
          title:
              'Agent ${threadId.length > 6 ? threadId.substring(threadId.length - 6) : threadId}',
          cwd: '',
          updatedAtMs: null,
          archived: false,
          status: 'loaded',
          active: false,
          loaded: true,
        ),
      );
      return;
    }
    if (value is! Map) {
      return;
    }
    final map = value.map((key, nestedValue) {
      return MapEntry(key.toString(), nestedValue);
    });
    final threadMap = map['thread'] is Map
        ? (map['thread'] as Map).map(
            (key, nestedValue) => MapEntry(key.toString(), nestedValue),
          )
        : null;
    final threadId = _threadEntryId(map, threadMap, parentKey);
    if (threadId != null) {
      final status = _stringValue(
        map['status'] ??
            map['state'] ??
            map['turnStatus'] ??
            map['turn_status'] ??
            threadMap?['status'] ??
            threadMap?['state'],
      );
      final active =
          _boolValue(
            map['active'] ??
                map['isActive'] ??
                map['is_active'] ??
                threadMap?['active'] ??
                threadMap?['isActive'],
          ) ??
          _statusLooksActive(status);
      final loaded =
          _boolValue(
            map['loaded'] ??
                map['isLoaded'] ??
                map['is_loaded'] ??
                threadMap?['loaded'] ??
                threadMap?['isLoaded'],
          ) ??
          _statusLooksLoaded(status);
      _mergeAgentSession(
        sessionsById,
        _AgentSessionSummary(
          threadId: threadId,
          title:
              _stringValue(
                map['name'] ??
                    map['title'] ??
                    map['preview'] ??
                    map['threadName'] ??
                    map['thread_name'] ??
                    threadMap?['name'] ??
                    threadMap?['title'] ??
                    threadMap?['preview'],
              ) ??
              'Agent ${threadId.length > 6 ? threadId.substring(threadId.length - 6) : threadId}',
          cwd:
              _stringValue(
                map['cwd'] ?? threadMap?['cwd'] ?? map['worktree'],
              ) ??
              '',
          preview:
              _stringValue(
                map['summary'] ??
                    map['subtitle'] ??
                    map['description'] ??
                    map['latestMessage'] ??
                    map['latest_message'] ??
                    threadMap?['summary'] ??
                    threadMap?['subtitle'] ??
                    threadMap?['description'] ??
                    threadMap?['latestMessage'],
              ) ??
              '',
          model:
              _stringValue(
                map['model'] ??
                    map['modelId'] ??
                    map['model_id'] ??
                    threadMap?['model'] ??
                    threadMap?['modelId'] ??
                    threadMap?['model_id'],
              ) ??
              '',
          branch:
              _stringValue(
                map['branch'] ??
                    map['gitBranch'] ??
                    map['git_branch'] ??
                    threadMap?['branch'] ??
                    threadMap?['gitBranch'] ??
                    threadMap?['git_branch'],
              ) ??
              '',
          sourceKind:
              _stringValue(
                map['sourceKind'] ??
                    map['source_kind'] ??
                    threadMap?['sourceKind'] ??
                    threadMap?['source_kind'],
              ) ??
              '',
          updatedAtMs: _timeValueMs(
            map['lastActivityAt'] ??
                map['last_activity_at'] ??
                map['updatedAt'] ??
                map['updated_at'] ??
                map['createdAt'] ??
                map['created_at'] ??
                threadMap?['lastActivityAt'] ??
                threadMap?['updatedAt'] ??
                threadMap?['createdAt'],
          ),
          archived:
              _boolValue(
                map['archived'] ??
                    map['isArchived'] ??
                    map['is_archived'] ??
                    threadMap?['archived'] ??
                    threadMap?['isArchived'],
              ) ??
              false,
          status: status ?? '',
          active: active,
          loaded: loaded,
        ),
      );
    }
    for (final entry in map.entries) {
      if (_threadNestedSkipKeys.contains(entry.key)) {
        continue;
      }
      visit(entry.value, entry.key);
    }
  }

  for (final payload in payloads) {
    visit(payload);
  }
  return sessionsById.values.toList(growable: true);
}

void _mergeAgentSession(
  Map<String, _AgentSessionSummary> sessionsById,
  _AgentSessionSummary next,
) {
  final existing = sessionsById[next.threadId];
  if (existing == null) {
    sessionsById[next.threadId] = next;
    return;
  }
  sessionsById[next.threadId] = _AgentSessionSummary(
    threadId: next.threadId,
    title:
        next.title.startsWith('Agent ') && !existing.title.startsWith('Agent ')
        ? existing.title
        : next.title,
    cwd: next.cwd.isNotEmpty ? next.cwd : existing.cwd,
    updatedAtMs: _maxNullableInt(existing.updatedAtMs, next.updatedAtMs),
    archived: next.archived || existing.archived,
    status: next.status.isNotEmpty ? next.status : existing.status,
    active: next.active || existing.active,
    loaded: next.loaded || existing.loaded,
    preview: next.preview.isNotEmpty ? next.preview : existing.preview,
    model: next.model.isNotEmpty ? next.model : existing.model,
    branch: next.branch.isNotEmpty ? next.branch : existing.branch,
    sourceKind: next.sourceKind.isNotEmpty
        ? next.sourceKind
        : existing.sourceKind,
  );
}

bool _looksLikeLoadedThreadId(String value, String? parentKey) {
  final text = value.trim();
  if (text.isEmpty) {
    return false;
  }
  final normalizedParentKey = parentKey?.toLowerCase() ?? '';
  return normalizedParentKey == 'threads' ||
      normalizedParentKey == 'loadedthreads' ||
      normalizedParentKey == 'loaded_threads' ||
      normalizedParentKey == 'data';
}

bool _statusLooksActive(String? status) {
  final normalized =
      status?.trim().toLowerCase().replaceAll(RegExp(r'[^a-z0-9]+'), '') ?? '';
  return normalized == 'running' ||
      normalized == 'active' ||
      normalized == 'busy' ||
      normalized == 'inprogress' ||
      normalized == 'inflight' ||
      normalized == 'executing';
}

bool _statusLooksLoaded(String? status) {
  final normalized =
      status?.trim().toLowerCase().replaceAll(RegExp(r'[^a-z0-9]+'), '') ?? '';
  return normalized == 'loaded' || normalized == 'ready';
}

int? _maxNullableInt(int? a, int? b) {
  if (a == null) return b;
  if (b == null) return a;
  return a > b ? a : b;
}

String? _threadEntryId(
  Map<String, dynamic> map,
  Map<String, dynamic>? threadMap,
  String? parentKey,
) {
  final direct = _stringValue(
    map['threadId'] ?? map['thread_id'] ?? threadMap?['id'],
  );
  if (direct != null) {
    return direct;
  }
  if (!_looksLikeThreadEntry(map, threadMap, parentKey)) {
    return null;
  }
  return _stringValue(map['id']);
}

bool _looksLikeThreadEntry(
  Map<String, dynamic> map,
  Map<String, dynamic>? threadMap,
  String? parentKey,
) {
  if (threadMap != null ||
      map.containsKey('threadId') ||
      map.containsKey('thread_id')) {
    return true;
  }
  if (!map.containsKey('id')) {
    return false;
  }
  final normalizedParentKey = parentKey?.toLowerCase() ?? '';
  if (normalizedParentKey == 'threads') {
    return true;
  }
  final type = _stringValue(map['type'])?.toLowerCase() ?? '';
  if (_nonThreadItemTypes.contains(type)) {
    return false;
  }
  return map.keys.any(_threadSummaryKeys.contains);
}

String? _stringValue(dynamic value) {
  if (value is Map) {
    final map = value.map((key, nestedValue) {
      return MapEntry(key.toString(), nestedValue);
    });
    for (final key in const <String>[
      'type',
      'status',
      'state',
      'value',
      'name',
    ]) {
      final nested = _stringValue(map[key]);
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }
  final text = value?.toString().trim() ?? '';
  return text.isEmpty ? null : text;
}

int? _intValue(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '');
}

bool? _boolValue(dynamic value) {
  if (value is bool) return value;
  if (value is num) return value.toInt() != 0;
  final normalized = value?.toString().trim().toLowerCase() ?? '';
  return switch (normalized) {
    'true' || '1' || 'yes' => true,
    'false' || '0' || 'no' => false,
    _ => null,
  };
}

int? _timeValueMs(dynamic value) {
  if (value == null) {
    return null;
  }
  if (value is num) {
    final raw = value.toInt();
    return raw < 100000000000 ? raw * 1000 : raw;
  }
  final text = value.toString().trim();
  if (text.isEmpty) {
    return null;
  }
  final rawInt = int.tryParse(text);
  if (rawInt != null) {
    return rawInt < 100000000000 ? rawInt * 1000 : rawInt;
  }
  return DateTime.tryParse(text)?.millisecondsSinceEpoch;
}

String? _lastPathSegment(String path) {
  final normalized = path.trim().replaceAll(RegExp(r'/+$'), '');
  if (normalized.isEmpty) {
    return null;
  }
  final parts = normalized.split('/').where((part) => part.isNotEmpty).toList();
  if (parts.isEmpty) {
    return normalized == '/' ? '/' : null;
  }
  return parts.last;
}

String _formatSessionTime(int? timestampMs) {
  if (timestampMs == null || timestampMs <= 0) {
    return '';
  }
  final time = DateTime.fromMillisecondsSinceEpoch(timestampMs);
  final now = DateTime.now();
  final today = DateTime(now.year, now.month, now.day);
  final day = DateTime(time.year, time.month, time.day);
  final hh = time.hour.toString().padLeft(2, '0');
  final mm = time.minute.toString().padLeft(2, '0');
  if (day == today) {
    return '${LegacyTextLocalizer.localize('今天')} $hh:$mm';
  }
  if (day == today.subtract(const Duration(days: 1))) {
    return '${LegacyTextLocalizer.localize('昨天')} $hh:$mm';
  }
  return '${time.month.toString().padLeft(2, '0')}/${time.day.toString().padLeft(2, '0')} $hh:$mm';
}

String _workspacePathForStatus(AgentRuntimeStatus status) {
  return (status.remoteCwd ?? status.cwd ?? '').trim();
}

String _bridgeLabel(String? url) {
  final raw = url?.trim() ?? '';
  if (raw.isEmpty) {
    return '';
  }
  try {
    final uri = Uri.parse(raw.contains('://') ? raw : 'ws://$raw');
    final host = uri.host.trim();
    if (host.isEmpty) {
      return raw;
    }
    return uri.hasPort ? '$host:${uri.port}' : host;
  } catch (_) {
    return raw;
  }
}

String _shortThreadId(String threadId) {
  final normalized = threadId.trim();
  if (normalized.length <= 8) {
    return normalized;
  }
  return '...${normalized.substring(normalized.length - 8)}';
}

String _formatUptime(int? uptimeMs) {
  if (uptimeMs == null || uptimeMs <= 0) {
    return '';
  }
  final totalMinutes = (uptimeMs / Duration.millisecondsPerMinute).floor();
  if (totalMinutes < 1) {
    return '<1m';
  }
  if (totalMinutes < 60) {
    return '${totalMinutes}m';
  }
  final hours = totalMinutes ~/ 60;
  final minutes = totalMinutes % 60;
  if (hours < 24) {
    return minutes == 0 ? '${hours}h' : '${hours}h ${minutes}m';
  }
  final days = hours ~/ 24;
  final remainingHours = hours % 24;
  return remainingHours == 0 ? '${days}d' : '${days}d ${remainingHours}h';
}

String? _threadIdFromResponse(Map<String, dynamic> response) {
  return _stringValue(response['threadId']) ??
      _stringValue(response['thread_id']) ??
      _stringValue(response['id']) ??
      _stringValue(_asStringMap(response['thread'])?['id']);
}

Map<String, dynamic>? _asStringMap(dynamic value) {
  if (value is! Map) {
    return null;
  }
  return value.map((key, nestedValue) {
    return MapEntry(key.toString(), nestedValue);
  });
}

@visibleForTesting
List<Map<String, dynamic>> extractAgentSessionSummariesForTesting(
  List<dynamic> payloads,
) {
  return _extractAgentSessions(
    payloads,
  ).map((session) => session.toDebugMap()).toList(growable: false);
}

const Set<String> _threadNestedSkipKeys = <String>{
  'messages',
  'turns',
  'input',
  'events',
};

const Set<String> _threadSummaryKeys = <String>{
  'cwd',
  'worktree',
  'name',
  'title',
  'preview',
  'threadName',
  'thread_name',
  'summary',
  'subtitle',
  'description',
  'latestMessage',
  'latest_message',
  'model',
  'modelId',
  'model_id',
  'branch',
  'gitBranch',
  'git_branch',
  'archived',
  'isArchived',
  'is_archived',
  'sourceKind',
  'source_kind',
  'createdAt',
  'created_at',
  'updatedAt',
  'updated_at',
  'lastActivityAt',
  'last_activity_at',
};

const Set<String> _nonThreadItemTypes = <String>{
  'agentmessage',
  'reasoning',
  'commandexecution',
  'local_shell_call',
  'commandexec',
  'processexecution',
  'filechange',
  'tool',
  'mcptoolcall',
  'dynamictoolcall',
  'function_call',
  'function_call_output',
  'custom_tool_call',
  'custom_tool_call_output',
  'tool_search_call',
  'tool_search_output',
  'websearch',
  'web_search_call',
  'imageview',
  'imagegeneration',
  'image_generation_call',
  'collabagenttoolcall',
  'collabtoolcall',
  'usermessage',
  'plan',
  'serverrequest',
};
