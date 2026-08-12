import 'package:flutter/services.dart';
import 'package:ui/services/storage_service.dart';

/// 后台隐藏服务
/// 用于调用原生Android代码来设置应用是否从最近任务中隐藏
class HideFromRecentsService {
  static const MethodChannel _channel = MethodChannel('hide_from_recents');

  /// 设置应用是否从最近任务中隐藏
  static Future<bool> setExcludeFromRecents(bool exclude) async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'setExcludeFromRecents',
        {'exclude': exclude},
      );

      if (result == true) {
        // 成功后同时保存到全局设置
        await StorageService.setBool('hide_from_recents', exclude);
      }

      return result ?? false;
    } catch (e) {
      return false;
    }
  }
}
