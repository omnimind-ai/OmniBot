import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// The CES edition is selected by the Android flavor's Flutter build, never
/// by an audience-editable preference.
class JackeryCesConfig {
  JackeryCesConfig._();

  static const enabled = bool.fromEnvironment('JACKERY_CES');
  static const productName = 'Jackery Power AI';
  static const modelDisplayName = 'Jackery Power AI · On-device';
  static const slogan = 'Local AI. Powered anywhere.';
  static const accent = Color(0xFFFF6727);
  static const background = Color(0xFFF8F7F5);
  static const surface = Color(0xFFFFFFFF);
  static const border = Color(0xFFE8E5E1);
  static const text = Color(0xFF303030);

  static String modelApiBase = '';
  static String modelId = '';
  static bool get modelConfigured =>
      modelApiBase.isNotEmpty && modelId.isNotEmpty;

  static Future<void> initialize() async {
    if (!enabled) return;
    final config = await const MethodChannel('cn.com.omnimind.bot/jackery_ces')
        .invokeMapMethod<String, dynamic>('getConfig');
    if (config?['enabled'] != true) {
      throw StateError('The Flutter and Android CES editions do not match.');
    }
    modelApiBase = (config?['modelApiBase'] as String?) ?? '';
    modelId = (config?['modelId'] as String?) ?? '';
  }

  static bool allowsRoute(String route) => const {
    '/',
    '/home/home',
    '/home/chat',
    '/home/settings',
    '/my/my_page',
    '/home/model_provider_setting',
    '/home/chat_history',
  }.contains(route);
}
