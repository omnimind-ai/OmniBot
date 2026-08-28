import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/command_overlay/services/manual_recording_permission_guard.dart';
import 'package:ui/features/task/run_log/omniflow_tool_client.dart';
import 'package:ui/services/screen_dialog_service.dart';
import 'package:ui/utils/ui.dart';

class ManualRecordingFlowMessageIds {
  const ManualRecordingFlowMessageIds({
    required this.userMessageId,
    required this.aiMessageId,
  });

  final String userMessageId;
  final String aiMessageId;
}

typedef ManualRecordingAuthorizer = Future<bool> Function(BuildContext context);

typedef ManualRecordingNativeStarter =
    Future<Map<String, dynamic>> Function({
      required String name,
      required String description,
      required bool enableDebugScreenshots,
    });

class ManualRecordingFlowController {
  const ManualRecordingFlowController._();

  static bool isCommand(String messageText) {
    final normalized = messageText.trim().toLowerCase();
    return normalized == '/record' ||
        normalized == '手动录制' ||
        normalized == '开始手动录制' ||
        normalized == '人工录制' ||
        normalized == '录制轨迹' ||
        normalized == '开始录制轨迹' ||
        normalized == '轨迹录制' ||
        normalized == 'manual recording' ||
        normalized == 'manual record' ||
        normalized == 'record trajectory' ||
        normalized == 'start recording';
  }

  static Future<bool> start({
    required BuildContext context,
    required FocusNode inputFocusNode,
    required String userMessageText,
    required bool recordDebugScreenshots,
    required bool Function() isMounted,
    required ManualRecordingFlowMessageIds Function(String text) addUserMessage,
    FutureOr<void> Function(ManualRecordingFlowMessageIds ids)?
    afterUserMessageAdded,
    void Function(String messageId, Map<String, dynamic> result)?
    insertResultMessage,
    FutureOr<void> Function()? beforeNativeRecording,
    FutureOr<void> Function()? afterNativeRecording,
    FutureOr<void> Function()? onFinally,
    ManualRecordingAuthorizer? ensureAuthorized,
    ManualRecordingNativeStarter? startNativeRecording,
  }) async {
    final locale = Localizations.localeOf(context);
    final canRecord =
        await (ensureAuthorized ??
                ManualRecordingPermissionGuard.ensureAuthorized)
            .call(context);
    if (!isMounted() || !canRecord) return false;

    inputFocusNode.unfocus();
    ManualRecordingFlowMessageIds? messageIds;

    var shouldRestoreNativeSurface = false;
    var didRestoreNativeSurface = false;
    Future<void> restoreNativeSurfaceIfNeeded() async {
      if (!shouldRestoreNativeSurface || didRestoreNativeSurface) return;
      didRestoreNativeSurface = true;
      try {
        await afterNativeRecording?.call();
      } catch (error) {
        debugPrint('Failed to restore after manual recording: $error');
      }
    }

    try {
      final normalizedUserMessage = userMessageText.trim();
      if (normalizedUserMessage.isNotEmpty) {
        messageIds = addUserMessage(normalizedUserMessage);
        await afterUserMessageAdded?.call(messageIds);
      }
      final recordingName = _text(locale, '手动录制', 'Manual recording');
      showToast(
        _text(
          locale,
          '手动录制已就绪，请在悬浮控件中开始，完成后点击「完成」。',
          'Manual recording is ready. Start from the floating control and tap Finish when done.',
        ),
      );
      final hideNativeSurface =
          beforeNativeRecording ?? ScreenDialogService.hideForExternalActivity;
      final restoreNativeSurface =
          afterNativeRecording ??
          ScreenDialogService.restoreAfterExternalActivity;
      shouldRestoreNativeSurface = true;
      beforeNativeRecording = hideNativeSurface;
      afterNativeRecording = restoreNativeSurface;
      await beforeNativeRecording();
      final result = startNativeRecording != null
          ? await startNativeRecording(
              name: recordingName,
              description: normalizedUserMessage.isEmpty
                  ? recordingName
                  : normalizedUserMessage,
              enableDebugScreenshots: recordDebugScreenshots,
            )
          : await OmniFlowToolClient.startHumanTrajectoryLearning(
              name: recordingName,
              description: normalizedUserMessage.isEmpty
                  ? recordingName
                  : normalizedUserMessage,
              enableDebugScreenshots: recordDebugScreenshots,
            );
      await restoreNativeSurfaceIfNeeded();
      if (!isMounted()) return true;
      final resultMessageId = messageIds?.aiMessageId;
      if (resultMessageId != null) {
        insertResultMessage?.call(resultMessageId, result);
      }
      _showCompletionToast(locale, result);
    } catch (error) {
      await restoreNativeSurfaceIfNeeded();
      if (!isMounted()) return true;
      final failedMessageId = messageIds?.aiMessageId;
      if (failedMessageId != null) {
        insertResultMessage?.call(failedMessageId, {
          'success': false,
          'error_message': error.toString(),
        });
      }
      showToast(error.toString(), type: ToastType.error);
    } finally {
      if (isMounted()) {
        await onFinally?.call();
      }
    }
    return true;
  }

