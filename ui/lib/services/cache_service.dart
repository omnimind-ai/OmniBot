import 'dart:convert';

import '../utils/cache_util.dart';

/// MMKV 缓存统一管理类
/// OSS 版本统一使用全局缓存，不再区分登录用户作用域。
class CacheService {
  // ==================== String 类型 ====================

  /// 缓存 String 值到MMKV
  /// [key] 缓存的key
  /// [value] 要缓存的值
  static Future<void> setString(String key, String value) async {
    await CacheUtil.cacheString(key, value);
  }

  /// 从MMKV读取 String 值
  /// [key] 缓存的key
  /// [defaultValue] 默认值，当key不存在时返回
  static Future<String> getString(
    String key, {
    String defaultValue = "",
  }) async {
    return await CacheUtil.getString(key, defaultValue: defaultValue);
  }

  // ==================== bool 类型 ====================

  /// 缓存 bool 值到MMKV
  static Future<void> setBool(String key, bool value) async {
    await CacheUtil.cacheBool(key, value);
  }

  /// 从MMKV读取 bool 值
  static Future<bool> getBool(String key, {bool defaultValue = false}) async {
    return await CacheUtil.getBool(key, defaultValue: defaultValue);
  }

  // ==================== int 类型 ====================

  /// 缓存 int 值到MMKV
  static Future<void> setInt(String key, int value) async {
    await CacheUtil.cacheInt(key, value);
  }

  /// 从MMKV读取 int 值
  static Future<int> getInt(String key, {int defaultValue = 0}) async {
    return await CacheUtil.getInt(key, defaultValue: defaultValue);
  }

  // ==================== double 类型 ====================

  /// 缓存 double 值到MMKV
  static Future<void> setDouble(String key, double value) async {
    await CacheUtil.cacheDouble(key, value);
  }

  /// 从MMKV读取 double 值
  static Future<double> getDouble(
    String key, {
    double defaultValue = 0.0,
  }) async {
    final result = await CacheUtil.getDouble(key, defaultValue: defaultValue);
    // CacheUtil.getDouble 返回 String，需要转换
    return double.tryParse(result) ?? defaultValue;
  }

  // ==================== `List<String>` 类型 ====================

  /// 缓存 `List<String>` 值到MMKV（使用JSON格式存储）
  static Future<void> setStringList(String key, List<String> value) async {
    final jsonString = jsonEncode(value);
    await CacheUtil.cacheString(key, jsonString);
  }

  /// 从MMKV读取 `List<String>` 值
  static Future<List<String>> getStringList(
    String key, {
    List<String> defaultValue = const [],
  }) async {
    final result = await CacheUtil.getString(key, defaultValue: "");
    if (result.isEmpty) {
      return defaultValue;
    }
    try {
      final decoded = jsonDecode(result);
      if (decoded is List) {
        return List<String>.from(decoded);
      }
      return defaultValue;
    } catch (e) {
      return defaultValue;
    }
  }

  // ==================== JSON 对象 ====================

  /// 缓存 JSON 对象到MMKV（自动序列化为字符串）
  /// [key] 缓存的key
  /// [value] 要缓存的对象，需要实现 toJson() 方法或是 Map/List
  static Future<void> setJson(String key, dynamic value) async {
    try {
      String jsonString;
      if (value is Map || value is List) {
        jsonString = jsonEncode(value);
      } else if (value is String) {
        jsonString = value;
      } else {
        jsonString = jsonEncode(value);
      }
      await setString(key, jsonString);
    } catch (e) {
    }
  }

  /// 从MMKV读取 JSON 对象（自动反序列化）
  /// [key] 缓存的key
  /// [fromJson] 可选的反序列化函数，用于将 Map 转换为具体对象
  ///
  /// 使用示例：
  /// ```dart
  /// // 读取数据为 Map
  /// final map = CacheService.getJson('user_data');
  ///
  /// // 读取数据为具体对象
  /// final settings = CacheService.getJson(
  ///   'settings',
  ///   fromJson: (json) => Settings.fromJson(json),
  /// );
  /// ```
  static Future<T?> getJson<T>(
    String key, {
    T Function(Map<String, dynamic>)? fromJson,
  }) async {
    try {
      final jsonString = await getString(key);
      if (jsonString.isEmpty) {
        return null;
      }

      final decoded = jsonDecode(jsonString);

      if (fromJson != null && decoded is Map<String, dynamic>) {
        return fromJson(decoded);
      }

      return decoded as T?;
    } catch (e) {
      return null;
    }
  }

  // ==================== 便捷方法 ====================

  /// 递增整数值
  /// [key] 缓存的key
  /// [increment] 递增的值，默认为1
  /// 返回递增后的值
  static Future<int> incrementInt(String key, {int increment = 1}) async {
    final currentValue = await getInt(key, defaultValue: 0);
    final newValue = currentValue + increment;
    await setInt(key, newValue);
    return newValue;
  }

  /// 切换布尔值
  /// [key] 缓存的key
  /// 返回切换后的值
  static Future<bool> toggleBool(String key) async {
    final currentValue = await getBool(key, defaultValue: false);
    final newValue = !currentValue;
    await setBool(key, newValue);
    return newValue;
  }

  /// 添加到字符串列表（如果不存在）
  /// [key] 缓存的key
  /// [value] 要添加的值
  static Future<void> addToStringList(String key, String value) async {
    final list = await getStringList(key, defaultValue: []);
    if (!list.contains(value)) {
      list.add(value);
      await setStringList(key, list);
    }
  }

  /// 从字符串列表中移除
  /// [key] 缓存的key
  /// [value] 要移除的值
  static Future<void> removeFromStringList(String key, String value) async {
    final list = await getStringList(key, defaultValue: []);
    if (list.contains(value)) {
      list.remove(value);
      await setStringList(key, list);
    }
  }
}
