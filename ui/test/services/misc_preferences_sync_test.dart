import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/state/habitual_hand_controller.dart';
import 'package:ui/features/home/state/predictive_back_controller.dart';
import 'package:ui/models/chat_startup_behavior.dart';
import 'package:ui/models/habitual_hand.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/services/ui_preferences_sync.dart';

import '../support/ui_preferences_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  tearDown(clearUiPreferencesChannelFixture);

  test('restores old Flutter controllers and existing sidebar listener after native edits', () async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    installUiPreferencesChannelFixture();
    final container = ProviderContainer();
    addTearDown(container.dispose);
    expect(container.read(habitualHandProvider), HabitualHand.right);
    expect(container.read(predictiveBackEnabledProvider), isTrue);

    final sidebarUpdate = ConversationService.sidebarPolicyChangedStream.first;
    SharedPreferences.setMockInitialValues({
      'habitual_hand': 'left',
      'predictive_back_enabled': false,
      'recent_conversations_only_enabled': true,
    });
    await UiPreferencesSync.refresh(container);

    expect(container.read(habitualHandProvider), HabitualHand.left);
    expect(container.read(predictiveBackEnabledProvider), isFalse);
    expect(await sidebarUpdate, isTrue);
    expect(StorageService.isRecentConversationsOnlyEnabled(), isTrue);

    expect(
      await StorageService.setChatStartupBehavior(
        ChatStartupBehavior.newConversation,
      ),
      isTrue,
    );
    expect(
      StorageService.getChatStartupBehavior(),
      ChatStartupBehavior.newConversation,
    );
  });
}
