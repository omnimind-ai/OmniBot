import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/utils/ui.dart';

// The channel name must match the one in MainActivity.kt
const spePermission = MethodChannel(
  'cn.com.omnimind.bot/SpecialPermissionEvent',
);
const _specialPermissionEvents = EventChannel(
  'cn.com.omnimind.bot/SpecialPermissionEvents',
);

class EmbeddedTerminalInitProgress {
  const EmbeddedTerminalInitProgress({
    required this.kind,
    required this.message,
    required this.timestamp,
  });

  final String kind;
  final String message;
  final DateTime timestamp;

  factory EmbeddedTerminalInitProgress.fromMap(Map<dynamic, dynamic> map) {
    final timestampValue = map['timestamp'];
    final millis = timestampValue is num
        ? timestampValue.toInt()
        : DateTime.now().millisecondsSinceEpoch;
    return EmbeddedTerminalInitProgress(
      kind: (map['kind'] as String? ?? 'status').trim(),
      message: (map['message'] as String? ?? '').trimRight(),
      timestamp: DateTime.fromMillisecondsSinceEpoch(millis),
    );
  }
}

class EmbeddedTerminalInitSnapshot {
  const EmbeddedTerminalInitSnapshot({
    required this.running,
    required this.completed,
    required this.success,
    required this.progress,
    required this.stage,
    required this.logLines,
    required this.startedAt,
    required this.completedAt,
  });

  final bool running;
  final bool completed;
  final bool? success;
  final double progress;
  final String stage;
  final List<String> logLines;
  final DateTime? startedAt;
  final DateTime? completedAt;

  factory EmbeddedTerminalInitSnapshot.fromMap(Map<dynamic, dynamic> map) {
    final rawLogLines = map['logLines'];
    return EmbeddedTerminalInitSnapshot(
      running: map['running'] == true,
      completed: map['completed'] == true,
      success: map['success'] as bool?,
      progress: ((map['progress'] as num?)?.toDouble() ?? 0.0).clamp(0.0, 1.0),
      stage: (map['stage'] as String? ?? '').trim(),
      logLines: rawLogLines is List
          ? rawLogLines.map((line) => line.toString()).toList(growable: false)
          : const <String>[],
      startedAt: _parseEmbeddedTerminalInitTimestamp(map['startedAt']),
      completedAt: _parseEmbeddedTerminalInitTimestamp(map['completedAt']),
    );
  }
}

DateTime? _parseEmbeddedTerminalInitTimestamp(dynamic rawValue) {
  if (rawValue is! num) {
    return null;
  }
  final millis = rawValue.toInt();
  if (millis <= 0) {
    return null;
  }
  return DateTime.fromMillisecondsSinceEpoch(millis);
}

class EmbeddedTerminalRuntimeStatus {
  const EmbeddedTerminalRuntimeStatus({
    required this.supported,
    required this.runtimeReady,
    required this.basePackagesReady,
    required this.allReady,
    required this.missingCommands,
    required this.message,
    required this.nodeReady,
    required this.nodeVersion,
    required this.nodeMinMajor,
    required this.pnpmReady,
    required this.pnpmVersion,
    required this.workspaceAccessGranted,
  });

  final bool supported;
  final bool runtimeReady;
  final bool basePackagesReady;
  final bool allReady;
  final List<String> missingCommands;
  final String message;
  final bool nodeReady;
  final String? nodeVersion;
  final int nodeMinMajor;
  final bool pnpmReady;
  final String? pnpmVersion;
  final bool workspaceAccessGranted;

  factory EmbeddedTerminalRuntimeStatus.fromMap(Map<dynamic, dynamic> map) {
    final rawMissing = map['missingCommands'];
    return EmbeddedTerminalRuntimeStatus(
      supported: map['supported'] == true,
      runtimeReady: map['runtimeReady'] == true,
      basePackagesReady: map['basePackagesReady'] == true,
      allReady: map['allReady'] == true,
      missingCommands: rawMissing is List
          ? rawMissing.map((it) => it.toString()).toList(growable: false)
          : const <String>[],
      message: (map['message'] as String? ?? '').trim(),
      nodeReady: map['nodeReady'] == true,
      nodeVersion: (map['nodeVersion'] as String?)?.trim(),
      nodeMinMajor: (map['nodeMinMajor'] as num?)?.toInt() ?? 22,
      pnpmReady: map['pnpmReady'] == true,
      pnpmVersion: (map['pnpmVersion'] as String?)?.trim(),
      workspaceAccessGranted: map['workspaceAccessGranted'] == true,
    );
  }
}

