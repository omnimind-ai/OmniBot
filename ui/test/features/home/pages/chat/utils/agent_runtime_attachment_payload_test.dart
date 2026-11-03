import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/utils/agent_runtime_attachment_payload.dart';

void main() {
  test(
    'complete history records are display files, not prompt attachments',
    () {
      final attachments = buildAgentRuntimeAttachmentsFromMessageContent({
        'attachments': [
          {'name': 'original.txt', 'path': '/workspace/original.txt'},
          {
            'name': 'complete.json',
            'path': '/workspace/complete.json',
            'historyPayloadOffloaded': true,
          },
        ],
      });

      expect(attachments.single['name'], 'original.txt');
    },
  );

  test('keeps local image attachments path-only for native agent runtime', () {
    final attachments = buildAgentRuntimeAttachmentsFromMessageContent({
      'attachments': [
        {
          'name': 'shot.png',
          'path': '/tmp/shot.png',
          'mimeType': 'image/png',
          'isImage': true,
        },
      ],
    });

    expect(attachments, hasLength(1));
    expect(attachments.single['path'], '/tmp/shot.png');
    expect(attachments.single.containsKey('dataUrl'), isFalse);
  });

  test('preserves existing remote url and compact dataUrl payloads', () {
    final attachments = buildAgentRuntimeAttachmentsFromMessageContent({
      'attachments': [
        {
          'name': 'remote.png',
          'url': 'https://example.com/remote.png',
          'mimeType': 'image/png',
          'isImage': true,
        },
        {
          'name': 'inline.png',
          'dataUrl': 'data:image/png;base64,AAAA',
          'mimeType': 'image/png',
          'isImage': true,
        },
      ],
    });

    expect(attachments, hasLength(2));
    expect(attachments[0]['url'], 'https://example.com/remote.png');
    expect(attachments[1]['dataUrl'], 'data:image/png;base64,AAAA');
  });
}
