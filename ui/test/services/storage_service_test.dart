import '../support/ui_preferences_channel.dart';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/models/chat_startup_behavior.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(clearUiPreferencesChannelFixture);

  setUp(() async {
    installUiPreferencesChannelFixture();
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
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