class EmbeddedTerminalSetupStatus {
  const EmbeddedTerminalSetupStatus({required this.packages});

  final Map<String, bool> packages;

  factory EmbeddedTerminalSetupStatus.fromMap(Map<dynamic, dynamic>? map) {
    final rawPackages = map?['packages'];
    final packages = <String, bool>{};
    if (rawPackages is Map) {
      for (final entry in rawPackages.entries) {
        packages[entry.key.toString()] = entry.value == true;
      }
    }
    return EmbeddedTerminalSetupStatus(packages: packages);
  }
}

class EmbeddedTerminalSetupInventoryItem {
  const EmbeddedTerminalSetupInventoryItem({
    required this.ready,
    required this.version,
  });

  final bool ready;
  final String? version;

  factory EmbeddedTerminalSetupInventoryItem.fromMap(
    Map<dynamic, dynamic>? map,
  ) {
    return EmbeddedTerminalSetupInventoryItem(
      ready: map?['ready'] == true,
      version: (map?['version'] as String?)?.trim(),
    );
  }
}

class EmbeddedTerminalSetupInventory {
  const EmbeddedTerminalSetupInventory({required this.packages});

  final Map<String, EmbeddedTerminalSetupInventoryItem> packages;

  factory EmbeddedTerminalSetupInventory.fromMap(Map<dynamic, dynamic>? map) {
    final rawPackages = map?['packages'];
    final packages = <String, EmbeddedTerminalSetupInventoryItem>{};
    if (rawPackages is Map) {
      for (final entry in rawPackages.entries) {
        packages[entry.key
            .toString()] = EmbeddedTerminalSetupInventoryItem.fromMap(
          entry.value is Map
              ? Map<dynamic, dynamic>.from(entry.value as Map)
              : const <dynamic, dynamic>{},
        );
      }
    }
    return EmbeddedTerminalSetupInventory(packages: packages);
  }
}

class EmbeddedTerminalSetupResult {
  const EmbeddedTerminalSetupResult({
    required this.success,
    required this.message,
    required this.output,
  });

  final bool success;
  final String message;
  final String output;

  factory EmbeddedTerminalSetupResult.fromMap(Map<dynamic, dynamic>? map) {
    return EmbeddedTerminalSetupResult(
      success: map?['success'] == true,
      message: (map?['message'] as String? ?? '').trim(),
      output: (map?['output'] as String? ?? '').trim(),
    );
  }
}

class EmbeddedTerminalSetupSessionSnapshot {
  const EmbeddedTerminalSetupSessionSnapshot({
    required this.sessionId,
    required this.running,
    required this.completed,
    required this.success,
    required this.message,
    required this.selectedPackageIds,
  });

  final String? sessionId;
  final bool running;
  final bool completed;
  final bool? success;
  final String message;
  final List<String> selectedPackageIds;

  bool get hasSession => (sessionId ?? '').trim().isNotEmpty;

  factory EmbeddedTerminalSetupSessionSnapshot.fromMap(
    Map<dynamic, dynamic>? map,
  ) {
    final rawSelectedPackageIds = map?['selectedPackageIds'];
    return EmbeddedTerminalSetupSessionSnapshot(
      sessionId: (map?['sessionId'] as String?)?.trim(),
      running: map?['running'] == true,
      completed: map?['completed'] == true,
      success: map?['success'] as bool?,
      message: (map?['message'] as String? ?? '').trim(),
      selectedPackageIds: rawSelectedPackageIds is List
          ? rawSelectedPackageIds
                .map((item) => item.toString())
                .where((item) => item.trim().isNotEmpty)
                .toList(growable: false)
          : const <String>[],
    );
  }
}

class EmbeddedTerminalAutoStartTask {
  const EmbeddedTerminalAutoStartTask({
    required this.id,
    required this.name,
    required this.command,
    required this.workingDirectory,
    required this.enabled,
    required this.running,
    required this.sessionId,
  });

  final String id;
  final String name;
  final String command;
  final String? workingDirectory;
  final bool enabled;
  final bool running;
  final String sessionId;

