import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:ui/theme/app_theme_mode.dart';

/// 应用状态服务 - 处理与Android应用状态相关的通信
class AppStateService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/app_state',
  );

  static Future<Map<dynamic, dynamic>> getMiscPreferences() async {
    final snapshot = await _channel.invokeMapMethod<dynamic, dynamic>(
      'getMiscPreferences',
    );
    if (snapshot == null) throw StateError('Missing miscellaneous preferences');
    return snapshot;
  }

  static Future<Map<dynamic, dynamic>> updateMiscPreferences(
    String operation,
    Object value,
  ) async {
    final snapshot = await _channel.invokeMapMethod<dynamic, dynamic>(
      'updateMiscPreferences',
      {'operation': operation, 'value': value},
    );
    if (snapshot == null) throw StateError('Missing miscellaneous preferences');
    return snapshot;
  }

  static Future<Map<dynamic, dynamic>> getUiPreferences() async {
    final snapshot = await _channel.invokeMapMethod<dynamic, dynamic>(
      'getUiPreferences',
    );
    if (snapshot == null) throw StateError('Missing preferences snapshot');
    return snapshot;
  }

  /// Android owns writes to the shared theme, language and home settings keys.
  static Future<Map<dynamic, dynamic>> updateUiPreferences(
    String operation, [
    Map<String, dynamic> values = const {},
  ]) async {
    final snapshot = await _channel.invokeMapMethod<dynamic, dynamic>(
      'updateUiPreferences',
      {'operation': operation, ...values},
    );
    if (snapshot == null) throw StateError('Missing preferences snapshot');
    return snapshot;
  }

  static Future<Map<dynamic, dynamic>> getBackgroundConfig() async {
    final config = await _channel.invokeMapMethod<dynamic, dynamic>(
      'getBackgroundConfig',
    );
    if (config == null) throw StateError('Missing background config');
    return config;
  }

  static Future<Map<dynamic, dynamic>> saveBackgroundConfig(
    Map<String, dynamic> config,
  ) async {
    final saved = await _channel.invokeMapMethod<dynamic, dynamic>(
      'saveBackgroundConfig',
      {'config': config},
    );
    if (saved == null) throw StateError('Missing saved background config');
    return saved;
  }

  static Future<String> importBackgroundImage(String sourcePath) async {
    final path = await _channel.invokeMethod<String>('importBackgroundImage', {
      'sourcePath': sourcePath,
    });
    if (path == null || path.isEmpty) {
      throw StateError('Missing imported background image');
    }
    return path;
  }

  static Future<bool> deleteManagedBackgroundImage(String path) async {
    final deleted = await _channel.invokeMethod<bool>(
      'deleteManagedBackgroundImage',
      {'path': path},
    );
    return deleted == true;
  }

  static Future<Map<dynamic, dynamic>> resetBackgroundConfig() async {
    final config = await _channel.invokeMapMethod<dynamic, dynamic>(
      'resetBackgroundConfig',
    );
    if (config == null) throw StateError('Missing reset background config');
    return config;
  }

  static Future<Map<dynamic, dynamic>?> getPendingShareDraft() async {
    try {
      return await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'getPendingShareDraft',
      );
    } catch (e) {
      debugPrint('⚠️ Failed to consume pending share draft: $e');
      return null;
    }
  }

  static Future<bool> clearPendingShareDraft() async {
    try {
      final result = await _channel.invokeMethod<dynamic>(
        'clearPendingShareDraft',
      );
      return result == true;
    } catch (e) {
      debugPrint('⚠️ Failed to clear pending share draft: $e');
      return false;
    }
  }

  static Future<String> getSharedOpenMode() async {
    try {
      final result = await _channel.invokeMethod<String>('getSharedOpenMode');
      return _normalizeSharedOpenMode(result);
    } catch (e) {
      debugPrint('⚠️ Failed to get shared open mode: $e');
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
    } catch (e) {
      debugPrint('⚠️ Failed to get shared open modes: $e');
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
    } catch (e) {
      debugPrint('⚠️ Failed to set shared open mode: $e');
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
    } catch (e) {
      debugPrint('⚠️ Failed to apply language preference on native side: $e');
      return false;
    }
  }

  static Future<bool> applyThemeMode(AppThemeMode mode) async {
    try {
      final result = await _channel.invokeMethod<dynamic>('applyThemeMode', {
        'mode': mode.storageValue,
      });
      return result == true;
    } catch (e) {
      debugPrint('⚠️ Failed to apply theme mode on native side: $e');
      return false;
    }
  }
}
