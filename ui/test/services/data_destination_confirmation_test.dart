import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/data_destination_confirmation.dart';

void main() {
  test('safe display contains only scheme host and port', () {
    final destination = DataDestination.parse(
      'https://example.com:8443/private/token?access_token=never-display',
    );

    expect(destination.displayOrigin, 'https://example.com:8443');
    expect(destination.displayOrigin, isNot(contains('/private')));
    expect(destination.displayOrigin, isNot(contains('access_token')));
    expect(destination.displayOrigin, isNot(contains('never-display')));
  });

  test('userinfo and malformed endpoints fail closed', () {
    expect(
      () => DataDestination.parse('https://user:secret@example.com/mcp'),
      throwsFormatException,
    );
    expect(() => DataDestination.parse('not a url'), throwsFormatException);
  });

  test('content requires TLS even when there is no credential', () {
    expect(
      () => DataDestination.parse(
        'http://example.com/mcp',
        allowInsecureDebugLoopback: false,
      ),
      throwsFormatException,
    );
    expect(
      () => DataDestination.parse(
        'ws://192.168.1.20:18789',
        allowInsecureDebugLoopback: true,
      ),
      throwsFormatException,
    );
    expect(
      () => DataDestination.parse(
        'http://localhost:8080/mcp',
        allowInsecureDebugLoopback: true,
      ),
      throwsFormatException,
    );
    expect(
      DataDestination.parse(
        'http://127.0.0.1:8080/mcp',
        allowInsecureDebugLoopback: true,
      ).isLoopback,
      isTrue,
    );
  });

  test('session approval is bound to full endpoint capability and subject', () {
    DataDestinationSessionApprovals.clearForTesting();
    DataDestinationSessionApprovals.remember(
      subject: 'provider-1',
      rawEndpoint: 'https://example.com/v1?private=value',
      capability: 'BYOK model provider',
      operation: 'send chat content',
    );

    expect(
      DataDestinationSessionApprovals.isConfirmed(
        subject: 'provider-1',
        rawEndpoint: 'https://example.com/another/private/path',
        capability: 'BYOK model provider',
        operation: 'send chat content',
      ),
      isFalse,
    );
    expect(
      DataDestinationSessionApprovals.isConfirmed(
        subject: 'provider-1',
        rawEndpoint: 'https://example.com/v1?private=value',
        capability: 'BYOK model provider',
        operation: 'send chat content',
      ),
      isTrue,
    );
    expect(
      DataDestinationSessionApprovals.isConfirmed(
        subject: 'provider-1',
        rawEndpoint: 'https://example.com:8443/v1',
        capability: 'BYOK model provider',
        operation: 'send chat content',
      ),
      isFalse,
    );
    expect(
      DataDestinationSessionApprovals.isConfirmed(
        subject: 'provider-2',
        rawEndpoint: 'https://example.com/v1',
        capability: 'BYOK model provider',
        operation: 'send chat content',
      ),
      isFalse,
    );
  });

  testWidgets('rejecting confirmation invokes no network action', (tester) async {
    var actions = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              await confirmDataDestinationAndRun<void>(
                context: context,
                rawEndpoint:
                    'https://example.com/private?token=never-display',
                capability: 'Remote MCP',
                operation: 'Enable',
                dataTypes: const ['Tool arguments'],
                action: () async => actions++,
              );
            },
            child: const Text('open'),
          ),
        ),
      ),
    );

    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(find.textContaining('never-display'), findsNothing);
    expect(find.textContaining('/private'), findsNothing);
    await tester.tap(find.byKey(const Key('data-destination-cancel')));
    await tester.pumpAndSettle();

    expect(actions, 0);
  });

  testWidgets('one-operation acknowledgement runs action once', (tester) async {
    var actions = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              await confirmDataDestinationAndRun<void>(
                context: context,
                rawEndpoint: 'wss://example.com/codex',
                capability: 'Remote Codex Bridge',
                operation: 'Connect',
                dataTypes: const ['Prompt and workspace path'],
                action: () async => actions++,
              );
            },
            child: const Text('open'),
          ),
        ),
      ),
    );

    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    final confirm = tester.widget<FilledButton>(
      find.byKey(const Key('data-destination-confirm')),
    );
    expect(confirm.onPressed, isNull);
    await tester.tap(
      find.descendant(
        of: find.byKey(const Key('data-destination-acknowledgement')),
        matching: find.byType(Checkbox),
      ),
    );
    await tester.pump();
    await tester.tap(find.byKey(const Key('data-destination-confirm')));
    await tester.pumpAndSettle();

    expect(actions, 1);
  });
}
