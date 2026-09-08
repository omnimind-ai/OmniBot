import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/services/omni_plugin_service.dart';
import 'package:ui/utils/ui.dart';

/// Presents the user-facing result of an Agent Web plugin action.
///
/// Both the Agent settings page and global quick launch surfaces use this
/// presenter so runtime outcomes never drift into page-specific behavior.
class AgentWebActionPresenter {
  const AgentWebActionPresenter._();

  static Future<void> invoke(
    OmniPluginActionItem action, {
    required bool english,
  }) async {
    final label = action.localizedPresentationValue(
      'label',
      english: english,
      fallback: action.displayName,
    );
    String text(String zh, String en) => english ? en : zh;

    try {
      final response = await OmniPluginService.invokeAction(
        action.pluginId,
        action.id,
      );
      final code = response['code']?.toString().trim() ?? '';
      switch (code) {
        case 'OPENED':
          showToast(text('$label 已打开', '$label opened'));
          return;
        case 'RUNTIME_MISSING':
          final responsePackageId =
              response['packageId']?.toString().trim() ?? '';
          final packageId = responsePackageId.isNotEmpty
              ? responsePackageId
              : action.presentation['packageId']?.toString().trim() ?? '';
          GoRouterManager.push(
            packageId.isEmpty
                ? '/home/termux_setting'
                : '/home/termux_setting?focus=${Uri.encodeComponent(packageId)}',
          );
          return;
        case 'PROVIDER_REQUIRED':
          showToast(
            text(
              '请先配置统一 Dispatch Provider',
              'Configure the shared Dispatch Provider first',
            ),
            type: ToastType.warning,
          );
          GoRouterManager.push('/home/model_provider_setting');
          return;
        case 'MODEL_REQUIRED':
          showToast(
            text('请先为 Dispatch 选择模型', 'Select a model for Dispatch first'),
            type: ToastType.warning,
          );
          GoRouterManager.push('/home/model_provider_setting');
          return;
        case 'UNSUPPORTED_PROVIDER':
          showToast(
            text(
              '$label 暂不支持当前 Provider 协议',
              '$label does not support the current Provider protocol',
            ),
            type: ToastType.warning,
          );
          return;
        case 'URL_TIMEOUT':
          showToast(
            text('$label 启动超时，后台进程已停止', '$label timed out and was stopped'),
            type: ToastType.error,
          );
          return;
        case 'STOP_FAILED':
          showToast(
            text(
              '$label 的旧进程无法停止，请在终端设置中处理后重试',
              'The previous $label process could not be stopped; check Terminal settings and retry',
            ),
            type: ToastType.error,
          );
          return;
        case 'BROWSER_UNAVAILABLE':
          showToast(
            text('没有可用的系统浏览器', 'No system browser is available'),
            type: ToastType.error,
          );
          return;
        default:
          showToast(
            text('$label 启动失败', 'Failed to start $label'),
            type: ToastType.error,
          );
      }
    } catch (error) {
      showToast(
        text('启动 Web 界面失败：$error', 'Failed to open Web UI: $error'),
        type: ToastType.error,
      );
    }
  }
}
