import 'package:flutter/services.dart';

/// 设备信息服务
class DeviceService {
  static const MethodChannel _methodChannel = MethodChannel('device_info');

  /// 获取Android设备ID
  static Future<String?> getAndroidId() async {
    try {
      final String? androidId = await _methodChannel.invokeMethod('getAndroidId');
      return androidId;
    } on PlatformException catch (_) {
      return null;
    } catch (_) {
      return null;
    }
  }

  /// 获取设备信息
  static Future<Map<String, dynamic>?> getDeviceInfo() async {
    try {
      final result = await _methodChannel.invokeMethod('getDeviceInfo');
      return _mapFromResult(result);
    } on PlatformException catch (_) {
      return null;
    } catch (_) {
      return null;
    }
  }

  /// 获取设备IP地址
  static Future<String?> getIpAddress() async {
    try {
      final String? ipAddress = await _methodChannel.invokeMethod('getIpAddress');
      return ipAddress;
    } on PlatformException catch (_) {
      return null;
    } catch (_) {
      return null;
    }
  }

  /// 获取应用版本信息（版本号、平台类型）
  static Future<Map<String, dynamic>?> getAppVersion() async {
    try {
      final result = await _methodChannel.invokeMethod('getAppVersion');
      if (result == null) {
        return null;
      }
      return Map<String, dynamic>.from(result);
    } on PlatformException catch (_) {
      return null;
    } catch (_) {
      return null;
    }
  }

  /// 将从平台调用返回的动态结果规范化为 Map<String, dynamic>
  static Map<String, dynamic>? _mapFromResult(dynamic result) {
    if (result == null) return null;
    if (result is Map) {
      try {
        return result.map((key, value) => MapEntry(key?.toString() ?? '', value));
      } catch (_) {
        return null;
      }
    }
    return null;
  }
}
