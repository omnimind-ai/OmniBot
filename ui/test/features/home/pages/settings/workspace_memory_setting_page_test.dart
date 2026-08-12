import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/settings/workspace_memory_setting_page.dart';
import 'package:ui/theme/app_theme.dart';

class _SvgTestAssetBundle extends CachingAssetBundle {
  static final Uint8List _svgBytes = Uint8List.fromList(
    utf8.encode(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
      '<rect width="24" height="24" fill="#000000"/>'
      '</svg>',
    ),
  );

  @override
  Future<ByteData> load(String key) async {
    return ByteData.view(_svgBytes.buffer);
  }

  @override
  Future<String> loadString(String key, {bool cache = true}) async {
    return utf8.decode(_svgBytes);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');

  Widget buildTestApp(Widget child) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      home: DefaultAssetBundle(bundle: _SvgTestAssetBundle(), child: child),
    );
  }

  late String soulContent;
  late String chatContent;
  late String memoryContent;
  late bool embeddingEnabled;
  late bool rollupEnabled;
  late Completer<Map<String, Object?>>? embeddingSaveCompleter;
  late Completer<Map<String, Object?>>? rollupSaveCompleter;
  late List<MethodCall> recordedCalls;

  setUp(() {
    soulContent = '# SOUL\ninitial soul\n';
    chatContent = '# CHAT\ninitial chat prompt\n';
    memoryContent = '# MEMORY\ninitial memory\n';
    embeddingEnabled = true;
    rollupEnabled = true;
    embeddingSaveCompleter = null;
    rollupSaveCompleter = null;
    recordedCalls = <MethodCall>[];

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          recordedCalls.add(call);
          switch (call.method) {
            case 'getAgentSoulSetting':
              return <String, Object?>{'content': soulContent};
            case 'getChatPromptSetting':
              return <String, Object?>{'content': chatContent};
            case 'saveAgentSoulSetting':
              soulContent = (call.arguments as Map)['content'].toString();
              return <String, Object?>{'content': soulContent};
            case 'saveChatPromptSetting':
              chatContent = (call.arguments as Map)['content'].toString();
              return <String, Object?>{'content': chatContent};
            case 'getWorkspaceLongMemory':
              return <String, Object?>{'content': memoryContent};
            case 'saveWorkspaceLongMemory':
              memoryContent = (call.arguments as Map)['content'].toString();
              return <String, Object?>{'content': memoryContent};
            case 'getWorkspaceMemoryEmbeddingConfig':
              return <String, Object?>{
                'enabled': embeddingEnabled,
                'configured': true,
                'sceneId': 'scene.memory.embedding',
                'providerProfileId': 'provider-1',
                'providerProfileName': 'Provider One',
                'modelId': 'embedding-1',
                'apiBase': 'https://example.com/v1',
                'hasApiKey': true,
              };
            case 'saveWorkspaceMemoryEmbeddingConfig':
              final pending = embeddingSaveCompleter;
              if (pending != null) {
                return pending.future;
              }
              embeddingEnabled = (call.arguments as Map)['enabled'] == true;
              return <String, Object?>{
                'enabled': embeddingEnabled,
                'configured': true,
                'sceneId': 'scene.memory.embedding',
                'providerProfileId': 'provider-1',
                'providerProfileName': 'Provider One',
                'modelId': 'embedding-1',
                'apiBase': 'https://example.com/v1',
                'hasApiKey': true,
              };
            case 'getWorkspaceMemoryRollupStatus':
              return <String, Object?>{
                'enabled': rollupEnabled,
                'lastRunAtMillis': 1712800000000,
                'nextRunAtMillis': 1712886400000,
                'lastRunSummary': 'ok',
              };
            case 'saveWorkspaceMemoryRollupEnabled':
              final pending = rollupSaveCompleter;
              if (pending != null) {
                return pending.future;
              }
              rollupEnabled = (call.arguments as Map)['enabled'] == true;
              return <String, Object?>{
                'enabled': rollupEnabled,
                'lastRunAtMillis': 1712800000000,
                'nextRunAtMillis': 1712886400000,
                'lastRunSummary': 'ok',
              };
            default:
              return null;
          }
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('loads and saves chat prompt setting', (tester) async {
    await tester.pumpWidget(buildTestApp(const WorkspaceMemorySettingPage()));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.text('Workspace 记忆'), findsOneWidget);
    await tester.drag(find.byType(ListView), const Offset(0, -520));
    await tester.pump();
    final chatTitleFinder = find.text('纯聊天系统提示词');
    await tester.ensureVisible(chatTitleFinder);
    await tester.pump();
    expect(chatTitleFinder, findsOneWidget);

    final chatCardFinder = find.ancestor(
      of: chatTitleFinder,
      matching: find.byType(Padding),
    );
    final chatFieldFinder = find.descendant(
      of: chatCardFinder.first,
      matching: find.byType(TextField),
    );
    final chatSaveButtonFinder = find.descendant(
      of: chatCardFinder.first,
      matching: find.widgetWithText(ElevatedButton, '保存'),
    );
    TextField readChatField() => tester.widget<TextField>(chatFieldFinder);

    expect(readChatField().controller!.text, chatContent);

    await tester.enterText(chatFieldFinder, '# CHAT\nsaved from test\n');
    await tester.pump();

    await tester.tap(chatSaveButtonFinder);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(chatContent, '# CHAT\nsaved from test\n');
    expect(readChatField().controller!.text, '# CHAT\nsaved from test\n');
    expect(
      recordedCalls.where((call) => call.method == 'saveChatPromptSetting'),
      hasLength(1),
    );
  });

  testWidgets(
    'serializes each memory toggle independently and trusts server results',
    (tester) async {
      embeddingSaveCompleter = Completer<Map<String, Object?>>();
      rollupSaveCompleter = Completer<Map<String, Object?>>();

      await tester.pumpWidget(buildTestApp(const WorkspaceMemorySettingPage()));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));

      Finder embeddingSwitchFinder() => find.byType(Switch).at(0);
      Finder rollupSwitchFinder() => find.byType(Switch).at(1);
      Switch embeddingSwitch() => tester.widget(embeddingSwitchFinder());
      Switch rollupSwitch() => tester.widget(rollupSwitchFinder());
      int saveCallCount(String method) =>
          recordedCalls.where((call) => call.method == method).length;

      embeddingSwitch().onChanged!(false);
      await tester.pump();

      expect(embeddingSwitch().value, isFalse);
      expect(embeddingSwitch().onChanged, isNull);
      expect(rollupSwitch().onChanged, isNotNull);
      expect(saveCallCount('saveWorkspaceMemoryEmbeddingConfig'), 1);

      await tester.tap(embeddingSwitchFinder());
      await tester.pump();
      expect(saveCallCount('saveWorkspaceMemoryEmbeddingConfig'), 1);

      rollupSwitch().onChanged!(false);
      await tester.pump();

      expect(embeddingSwitch().onChanged, isNull);
      expect(rollupSwitch().value, isFalse);
      expect(rollupSwitch().onChanged, isNull);
      expect(saveCallCount('saveWorkspaceMemoryRollupEnabled'), 1);

      await tester.tap(rollupSwitchFinder());
      await tester.pump();
      expect(saveCallCount('saveWorkspaceMemoryRollupEnabled'), 1);

      embeddingSaveCompleter!.complete(<String, Object?>{
        'enabled': true,
        'configured': true,
        'sceneId': 'scene.memory.embedding',
        'providerProfileId': 'provider-1',
        'providerProfileName': 'Provider One',
        'modelId': 'embedding-1',
        'apiBase': 'https://example.com/v1',
        'hasApiKey': true,
      });
      await tester.pump();

      expect(embeddingSwitch().value, isTrue);
      expect(embeddingSwitch().onChanged, isNotNull);
      expect(rollupSwitch().onChanged, isNull);

      rollupSaveCompleter!.complete(<String, Object?>{
        'enabled': true,
        'lastRunAtMillis': 1712800000000,
        'nextRunAtMillis': 1712886400000,
        'lastRunSummary': 'server kept rollup enabled',
      });
      await tester.pump();

      expect(rollupSwitch().value, isTrue);
      expect(rollupSwitch().onChanged, isNotNull);
      expect(saveCallCount('saveWorkspaceMemoryEmbeddingConfig'), 1);
      expect(saveCallCount('saveWorkspaceMemoryRollupEnabled'), 1);
    },
  );
}
