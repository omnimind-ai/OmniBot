import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';

void main() {
  test('parses rich browser snapshot payloads', () {
    final snapshot = ChatBrowserSessionSnapshot.fromMap(<String, dynamic>{
      'available': true,
      'workspaceId': 'conversation_7',
      'activeTabId': 3,
      'currentUrl': 'https://example.com',
      'title': 'Example',
      'userAgentProfile': 'desktop_safari',
      'isBookmarked': true,
      'canGoBack': true,
      'canGoForward': false,
      'isLoading': true,
      'hasSslError': false,
      'isDesktopMode': true,
      'riskChallengeDetected': true,
      'riskChallengeKind': 'search_engine_challenge',
      'recommendedNextAction': 'ask_user_to_complete_verification_manually',
      'throttleDelayMs': 1500,
      'activeDownloadCount': 1,
      'tabs': <Map<String, dynamic>>[
        <String, dynamic>{
          'tabId': 3,
          'url': 'https://example.com',
          'title': 'Example',
          'isActive': true,
          'isLoading': true,
          'riskChallengeDetected': true,
          'riskChallengeKind': 'search_engine_challenge',
        },
      ],
      'bookmarks': <Map<String, dynamic>>[
        <String, dynamic>{
          'url': 'https://example.com',
          'title': 'Example',
          'updatedAt': 100,
        },
      ],
      'downloads': <Map<String, dynamic>>[
        <String, dynamic>{
          'id': 'download-1',
          'fileName': 'demo.pdf',
          'url': 'https://example.com/demo.pdf',
          'destinationPath': '/tmp/demo.pdf',
          'status': 'completed',
          'canOpenFile': true,
        },
      ],
      'downloadSummary': <String, dynamic>{
        'activeCount': 1,
        'failedCount': 0,
        'overallProgress': 0.5,
      },
      'externalOpenPrompt': <String, dynamic>{
        'requestId': 'external-1',
        'title': 'Open app',
        'target': 'intent://demo',
      },
      'permissionPrompt': <String, dynamic>{
        'requestId': 'permission-1',
        'kind': 'web',
        'origin': 'https://media.example',
        'recipient': 'media.example',
        'capabilities': <String>['camera', 'microphone'],
      },
      'userscriptSummary': <String, dynamic>{
        'installedScripts': <Map<String, dynamic>>[
          <String, dynamic>{
            'id': 9,
            'name': 'Demo Script',
            'enabled': true,
            'grants': <String>['GM_getValue'],
          },
        ],
        'currentPageMenuCommands': <Map<String, dynamic>>[
          <String, dynamic>{
            'commandId': 'menu-1',
            'scriptId': 9,
            'title': 'Run demo',
          },
        ],
        'pendingInstall': <String, dynamic>{
          'id': 9,
          'name': 'Demo Script',
          'isUpdate': true,
        },
      },
    });

    expect(snapshot.available, isTrue);
    expect(snapshot.isBookmarked, isTrue);
    expect(snapshot.riskChallengeDetected, isTrue);
    expect(snapshot.riskChallengeKind, 'search_engine_challenge');
    expect(snapshot.throttleDelayMs, 1500);
    expect(snapshot.tabs.single.tabId, 3);
    expect(snapshot.tabs.single.riskChallengeDetected, isTrue);
    expect(snapshot.bookmarks.single.url, 'https://example.com');
    expect(snapshot.downloads.single.fileName, 'demo.pdf');
    expect(snapshot.downloadSummary.overallProgress, 0.5);
    expect(snapshot.externalOpenPrompt?.target, 'intent://demo');
    expect(snapshot.permissionPrompt?.recipient, 'media.example');
    expect(
      snapshot.permissionPrompt?.capabilities,
      <String>['camera', 'microphone'],
    );
    expect(snapshot.permissionPrompt?.toMap(), isNot(contains('resources')));
    expect(snapshot.userscriptSummary.installedScripts.single.name, 'Demo Script');
    expect(snapshot.userscriptSummary.pendingInstall?.isUpdate, isTrue);
  });
}
