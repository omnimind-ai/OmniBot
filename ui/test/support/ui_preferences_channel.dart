import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

const uiPreferencesChannel = MethodChannel('cn.com.omnimind.bot/app_state');

/// Host-boundary fixture for existing theme/language and compatibility tests.
/// It models storage replies only; native validation and side effects need device checks.
void installUiPreferencesChannelFixture() {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(uiPreferencesChannel, (call) async {
        final preferences = await SharedPreferences.getInstance();
        if (call.method == 'updateUiPreferences') {
          final args = Map<String, dynamic>.from(call.arguments as Map);
          final key = switch (args['operation']) {
            'theme' => 'theme_option',
            'language' => 'language_option',
            _ => throw StateError('Unexpected preference mutation in fixture'),
          };
          await preferences.setString(key, args['value'] as String);
        } else if (call.method != 'getUiPreferences') {
          throw MissingPluginException('Unexpected app-state method');
        }
        return {
          'theme': preferences.getString('theme_option') ?? 'system',
          'language': preferences.getString('language_option') ?? 'system',
          'systemLocaleTag': 'en-US',
          'home': jsonDecode(
            preferences.getString('home_greeting_settings') ?? '{}',
          ),
        };
      });
}

void clearUiPreferencesChannelFixture() {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(uiPreferencesChannel, null);
}
