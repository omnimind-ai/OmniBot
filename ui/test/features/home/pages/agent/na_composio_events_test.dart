import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/agent/na_composio_events.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/theme/app_theme.dart';

const eventSlug = 'GMAIL_NEW_GMAIL_MESSAGE';
Map<String, dynamic> settings({int revision = 3}) => {
  'enabled': true,
  'configured': true,
  'revision': revision,
  'publicUrl':
      'https://na.example.test/api/integrations/composio/events/receive',
  'signingSecretConfigured': true,
  'providerRegistered': false,
  'deliveryVerifiedAt': null,
};
Map<String, dynamic> type(String slug, String name, String toolkit) => {
  'slug': slug,
  'name': name,
  'toolkit': toolkit,
};
Map<String, dynamic> definition(String slug, {String name = 'New Gmail'}) => {
  ...type(slug, name, 'gmail'),
  'description': 'A new email event',
  'version': '20261103_00',
  'schemaComplete': true,
  'configSchema': {'type': 'object', 'properties': {}},
  'payloadSchema': {'type': 'object'},
};
Map<String, dynamic> row() => {
  'id': 'local_event',
  'slug': eventSlug,
  'accountId': 'ca_one',
  'routineId': 'r_event',
  'enabled': true,
  'state': 'active',
  'config': <String, dynamic>{},
};