  factory EmbeddedTerminalAutoStartTask.fromMap(Map<dynamic, dynamic>? map) {
    return EmbeddedTerminalAutoStartTask(
      id: (map?['id'] as String? ?? '').trim(),
      name: (map?['name'] as String? ?? '').trim(),
      command: (map?['command'] as String? ?? '').trim(),
      workingDirectory: (map?['workingDirectory'] as String?)?.trim(),
      enabled: map?['enabled'] != false,
      running: map?['running'] == true,
      sessionId: (map?['sessionId'] as String? ?? '').trim(),
    );
  }
}

class EmbeddedTerminalAutoStartTaskList {
  const EmbeddedTerminalAutoStartTaskList({required this.tasks});

  final List<EmbeddedTerminalAutoStartTask> tasks;

  factory EmbeddedTerminalAutoStartTaskList.fromMap(
    Map<dynamic, dynamic>? map,
  ) {
    final rawTasks = map?['tasks'];
    return EmbeddedTerminalAutoStartTaskList(
      tasks: rawTasks is List
          ? rawTasks
                .map(
                  (item) => EmbeddedTerminalAutoStartTask.fromMap(
                    item is Map
                        ? Map<dynamic, dynamic>.from(item)
                        : const <dynamic, dynamic>{},
                  ),
                )
                .toList(growable: false)
          : const <EmbeddedTerminalAutoStartTask>[],
    );
  }
}

class EmbeddedTerminalAutoStartTaskRunResult {
  const EmbeddedTerminalAutoStartTaskRunResult({
    required this.taskId,
    required this.started,
    required this.alreadyRunning,
    required this.message,
    required this.sessionId,
  });

  final String taskId;
  final bool started;
  final bool alreadyRunning;
  final String message;
  final String sessionId;

  factory EmbeddedTerminalAutoStartTaskRunResult.fromMap(
    Map<dynamic, dynamic>? map,
  ) {
    return EmbeddedTerminalAutoStartTaskRunResult(
      taskId: (map?['taskId'] as String? ?? '').trim(),
      started: map?['started'] == true,
      alreadyRunning: map?['alreadyRunning'] == true,
      message: (map?['message'] as String? ?? '').trim(),
      sessionId: (map?['sessionId'] as String? ?? '').trim(),
    );
  }
}

class EmbeddedTerminalBackupStatus {
  const EmbeddedTerminalBackupStatus({
    required this.configured,
    required this.enabled,
    required this.toolPath,
    required this.source,
    required this.repository,
    required this.passwordFile,
    required this.publicStorageGranted,
    required this.resticReady,
    required this.schedulerRunning,
    required this.watchdogRunning,
    required this.lastSuccessEpoch,
    required this.lastSuccessLocal,
    required this.rawOutput,
    required this.message,
  });

  final bool configured;
  final bool enabled;
  final String toolPath;
  final String source;
  final String repository;
  final String passwordFile;
  final bool publicStorageGranted;
  final bool resticReady;
  final bool schedulerRunning;
  final bool watchdogRunning;
  final int? lastSuccessEpoch;
  final String? lastSuccessLocal;
  final String rawOutput;
  final String? message;

  factory EmbeddedTerminalBackupStatus.fromMap(Map<dynamic, dynamic>? map) {
    return EmbeddedTerminalBackupStatus(
      configured: map?['configured'] == true,
      enabled: map?['enabled'] == true,
      toolPath: (map?['toolPath'] as String? ?? '').trim(),
      source: (map?['source'] as String? ?? '').trim(),
      repository: (map?['repository'] as String? ?? '').trim(),
      passwordFile: (map?['passwordFile'] as String? ?? '').trim(),
      publicStorageGranted: map?['publicStorageGranted'] == true,
      resticReady: map?['resticReady'] == true,
      schedulerRunning: map?['schedulerRunning'] == true,
      watchdogRunning: map?['watchdogRunning'] == true,
      lastSuccessEpoch: (map?['lastSuccessEpoch'] as num?)?.toInt(),
      lastSuccessLocal: (map?['lastSuccessLocal'] as String?)?.trim(),
      rawOutput: (map?['rawOutput'] as String? ?? '').trim(),
      message: (map?['message'] as String?)?.trim(),
    );
  }
}

class EmbeddedTerminalBackupCommandResult {
  const EmbeddedTerminalBackupCommandResult({
    required this.success,
    required this.timedOut,
    required this.exitCode,
    required this.message,
    required this.output,
    required this.status,
  });

  final bool success;
  final bool timedOut;
  final int? exitCode;
  final String message;
  final String output;
  final EmbeddedTerminalBackupStatus? status;

