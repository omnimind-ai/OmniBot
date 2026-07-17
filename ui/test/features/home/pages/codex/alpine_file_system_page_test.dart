import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/codex/alpine_file_system_page.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AlpineFileSystem');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  testWidgets('browses Alpine root and opens a directory', (tester) async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      final path = (call.arguments as Map)['path'];
      return <String, dynamic>{
        'path': path,
        'entries': path == '/'
            ? <Map<String, dynamic>>[
                _entry(path: '/root', name: 'root', directory: true),
                _entry(
                  path: '/etc/os-release',
                  name: 'os-release',
                  directory: false,
                ),
              ]
            : <Map<String, dynamic>>[
                _entry(path: '/root/.codex', name: '.codex', directory: true),
              ],
      };
    });

    await tester.pumpWidget(const MaterialApp(home: AlpineFileSystemPage()));
    await tester.pumpAndSettle();

    expect(find.text('root'), findsOneWidget);
    expect(find.text('os-release'), findsOneWidget);

    await tester.tap(find.text('root'));
    await tester.pumpAndSettle();

    expect(find.text('.codex'), findsOneWidget);
    expect(calls.map((call) => call.method), <String>['list', 'list']);
    expect((calls.last.arguments as Map)['path'], '/root');
  });

  testWidgets('opens and saves a writable Alpine text file', (tester) async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'read') {
        return <String, dynamic>{
          'path': '/root/.codex/config.toml',
          'content': 'model = "gpt-test"',
          'size': 18,
          'truncated': false,
        };
      }
      if (call.method == 'write') {
        return <String, dynamic>{'path': '/root/.codex/config.toml'};
      }
      return <String, dynamic>{
        'path': '/root/.codex',
        'entries': <Map<String, dynamic>>[
          _entry(
            path: '/root/.codex/config.toml',
            name: 'config.toml',
            directory: false,
          ),
        ],
      };
    });

    await tester.pumpWidget(
      const MaterialApp(
        home: AlpineFileSystemPage(initialPath: '/root/.codex'),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('config.toml'));
    await tester.pumpAndSettle();

    expect(find.text('model = "gpt-test"'), findsOneWidget);
    await tester.enterText(
      find.byKey(const Key('alpine-fs-editor')),
      'model = "gpt-updated"',
    );
    await tester.tap(find.byKey(const Key('alpine-fs-save-button')));
    await tester.pumpAndSettle();

    final writeCall = calls.firstWhere((call) => call.method == 'write');
    expect(writeCall.arguments, <String, dynamic>{
      'path': '/root/.codex/config.toml',
      'content': 'model = "gpt-updated"',
    });
  });
}

Map<String, dynamic> _entry({
  required String path,
  required String name,
  required bool directory,
}) {
  return <String, dynamic>{
    'path': path,
    'name': name,
    'isDirectory': directory,
    'isFile': !directory,
    'isLink': false,
    'size': directory ? 4096 : 18,
    'modifiedAt': 0,
    'mode': directory ? '755' : '644',
    'readable': true,
    'writable': true,
    'linkTarget': '',
  };
}
