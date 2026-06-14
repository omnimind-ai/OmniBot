import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/codex_app_server_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/CodexAppServer');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test('startTurn forwards codex permission payload', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await CodexAppServerService.startTurn(
      conversationId: 42,
      threadId: 'thread-1',
      text: 'hello',
      approvalPolicy: 'never',
      approvalsReviewer: 'user',
      sandboxPolicy: const <String, dynamic>{'type': 'dangerFullAccess'},
      model: 'gpt-5-codex',
      effort: 'high',
      collaborationMode: 'plan',
    );

    expect(capturedCall?.method, 'turn/start');
    final args = Map<String, dynamic>.from(
      (capturedCall?.arguments as Map).cast<String, dynamic>(),
    );
    expect(args['conversationId'], 42);
    expect(args['threadId'], 'thread-1');
    expect(args['text'], 'hello');
    expect(args['approvalPolicy'], 'never');
    expect(args['approvalsReviewer'], 'user');
    expect(args['sandboxPolicy'], const <String, dynamic>{
      'type': 'dangerFullAccess',
    });
    expect(args['model'], 'gpt-5-codex');
    expect(args['effort'], 'high');
    expect(args['collaborationMode'], 'plan');
  });

  test('startReview forwards codex review payload', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await CodexAppServerService.startReview(
      conversationId: 42,
      threadId: 'thread-1',
      approvalPolicy: 'on-request',
      approvalsReviewer: 'guardian_subagent',
      model: 'gpt-5-codex',
      effort: 'xhigh',
      collaborationMode: 'plan',
    );

    expect(capturedCall?.method, 'review/start');
    final args = Map<String, dynamic>.from(
      (capturedCall?.arguments as Map).cast<String, dynamic>(),
    );
    expect(args['conversationId'], 42);
    expect(args['threadId'], 'thread-1');
    expect(args['approvalPolicy'], 'on-request');
    expect(args['approvalsReviewer'], 'guardian_subagent');
    expect(args['target'], const <String, dynamic>{
      'type': 'uncommittedChanges',
    });
    expect(args['model'], 'gpt-5-codex');
    expect(args['effort'], 'xhigh');
    expect(args['collaborationMode'], 'plan');
  });

  test('lists codex models, collaboration modes, and config', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <String, dynamic>{'ok': true};
    });

    await CodexAppServerService.listModels();
    await CodexAppServerService.listCollaborationModes();
    await CodexAppServerService.readConfig();
    await CodexAppServerService.listLoadedThreads();

    expect(calls.map((call) => call.method), [
      'model/list',
      'collaborationMode/list',
      'config/read',
      'thread/loaded/list',
    ]);
    expect(calls.first.arguments, {'limit': 100});
  });

  test('readThread requests turns by default', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await CodexAppServerService.readThread(threadId: 'thread-1');

    expect(capturedCall?.method, 'thread/read');
    expect(capturedCall?.arguments, {
      'threadId': 'thread-1',
      'includeTurns': true,
    });
  });

  test('reads and writes local codex config files', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <String, dynamic>{
        'baseUrl': 'https://example.com/v1',
        'model': 'gpt-5.5',
        'apiKey': 'key',
        'codexHome': '/root/.codex',
        'contextInjectionEnabled': true,
      };
    });

    final read = await CodexAppServerService.readLocalConfig();
    final written = await CodexAppServerService.writeLocalConfig(
      baseUrl: ' https://example.com/v1 ',
      model: ' gpt-5.5 ',
      apiKey: ' key ',
      contextInjectionEnabled: true,
    );

    expect(read.baseUrl, 'https://example.com/v1');
    expect(read.model, 'gpt-5.5');
    expect(read.apiKey, 'key');
    expect(read.contextInjectionEnabled, isTrue);
    expect(written.codexHome, '/root/.codex');
    expect(written.contextInjectionEnabled, isTrue);
    expect(calls.map((call) => call.method), [
      'config/local/read',
      'config/local/write',
    ]);
    expect(calls.last.arguments, <String, dynamic>{
      'baseUrl': 'https://example.com/v1',
      'model': 'gpt-5.5',
      'apiKey': 'key',
      'remoteEnabled': false,
      'remoteBridgeUrl': '',
      'remoteBridgeToken': '',
      'remoteCwd': '',
      'contextInjectionEnabled': true,
    });
  });

  test(
    'forwards remote filesystem operations without trimming content',
    () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        if (call.method == 'config/remote/fs/read') {
          return <String, dynamic>{
            'ok': true,
            'path': '/repo/lib/main.dart',
            'name': 'main.dart',
            'previewKind': 'code',
            'mimeType': 'text/plain',
            'content': 'void main() {}',
          };
        }
        return <String, dynamic>{'ok': true};
      });

      final read = await CodexAppServerService.readRemoteFile(
        remoteBridgeUrl: ' ws://pc:17321/codex ',
        remoteBridgeToken: ' token ',
        remoteCwd: ' /repo ',
        path: ' /repo/lib/main.dart ',
      );
      await CodexAppServerService.writeRemoteFile(
        path: '/repo/lib/main.dart',
        content: '  keep whitespace\n',
      );
      await CodexAppServerService.deleteRemotePath(
        path: '/repo/tmp',
        recursive: true,
      );
      await CodexAppServerService.moveRemotePath(
        path: '/repo/a.dart',
        destinationPath: '/repo/b.dart',
      );

      expect(read.content, 'void main() {}');
      expect(calls.map((call) => call.method), [
        'config/remote/fs/read',
        'config/remote/fs/write',
        'config/remote/fs/delete',
        'config/remote/fs/move',
      ]);
      expect(calls[0].arguments, <String, dynamic>{
        'remoteBridgeUrl': 'ws://pc:17321/codex',
        'remoteBridgeToken': 'token',
        'remoteCwd': '/repo',
        'path': '/repo/lib/main.dart',
      });
      expect((calls[1].arguments as Map)['content'], '  keep whitespace\n');
      expect((calls[2].arguments as Map)['recursive'], true);
      expect((calls[3].arguments as Map)['destinationPath'], '/repo/b.dart');
    },
  );
}