  factory EmbeddedTerminalBackupCommandResult.fromMap(
    Map<dynamic, dynamic>? map,
  ) {
    final rawStatus = map?['status'];
    return EmbeddedTerminalBackupCommandResult(
      success: map?['success'] == true,
      timedOut: map?['timedOut'] == true,
      exitCode: (map?['exitCode'] as num?)?.toInt(),
      message: (map?['message'] as String? ?? '').trim(),
      output: (map?['output'] as String? ?? '').trim(),
      status: rawStatus is Map
          ? EmbeddedTerminalBackupStatus.fromMap(
              Map<dynamic, dynamic>.from(rawStatus),
            )
          : null,
    );
  }
}

Stream<EmbeddedTerminalInitProgress> get embeddedTerminalInitProgressStream {
  return _specialPermissionEvents.receiveBroadcastStream().map((event) {
    final payload = event is Map
        ? Map<dynamic, dynamic>.from(event)
        : const <dynamic, dynamic>{};
    return EmbeddedTerminalInitProgress.fromMap(payload);
  });
}

Future<EmbeddedTerminalInitSnapshot> getEmbeddedTerminalInitSnapshot() async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'getEmbeddedTerminalInitSnapshot',
  );
  return EmbeddedTerminalInitSnapshot.fromMap(result ?? const {});
}

Future<EmbeddedTerminalRuntimeStatus> getEmbeddedTerminalRuntimeStatus() async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'getEmbeddedTerminalRuntimeStatus',
  );
  return EmbeddedTerminalRuntimeStatus.fromMap(result ?? const {});
}

Future<EmbeddedTerminalSetupStatus> getEmbeddedTerminalSetupStatus() async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'getEmbeddedTerminalSetupStatus',
  );
  return EmbeddedTerminalSetupStatus.fromMap(result ?? const {});
}

Future<EmbeddedTerminalSetupInventory>
getEmbeddedTerminalSetupInventory() async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'getEmbeddedTerminalSetupInventory',
  );
  return EmbeddedTerminalSetupInventory.fromMap(result ?? const {});
}

Future<EmbeddedTerminalSetupSessionSnapshot>
getEmbeddedTerminalSetupSessionSnapshot() async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'getEmbeddedTerminalSetupSessionSnapshot',
  );
  return EmbeddedTerminalSetupSessionSnapshot.fromMap(result ?? const {});
}

Future<EmbeddedTerminalSetupResult> installEmbeddedTerminalPackages(
  List<String> packageIds,
) async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'installEmbeddedTerminalPackages',
    {'packageIds': packageIds},
  );
  return EmbeddedTerminalSetupResult.fromMap(result ?? const {});
}

Future<EmbeddedTerminalSetupSessionSnapshot> startEmbeddedTerminalSetupSession(
  List<String> packageIds,
) async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'startEmbeddedTerminalSetupSession',
    {'packageIds': packageIds},
  );
  return EmbeddedTerminalSetupSessionSnapshot.fromMap(result ?? const {});
}

Future<void> dismissEmbeddedTerminalSetupSession() async {
  await spePermission.invokeMethod<void>('dismissEmbeddedTerminalSetupSession');
}

Future<EmbeddedTerminalAutoStartTaskList>
getEmbeddedTerminalAutoStartTasks() async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'getEmbeddedTerminalAutoStartTasks',
  );
  return EmbeddedTerminalAutoStartTaskList.fromMap(result ?? const {});
}

Future<EmbeddedTerminalAutoStartTask> saveEmbeddedTerminalAutoStartTask({
  String? id,
  required String name,
  required String command,
  String? workingDirectory,
  required bool enabled,
}) async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'saveEmbeddedTerminalAutoStartTask',
    <String, dynamic>{
      'id': id,
      'name': name,
      'command': command,
      'workingDirectory': workingDirectory,
      'enabled': enabled,
    },
  );
  return EmbeddedTerminalAutoStartTask.fromMap(result ?? const {});
}

Future<void> deleteEmbeddedTerminalAutoStartTask(String id) async {
  await spePermission.invokeMethod<void>(
    'deleteEmbeddedTerminalAutoStartTask',
    {'id': id},
  );
}

Future<EmbeddedTerminalAutoStartTaskRunResult> runEmbeddedTerminalAutoStartTask(
  String id,
) async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'runEmbeddedTerminalAutoStartTask',
    <String, dynamic>{'id': id},
  );
  return EmbeddedTerminalAutoStartTaskRunResult.fromMap(result ?? const {});
}

