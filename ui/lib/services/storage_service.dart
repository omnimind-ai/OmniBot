import 'dart:convert';
import 'dart:ui';

import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/l10n/app_language_mode.dart';
import 'package:ui/models/chat_startup_behavior.dart';
import 'package:ui/models/habitual_hand.dart';
import 'package:ui/theme/app_theme_mode.dart';

/// SharedPreferences 统一管理类。
/// OSS 版本统一使用全局存储，不再区分登录用户作用域。
class StorageService {
  static SharedPreferences? _prefs;

  /// 初始化 SharedPreferences
  static Future<void> init() async {
    _prefs = await SharedPreferences.getInstance();
  }

  static SharedPreferences get _instance {
    if (_prefs == null) {
      throw Exception('StorageService未初始化，请先调用 StorageService.init()');
    }
    return _prefs!;
  }

  static Future<bool> setString(String key, String value) async {
    return _instance.setString(key, value);
  }

  static String? getString(String key, {String? defaultValue}) {
    return _instance.getString(key) ?? defaultValue;
  }

  static Future<bool> setInt(String key, int value) async {
    return _instance.setInt(key, value);
  }

  static int? getInt(String key, {int? defaultValue}) {
    return _instance.getInt(key) ?? defaultValue;
  }

  static Future<bool> setBool(String key, bool value) async {
    return _instance.setBool(key, value);
  }

  static bool? getBool(String key, {bool? defaultValue}) {
    return _instance.getBool(key) ?? defaultValue;
  }

  static Future<bool> setDouble(String key, double value) async {
    return _instance.setDouble(key, value);
  }

  static double? getDouble(String key, {double? defaultValue}) {
    return _instance.getDouble(key) ?? defaultValue;
  }

  static Future<bool> setStringList(String key, List<String> value) async {
    return _instance.setStringList(key, value);
  }

  static List<String>? getStringList(String key, {List<String>? defaultValue}) {
    return _instance.getStringList(key) ?? defaultValue;
  }

  static Future<bool> setJson(String key, dynamic value) async {
    try {
      final jsonString = value is Map || value is List
          ? jsonEncode(value)
          : jsonEncode(value.toJson());
      return await setString(key, jsonString);
    } catch (e) {
      print('StorageService: setJson 失败 - $e');
      return false;
    }
  }

  static T? getJson<T>(
    String key, {
    T Function(Map<String, dynamic>)? fromJson,
  }) {
    try {
      final jsonString = getString(key);
      if (jsonString == null || jsonString.isEmpty) {
        return null;
      }

      final decoded = jsonDecode(jsonString);
      if (fromJson != null && decoded is Map<String, dynamic>) {
        return fromJson(decoded);
      }
      return decoded as T?;
    } catch (e) {
      print('StorageService: getJson 失败 - $e');
      return null;
    }
  }

  static Future<bool> remove(String key) async {
    return _instance.remove(key);
  }

  static Future<bool> clear() async {
    return _instance.clear();
  }

  static Future<void> removeMultiple(List<String> keys) async {
    for (final key in keys) {
      await remove(key);
    }
  }

  static bool containsKey(String key) {
    return _instance.containsKey(key);
  }

  static Set<String> getAllKeys() {
    return _instance.getKeys();
  }

  static Future<int> incrementInt(String key, {int increment = 1}) async {
    final currentValue = getInt(key, defaultValue: 0) ?? 0;
    final newValue = currentValue + increment;
    await setInt(key, newValue);
    return newValue;
  }

  static Future<bool> toggleBool(String key) async {
    final currentValue = getBool(key, defaultValue: false) ?? false;
    final newValue = !currentValue;
    await setBool(key, newValue);
    return newValue;
  }

  static Future<bool> addToStringList(String key, String value) async {
    final list = getStringList(key, defaultValue: []) ?? [];
    if (!list.contains(value)) {
      list.add(value);
      return setStringList(key, list);
    }
    return true;
  }

  static Future<bool> removeFromStringList(String key, String value) async {
    final list = getStringList(key, defaultValue: []) ?? [];
    if (list.contains(value)) {
      list.remove(value);
      return setStringList(key, list);
    }
    return true;
  }

  static const String kPreventScreenSleepDuringTasksKey =
      'prevent_screen_sleep_during_tasks';
  static const String kTaskCompletionNotificationEnabledKey =
      'task_completion_notification_enabled';
  static const String kPetOverlayImagePathKey = 'pet_overlay_image_path';
  static const String kPetOverlaySelectedIdKey = 'pet_overlay_selected_id';
  static const String kPetOverlayVisibleKey = 'pet_overlay_visible';
  static const String kUseIndependentChatSendButtonKey =
      'use_independent_chat_send_button';
  static const String kChatStartupBehaviorKey = 'chat_startup_behavior';
  static const String kHabitualHandKey = 'habitual_hand';
  static const String kThemeOptionKey = 'theme_option';
  static const String kLanguageOptionKey = 'language_option';
  static const String kMcpLocalServiceEnabledKey = 'mcp_local_service_enabled';
  static const String kWorkspaceMemoryConfiguredKey =
      'workspace_memory_configured';
  static const String kRemoteBridgeEnabledKey = 'remote_bridge_enabled';
  static const String kPredictiveBackEnabledKey = 'predictive_back_enabled';
  static const String kRecentConversationsOnlyEnabledKey =
      'recent_conversations_only_enabled';

