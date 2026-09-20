import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';

void main() {
  test('enabled Bridge cannot turn a durable local event into an ephemeral projection', () {
    expect(usesRemoteCodexEventProjection(remoteConfigured: true, conversationId: 31), isFalse);
    expect(usesRemoteCodexEventProjection(remoteConfigured: false, conversationId: 31), isFalse);
  });
  test('remote-only and unbound remote sessions retain their existing route', () {
    expect(usesRemoteCodexEventProjection(remoteConfigured: true, conversationId: -31), isTrue);
    expect(usesRemoteCodexEventProjection(remoteConfigured: true, conversationId: null), isTrue);
    expect(usesRemoteCodexEventProjection(remoteConfigured: false, conversationId: -31), isFalse);
  });
}