Future<EmbeddedTerminalBackupStatus> getEmbeddedTerminalBackupStatus() async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'getEmbeddedTerminalBackupStatus',
  );
  return EmbeddedTerminalBackupStatus.fromMap(result ?? const {});
}

Future<EmbeddedTerminalBackupStatus> setEmbeddedTerminalBackupEnabled(
  bool enabled,
) async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'setEmbeddedTerminalBackupEnabled',
    <String, dynamic>{'enabled': enabled},
  );
  return EmbeddedTerminalBackupStatus.fromMap(result ?? const {});
}

Future<EmbeddedTerminalBackupCommandResult>
runEmbeddedTerminalBackupNow() async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'runEmbeddedTerminalBackupNow',
  );
  return EmbeddedTerminalBackupCommandResult.fromMap(result ?? const {});
}

Future<void> openNativeTerminal({
  bool openSetup = false,
  List<String> setupPackageIds = const <String>[],
}) async {
  await spePermission.invokeMethod<void>('openNativeTerminal', {
    'openSetup': openSetup,
    'setupPackageIds': setupPackageIds,
  });
}

class ShizukuStatusSnapshot {
  const ShizukuStatusSnapshot({
    required this.status,
    required this.backend,
    required this.installed,
    required this.running,
    required this.permissionGranted,
    required this.binderReady,
    required this.serviceBound,
    required this.availableActions,
    required this.message,
    this.uid,
    this.version,
  });

  final String status;
  final String backend;
  final bool installed;
  final bool running;
  final bool permissionGranted;
  final bool binderReady;
  final bool serviceBound;
  final List<String> availableActions;
  final String message;
  final int? uid;
  final int? version;

  bool get isGranted => status == 'GRANTED_ADB' || status == 'GRANTED_ROOT';
  bool get isRootBackend => backend == 'ROOT';

  factory ShizukuStatusSnapshot.fromMap(Map<dynamic, dynamic>? map) {
    final rawActions = map?['availableActions'];
    return ShizukuStatusSnapshot(
      status: (map?['status'] as String? ?? 'NOT_INSTALLED').trim(),
      backend: (map?['backend'] as String? ?? 'NONE').trim(),
      installed: map?['installed'] == true,
      running: map?['running'] == true,
      permissionGranted: map?['permissionGranted'] == true,
      binderReady: map?['binderReady'] == true,
      serviceBound: map?['serviceBound'] == true,
      availableActions: rawActions is List
          ? rawActions.map((item) => item.toString()).toList(growable: false)
          : const <String>[],
      message: (map?['message'] as String? ?? '').trim(),
      uid: (map?['uid'] as num?)?.toInt(),
      version: (map?['version'] as num?)?.toInt(),
    );
  }

  factory ShizukuStatusSnapshot.fallback() {
    return const ShizukuStatusSnapshot(
      status: 'NOT_INSTALLED',
      backend: 'NONE',
      installed: false,
      running: false,
      permissionGranted: false,
      binderReady: false,
      serviceBound: false,
      availableActions: <String>[],
      message: '',
    );
  }

  String get localizedStatusLabel {
    switch (status) {
      case 'GRANTED_ROOT':
        return LegacyTextLocalizer.isEnglish ? 'Granted (root)' : '已授权（root）';
      case 'GRANTED_ADB':
        return LegacyTextLocalizer.isEnglish ? 'Granted (adb)' : '已授权（adb）';
      case 'PERMISSION_DENIED':
        return LegacyTextLocalizer.isEnglish
            ? 'Running, permission not granted'
            : '已启动，尚未授权';
      case 'NOT_RUNNING':
        return LegacyTextLocalizer.isEnglish
            ? 'Installed, not running'
            : '已安装，未启动';
      case 'BINDER_DEAD':
        return LegacyTextLocalizer.isEnglish
            ? 'Binder lost, restart required'
            : '连接已断开，需要重新启动';
      default:
        return LegacyTextLocalizer.isEnglish ? 'Not installed' : '未安装';
    }
  }