void main() {
  Future<void> pump(
    WidgetTester tester,
    NaComposioEventsRequest request, {
    Object? ownerKey,
  }) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: NaComposioEventsPage(request: request, ownerKey: ownerKey),
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> tap(WidgetTester tester, Finder target) async {
    await tester.ensureVisible(target);
    await tester.tap(target);
    await tester.pumpAndSettle();
  }

  Map<String, dynamic> inventory() => {
    'toolkits': ['gmail', 'slack'],
    'accounts': [
      {
        'id': 'ca_one',
        'toolkit': 'gmail',
        'label': 'Fixture Gmail',
        'status': 'ACTIVE',
      },
    ],
  };
  Map<String, dynamic> duties() => {
    'routines': [
      {
        'id': 'r_event',
        'title': 'Watch Mail',
        'trigger': 'event',
        'enabled': true,
      },
    ],
  };

  testWidgets(
    'settings distinguish registration from delivery; secret is write-only, clears after save and saves once',
    (tester) async {
      var current = settings();
      final writes = <Map<String, dynamic>>[];
      final gate = Completer<Map<String, dynamic>>();
      await pump(tester, (operation, {id, payload = const {}}) async {
        if (operation == 'composio.events.list') {
          return {'settings': current, 'triggers': []};
        }
        if (operation == 'composio.read') return inventory();
        if (operation == 'routines.list') return duties();
        if (operation == 'composio.events.settings.update') {
          writes.add(payload);
          return gate.future;
        }
        return {};
      });
      expect(find.text('尚未确认 Composio 回调登记'), findsOneWidget);
      expect(find.text('尚未收到通过签名校验的事件'), findsOneWidget);
      await tap(tester, find.text('已有 Webhook 或手动配置'));
      final secret = find.byKey(const Key('na-event-signing-secret'));
      await tester.ensureVisible(secret);
      expect(tester.widget<TextField>(secret).obscureText, isTrue);
      await tester.enterText(secret, 'fixture-private-signing-key');
      final save = find.widgetWithText(FilledButton, '保存事件设置');
      await tester.ensureVisible(save);
      await tester.tap(save);
      await tester.tap(save, warnIfMissed: false);
      await tester.pump(const Duration(milliseconds: 100));
      expect(writes.length, 1);
      expect(writes.single['expectedRevision'], 3);
      expect(writes.single['signingSecret'], 'fixture-private-signing-key');
      current = settings(revision: 4);
      gate.complete({'settings': current});
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<TextField>(find.byKey(const Key('na-event-signing-secret')))
            .controller!
            .text,
        isEmpty,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'full schema and exact account duty precede creation; cancel sends nothing and repeated confirm sends once',
    (tester) async {
      final writes = <Map<String, dynamic>>[];
      final gate = Completer<Map<String, dynamic>>();
      await pump(tester, (operation, {id, payload = const {}}) async {
        if (operation == 'composio.events.list') {
          return {'settings': settings(), 'triggers': []};
        }
        if (operation == 'composio.read') return inventory();
        if (operation == 'routines.list') return duties();
        if (operation == 'composio.events.catalog') {
          return {
            'items': [type(eventSlug, 'New Gmail', 'gmail')],
            'nextCursor': null,
          };
        }
        if (operation == 'composio.events.describe') {
          return definition(eventSlug);
        }
        if (operation == 'composio.events.create') {
          writes.add(payload);
          return gate.future;
        }
        return {};
      });
      await tap(tester, find.text('浏览事件类型'));
      await tap(tester, find.text('New Gmail'));
      expect(find.text('完整事件配置与数据定义'), findsOneWidget);
      await tap(
        tester,
        find.byKey(const ValueKey('na-event-account:$eventSlug')),
      );
      await tester.tap(find.text('Fixture Gmail').last);
      await tester.pumpAndSettle();
      await tap(
        tester,
        find.byKey(const ValueKey('na-event-routine:$eventSlug')),
      );
      await tester.tap(find.text('Watch Mail').last);
      await tester.pumpAndSettle();
      final config = find.byKey(const Key('na-event-config-json'));
      await tester.ensureVisible(config);
      await tester.enterText(config, '{"label":"INBOX"}');
      await tap(tester, find.widgetWithText(FilledButton, '创建事件订阅'));
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(writes, isEmpty);
      await tap(tester, find.widgetWithText(FilledButton, '创建事件订阅'));
      final confirm = find.widgetWithText(FilledButton, '确认');
      await tester.tap(confirm);
      await tester.tap(confirm, warnIfMissed: false);
      await tester.pump(const Duration(milliseconds: 100));
      expect(writes.length, 1);
      expect(writes.single, {
        'slug': eventSlug,
        'routineId': 'r_event',
        'accountId': 'ca_one',
        'config': {'label': 'INBOX'},
      });
      gate.complete({'trigger': row()});
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'an owner change while delete confirmation is open prevents every old mutation',
    (tester) async {
      final writes = <String>[];
      Future<Map<String, dynamic>> request(
        String operation, {
        String? id,
        Map<String, dynamic> payload = const {},
      }) async {
        if (operation == 'composio.events.list') {
          return {
            'settings': settings(),
            'triggers': [row()],
          };
        }
        if (operation == 'composio.read') return inventory();
        if (operation == 'routines.list') return duties();
        if (operation == 'composio.events.delete') writes.add(id!);
        return {};
      }

      await pump(tester, request, ownerKey: 'owner-a');
      await tap(tester, find.text('删除订阅'));
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: NaComposioEventsPage(request: request, ownerKey: 'owner-b'),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, '确认'));
      await tester.pumpAndSettle();
      expect(writes, isEmpty);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'late old catalog replies cannot overwrite a newer toolkit filter',
    (tester) async {
      final slow = Completer<Map<String, dynamic>>();
      await pump(tester, (operation, {id, payload = const {}}) async {
        if (operation == 'composio.events.list') {
          return {'settings': settings(), 'triggers': []};
        }
        if (operation == 'composio.read') return inventory();
        if (operation == 'routines.list') return duties();
        if (operation == 'composio.events.catalog') {
          if (payload['toolkit'] == 'gmail') return slow.future;
          return {
            'items': [type('SLACK_NEW_MESSAGE', 'Current Slack', 'slack')],
            'nextCursor': null,
          };
        }
        return {};
      });
      final filter = find.byKey(const Key('na-events-toolkit'));
      await tap(tester, filter);
      await tester.tap(find.text('gmail').last);
      await tester.pump(const Duration(milliseconds: 500));
      await tap(tester, filter);
      await tester.tap(find.text('slack').last);
      await tester.pumpAndSettle();
      expect(find.text('Current Slack'), findsOneWidget);
      slow.complete({
        'items': [type(eventSlug, 'Late Gmail', 'gmail')],
        'nextCursor': null,
      });
      await tester.pumpAndSettle();
      expect(find.text('Late Gmail'), findsNothing);
      expect(find.text('Current Slack'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'missing public setup explains event prerequisites while keeping ordinary proactive notifications independent',
    (tester) async {
      await pump(tester, (operation, {id, payload = const {}}) async {
        if (operation == 'composio.events.list') {
          return {
            'settings': {
              ...settings(),
              'enabled': false,
              'configured': false,
              'publicUrl': '',
              'signingSecretConfigured': false,
            },
            'triggers': [],
          };
        }
        if (operation == 'composio.read') return inventory();
        if (operation == 'routines.list') return duties();
        return {};
      });
      expect(find.text('事件接收未开启'), findsOneWidget);
      expect(find.textContaining('定时主动消息不需要公网回调'), findsOneWidget);
      await tester.enterText(
        find.byKey(const Key('na-event-public-url')),
        'http://127.0.0.1:8787/hook',
      );
      await tap(tester, find.widgetWithText(FilledButton, '配置事件接收'));
      expect(find.textContaining('公网 HTTPS URL'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}
