import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

const uiPreferencesChannel = MethodChannel('cn.com.omnimind.bot/app_state');

/// Host-boundary fixture for existing theme/language and compatibility tests.
/// It models storage replies only; native validation and side effects need device checks.
bool _mockVibration = true;

void installUiPreferencesChannelFixture() {
  _mockVibration = true;
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
        } else if (call.method == 'updateMiscPreferences') {
          final args = Map<String, dynamic>.from(call.arguments as Map);
          final key = switch (args['operation']) {
            'startup' => 'chat_startup_behavior',
            'recentOnly' => 'recent_conversations_only_enabled',
            'hideFromRecents' => 'hide_from_recents',
            'independentSend' => 'use_independent_chat_send_button',
            'predictiveBack' => 'predictive_back_enabled',
            'preventSleep' => 'prevent_screen_sleep_during_tasks',
            'completionNotification' => 'task_completion_notification_enabled',
            'habitualHand' => 'habitual_hand',
            'vibration' => null,
            _ => throw StateError(
              'Unexpected miscellaneous mutation in fixture',
            ),
          };
          if (key == null) {
            _mockVibration = args['value'] as bool;
          } else if (args['value'] is bool) {
            await preferences.setBool(key, args['value'] as bool);
          } else {
            await preferences.setString(key, args['value'] as String);
          }
        } else if (call.method != 'getUiPreferences' &&
            call.method != 'getMiscPreferences') {
          throw MissingPluginException('Unexpected app-state method');
        }
        if (call.method == 'getMiscPreferences' ||
            call.method == 'updateMiscPreferences') {
          return {
            'startup':
                preferences.getString('chat_startup_behavior') ?? 'resume_last',
            'recentOnly':
                preferences.getBool('recent_conversations_only_enabled') ??
                false,
            'hideFromRecents':
                preferences.getBool('hide_from_recents') ?? false,
            'vibration': _mockVibration,
            'independentSend':
                preferences.getBool('use_independent_chat_send_button') ?? true,
            'predictiveBack':
                preferences.getBool('predictive_back_enabled') ?? true,
            'preventSleep':
                preferences.getBool('prevent_screen_sleep_during_tasks') ??
                true,
            'completionNotification':
                preferences.getBool('task_completion_notification_enabled') ??
                true,
            'habitualHand': preferences.getString('habitual_hand') ?? 'right',
          };
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