  static const String _kManualModelContextThresholdsKey =
      'manual_model_context_thresholds';

  static Future<bool> setManualModelContextThreshold(
    String modelId,
    int threshold,
  ) async {
    final map =
        getJson<Map<String, dynamic>>(_kManualModelContextThresholdsKey) ??
        <String, dynamic>{};
    map[modelId] = threshold;
    return setJson(_kManualModelContextThresholdsKey, map);
  }

  static Future<bool> clearManualModelContextThreshold(String modelId) async {
    final map =
        getJson<Map<String, dynamic>>(_kManualModelContextThresholdsKey) ??
        <String, dynamic>{};
    if (!map.containsKey(modelId)) return true;
    map.remove(modelId);
    return setJson(_kManualModelContextThresholdsKey, map);
  }

  static int? getManualModelContextThreshold(String modelId) {
    final map = getJson<Map<String, dynamic>>(
      _kManualModelContextThresholdsKey,
    );
    if (map == null || !map.containsKey(modelId)) return null;
    final value = map[modelId];
    if (value is int) return value;
    if (value is num) return value.toInt();
    return null;
  }

  static Future<bool> isPreventScreenSleepDuringTasksEnabled() async {
    final enabled = getBool(
      kPreventScreenSleepDuringTasksKey,
      defaultValue: true,
    );
    return enabled ?? true;
  }

  static Future<void> setPreventScreenSleepDuringTasksEnabled(
    bool enabled,
  ) async {
    await setBool(kPreventScreenSleepDuringTasksKey, enabled);
  }

  static Future<bool> isTaskCompletionNotificationEnabled() async {
    final enabled = getBool(
      kTaskCompletionNotificationEnabledKey,
      defaultValue: true,
    );
    return enabled ?? true;
  }

  static Future<void> setTaskCompletionNotificationEnabled(bool enabled) async {
    await setBool(kTaskCompletionNotificationEnabledKey, enabled);
  }

  static String getPetOverlayImagePath() {
    return getString(kPetOverlayImagePathKey, defaultValue: '') ?? '';
  }

  static Future<void> setPetOverlayImagePath(String path) async {
    await setString(kPetOverlayImagePathKey, path);
  }

  static String getPetOverlaySelectedId() {
    return getString(
          kPetOverlaySelectedIdKey,
          defaultValue: 'builtin:xiaowan',
        ) ??
        'builtin:xiaowan';
  }

  static Future<void> setPetOverlaySelectedId(String id) async {
    await setString(kPetOverlaySelectedIdKey, id);
  }

  static bool isPetOverlayVisible() {
    return getBool(kPetOverlayVisibleKey, defaultValue: false) ?? false;
  }

  static Future<void> setPetOverlayVisible(bool visible) async {
    await setBool(kPetOverlayVisibleKey, visible);
  }

  static bool isIndependentChatSendButtonEnabled() {
    return getBool(kUseIndependentChatSendButtonKey, defaultValue: true) ??
        true;
  }

  static Future<bool> setIndependentChatSendButtonEnabled(bool enabled) {
    return setBool(kUseIndependentChatSendButtonKey, enabled);
  }

  static bool isPredictiveBackEnabled() {
    return getBool(kPredictiveBackEnabledKey, defaultValue: true) ?? true;
  }

  static Future<bool> setPredictiveBackEnabled(bool enabled) {
    return setBool(kPredictiveBackEnabledKey, enabled);
  }

  static bool isRecentConversationsOnlyEnabled() {
    return getBool(kRecentConversationsOnlyEnabledKey, defaultValue: false) ??
        false;
  }

  static ChatStartupBehavior getChatStartupBehavior() {
    return ChatStartupBehavior.fromStorageValue(
      getString(
        kChatStartupBehaviorKey,
        defaultValue: ChatStartupBehavior.resumeLast.storageValue,
      ),
    );
  }

  static Future<bool> setChatStartupBehavior(ChatStartupBehavior behavior) {
    return setString(kChatStartupBehaviorKey, behavior.storageValue);
  }

  static HabitualHand getHabitualHand() {
    return habitualHandFromStorageValue(
      getString(
        kHabitualHandKey,
        defaultValue: HabitualHand.right.storageValue,
      ),
    );
  }

  static Future<bool> setHabitualHand(HabitualHand hand) async {
    return setString(kHabitualHandKey, hand.storageValue);
  }

  static AppThemeMode getThemeMode() {
    return appThemeModeFromString(
      getString(
        kThemeOptionKey,
        defaultValue: AppThemeMode.system.storageValue,
      ),
    );
  }

  static Future<void> setThemeMode(AppThemeMode mode) async {
    await setString(kThemeOptionKey, mode.storageValue);
  }

  static AppLanguageMode getLanguageMode() {
    return AppLanguageMode.fromStorageValue(
      getString(
        kLanguageOptionKey,
        defaultValue: AppLanguageMode.system.storageValue,
      ),
    );
  }

  static Future<void> setLanguageMode(AppLanguageMode mode) async {
    await setString(kLanguageOptionKey, mode.storageValue);
  }

  static ResolvedAppLocale getResolvedAppLocale({Locale? systemLocale}) {
    return resolveAppLocale(
      mode: getLanguageMode(),
      systemLocale: systemLocale ?? PlatformDispatcher.instance.locale,
    );
  }

  static Locale getResolvedLocale({Locale? systemLocale}) {
    return getResolvedAppLocale(systemLocale: systemLocale).locale;
  }
}
