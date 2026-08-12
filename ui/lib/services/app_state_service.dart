import 'package:flutter/services.dart';
import 'package:ui/theme/app_theme_mode.dart';

/// 应用状态服务 - 处理与Android应用状态相关的通信
class AppStateService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/app_state',
  );

  static Future<bool> exitApp() async {
    try {
      final result = await _channel.invokeMethod('exitApp');
      return result == true;
    } catch (_) {
      return false;
    }
  }

  static Future<Map<dynamic, dynamic>?> getPendingShareDraft() async {
    try {
      return await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'getPendingShareDraft',
      );
    } catch (_) {
      return null;
    }
  }

  static Future<bool> clearPendingShareDraft() async {
    try {
      final result = await _channel.invokeMethod<dynamic>(
        'clearPendingShareDraft',
      );
      return result == true;
    } catch (_) {
      return false;
    }
  }

  static Future<String> getSharedOpenMode() async {
    try {
      final result = await _channel.invokeMethod<String>('getSharedOpenMode');
      return _normalizeSharedOpenMode(result);
    } catch (_) {
      return 'default';
    }
  }

  static Future<Map<String, String>> getSharedOpenModes() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'getSharedOpenModes',
      );
      return {
        'imageMode': _normalizeSharedOpenMode(result?['imageMode']?.toString()),
        'fileMode': _normalizeSharedOpenMode(result?['fileMode']?.toString()),
      };
    } catch (_) {
      return const {'imageMode': 'default', 'fileMode': 'default'};
    }
  }

  static Future<String> setSharedOpenMode(String mode, {String? target}) async {
    try {
      final result = await _channel.invokeMethod<String>('setSharedOpenMode', {
        'mode': mode,
        if (target != null) 'target': target,
      });
      return _normalizeSharedOpenMode(result);
    } catch (_) {
      return _normalizeSharedOpenMode(mode);
    }
  }

  static String _normalizeSharedOpenMode(String? mode) {
    return switch (mode?.trim()) {
      'workspace' => 'workspace',
      _ => 'default',
    };
  }

  static Future<bool> applyLanguagePreference() async {
    try {
      final result = await _channel.invokeMethod<dynamic>(
        'applyLanguagePreference',
      );
      return result == true;
    } catch (_) {
      return false;
    }
  }

  static Future<bool> applyThemeMode(AppThemeMode mode) async {
    try {
      final result = await _channel.invokeMethod<dynamic>('applyThemeMode', {
        'mode': mode.storageValue,
      });
      return result == true;
    } catch (_) {
      return false;
    }
  }
}
