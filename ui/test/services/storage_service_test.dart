import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/models/chat_startup_behavior.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
  });

  group('recent conversations only preference', () {
    const preferenceKey = StorageService.kRecentConversationsOnlyEnabledKey;

    test('defaults to disabled without creating a saved preference', () async {
      expect(StorageService.containsKey(preferenceKey), isFalse);
      expect(StorageService.isRecentConversationsOnlyEnabled(), isFalse);

      await StorageService.init();

      expect(StorageService.isRecentConversationsOnlyEnabled(), isFalse);
      expect(StorageService.containsKey(preferenceKey), isFalse);
    });

    test('preserves an existing enabled preference when initialized', () async {
      SharedPreferences.setMockInitialValues(<String, Object>{
        preferenceKey: true,
      });
      await StorageService.init();

      expect(StorageService.isRecentConversationsOnlyEnabled(), isTrue);

      await StorageService.init();
      final preferences = await SharedPreferences.getInstance();
      await preferences.reload();

      expect(preferences.getBool(preferenceKey), isTrue);
      expect(StorageService.isRecentConversationsOnlyEnabled(), isTrue);
    });

    test('persists disabling an existing enabled preference', () async {
      SharedPreferences.setMockInitialValues(<String, Object>{
        preferenceKey: true,
      });
      await StorageService.init();

      expect(await StorageService.setBool(preferenceKey, false), isTrue);

      final preferences = await SharedPreferences.getInstance();
      await preferences.reload();
      await StorageService.init();

      expect(preferences.getBool(preferenceKey), isFalse);
      expect(StorageService.containsKey(preferenceKey), isTrue);
      expect(StorageService.isRecentConversationsOnlyEnabled(), isFalse);
    });
  });

  test('defaults chat startup behavior to resume last', () {
    expect(
      StorageService.getChatStartupBehavior(),
      ChatStartupBehavior.resumeLast,
    );
  });

  test('round-trips chat startup behavior', () async {
    await StorageService.setChatStartupBehavior(
      ChatStartupBehavior.newConversation,
    );

    expect(
      StorageService.getChatStartupBehavior(),
      ChatStartupBehavior.newConversation,
    );
  });

  test('falls back to resume last for unknown chat startup behavior', () async {
    await StorageService.setString(
      StorageService.kChatStartupBehaviorKey,
      'unknown',
    );

    expect(
      StorageService.getChatStartupBehavior(),
      ChatStartupBehavior.resumeLast,
    );
  });
}
