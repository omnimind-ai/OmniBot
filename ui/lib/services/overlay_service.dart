import 'package:flutter/services.dart';

/// Overlay服务，用于与原生OverlayChannel通信
/// !!暂不使用!!
class OverlayService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/overlay',
  );

  /// 显示消息提示（在MessageView中显示）
  /// [message] 要显示的消息内容
  static Future<bool> showMessage(String message) async {
    try {
      final result = await _channel.invokeMethod('showMessage', {
        'message': message,
      });
      return result == true;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> setPetOverlayImagePath(
    String path, {
    String selectedId = '',
  }) async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'setPetOverlayImagePath',
        {'path': path, 'selectedId': selectedId},
      );
      return result == true;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> showPetOverlay() async {
    try {
      final result = await _channel.invokeMethod<bool>('showPetOverlay');
      return result == true;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> playPetAction(String action, {bool loop = true}) async {
    try {
      final result = await _channel.invokeMethod<bool>('playPetAction', {
        'action': action,
        'loop': loop,
      });
      return result == true;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> hidePetOverlay() async {
    try {
      final result = await _channel.invokeMethod<bool>('hidePetOverlay');
      return result == true;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> isPetOverlayShowing() async {
    try {
      final result = await _channel.invokeMethod<bool>('isPetOverlayShowing');
      return result == true;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<Map<String, dynamic>> getPetOverlayState() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'getPetOverlayState',
      );
      return Map<String, dynamic>.from(result ?? const {});
    } on PlatformException catch (_) {
      return const {};
    }
  }
}
