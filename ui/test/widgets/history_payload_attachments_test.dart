import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/artifact_card.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/message_bubble.dart';
import 'package:ui/features/home/pages/omnibot_workspace/omnibot_artifact_preview_page.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const assistCore = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  const voiceEvents = MethodChannel('cn.com.omnimind.bot/VoicePlaybackEvents');
  late Directory directory;
  late File record;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    directory = await Directory.systemTemp.createTemp('history_attachment_');
    record = File('${directory.path}/complete.json');
    // If the file actions accidentally switch to text preview, UTF-8 decoding
    // fails. Opening a complete record must not eagerly read its contents.
    await record.writeAsBytes(List<int>.filled(4096, 255));
    OmnibotResourceService.debugSetWorkspacePaths(
      OmnibotWorkspacePaths(
        rootPath: directory.path,
        shellRootPath: '/workspace',
        internalRootPath: '${directory.path}/.omnibot',
      ),
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, (MethodCall call) async {
          if (call.method == 'isWorkspaceStorageAccessGranted') return true;
          return null;
        });
    for (final channel in [assistCore, voiceEvents]) {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (_) async => null);
    }
  });

  tearDown(() async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, null);
    for (final channel in [assistCore, voiceEvents]) {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    }
    OmnibotResourceService.debugResetWorkspacePaths();
    await directory.delete(recursive: true);
  });

  testWidgets('a user preview cannot open the full-message editor', (
    tester,
  ) async {
    var edits = 0;
    for (final preview in [true, false]) {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: MessageBubble(
              message: ChatMessageModel(
                id: 'user',
                type: 1,
                user: 1,
                content: {
                  'text': 'message text',
                  'historyPayloadOffloaded': preview,
                },
              ),
              onUserMessageEditTap: () => edits++,
            ),
          ),
        ),
      );
      await tester.tap(find.text('message text'));
      expect(edits, preview ? 0 : 1);
    }
  });

  testWidgets('a failure preview cannot expose a retry action', (tester) async {
    var retries = 0;
    for (final preview in [true, false]) {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: MessageBubble(
              message: ChatMessageModel(
                id: 'failure',
                type: 1,
                user: 2,
                isError: true,
                content: {
                  'text': 'failed',
                  'agentRetryable': true,
                  'historyPayloadOffloaded': preview,
                },
              ),
              onRetryAgentMessage: () => retries++,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        find.byIcon(Icons.refresh_rounded),
        preview ? findsNothing : findsOneWidget,
      );
      if (!preview) await tester.tap(find.byIcon(Icons.refresh_rounded));
    }
    expect(retries, 1);
  });

  for (final type in [1, 2]) {
    testWidgets(
      'history type $type exposes complete record without reading it',
      (tester) async {
        final message = ChatMessageModel(
          id: 'message-$type',
          type: type,
          user: type == 1 ? 1 : 3,
          content: {
            'id': 'message-$type',
            'historyPayloadOffloaded': true,
            if (type == 1) 'text': '可见预览',
            if (type == 2)
              'cardData': {
                'type': 'history_omitted_card',
                'summary': '可见卡片预览',
                'historyPayloadOffloaded': true,
              },
            'attachments': [
              {
                'title': '完整消息记录',
                'name': '完整消息记录',
                'fileName': 'complete.json',
                'androidPath': record.path,
                'path': record.path,
                'uri': 'omnibot://workspace/complete.json',
                'mimeType': 'application/json',
                'previewKind': 'code',
                'historyPayloadOffloaded': true,
              },
            ],
          },
        );
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              body: SingleChildScrollView(
                child: MessageBubble(message: message),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.text('完整消息记录'), findsOneWidget);
        expect(find.byType(ArtifactCard), findsOneWidget);
        await tester.tap(find.text('预览'));
        await tester.runAsync(() async {
          await Future<void>.delayed(const Duration(milliseconds: 20));
        });
        await tester.pumpAndSettle();

        final preview = tester.widget<OmnibotArtifactPreviewPage>(
          find.byType(OmnibotArtifactPreviewPage),
        );
        expect(preview.previewKind, 'file');
        expect(preview.path, record.path);
        expect(find.text('Open with system'), findsOneWidget);
        expect(find.textContaining('读取失败'), findsNothing);
        expect(find.byTooltip('编辑文件'), findsNothing);
        expect(tester.takeException(), isNull);
      },
    );
  }
}