  String get localizedGuide {
    switch (status) {
      case 'GRANTED_ROOT':
        return LegacyTextLocalizer.isEnglish
            ? 'Shizuku is ready through root/Sui. Agent privileged tools are enabled, including arbitrary shell and persistent shell sessions. Every raw shell and privileged session command still requires confirmation.'
            : 'Shizuku 已通过 root/Sui 就绪。Agent 高权限工具已启用，支持任意 shell 和持久高权限会话；每次 raw shell 与高权限会话命令仍需用户确认。';
      case 'GRANTED_ADB':
        return LegacyTextLocalizer.isEnglish
            ? 'Shizuku is ready through adb shell. Agent privileged tools are enabled, including arbitrary shell and persistent shell sessions. Commands still follow adb-shell capability limits, and every raw shell and privileged session command requires confirmation.'
            : 'Shizuku 已通过 adb shell 就绪。Agent 高权限工具已启用，支持任意 shell 和持久高权限会话，但能力仍受 adb shell 后端限制；每次 raw shell 与高权限会话命令都需要用户确认。';
      case 'PERMISSION_DENIED':
        return LegacyTextLocalizer.isEnglish
            ? 'Open Shizuku and grant Omnibot permission.'
            : '请打开 Shizuku 并授予 Omnibot 权限。';
      case 'NOT_RUNNING':
        return LegacyTextLocalizer.isEnglish
            ? 'Open Shizuku and start it. On Android 11+, non-root devices usually start it from Wireless debugging. You need to restart Shizuku after each reboot.'
            : '请打开 Shizuku 并启动它。Android 11+ 非 root 设备通常需要从无线调试启动，并且每次重启后都需要重新启动 Shizuku。';
      case 'BINDER_DEAD':
        return LegacyTextLocalizer.isEnglish
            ? 'Shizuku was restarted or disconnected. Open it again to reconnect.'
            : 'Shizuku 已重启或断开，请重新打开并启动。';
      default:
        return LegacyTextLocalizer.isEnglish
            ? 'Install Shizuku first, then start it and grant Omnibot permission.'
            : '请先安装 Shizuku，然后启动它并授予 Omnibot 权限。';
    }
  }
}

class ShizukuHealthCheckSnapshot {
  const ShizukuHealthCheckSnapshot({
    required this.status,
    required this.probeSuccess,
    required this.probeMessage,
    required this.rawShellProbeSuccess,
    required this.rawShellProbeMessage,
    required this.sessionProbeSuccess,
    required this.sessionProbeMessage,
  });

  final ShizukuStatusSnapshot status;
  final bool probeSuccess;
  final String probeMessage;
  final bool rawShellProbeSuccess;
  final String rawShellProbeMessage;
  final bool sessionProbeSuccess;
  final String sessionProbeMessage;

  factory ShizukuHealthCheckSnapshot.fromMap(Map<dynamic, dynamic>? map) {
    final probe = map?['probe'];
    final probeMap = probe is Map ? Map<dynamic, dynamic>.from(probe) : null;
    final rawShellProbe = map?['rawShellProbe'];
    final rawShellProbeMap =
        rawShellProbe is Map ? Map<dynamic, dynamic>.from(rawShellProbe) : null;
    final sessionProbe = map?['sessionProbe'];
    final sessionProbeMap =
        sessionProbe is Map ? Map<dynamic, dynamic>.from(sessionProbe) : null;
    final sessionStart = _asNestedMap(sessionProbeMap?['start']);
    final sessionExec = _asNestedMap(sessionProbeMap?['exec']);
    final sessionRead = _asNestedMap(sessionProbeMap?['read']);
    final sessionStop = _asNestedMap(sessionProbeMap?['stop']);
    final sessionStages = [
      if (sessionStart != null) sessionStart,
      if (sessionExec != null) sessionExec,
      if (sessionRead != null) sessionRead,
      if (sessionStop != null) sessionStop,
    ];
    return ShizukuHealthCheckSnapshot(
      status: ShizukuStatusSnapshot.fromMap(map),
      probeSuccess: probeMap?['success'] == true,
      probeMessage: (probeMap?['message'] as String? ?? '').trim(),
      rawShellProbeSuccess: rawShellProbeMap?['success'] == true,
      rawShellProbeMessage: (rawShellProbeMap?['message'] as String? ?? '')
          .trim(),
      sessionProbeSuccess:
          sessionStages.isNotEmpty &&
          sessionStages.every((stage) => stage['success'] == true),
      sessionProbeMessage: ((sessionExec ?? sessionStart)?['message'] as String? ??
              (sessionStop?['message'] as String? ?? ''))
          .trim(),
    );
  }
}

Map<dynamic, dynamic>? _asNestedMap(dynamic raw) {
  return raw is Map ? Map<dynamic, dynamic>.from(raw) : null;
}

