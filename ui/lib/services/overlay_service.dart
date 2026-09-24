import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Flutter adapter for the existing native overlay and pet appearance owner.
class OverlayService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/overlay',
  );

  /// The native repository owns discovery and persisted selection for both UIs.
  static Future<Map<String, dynamic>> getPetAppearanceState() async {
    final snapshot = await _channel.invokeMapMethod<dynamic, dynamic>(
      'listPetAppearances',
    );
    if (snapshot == null) throw StateError('Missing pet appearance state');
    return snapshot.map((key, value) => MapEntry(key.toString(), value));
  }

  static Future<Map<String, dynamic>> selectPetAppearance(String id) async {
    final snapshot = await _channel.invokeMapMethod<dynamic, dynamic>(
      'selectPetAppearance',
      {'id': id},
    );
    if (snapshot == null) throw StateError('Missing selected pet state');
    return snapshot.map((key, value) => MapEntry(key.toString(), value));
  }

  static Future<Map<String, dynamic>> importPetPackage(String path) async {
    final snapshot = await _channel.invokeMapMethod<dynamic, dynamic>(
      'importPetPackage',
      {'path': path},
    );
    if (snapshot == null) throw StateError('Missing installed pet state');
    return snapshot.map((key, value) => MapEntry(key.toString(), value));
  }

  /// 显示消息提示（在MessageView中显示）
  /// [message] 要显示的消息内容
  static Future<bool> showMessage(String message) async {
    try {
      final result = await _channel.invokeMethod('showMessage', {
        'message': message,
      });
      return result == true;
    } on PlatformException catch (e) {
      if (kDebugMode) {
        print('显示消息失败: ${e.message}');
      }
      return false;
    }
  }

  static Future<bool> showPetOverlay() async {
    try {
      final result = await _channel.invokeMethod<bool>('showPetOverlay');
      return result == true;
    } on PlatformException catch (e) {
      if (kDebugMode) {
        print('Failed to show pet overlay: ${e.message}');
      }
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
    } on PlatformException catch (e) {
      if (kDebugMode) {
        print('Failed to play pet action: ${e.message}');
      }
      return false;
    }
  }

  static Future<bool> hidePetOverlay() async {
    try {
      final result = await _channel.invokeMethod<bool>('hidePetOverlay');
      return result == true;
    } on PlatformException catch (e) {
      if (kDebugMode) {
        print('Failed to hide pet overlay: ${e.message}');
      }
      return false;
    }
  }

  static Future<bool> isPetOverlayShowing() async {
    try {
      final result = await _channel.invokeMethod<bool>('isPetOverlayShowing');
      return result == true;
    } on PlatformException catch (e) {
      if (kDebugMode) {
        print('Failed to query pet overlay state: ${e.message}');
      }
      return false;
    }
  }
}
