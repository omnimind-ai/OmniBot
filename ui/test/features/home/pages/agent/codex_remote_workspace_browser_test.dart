import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/agent/codex_remote_workspace_browser.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  testWidgets('Na reuses workspace browsing with an explicit cloud owner', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'config/remote/fs/read')
        return {
          'ok': true,
          'path': '/workspace/result.md',
          'name': 'result.md',
          'previewKind': 'text',
          'content': 'Na cloud result',
        };
      return {
        'ok': true,
        'path': '/workspace',
        'cwd': '/workspace',
        'entries': [
          {'name': 'result.md', 'path': '/workspace/result.md', 'type': 'file'},
        ],
      };
    });
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: CodexRemoteWorkspaceBrowser(
            workspacePath: '/workspace',
            agentId: 'na-cloud',
            allowFileMutations: false,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(calls.single.arguments, {
      'agentId': 'na-cloud',
      'remoteCwd': '/workspace',
      'path': '/workspace',
    });
    await tester.longPress(find.text('result.md'));
    await tester.pumpAndSettle();
    expect(find.text('Rename'), findsNothing);
    expect(find.text('Delete'), findsNothing);
    expect(find.text('Edit'), findsOneWidget);
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();
    expect(calls.last.method, 'config/remote/fs/read');
    expect((calls.last.arguments as Map)['agentId'], 'na-cloud');
    expect(find.textContaining('Na cloud result'), findsWidgets);
  });

  testWidgets('same workspace path reloads when its Agent owner changes', (tester) async {
    final owners = <String?>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      final owner = (call.arguments as Map)['agentId'] as String?;
      owners.add(owner);
      final name = owner == 'na-cloud' ? 'cloud.md' : 'bridge.md';
      return {'ok': true, 'path': '/workspace', 'cwd': '/workspace',
        'entries': [{'name': name, 'path': '/workspace/$name', 'type': 'file'}]};
    });
    Widget browser(String? owner) => MaterialApp(home: Scaffold(body:
      CodexRemoteWorkspaceBrowser(workspacePath: '/workspace', agentId: owner)));
    await tester.pumpWidget(browser(null));
    await tester.pumpAndSettle();
    expect(find.text('bridge.md'), findsOneWidget);
    await tester.pumpWidget(browser('na-cloud'));
    await tester.pumpAndSettle();
    expect(owners, [null, 'na-cloud']);
    expect(find.text('bridge.md'), findsNothing);
    expect(find.text('cloud.md'), findsOneWidget);
  });

  testWidgets('loads remote Codex workspace entries from bridge list API', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <String, dynamic>{
        'ok': true,
        'path': '/repo',
        'cwd': '/repo',
        'parent': '/Users/me',
        'entries': <Map<String, dynamic>>[
          <String, dynamic>{
            'name': 'lib',
            'path': '/repo/lib',
            'type': 'directory',
          },
          <String, dynamic>{
            'name': 'README.md',
            'path': '/repo/README.md',
            'type': 'file',
          },
        ],
      };
    });

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: CodexRemoteWorkspaceBrowser(
            workspacePath: '/repo',
            remoteBridgeUrl: 'ws://192.168.1.2:17321/codex',
            remoteBridgeToken: 'token',
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('lib'), findsOneWidget);
    expect(find.text('README.md'), findsOneWidget);
    expect(calls.single.method, 'config/remote/fs/list');
    expect(calls.single.arguments, <String, dynamic>{
      'remoteBridgeUrl': 'ws://192.168.1.2:17321/codex',
      'remoteBridgeToken': 'token',
      'remoteCwd': '/repo',
      'path': '/repo',
    });
  });

  testWidgets('opens remote file preview on file tap', (tester) async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'config/remote/fs/read') {
        return <String, dynamic>{
          'ok': true,
          'path': '/repo/README.md',
          'name': 'README.md',
          'type': 'file',
          'previewKind': 'text',
          'mimeType': 'text/plain',
          'content': 'Remote readme content',
        };
      }
      return <String, dynamic>{
        'ok': true,
        'path': '/repo',
        'cwd': '/repo',
        'entries': <Map<String, dynamic>>[
          <String, dynamic>{
            'name': 'README.md',
            'path': '/repo/README.md',
            'type': 'file',
          },
        ],
      };
    });

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: CodexRemoteWorkspaceBrowser(
            workspacePath: '/repo',
            remoteBridgeUrl: 'ws://192.168.1.2:17321/codex',
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('README.md'));
    await tester.pumpAndSettle();

    expect(find.text('Remote readme content'), findsOneWidget);
    expect(calls.map((call) => call.method), [
      'config/remote/fs/list',
      'config/remote/fs/read',
    ]);
  });
}
