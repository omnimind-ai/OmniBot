import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/acp_capabilities.dart';

void main() {
  test('normalizes nested ACP capability payloads', () {
    final capabilities = AcpCapabilities.fromMap({
      'loadSession': true,
      'prompt': {'image': true, 'audio': false, 'embeddedContext': true},
      'session': {
        'list': true,
        'resume': true,
        'delete': true,
        'close': true,
      },
      'auth': {
        'methods': [
          {'id': 'oauth', 'name': 'OAuth'},
        ],
        'logout': true,
        'providers': true,
      },
      'client': {
        'fs': {'readTextFile': true, 'writeTextFile': true},
        'terminal': true,
        'plan': true,
        'elicitation': {'form': true, 'url': true},
      },
    });

    expect(capabilities.loadSession, isTrue);
    expect(capabilities.promptImage, isTrue);
    expect(capabilities.promptEmbeddedContext, isTrue);
    expect(capabilities.sessionList, isTrue);
    expect(capabilities.sessionResume, isTrue);
    expect(capabilities.sessionDelete, isTrue);
    expect(capabilities.sessionClose, isTrue);
    expect(capabilities.authMethods.single['id'], 'oauth');
    expect(capabilities.authLogout, isTrue);
    expect(capabilities.authProviders, isTrue);
    expect(capabilities.clientTerminal, isTrue);
    expect(capabilities.clientFsRead, isTrue);
    expect(capabilities.clientFsWrite, isTrue);
    expect(capabilities.clientPlan, isTrue);
    expect(capabilities.clientElicitationForm, isTrue);
    expect(capabilities.clientElicitationUrl, isTrue);
    expect(capabilities.supports('session/load'), isTrue);
    expect(capabilities.supports('session/delete'), isTrue);
    expect(capabilities.supports('prompt/image'), isTrue);
    expect(capabilities.supports('terminal'), isTrue);
    expect(capabilities.supports('fs/read_text_file'), isTrue);
    expect(capabilities.supports('fs/write_text_file'), isTrue);
    expect(capabilities.supports('client/plan'), isTrue);
    expect(capabilities.supports('auth/logout'), isTrue);
  });

  test('keeps unknown capability fields available through raw payload', () {
    final capabilities = AcpCapabilities.fromMap({
      'customCapability': true,
      'session': {'list': false},
    });

    expect(capabilities.supports('customCapability'), isTrue);
    expect(capabilities.supports('session/list'), isFalse);
    expect(capabilities.raw['customCapability'], isTrue);
  });
}
