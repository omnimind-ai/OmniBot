import 'package:flutter/services.dart';
import 'package:ui/models/omni_plugin_item.dart';

class OmniVlmReadiness {
  const OmniVlmReadiness({
    this.debugBuild = false,
    this.providerConfigured = false,
    this.providerName = '',
    this.model = '',
  });

  final bool debugBuild;
  final bool providerConfigured;
  final String providerName;
  final String model;

  factory OmniVlmReadiness.fromMap(Map<dynamic, dynamic>? raw) {
    return OmniVlmReadiness(
      debugBuild: raw?['debugBuild'] == true,
      providerConfigured: raw?['providerConfigured'] == true,
      providerName: (raw?['providerName'] ?? '').toString(),
      model: (raw?['model'] ?? '').toString(),
    );
  }
}

class OmniPluginActionItem {
  const OmniPluginActionItem({
    required this.id,
    required this.pluginId,
    required this.displayName,
    required this.description,
    required this.presentation,
  });

  final String id;
  final String pluginId;
  final String displayName;
  final String description;
  final Map<String, dynamic> presentation;

  factory OmniPluginActionItem.fromMap(Map<dynamic, dynamic> raw) {
    return OmniPluginActionItem(
      id: (raw['id'] ?? '').toString(),
      pluginId: (raw['pluginId'] ?? '').toString(),
      displayName: (raw['displayName'] ?? '').toString(),
      description: (raw['description'] ?? '').toString(),
      presentation: Map<String, dynamic>.from(
        (raw['presentation'] as Map?) ?? const <String, dynamic>{},
      ),
    );
  }

  bool supportsPlacement(String placement) {
    final normalized = placement.trim();
    if (normalized.isEmpty) return false;
    if (presentation['placement']?.toString().trim() == normalized) {
      return true;
    }
    final placements = presentation['placements'];
    return placements is Iterable &&
        placements.any((value) => value?.toString().trim() == normalized);
  }

  String localizedPresentationValue(
    String key, {
    required bool english,
    required String fallback,
  }) {
    final value = presentation[key];
    if (value is Map) {
      final localized = value[english ? 'en' : 'zh']?.toString().trim() ?? '';
      if (localized.isNotEmpty) return localized;
      final englishValue = value['en']?.toString().trim() ?? '';
      if (englishValue.isNotEmpty) return englishValue;
      final chineseValue = value['zh']?.toString().trim() ?? '';
      if (chineseValue.isNotEmpty) return chineseValue;
    }
    final text = value?.toString().trim() ?? '';
    return text.isEmpty ? fallback : text;
  }

  int get quickLaunchOrder =>
      int.tryParse(presentation['quickLaunchOrder']?.toString() ?? '') ?? 0;
}

class OmniPluginService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/PluginPlatform',
  );

  static Future<List<OmniPluginItem>> listPlugins() async {
    final raw = await _channel.invokeListMethod<dynamic>('list');
    return raw
            ?.whereType<Map>()
            .map(OmniPluginItem.fromMap)
            .toList(growable: false) ??
        const <OmniPluginItem>[];
  }

  static Future<OmniPluginItem?> getPlugin(String pluginId) async {
    final plugins = await listPlugins();
    for (final plugin in plugins) {
      if (plugin.id == pluginId) return plugin;
    }
    return null;
  }

  static Future<OmniPluginItem> install(String pluginId) async {
    return _invokeState('install', <String, Object?>{'pluginId': pluginId});
  }

  static Future<OmniPluginItem> update(String pluginId) async {
    return _invokeState('update', <String, Object?>{'pluginId': pluginId});
  }

  static Future<OmniPluginItem> setEnabled(
    String pluginId,
    bool enabled,
  ) async {
    return _invokeState('setEnabled', <String, Object?>{
      'pluginId': pluginId,
      'enabled': enabled,
    });
  }

  static Future<List<OmniPluginActionItem>> listActions() async {
    final raw = await _channel.invokeListMethod<dynamic>('listActions');
    return raw
            ?.whereType<Map>()
            .map(OmniPluginActionItem.fromMap)
            .where(
              (action) => action.id.isNotEmpty && action.pluginId.isNotEmpty,
            )
            .toList(growable: false) ??
        const <OmniPluginActionItem>[];
  }

  static Future<Map<String, dynamic>> invokeAction(
    String pluginId,
    String actionId, [
    Map<String, dynamic> arguments = const <String, dynamic>{},
  ]) async {
    final raw = await _channel.invokeMapMethod<dynamic, dynamic>(
      'invokeAction',
      <String, Object?>{
        'pluginId': pluginId,
        'actionId': actionId,
        'arguments': arguments,
      },
    );
    return Map<String, dynamic>.from(raw ?? const <dynamic, dynamic>{});
  }

  static Future<OmniVlmReadiness> getVlmReadiness() async {
    final raw = await _channel.invokeMapMethod<dynamic, dynamic>(
      'getVlmReadiness',
    );
    return OmniVlmReadiness.fromMap(raw);
  }

  static Future<Map<String, dynamic>> invokeSandbox(
    String pluginId,
    String method,
    Map<String, dynamic> params,
  ) async {
    final raw = await _channel.invokeMapMethod<dynamic, dynamic>(
      'sandboxInvoke',
      <String, Object?>{
        'pluginId': pluginId,
        'method': method,
        'params': params,
      },
    );
    return Map<String, dynamic>.from(raw ?? const <dynamic, dynamic>{});
  }

  static Future<void> uninstall(String pluginId) async {
    await _channel.invokeMethod<bool>('uninstall', <String, Object?>{
      'pluginId': pluginId,
    });
  }

  static Future<OmniPluginItem> _invokeState(
    String method,
    Map<String, Object?> arguments,
  ) async {
    final raw = await _channel.invokeMapMethod<dynamic, dynamic>(
      method,
      arguments,
    );
    if (raw == null) {
      throw StateError('Plugin platform returned no state for $method');
    }
    return OmniPluginItem.fromMap(raw);
  }
}