  static Future<bool> startStandalone({
    required BuildContext context,
    required FocusNode inputFocusNode,
    required String userMessageText,
    required bool recordDebugScreenshots,
    required bool Function() isMounted,
  }) {
    return start(
      context: context,
      inputFocusNode: inputFocusNode,
      userMessageText: userMessageText,
      recordDebugScreenshots: recordDebugScreenshots,
      isMounted: isMounted,
      addUserMessage: (_) => const ManualRecordingFlowMessageIds(
        userMessageId: '',
        aiMessageId: '',
      ),
    );
  }

  static void _showCompletionToast(Locale locale, Map<String, dynamic> result) {
    final recordingSuccess = _recordingSucceeded(result);
    final conversionSuccess = hasOmniFlowRegisteredFunction(result);
    showToast(
      recordingSuccess
          ? (conversionSuccess
                ? _text(
                    locale,
                    '手动录制完成，复用指令已保存',
                    'Recording complete. Reusable command saved.',
                  )
                : _text(
                    locale,
                    '手动录制完成，RunLog 已保存；复用指令生成失败',
                    'Recording complete and RunLog saved, but reusable command creation failed.',
                  ))
          : _text(locale, '手动录制失败', 'Manual recording failed'),
      type: recordingSuccess ? ToastType.success : ToastType.error,
    );
  }

  static bool _recordingSucceeded(Map<String, dynamic> result) {
    return result['success'] == true;
  }

  static Future<void> openRunLogList() async {
    GoRouterManager.push('/task/omniflow?tab=run_logs');
  }

  static Future<void> openPreviousRunLog(
    BuildContext context, {
    required bool Function() isMounted,
    bool isBusy = false,
  }) async {
    if (isBusy) return;
    final locale = Localizations.localeOf(context);
    try {
      final snapshot = await OmniFlowToolClient.listRunLogs(limit: 1);
      if (!isMounted()) return;
      Map<String, dynamic>? latest;
      final rawRuns = snapshot['runs'];
      if (rawRuns is List) {
        for (final rawRun in rawRuns) {
          if (rawRun is! Map) continue;
          final run = rawRun.map(
            (key, value) => MapEntry(key.toString(), value),
          );
          if ((run['run_id'] ?? '').toString().trim().isNotEmpty) {
            latest = run;
            break;
          }
        }
      }
      if (latest == null) {
        showToast(
          _text(locale, '暂无可查看的 RunLog', 'No RunLog available'),
          type: ToastType.warning,
        );
        return;
      }
      final runId = (latest['run_id'] ?? '').toString().trim();
      if (runId.isNotEmpty) {
        GoRouterManager.push('/task/run_log/$runId');
      }
    } catch (error) {
      if (!isMounted()) return;
      showToast(error.toString(), type: ToastType.error);
    }
  }

  static String _text(Locale locale, String zh, String en) =>
      locale.languageCode == 'en' ? en : zh;
}
