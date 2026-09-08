import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/omnibot_workspace/widgets/omnibot_workspace_browser.dart';

class _SlowDirectory implements Directory {
  int syncReads = 0;
  final ready = Completer<void>();
  @override
  String get path => '/workspace';
  @override
  bool existsSync() {
    syncReads++;
    return true;
  }

  @override
  List<FileSystemEntity> listSync({
    bool recursive = false,
    bool followLinks = true,
  }) {
    syncReads++;
    return [];
  }

  @override
  Future<bool> exists() async => true;
  @override
  Stream<FileSystemEntity> list({
    bool recursive = false,
    bool followLinks = true,
  }) async* {
    await ready.future;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  testWidgets(
    'swiping into a slow workspace never performs filesystem reads synchronously',
    (tester) async {
      final directory = _SlowDirectory();
      await IOOverrides.runZoned(() async {
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              body: PageView(
                children: const [
                  Center(child: Text('Chat')),
                  OmnibotWorkspaceBrowser(
                    workspacePath: '/workspace',
                    enableSystemBackHandler: false,
                  ),
                ],
              ),
            ),
          ),
        );
        await tester.drag(find.byType(PageView), const Offset(-650, 0));
        await tester.pump(const Duration(milliseconds: 100));
        expect(
          directory.syncReads,
          0,
          reason: 'Directory I/O must not block a swipe frame',
        );
        directory.ready.complete();
        await tester.pumpAndSettle();
      }, createDirectory: (_) => directory);
    },
  );
}
