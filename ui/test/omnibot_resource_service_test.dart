import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/special_permission.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const workspacePaths = OmnibotWorkspacePaths(
    rootPath: '/data/user/0/cn.com.omnimind.bot/workspace',
    shellRootPath: '/workspace',
    internalRootPath: '/data/user/0/cn.com.omnimind.bot/workspace/.omnibot',
  );

  setUpAll(() {
    OmnibotResourceService.debugSetWorkspacePaths(workspacePaths);
  });

  tearDown(() {
    debugResetAppEditionCapabilitySnapshot();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, null);
  });

  test(
    'resolveUri maps omnibot workspace uri to shell path and preview metadata',
    () {
      final metadata = OmnibotResourceService.resolveUri(
        'omnibot://workspace/demo/output.png',
      );

      expect(metadata, isNotNull);
      expect(
        metadata!.path,
        '/data/user/0/cn.com.omnimind.bot/workspace/demo/output.png',
      );
      expect(metadata.shellPath, '/workspace/demo/output.png');
      expect(metadata.previewKind, 'image');
      expect(metadata.embedKind, 'image');
      expect(metadata.inlineRenderable, isTrue);
    },
  );

  test('resolveUri maps omnibot public uri to storage path and shell path', () {
    final metadata = OmnibotResourceService.resolveUri(
      'omnibot://public/DCIM/Camera/demo.jpg',
    );

    expect(metadata, isNotNull);
    expect(metadata!.path, '/storage/DCIM/Camera/demo.jpg');
    expect(metadata.shellPath, '/storage/DCIM/Camera/demo.jpg');
    expect(
      OmnibotResourceService.resolveUriToPath(
        'omnibot://public/Music/demo.mp3',
      ),
      '/storage/Music/demo.mp3',
    );
    expect(
      OmnibotResourceService.resolveUriToShellPath(
        'omnibot://public/Music/demo.mp3',
      ),
      '/storage/Music/demo.mp3',
    );
    expect(
      OmnibotResourceService.shellPathForAndroidPath(
        '/sdcard/Download/demo.txt',
      ),
      '/sdcard/Download/demo.txt',
    );
    expect(
      OmnibotResourceService.androidPathForShellPath(
        '/sdcard/Download/demo.txt',
      ),
      '/sdcard/Download/demo.txt',
    );
  });

  test('describePath derives inline rendering hints from file extension', () {
    final audio = OmnibotResourceService.describePath(
      '/data/user/0/cn.com.omnimind.bot/workspace/audio/demo.mp3',
    );
    final video = OmnibotResourceService.describePath(
      '/data/user/0/cn.com.omnimind.bot/workspace/video/demo.mp4',
    );
    final office = OmnibotResourceService.describePath(
      '/data/user/0/cn.com.omnimind.bot/workspace/docs/quarterly-report.docx',
    );
    final document = OmnibotResourceService.describePath(
      '/data/user/0/cn.com.omnimind.bot/workspace/docs/spec.pdf',
    );
    final html = OmnibotResourceService.describePath(
      '/data/user/0/cn.com.omnimind.bot/workspace/docs/report.html',
    );

    expect(audio.shellPath, '/workspace/audio/demo.mp3');
    expect(audio.embedKind, 'audio');
    expect(audio.inlineRenderable, isTrue);

    expect(video.shellPath, '/workspace/video/demo.mp4');
    expect(video.embedKind, 'video');
    expect(video.inlineRenderable, isTrue);

    expect(office.shellPath, '/workspace/docs/quarterly-report.docx');
    expect(office.previewKind, 'office_word');
    expect(
      office.mimeType,
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    );
    expect(office.embedKind, 'office');
    expect(office.inlineRenderable, isTrue);

    expect(document.shellPath, '/workspace/docs/spec.pdf');
    expect(document.embedKind, 'pdf');
    expect(document.inlineRenderable, isTrue);

    expect(html.shellPath, '/workspace/docs/report.html');
    expect(html.previewKind, 'html');
    expect(html.embedKind, 'html');
    expect(html.inlineRenderable, isTrue);
  });

  test(
    'ensureWorkspacePathsLoaded retries after an initial channel failure',
    () async {
      OmnibotResourceService.debugResetWorkspacePaths();
      var callCount = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            if (call.method != 'getWorkspacePathSnapshot') {
              return null;
            }
            callCount += 1;
            if (callCount == 1) {
              throw PlatformException(
                code: 'channel-unavailable',
                message: 'not ready',
              );
            }
            return <String, String>{
              'rootPath': '/data/user/0/cn.com.omnimind.bot.debug/workspace',
              'shellRootPath': '/workspace',
              'internalRootPath':
                  '/data/user/0/cn.com.omnimind.bot.debug/workspace/.omnibot',
            };
          });

      final initial = await OmnibotResourceService.ensureWorkspacePathsLoaded(
        forceRefresh: true,
      );
      expect(initial.rootPath, workspacePaths.rootPath);

      final retried = await OmnibotResourceService.ensureWorkspacePathsLoaded();
      expect(callCount, 2);
      expect(
        retried.rootPath,
        '/data/user/0/cn.com.omnimind.bot.debug/workspace',
      );
      expect(
        OmnibotResourceService.androidPathForShellPath(
          '/workspace/docs/spec.pdf',
        ),
        '/data/user/0/cn.com.omnimind.bot.debug/workspace/docs/spec.pdf',
      );
    },
  );

  test(
    'Play public resources fail before any permission settings request',
    () async {
      final calls = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            calls.add(call.method);
            if (call.method == 'getAppEditionCapabilitySnapshot') {
              return <String, dynamic>{
                'schemaVersion': 1,
                'edition': 'play',
                'installedAppsQuery': false,
                'publicStorageAccess': false,
              };
            }
            return null;
          });

      final granted = await OmnibotResourceService.ensureResourceAccess(
        path: '/storage/emulated/0/Download/report.pdf',
      );

      expect(granted, isFalse);
      expect(calls, <String>['getAppEditionCapabilitySnapshot']);
    },
  );
}
