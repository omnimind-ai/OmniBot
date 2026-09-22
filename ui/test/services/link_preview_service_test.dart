import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/link_preview_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('link data processing crosses the native boundary once', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'reconcileChatLinkPreviews') {
        return [
          {'url': 'https://example.com', 'status': 'loading'},
        ];
      }
      return {
        'url': 'https://example.com',
        'title': 'Native title',
        'status': 'ready',
      };
    });
    final service = LinkPreviewService();
    final rows = await service.reconcilePreviewMaps(
      text: 'https://example.com',
    );
    expect(rows!.single['status'], 'loading');
    expect(
      (await service.loadPreview(rows.single['url'] as String)).title,
      'Native title',
    );
    expect(calls.map((call) => call.method), [
      'reconcileChatLinkPreviews',
      'loadChatLinkPreview',
    ]);
    expect(calls.first.arguments['text'], 'https://example.com');
  });

  test('unavailable native preview preserves the existing message', () async {
    expect(
      await LinkPreviewService().reconcilePreviewMaps(text: 'hello'),
      isNull,
    );
    expect(
      (await LinkPreviewService().loadPreview('https://example.com')).status,
      'failed',
    );
  });
}