Future<bool> isShizukuInstalled() async {
  try {
    return await spePermission.invokeMethod<bool>('isShizukuInstalled') ??
        false;
  } catch (e) {
    debugPrint('检查 Shizuku 安装状态失败: $e');
    return false;
  }
}

Future<bool> isShizukuRunning() async {
  try {
    return await spePermission.invokeMethod<bool>('isShizukuRunning') ?? false;
  } catch (e) {
    debugPrint('检查 Shizuku 运行状态失败: $e');
    return false;
  }
}

Future<void> openShizukuDownloadOrApp() async {
  await spePermission.invokeMethod<void>('openShizukuDownloadOrApp');
}

Future<ShizukuStatusSnapshot> getShizukuStatus() async {
  try {
    final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
      'getShizukuStatus',
    );
    return ShizukuStatusSnapshot.fromMap(result);
  } catch (e) {
    debugPrint('读取 Shizuku 状态失败: $e');
    return ShizukuStatusSnapshot.fallback();
  }
}

Future<bool> requestShizukuPermission() async {
  try {
    final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
      'requestShizukuPermission',
    );
    return ShizukuStatusSnapshot.fromMap(result).isGranted;
  } catch (e) {
    debugPrint('请求 Shizuku 权限失败: $e');
    return false;
  }
}

Future<ShizukuHealthCheckSnapshot> runShizukuHealthCheck() async {
  try {
    final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
      'runShizukuHealthCheck',
    );
    return ShizukuHealthCheckSnapshot.fromMap(result);
  } catch (e) {
    debugPrint('执行 Shizuku 健康检查失败: $e');
    return ShizukuHealthCheckSnapshot(
      status: ShizukuStatusSnapshot.fallback(),
      probeSuccess: false,
      probeMessage: '',
      rawShellProbeSuccess: false,
      rawShellProbeMessage: '',
      sessionProbeSuccess: false,
      sessionProbeMessage: '',
    );
  }
}

Future<bool> ensureShizukuPermission(BuildContext context) async {
  final status = await getShizukuStatus();
  if (status.isGranted) {
    return true;
  }
  if (!context.mounted) {
    return false;
  }
  final confirmed = await AppDialog.confirm(
    context,
    title: LegacyTextLocalizer.isEnglish ? 'Shizuku Permission' : 'Shizuku 权限',
    content: status.localizedGuide,
    cancelText: LegacyTextLocalizer.isEnglish ? 'Cancel' : '取消',
    confirmText: LegacyTextLocalizer.isEnglish
        ? (status.installed ? 'Open Shizuku' : 'Install Shizuku')
        : (status.installed ? '打开 Shizuku' : '安装 Shizuku'),
  );
  if (confirmed != true) {
    return false;
  }
  await openShizukuDownloadOrApp();
  if (!status.installed || !status.running) {
    return false;
  }
  return requestShizukuPermission();
}

/// 检查无障碍权限，如果没有权限则弹出授权对话框
/// 返回 true 表示有权限，false 表示没有权限
Future<bool> checkAccessibilityPermission(BuildContext context) async {
  try {
    final hasPermission = await spePermission.invokeMethod(
      'isAccessibilityServiceEnabled',
    );
    if (hasPermission == true) {
      return true;
    }

    if (!context.mounted) {
      return false;
    }

    // 没有权限，弹出对话框
    final result = await AppDialog.confirm(
      context,
      title: LegacyTextLocalizer.isEnglish ? 'Accessibility' : '无障碍权限',
      content: LegacyTextLocalizer.isEnglish
          ? 'Accessibility permission needs to be re-granted each time the app starts for your security.'
          : '每次开启App需重新授权无障碍的权限，这也是为了你的安全～',
      cancelText: LegacyTextLocalizer.isEnglish ? 'Cancel' : '取消',
      confirmText: LegacyTextLocalizer.isEnglish ? 'Authorize' : '去授权',
    );

    if (!context.mounted) {
      return false;
    }
    if (result == true) {
      await spePermission.invokeMethod('openAccessibilitySettings');
    }

    return false;
  } catch (e) {
    debugPrint('检查无障碍权限失败: $e');
    return false;
  }
}

Future<bool> requestPermission(List<String> permissions) async {
  try {
    final hasPermission = await spePermission.invokeMethod(
      'requestPermissions',
      {'permissions': permissions},
    );
    return hasPermission == "Success";
  } catch (e) {
    debugPrint('检查无障碍权限失败: $e');
    return false;
  }
}

