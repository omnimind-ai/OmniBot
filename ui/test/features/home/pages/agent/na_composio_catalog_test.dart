import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/agent/na_composio_catalog.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/theme/app_theme.dart';

Map<String, dynamic> card(String slug, String label, {bool noAuth = false}) => {
  'slug': slug,
  'label': label,
  'description': '$label 的应用连接',
  'noAuth': noAuth,
  'categories': ['Productivity'],
  'logo': null,
};
Map<String, dynamic> page(List<Map<String, dynamic>> cards, {String? cursor}) =>
    {
      'cards': cards,
      'nextCursor': cursor,
      'total': 1500,
      'source': 'api',
      'complete': true,
    };

void main() {
  Future<void> pump(WidgetTester tester, NaComposioRequest request) async {
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
        home: NaComposioCatalogPage(request: request),
      ),
    );
    await tester.pumpAndSettle();
  }

  Map<String, dynamic> settings({List<String> toolkits = const []}) => {
    'enabled': true,
    'apiKeyConfigured': true,
    'toolkits': toolkits,
    'authConfigs': <String, dynamic>{},
    'revision': 3,
  };

  testWidgets(
    'full catalog search drops late old replies and pagination keeps earlier apps',
    (tester) async {
      final slow = Completer<Map<String, dynamic>>();
      final requests = <Map<String, dynamic>>[];
      await pump(tester, (operation, payload) async {
        if (operation == 'composio.settings.read') {
          return {'settings': settings()};
        }
        if (operation == 'composio.read') return {'accounts': []};
        if (operation == 'composio.catalog') {
          requests.add(payload);
          if (payload['query'] == 'slack') return slow.future;
          if (payload['query'] == 'stripe') {
            return page([card('stripe', 'Stripe')]);
          }
          if (payload['cursor'] == 'next-page') {
            return page([card('notion', 'Notion')]);
          }
          return page([card('slack', 'Slack')], cursor: 'next-page');
        }
        return {};
      });
      expect(find.text('Slack'), findsOneWidget);
      await tester.tap(find.text('加载更多应用'));
      await tester.pumpAndSettle();
      expect(find.text('Slack'), findsOneWidget);
      expect(find.text('Notion'), findsOneWidget);
      final search = find.byKey(const Key('na-composio-catalog-search'));
      await tester.enterText(search, 'slack');
      await tester.pump(const Duration(milliseconds: 310));
      await tester.enterText(search, 'stripe');
      await tester.pump(const Duration(milliseconds: 310));
      await tester.pumpAndSettle();
      expect(find.text('Stripe'), findsOneWidget);
      slow.complete(page([card('slack', 'Late Slack')]));
      await tester.pumpAndSettle();
      expect(find.text('Late Slack'), findsNothing);
      expect(find.text('Stripe'), findsOneWidget);
      expect(requests.any((value) => value['cursor'] == 'next-page'), isTrue);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'public app enables server policy once without authorization or browser IO',
    (tester) async {
      final save = Completer<Map<String, dynamic>>();
      final mutations = <String>[];
      final current = settings();
      await pump(tester, (operation, payload) async {
        if (operation == 'composio.settings.read') return {'settings': current};
        if (operation == 'composio.read') return {'accounts': []};
        if (operation == 'composio.catalog') {
          return page([card('public_search', 'Public Search', noAuth: true)]);
        }
        if (operation == 'composio.settings.update') {
          mutations.add(operation);
          expect(payload['expectedRevision'], 3);
          expect(payload['toolkits'], ['public_search']);
          current['toolkits'] = ['public_search'];
          current['revision'] = 4;
          return save.future;
        }
        if (operation == 'composio.authorize') mutations.add(operation);
        return {};
      });
      await tester.tap(find.text('Public Search'));
      await tester.pumpAndSettle();
      final enable = find.widgetWithText(FilledButton, '启用应用');
      await tester.tap(enable);
      await tester.tap(enable, warnIfMissed: false);
      await tester.pump(const Duration(milliseconds: 450));
      expect(mutations, ['composio.settings.update']);
      save.complete({'settings': current});
      await tester.pumpAndSettle();
      expect(find.text('已启用 · 无需授权'), findsOneWidget);
      expect(mutations, ['composio.settings.update']);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'a changed owner while settings are open prevents every mutation',
    (tester) async {
      bool changed = false;
      final writes = <String>[];
      await pump(tester, (operation, payload) async {
        if (operation == 'composio.settings.read') {
          return {'settings': settings()};
        }
        if (operation == 'composio.read') return {'accounts': []};
        if (operation == 'composio.catalog') {
          return page([card('public_search', 'Public Search', noAuth: true)]);
        }
        if (operation == 'status' && changed) {
          throw StateError('Na 连接已更改，请重新载入连接。');
        }
        if (operation == 'composio.settings.update' ||
            operation == 'composio.authorize') {
          writes.add(operation);
        }
        return {};
      });
      await tester.tap(find.text('Public Search'));
      await tester.pumpAndSettle();
      changed = true;
      await tester.tap(find.widgetWithText(FilledButton, '启用应用'));
      await tester.pumpAndSettle();
      expect(writes, isEmpty);
      expect(find.text('Na 连接已更改，请重新载入连接。'), findsOneWidget);
      final tile = tester.widget<ListTile>(
        find.byKey(const Key('na-composio-card-public_search')),
      );
      expect(tile.onTap, isNull);
    },
  );

  testWidgets(
    'custom config and alias are saved with revision before connecting an arbitrary app',
    (tester) async {
      final trace = <String>[], current = settings();
      Map<String, dynamic>? saved, authorized;
      await pump(tester, (operation, payload) async {
        if (operation == 'composio.settings.read') return {'settings': current};
        if (operation == 'composio.read') return {'accounts': []};
        if (operation == 'composio.catalog') {
          return page([card('custom_team-app', 'Team App')]);
        }
        if (operation == 'composio.authConfigs') {
          return {
            'configs': [
              {
                'id': 'ac_custom',
                'name': 'Own OAuth',
                'status': 'ENABLED',
                'managed': false,
              },
            ],
          };
        }
        if (operation == 'composio.settings.update') {
          trace.add('save');
          saved = payload;
          current.addAll(payload);
          current['revision'] = 4;
          return {'settings': current};
        }
        if (operation == 'composio.authorize') {
          trace.add('authorize');
          authorized = payload;
          return {'noAuth': true};
        }
        return {};
      });
      await tester.tap(find.text('Team App'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('na-composio-auth-config')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Own OAuth · ac_custom').last);
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const Key('na-composio-account-alias')),
        ' Work ',
      );
      await tester.tap(find.widgetWithText(FilledButton, '启用并连接'));
      await tester.pumpAndSettle();
      expect(trace, ['save', 'authorize']);
      expect(saved!['expectedRevision'], 3);
      expect(saved!['authConfigs'], {'custom_team-app': 'ac_custom'});
      expect(authorized, {'toolkit': 'custom_team-app', 'alias': 'Work'});
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'inventory failure preserves catalog and makes its status limitation clear',
    (tester) async {
      await pump(tester, (operation, payload) async {
        if (operation == 'composio.settings.read') {
          return {'settings': settings()};
        }
        if (operation == 'composio.read') throw StateError('Provider offline');
        if (operation == 'composio.catalog') {
          return page([card('github', 'GitHub')]);
        }
        return {};
      });
      expect(find.text('GitHub'), findsOneWidget);
      expect(find.text('账号状态暂不可用，仍可浏览应用目录。'), findsOneWidget);
    },
  );
}
