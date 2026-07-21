import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/codex/alpine_file_system_page.dart';
import 'package:ui/services/alpine_file_system_service.dart';

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
          'editable': true,
          'binary': false,
          'isLink': false,
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

  test('preserves trailing spaces and backslashes in Linux paths', () {
    expect(AlpineFileSystemService.normalizePath('/root/file '), '/root/file ');
    expect(AlpineFileSystemService.normalizePath(r'/root/a\b'), r'/root/a\b');
    expect(AlpineFileSystemService.joinPath('/root', r'a\b '), r'/root/a\b ');
  });

  testWidgets('keeps binary and malformed UTF-8 files read-only', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'read') {
        return <String, dynamic>{
          'path': '/root/libbinary.so',
          'content': '',
          'size': 32,
          'truncated': false,
          'editable': false,
          'binary': true,
          'isLink': false,
        };
      }
      return <String, dynamic>{
        'path': '/root',
        'entries': <Map<String, dynamic>>[
          _entry(
            path: '/root/libbinary.so',
            name: 'libbinary.so',
            directory: false,
          ),
        ],
      };
    });

    await tester.pumpWidget(
      const MaterialApp(home: AlpineFileSystemPage(initialPath: '/root')),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('libbinary.so'));
    await tester.pumpAndSettle();

    expect(find.textContaining('not valid UTF-8 text'), findsOneWidget);
    final editor = tester.widget<TextField>(
      find.byKey(const Key('alpine-fs-editor')),
    );
    expect(editor.readOnly, isTrue);
    final saveButton = tester.widget<IconButton>(
      find.byKey(const Key('alpine-fs-save-button')),
    );
    expect(saveButton.onPressed, isNull);
    expect(calls.where((call) => call.method == 'write'), isEmpty);
  });

  testWidgets('opens a directory symlink using its logical path', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      final path = (call.arguments as Map)['path'];
      return <String, dynamic>{
        'path': path,
        'entries': path == '/'
            ? <Map<String, dynamic>>[
                _entry(
                  path: '/var/run',
                  name: 'run',
                  directory: true,
                  isLink: true,
                  linkTarget: '../run',
                ),
              ]
            : <Map<String, dynamic>>[
                _entry(
                  path: '/var/run/service',
                  name: 'service',
                  directory: false,
                ),
              ],
      };
    });

    await tester.pumpWidget(const MaterialApp(home: AlpineFileSystemPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('run'));
    await tester.pumpAndSettle();

    expect((calls.last.arguments as Map)['path'], '/var/run');
    expect(find.text('service'), findsOneWidget);
  });

  testWidgets('discards stale directory responses', (tester) async {
    final rootResponse = Completer<Map<String, dynamic>>();
    final nestedResponse = Completer<Map<String, dynamic>>();
    messenger.setMockMethodCallHandler(channel, (call) {
      final path = (call.arguments as Map)['path'];
      return path == '/' ? rootResponse.future : nestedResponse.future;
    });

    await tester.pumpWidget(const MaterialApp(home: AlpineFileSystemPage()));
    await tester.pump();
    await tester.tap(find.byKey(const Key('alpine-fs-jump-button')));
    await tester.pump();
    await tester.enterText(
      find.byKey(const Key('alpine-fs-path-field')),
      '/root',
    );
    await tester.tap(find.text('Open'));
    await tester.pump();

    nestedResponse.complete(<String, dynamic>{
      'path': '/root',
      'entries': <Map<String, dynamic>>[
        _entry(
          path: '/root/current.txt',
          name: 'current.txt',
          directory: false,
        ),
      ],
    });
    await tester.pump();
    expect(find.text('current.txt'), findsOneWidget);

    rootResponse.complete(<String, dynamic>{
      'path': '/',
      'entries': <Map<String, dynamic>>[
        _entry(path: '/stale.txt', name: 'stale.txt', directory: false),
      ],
    });
    await tester.pump();

    expect(find.text('current.txt'), findsOneWidget);
    expect(find.text('stale.txt'), findsNothing);
  });

  testWidgets('surfaces rename conflicts without reloading the directory', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'move') {
        throw PlatformException(
          code: 'ALPINE_FS_OPERATION_FAILED',
          message: 'Target already exists: /root/existing.txt',
        );
      }
      return <String, dynamic>{
        'path': '/root',
        'entries': <Map<String, dynamic>>[
          _entry(
            path: '/root/source.txt',
            name: 'source.txt',
            directory: false,
          ),
        ],
      };
    });

    await tester.pumpWidget(
      const MaterialApp(home: AlpineFileSystemPage(initialPath: '/root')),
    );
    await tester.pumpAndSettle();

    final entry = find.byKey(
      const ValueKey('alpine-fs-entry-/root/source.txt'),
    );
    await tester.tap(
      find.descendant(
        of: entry,
        matching: find.byType(PopupMenuButton<String>),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Rename'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'existing.txt');
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Target already exists'), findsOneWidget);
    expect(calls.where((call) => call.method == 'list').length, 1);
  });

  testWidgets(
    'disables invalid UTF-8 paths without aliasing replacement-character names',
    (tester) async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        if (call.method == 'read') {
          return <String, dynamic>{
            'path': '/root/\uFFFD',
            'content': 'literal replacement character',
            'size': 29,
            'truncated': false,
            'editable': true,
            'binary': false,
            'isLink': false,
          };
        }
        return <String, dynamic>{
          'path': '/root',
          'entries': <Map<String, dynamic>>[
            _entry(
              path: '',
              name: '',
              directory: false,
              pathToken: 'L3Jvb3Qv/w==',
              nameToken: '/w==',
              hasValidUtf8Path: false,
            ),
            _entry(path: '/root/\uFFFD', name: '\uFFFD', directory: false),
          ],
        };
      });

      await tester.pumpWidget(
        const MaterialApp(home: AlpineFileSystemPage(initialPath: '/root')),
      );
      await tester.pumpAndSettle();

      final invalidEntry = find.byKey(
        const ValueKey('alpine-fs-entry-L3Jvb3Qv/w=='),
      );
      expect(
        find.descendant(
          of: invalidEntry,
          matching: find.textContaining('Non-UTF-8 filename'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: invalidEntry,
          matching: find.byType(PopupMenuButton<String>),
        ),
        findsNothing,
      );

      await tester.tap(invalidEntry);
      await tester.pumpAndSettle();
      expect(calls.where((call) => call.method == 'read'), isEmpty);

      await tester.tap(find.text('\uFFFD'));
      await tester.pumpAndSettle();
      final readCall = calls.singleWhere((call) => call.method == 'read');
      expect((readCall.arguments as Map)['path'], '/root/\uFFFD');
      expect(find.text('literal replacement character'), findsOneWidget);
    },
  );
}

Map<String, dynamic> _entry({
  required String path,
  required String name,
  required bool directory,
  bool isLink = false,
  String linkTarget = '',
  String? pathToken,
  String nameToken = '',
  bool hasValidUtf8Path = true,
}) {
  return <String, dynamic>{
    'path': path,
    'name': name,
    'isDirectory': directory,
    'isFile': !directory,
    'isLink': isLink,
    'size': directory ? 4096 : 18,
    'modifiedAt': 0,
    'mode': directory ? '755' : '644',
    'readable': true,
    'writable': true,
    'linkTarget': linkTarget,
    'pathToken': pathToken ?? path,
    'nameToken': nameToken,
    'hasValidUtf8Path': hasValidUtf8Path,
  };
}
