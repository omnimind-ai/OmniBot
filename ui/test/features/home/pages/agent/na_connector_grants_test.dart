import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/agent/na_connector_grants.dart';

Map<String, dynamic> action() => {
  'id': 'action-one',
  'routineId': 'routine-one',
  'status': 'pending',
  'operation': 'composio.GMAIL_SEND_EMAIL',
  'args': {
    'subject': 'Exact reviewed subject',
    'body': '<script>literal</script>',
  },
  'account': {'id': 'ca_one', 'label': 'Explicit account'},
  'connector': {
    'provider': 'composio',
    'toolkit': 'gmail',
    'tool': 'GMAIL_SEND_EMAIL',
    'version': '20261103_00',
    'accountId': 'ca_one',
    'authConfigId': 'ac_one',
    'noAuth': false,
    'schemaHash': 'a' * 64,
    'policyHash': 'b' * 64,
    'reviewHash': 'c' * 64,
  },
};
Map<String, dynamic> grant({
  String title = 'Reviewed routine',
  String status = 'active',
}) => {
  'id': 'grant-one',
  'routineId': 'routine-one',
  'routineTitle': title,
  'sourceActionId': 'action-one',
  'status': status,
  'tool': 'GMAIL_SEND_EMAIL',
  'version': '20261103_00',
  'accountId': 'ca_one',
  'noAuth': false,
  'expiresAt': '2026-12-04T00:00:00Z',
  if (status == 'revoked') 'reason': 'user_revoked',
};

