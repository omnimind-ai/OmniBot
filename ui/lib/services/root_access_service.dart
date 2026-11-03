import 'package:ui/services/special_permission.dart';

class RootAccessSnapshot {
  const RootAccessSnapshot({this.enabled = false, this.status = 'DISABLED'});

  final bool enabled;
  final String status;
  bool get granted => enabled && status == 'GRANTED';
  bool get checking => enabled && status == 'CHECKING';

  factory RootAccessSnapshot.fromMap(Map<dynamic, dynamic>? map) {
    final enabled = map?['enabled'] == true;
    return RootAccessSnapshot(
      enabled: enabled,
      status: enabled
          ? (map?['status'] as String? ?? 'UNAVAILABLE')
          : 'DISABLED',
    );
  }

  String label(bool english) => switch (status) {
    'NOT_GRANTED' => english ? 'Not authorized' : '尚未授权',
    'CHECKING' => english ? 'Checking access…' : '正在检查授权…',
    'GRANTED' => english ? 'Authorized · Root preferred' : '已授权 · 优先使用 Root',
    'DENIED' => english ? 'Access denied' : '授权被拒绝',
    'TIMED_OUT' => english ? 'Authorization timed out' : '授权超时',
    'DISABLED' => english ? 'Disabled' : '未启用',
    _ => english ? 'Root unavailable' : 'Root 不可用',
  };
}

abstract final class RootAccessService {
  static Future<RootAccessSnapshot> status() async =>
      RootAccessSnapshot.fromMap(
        await spePermission.invokeMapMethod('getRootStatus'),
      );

  static Future<RootAccessSnapshot> setEnabled(bool enabled) async =>
      RootAccessSnapshot.fromMap(
        await spePermission.invokeMapMethod('setRootEnabled', {
          'enabled': enabled,
        }),
      );

  static Future<RootAccessSnapshot> requestAccess() async =>
      RootAccessSnapshot.fromMap(
        await spePermission.invokeMapMethod('requestRootAccess'),
      );
}
