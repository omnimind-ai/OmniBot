import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/services/agent_avatar_service.dart';

class _AvatarFiles {
  static const previousPath = '/virtual/previous-avatar.png';
  final firstExists = Completer<bool>();
  final readStarted = Completer<void>();
  final writtenPaths = <String>[];
  int previousExistsCalls = 0;

  File createFile(String path) => _AvatarFile(this, path);

  Future<bool> exists(String path) {
    if (path == previousPath) {
      previousExistsCalls++;
      if (previousExistsCalls == 1) {
        readStarted.complete();
        return firstExists.future;
      }
    }
    // Save cleanup is independent of the first, deliberately paused read.
    return Future.value(false);
  }
}

class _AvatarFile implements File {
  _AvatarFile(this.owner, this.path);

  final _AvatarFiles owner;

  @override
  final String path;

  @override
  Future<bool> exists() => owner.exists(path);

  @override
  Future<File> writeAsBytes(
    List<int> bytes, {
    FileMode mode = FileMode.write,
    bool flush = false,
  }) async {
    owner.writtenPaths.add(path);
    return this;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => throw UnsupportedError(
    'Unexpected file operation: ${invocation.memberName}',
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    AgentAvatarService.resetForTesting();
    SharedPreferences.setMockInitialValues({
      AgentAvatarService.storageKey: 2,
      AgentAvatarService.customImagePathKey: _AvatarFiles.previousPath,
    });
  });

  test(
    'cold avatar consumers share one read and one state publication',
    () async {
      final files = _AvatarFiles();
      var notifications = 0;
      void listener() => notifications++;
      AgentAvatarService.avatarStateNotifier.addListener(listener);
      addTearDown(
        () => AgentAvatarService.avatarStateNotifier.removeListener(listener),
      );

      await IOOverrides.runZoned(() async {
        final first = AgentAvatarService.ensureLoaded();
        final rowLoads = List.generate(
          12,
          (_) => AgentAvatarService.ensureLoaded(),
        );
        final explicitLoad = AgentAvatarService.load();
        await files.readStarted.future;
        expect(rowLoads.every((load) => identical(load, first)), isTrue);
        expect(explicitLoad, same(first));
        expect(files.previousExistsCalls, 1);
        expect(notifications, 0);
        files.firstExists.complete(true);
        final results = await Future.wait([first, ...rowLoads, explicitLoad]);
        expect(
          results.every((state) => identical(state, results.first)),
          isTrue,
        );
        expect(results.first.presetIndex, 2);
        expect(results.first.customImagePath, _AvatarFiles.previousPath);
        expect(notifications, 1);

        expect(await AgentAvatarService.ensureLoaded(), same(results.first));
        expect(files.previousExistsCalls, 1);
        expect(notifications, 1);
      }, createFile: files.createFile);
    },
  );

  test(
    'a failed shared load can retry and cleans a missing custom avatar',
    () async {
      final files = _AvatarFiles();
      await IOOverrides.runZoned(() async {
        final first = AgentAvatarService.ensureLoaded();
        final second = AgentAvatarService.ensureLoaded();
        await files.readStarted.future;
        expect(second, same(first));
        final failure = expectLater(first, throwsA(isA<FileSystemException>()));
        files.firstExists.completeError(
          const FileSystemException('temporary read failure'),
        );
        await failure;
        final retry = AgentAvatarService.ensureLoaded();
        expect(retry, isNot(same(first)));
        final state = await retry;
        expect(files.previousExistsCalls, 2);
        expect(state.presetIndex, 2);
        expect(state.customImagePath, isEmpty);
        final prefs = await SharedPreferences.getInstance();
        expect(
          prefs.containsKey(AgentAvatarService.customImagePathKey),
          isFalse,
        );
      }, createFile: files.createFile);
    },
  );

  for (final oldFileExists in [false, true]) {
    test(
      'preset save wins over an earlier read, old file exists=$oldFileExists',
      () async {
        final files = _AvatarFiles();
        var notifications = 0;
        void listener() => notifications++;
        AgentAvatarService.avatarStateNotifier.addListener(listener);
        addTearDown(
          () => AgentAvatarService.avatarStateNotifier.removeListener(listener),
        );
        await IOOverrides.runZoned(() async {
          final read = AgentAvatarService.ensureLoaded();
          await files.readStarted.future;
          final saved = await AgentAvatarService.setPresetAvatarIndex(4);
          expect(saved.presetIndex, 4);
          expect(saved.customImagePath, isEmpty);
          expect(notifications, 1);
          files.firstExists.complete(oldFileExists);
          expect(await read, same(saved));
          expect(AgentAvatarService.avatarStateNotifier.value, same(saved));
          expect(notifications, 1);
          final prefs = await SharedPreferences.getInstance();
          expect(prefs.getInt(AgentAvatarService.storageKey), 4);
          expect(
            prefs.containsKey(AgentAvatarService.customImagePathKey),
            isFalse,
          );
        }, createFile: files.createFile);
      },
    );
  }

  test(
    'read started during a custom save cannot erase or publish over that save',
    () async {
      final supportDirectory = await Directory.systemTemp.createTemp(
        'avatar-service-test-',
      );
      addTearDown(() => supportDirectory.delete(recursive: true));
      const channel = MethodChannel('plugins.flutter.io/path_provider');
      final pathRequested = Completer<void>();
      final pathResponse = Completer<String>();
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(channel, (call) {
        expect(call.method, 'getApplicationSupportDirectory');
        pathRequested.complete();
        return pathResponse.future;
      });
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));

      final files = _AvatarFiles();
      var notifications = 0;
      void listener() => notifications++;
      AgentAvatarService.avatarStateNotifier.addListener(listener);
      addTearDown(
        () => AgentAvatarService.avatarStateNotifier.removeListener(listener),
      );
      await IOOverrides.runZoned(() async {
        final save = AgentAvatarService.setCustomAvatarBytes(
          Uint8List.fromList([1, 2, 3]),
        );
        await pathRequested.future;
        // Capture the read after save admission, so only the save's commit
        // revision can reject this read when the old path check resumes.
        final read = AgentAvatarService.ensureLoaded();
        await files.readStarted.future;
        pathResponse.complete(supportDirectory.path);
        final saved = await save;
        expect(saved.customImagePath, files.writtenPaths.single);
        expect(notifications, 1);
        files.firstExists.complete(false);
        expect(await read, same(saved));
        expect(AgentAvatarService.avatarStateNotifier.value, same(saved));
        expect(notifications, 1);
        final prefs = await SharedPreferences.getInstance();
        expect(
          prefs.getString(AgentAvatarService.customImagePathKey),
          saved.customImagePath,
        );
        expect(await AgentAvatarService.ensureLoaded(), same(saved));
      }, createFile: files.createFile);
    },
  );

  test(
    'preset reset still normalizes invalid indexes and removes custom path',
    () async {
      final files = _AvatarFiles()..firstExists.complete(false);
      await IOOverrides.runZoned(() async {
        final saved = await AgentAvatarService.setPresetAvatarIndex(999);
        expect(saved.presetIndex, 0);
        expect(saved.hasCustomImage, isFalse);
        final prefs = await SharedPreferences.getInstance();
        expect(prefs.getInt(AgentAvatarService.storageKey), 0);
        expect(
          prefs.containsKey(AgentAvatarService.customImagePathKey),
          isFalse,
        );
      }, createFile: files.createFile);
    },
  );
}