void main() {
  Future<void> pumpButton(
    WidgetTester tester,
    NaGrantRequest request, {
    Map<String, dynamic>? value,
    Object? owner = 'owner-one',
    VoidCallback? onChanged,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: NaRepeatGrantButton(
              action: value ?? action(),
              request: request,
              ownerKey: owner,
              onChanged: onChanged,
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> pumpList(
    WidgetTester tester,
    NaGrantRequest request, {
    Object? owner = 'owner-one',
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: NaConnectorGrantList(request: request, ownerKey: owner),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> tap(WidgetTester tester, String label) async {
    final f = find.text(label);
    await tester.ensureVisible(f);
    await tester.tap(f);
    await tester.pumpAndSettle();
  }

  test('official leading underscore and digit toolkit/tool slugs remain grant-eligible', () {
    for (final pair in {
      '_1password': '_1PASSWORD_LIST_VAULTS',
      '_21risk': '_21RISK_LIST_ASSETS',
      '_2chat': '_2CHAT_LIST_CONTACTS',
      '1-custom-app': '1CUSTOM_LIST_ITEMS',
      'team-app': 'TEAM_LIST_ITEMS',
    }.entries) {
      final value = action();
      (value['connector'] as Map)['toolkit'] = pair.key;
      (value['connector'] as Map)['tool'] = pair.value;
      value['operation'] = 'composio.${pair.value}';
      expect(canSaveNaRepeatGrant(value), isTrue, reason: pair.key);
    }
    for (final tool in [
      'COMPOSIO_SEARCH_TOOLS',
      '../PRIVATE',
      'invalid-lowercase',
    ]) {
      final value = action();
      (value['connector'] as Map)['tool'] = tool;
      value['operation'] = 'composio.$tool';
      expect(canSaveNaRepeatGrant(value), isFalse);
    }
  });

  testWidgets(
    'ordinary actions and old connector schemas cannot offer repeat permission',
    (tester) async {
      Future<Map<String, dynamic>> request(
        String operation, {
        String? id,
        Map<String, dynamic> payload = const {},
      }) async {
        fail('No request should be performed');
      }

      final ordinary = action()..remove('routineId');
      await pumpButton(tester, request, value: ordinary);
      expect(find.text('保存重复授权'), findsNothing);
      final legacy = action();
      (legacy['connector'] as Map).remove('authConfigId');
      await pumpButton(tester, request, value: legacy);
      expect(find.text('保存重复授权'), findsNothing);
    },
  );

  testWidgets(
    'review renders exact args and cancel never creates or approves',
    (tester) async {
      final calls = <String>[];
      Future<Map<String, dynamic>> request(
        String operation, {
        String? id,
        Map<String, dynamic> payload = const {},
      }) async {
        calls.add(operation);
        return {};
      }

      await pumpButton(tester, request);
      await tap(tester, '保存重复授权');
      expect(find.textContaining('Exact reviewed subject'), findsOneWidget);
      expect(find.textContaining('默认有效 30 天'), findsOneWidget);
      expect(find.textContaining('当前待审核操作仍需单独审核执行'), findsOneWidget);
      await tap(tester, '取消');
      expect(calls, isEmpty);
    },
  );

  testWidgets(
    'save sends action id once and never executes the pending action',
    (tester) async {
      final pending = Completer<Map<String, dynamic>>(),
          calls = <Map<String, dynamic>>[];
      var changed = 0;
      Future<Map<String, dynamic>> request(
        String operation, {
        String? id,
        Map<String, dynamic> payload = const {},
      }) async {
        calls.add({'operation': operation, 'id': id, 'payload': payload});
        return pending.future;
      }

      await pumpButton(
        tester,
        request,
        onChanged: () {
          changed++;
        },
      );
      await tap(tester, '保存重复授权');
      await tap(tester, '确认保存重复授权');
      expect(calls, [
        {
          'operation': 'connectors.grants.create',
          'id': null,
          'payload': {'actionId': 'action-one'},
        },
      ]);
      expect(
        tester
            .widget<TextButton>(
              find.byKey(const ValueKey('na-save-repeat-action-one')),
            )
            .onPressed,
        isNull,
      );
      pending.complete({'grant': grant()});
      await tester.pumpAndSettle();
      expect(find.text('重复授权已保存'), findsOneWidget);
      expect(changed, 1);
      expect(
        calls.any((entry) => entry['operation'].toString().contains('approve')),
        isFalse,
      );
    },
  );

  testWidgets(
    'owner change discards an old creation response and its parent refresh',
    (tester) async {
      final pending = Completer<Map<String, dynamic>>();
      var changed = 0;
      Future<Map<String, dynamic>> old(
        String operation, {
        String? id,
        Map<String, dynamic> payload = const {},
      }) => pending.future;
      Future<Map<String, dynamic>> current(
        String operation, {
        String? id,
        Map<String, dynamic> payload = const {},
      }) async => {};
      await pumpButton(
        tester,
        old,
        onChanged: () {
          changed++;
        },
      );
      await tap(tester, '保存重复授权');
      await tap(tester, '确认保存重复授权');
      await pumpButton(
        tester,
        current,
        owner: 'owner-two',
        onChanged: () {
          changed++;
        },
      );
      pending.complete({'grant': grant()});
      await tester.pumpAndSettle();
      expect(find.text('重复授权已保存'), findsNothing);
      expect(find.text('保存重复授权'), findsOneWidget);
      expect(changed, 0);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'owner change closes an unconfirmed review without issuing creation',
    (tester) async {
      var count = 0;
      Future<Map<String, dynamic>> request(
        String operation, {
        String? id,
        Map<String, dynamic> payload = const {},
      }) async {
        count++;
        return {};
      }

      await pumpButton(tester, request);
      await tap(tester, '保存重复授权');
      await pumpButton(tester, request, owner: 'owner-two');
      expect(find.text('确认保存重复授权'), findsNothing);
      expect(count, 0);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('list shows exact scope and revoke requires confirmation', (
    tester,
  ) async {
    final calls = <String>[];
    Future<Map<String, dynamic>> request(
      String operation, {
      String? id,
      Map<String, dynamic> payload = const {},
    }) async {
      calls.add(operation);
      if (operation == 'connectors.grants.list') {
        return {
          'grants': [grant()],
        };
      }
      expect(operation, 'connectors.grants.revoke');
      expect(id, 'grant-one');
      return {'grant': grant(status: 'revoked')};
    }

    await pumpList(tester, request);
    expect(find.textContaining('来源操作：action-one'), findsOneWidget);
    expect(find.textContaining('到期：'), findsOneWidget);
    await tap(tester, '撤销授权');
    await tap(tester, '取消');
    expect(calls, ['connectors.grants.list']);
    await tap(tester, '撤销授权');
    await tap(tester, '确认撤销');
    expect(calls, ['connectors.grants.list', 'connectors.grants.revoke']);
    expect(find.text('你已撤销这项授权。'), findsOneWidget);
    expect(find.text('撤销授权'), findsNothing);
  });

  testWidgets('late list response cannot show an old owner grant', (
    tester,
  ) async {
    final late = Completer<Map<String, dynamic>>();
    Future<Map<String, dynamic>> old(
      String operation, {
      String? id,
      Map<String, dynamic> payload = const {},
    }) => late.future;
    Future<Map<String, dynamic>> current(
      String operation, {
      String? id,
      Map<String, dynamic> payload = const {},
    }) async => {
      'grants': [grant(title: 'Current owner')],
    };
    await pumpList(tester, old);
    await pumpList(tester, current, owner: 'owner-two');
    late.complete({
      'grants': [grant(title: 'Stale private routine')],
    });
    await tester.pumpAndSettle();
    expect(find.textContaining('Current owner'), findsOneWidget);
    expect(find.textContaining('Stale private routine'), findsNothing);
  });
}