Future<bool> isTermuxInstalled() async {
  try {
    return await spePermission.invokeMethod<bool>('isTermuxInstalled') ?? false;
  } catch (e) {
    debugPrint('检查 Termux 安装状态失败: $e');
    return false;
  }
}

Future<bool> openTermuxApp() async {
  try {
    return await spePermission.invokeMethod<bool>('openTermuxApp') ?? false;
  } catch (e) {
    debugPrint('打开 Termux 失败: $e');
    return false;
  }
}

Future<bool> isTermuxRunCommandPermissionGranted() async {
  try {
    return await spePermission.invokeMethod<bool>(
          'isTermuxRunCommandPermissionGranted',
        ) ??
        false;
  } catch (e) {
    debugPrint('检查 Termux RUN_COMMAND 权限失败: $e');
    return false;
  }
}

Future<bool> requestTermuxRunCommandPermission() async {
  try {
    return await spePermission.invokeMethod<bool>(
          'requestTermuxRunCommandPermission',
        ) ??
        false;
  } catch (e) {
    debugPrint('请求 Termux RUN_COMMAND 权限失败: $e');
    return false;
  }
}

Future<bool> ensureInstalledAppsPermission() async {
  try {
    final hasPermission =
        await spePermission.invokeMethod<bool>(
          'isInstalledAppsPermissionGranted',
        ) ??
        false;
    if (hasPermission) {
      return true;
    }
    await openInstalledAppsSettings();
  } catch (e) {
    debugPrint('检查应用列表读取权限失败: $e');
  }
  return false;
}

Future<void> openInstalledAppsSettings() async {
  await spePermission.invokeMethod('openInstalledAppsSettings');
}

Future<void> openAppDetailsSettings() async {
  await spePermission.invokeMethod('openAppDetailsSettings');
}

Future<bool> isNotificationPermissionGranted() async {
  try {
    return await spePermission.invokeMethod<bool>(
          'isNotificationPermissionGranted',
        ) ??
        false;
  } catch (e) {
    debugPrint('检查通知权限失败: $e');
    return false;
  }
}

Future<bool> requestNotificationPermission() async {
  try {
    return await spePermission.invokeMethod<bool>(
          'requestNotificationPermission',
        ) ??
        false;
  } catch (e) {
    debugPrint('请求通知权限失败: $e');
    return false;
  }
}

Future<bool> ensureNotificationPermission() async {
  if (await isNotificationPermissionGranted()) {
    return true;
  }
  return requestNotificationPermission();
}

Future<bool> isWorkspaceStorageAccessGranted() async {
  try {
    return await spePermission.invokeMethod<bool>(
          'isWorkspaceStorageAccessGranted',
        ) ??
        false;
  } catch (e) {
    debugPrint('检查内置 workspace 状态失败: $e');
    return false;
  }
}

Future<void> openWorkspaceStorageSettings() async {
  await spePermission.invokeMethod('openWorkspaceStorageSettings');
}

Future<bool> isPublicStorageAccessGranted() async {
  try {
    return await spePermission.invokeMethod<bool>(
          'isPublicStorageAccessGranted',
        ) ??
        false;
  } catch (e) {
    debugPrint('检查公共文件访问权限失败: $e');
    return false;
  }
}

Future<void> openPublicStorageSettings() async {
  await spePermission.invokeMethod('openPublicStorageSettings');
}

Future<Map<String, dynamic>> prepareTermuxLiveWrapper() async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'prepareTermuxLiveWrapper',
  );
  return Map<String, dynamic>.from(result ?? const {});
}

Future<bool> isUnknownAppInstallAllowed() async {
  try {
    return await spePermission.invokeMethod<bool>(
          'isUnknownAppInstallAllowed',
        ) ??
        false;
  } catch (e) {
    debugPrint('检查未知应用安装权限失败: $e');
    return false;
  }
}

Future<void> openUnknownAppInstallSettings() async {
  await spePermission.invokeMethod('openUnknownAppInstallSettings');
}

Future<Map<String, dynamic>> downloadAndInstallTermuxApk(
  String downloadUrl,
) async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'downloadAndInstallTermuxApk',
    {'downloadUrl': downloadUrl},
  );
  return Map<String, dynamic>.from(result ?? const {});
}

Future<bool> isBackgroundRunAllowed() async {
  try {
    return await spePermission.invokeMethod<bool>('isBackgroundRunAllowed') ??
        false;
  } catch (e) {
    debugPrint('检查后台运行权限失败: $e');
    return false;
  }
}
