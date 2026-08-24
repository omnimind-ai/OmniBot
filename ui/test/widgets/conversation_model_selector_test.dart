import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/widgets/conversation_model_selector.dart';

void main() {
  const officialProfile = ModelProviderProfileSummary(
    id: 'official',
    name: 'OmniBot 官方 AI',
    baseUrl: '',
    apiKey: '',
    customHeaders: <String, String>{},
    sourceType: 'omnibot_official',
    readOnly: true,
    ready: true,
    statusText: '',
    configured: true,
  );

  Future<void> pumpSelector(
    WidgetTester tester, {
    required ProviderModelOption model,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ConversationModelSelectorContent(
            width: 320,
            maxHeight: 360,
            profiles: const <ModelProviderProfileSummary>[officialProfile],
            providerModelsByProfileId: <String, List<ProviderModelOption>>{
              officialProfile.id: <ProviderModelOption>[model],
            },
            currentSelection: ConversationModelSelection(
              providerProfileId: officialProfile.id,
              modelId: model.id,
            ),
            showSearchField: false,
          ),
        ),
      ),
    );
    await tester.pump();
  }

  testWidgets('shows the catalog display name and keeps the model ID tooltip', (
    tester,
  ) async {
    await pumpSelector(
      tester,
      model: const ProviderModelOption(id: 'opus-6', displayName: 'opus 6☺️'),
    );

    expect(find.text('opus 6☺️'), findsOneWidget);
    expect(find.text('opus-6'), findsNothing);
    expect(find.byTooltip('opus-6'), findsOneWidget);
  });

  testWidgets('falls back to the model ID when display name is blank', (
    tester,
  ) async {
    await pumpSelector(
      tester,
      model: const ProviderModelOption(id: 'opus-6', displayName: '   '),
    );

    expect(find.text('opus-6'), findsOneWidget);
  });
}
