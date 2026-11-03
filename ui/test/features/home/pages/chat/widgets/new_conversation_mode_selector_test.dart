import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_widgets.dart';
import 'package:ui/features/home/pages/chat/widgets/new_conversation_mode_selector.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/omni_menu.dart';

const _agents = [
  ChatAcpAgentModeOption(id: 'codex-acp', name: 'Codex'),
  ChatAcpAgentModeOption(id: 'claude-code', name: 'Claude Code'),
  ChatAcpAgentModeOption(id: 'disabled', name: 'Disabled', enabled: false),
  ChatAcpAgentModeOption(
    id: 'uninstalled',
    name: 'Uninstalled',
    installed: false,
  ),
  ChatAcpAgentModeOption(id: 'missing', name: 'Missing', status: 'missing'),
];
const _trigger = ValueKey('new-conversation-mode-selector');
const _menu = ValueKey('new-conversation-mode-menu');

class _SvgTestAssetBundle extends CachingAssetBundle {
  final _bytes = Uint8List.fromList(
    utf8.encode(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
      '<rect width="24" height="24" fill="#000"/></svg>',
    ),
  );

  @override
  Future<ByteData> load(String key) async => key.endsWith('.svg')
      ? ByteData.view(_bytes.buffer)
      : rootBundle.load(key);

  @override
  Future<String> loadString(String key, {bool cache = true}) async =>
      utf8.decode(_bytes);
}

Widget _app(Widget child, {GlobalKey<NavigatorState>? navigatorKey}) =>
    DefaultAssetBundle(
      bundle: _SvgTestAssetBundle(),
      child: MaterialApp(
        navigatorKey: navigatorKey,
        theme: AppTheme.lightTheme,
        home: Scaffold(body: child),
      ),
    );

Widget _bottom(Widget child) => Align(
  alignment: Alignment.bottomLeft,
  child: Padding(padding: const EdgeInsets.all(16), child: child),
);

Future<void> _open(WidgetTester tester) async {
  await tester.tap(find.byKey(_trigger));
  await tester.pumpAndSettle();
  expect(find.byKey(_menu), findsOneWidget);
}

void main() {
  testWidgets('available Agent choices and pure chat return their actual IDs', (
    tester,
  ) async {
    final selected = <String>[];
    final visibility = <bool>[];
    await tester.pumpWidget(
      _app(
        _bottom(
          NewConversationModeSelector(
            selectedId: 'codex-acp',
            agentModes: _agents,
            onSelected: selected.add,
            onPopupVisibilityChanged: visibility.add,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Codex'), findsOneWidget);
    expect(
      tester.getSize(find.byKey(_trigger)).height,
      NewConversationModeSelector.height,
    );

    final triggerRect = tester.getRect(find.byKey(_trigger));
    await _open(tester);
    expect(
      tester.getRect(find.byKey(_menu)).contains(triggerRect.center),
      isTrue,
    );
    expect(find.text('Disabled'), findsNothing);
    expect(find.text('Uninstalled'), findsNothing);
    expect(find.text('Missing'), findsNothing);
    expect(
      tester
          .widget<OmniMenuItem>(
            find.byKey(
              const ValueKey('new-conversation-mode-option-codex-acp'),
            ),
          )
          .selected,
      isTrue,
    );
    await tester.tap(
      find.byKey(const ValueKey('new-conversation-mode-option-claude-code')),
    );
    await tester.pumpAndSettle();
    expect(selected, ['claude-code']);
    expect(visibility, [true, false]);

    await _open(tester);
    await tester.tap(
      find.byKey(
        const ValueKey(
          'new-conversation-mode-option-$kNewConversationPureChatModeId',
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(selected, ['claude-code', kNewConversationPureChatModeId]);
    expect(visibility, [true, false, true, false]);
  });

  testWidgets('opening and selecting preserve the editor focus and draft', (
    tester,
  ) async {
    final controller = TextEditingController(text: 'keep this draft');
    final focusNode = FocusNode();
    final selected = <String>[];
    await tester.pumpWidget(
      _app(
        _bottom(
          Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              NewConversationModeSelector(
                selectedId: 'codex-acp',
                agentModes: _agents,
                onSelected: selected.add,
              ),
              TextField(
                controller: controller,
                focusNode: focusNode,
                onTapOutside: (_) => focusNode.unfocus(),
              ),
            ],
          ),
        ),
      ),
    );
    await tester.tap(find.byType(TextField));
    await tester.pumpAndSettle();
    final editorState = tester.state(find.byType(EditableText));
    expect(focusNode.hasFocus, isTrue);
    expect(tester.testTextInput.isVisible, isTrue);
    await _open(tester);
    expect(focusNode.hasFocus, isTrue);
    await tester.tap(
      find.byKey(const ValueKey('new-conversation-mode-option-claude-code')),
    );
    await tester.pumpAndSettle();
    expect(selected, ['claude-code']);
    expect(focusNode.hasFocus, isTrue);
    expect(tester.testTextInput.isVisible, isTrue);
    expect(controller.text, 'keep this draft');
    expect(tester.state(find.byType(EditableText)), same(editorState));
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
    focusNode.dispose();
  });

  testWidgets('system back dismisses only the popup and restores page back', (
    tester,
  ) async {
    final navigatorKey = GlobalKey<NavigatorState>();
    final focusNode = FocusNode();
    final controller = TextEditingController(text: 'draft');
    final visibility = <bool>[];
    await tester.pumpWidget(
      _app(const Text('Home'), navigatorKey: navigatorKey),
    );
    navigatorKey.currentState!.push(
      MaterialPageRoute<void>(
        builder: (_) => Scaffold(
          body: _bottom(
            Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                NewConversationModeSelector(
                  selectedId: 'codex-acp',
                  agentModes: _agents,
                  onSelected: (_) => fail('back must not select a mode'),
                  onPopupVisibilityChanged: visibility.add,
                ),
                TextField(controller: controller, focusNode: focusNode),
              ],
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byType(TextField));
    await tester.pumpAndSettle();
    await _open(tester);
    final route = ModalRoute.of(tester.element(find.byType(TextField)))!;
    expect(route.popDisposition, RoutePopDisposition.doNotPop);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.byKey(_menu), findsNothing);
    expect(find.byType(TextField), findsOneWidget);
    expect(navigatorKey.currentState!.canPop(), isTrue);
    expect(route.popDisposition, RoutePopDisposition.pop);
    expect(focusNode.hasFocus, isTrue);
    expect(tester.testTextInput.isVisible, isTrue);
    expect(controller.text, 'draft');
    expect(visibility, [true, false]);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('Home'), findsOneWidget);
    expect(find.byType(TextField), findsNothing);
    controller.dispose();
    focusNode.dispose();
  });

  testWidgets('unavailable targets and owner removal invalidate the popup', (
    tester,
  ) async {
    late StateSetter rebuild;
    var showSelector = true;
    var enabled = true;
    var agents = _agents;
    final visibility = <bool>[];
    final selections = <String>[];
    await tester.pumpWidget(
      _app(
        StatefulBuilder(
          builder: (_, setState) {
            rebuild = setState;
            return _bottom(
              showSelector
                  ? NewConversationModeSelector(
                      selectedId: 'codex-acp',
                      agentModes: agents,
                      enabled: enabled,
                      onSelected: selections.add,
                      onPopupVisibilityChanged: visibility.add,
                    )
                  : const Text('Sent'),
            );
          },
        ),
      ),
    );
    await _open(tester);
    rebuild(
      () => agents = const [
        ChatAcpAgentModeOption(id: 'codex-acp', name: 'Codex'),
      ],
    );
    await tester.pumpAndSettle();
    expect(find.byKey(_menu), findsNothing);
    expect(visibility, [true, false]);

    rebuild(() => enabled = false);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(_trigger));
    await tester.pumpAndSettle();
    expect(find.byKey(_menu), findsNothing);

    rebuild(() => enabled = true);
    await tester.pumpAndSettle();
    // Remove the source before the inserted overlay's first frame.
    await tester.tap(find.byKey(_trigger));
    rebuild(() => showSelector = false);
    await tester.pumpAndSettle();
    expect(find.byKey(_menu), findsNothing);
    expect(find.text('Sent'), findsOneWidget);
    expect(visibility, [true, false, true, false]);
    expect(selections, isEmpty);
    expect(tester.takeException(), isNull);
  });

  testWidgets('long menus scroll above the keyboard with large text', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    tester.view.viewInsets = const FakeViewPadding(bottom: 240);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetViewInsets);
    tester.platformDispatcher.textScaleFactorTestValue = 1.8;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    final selections = <String>[];
    await tester.pumpWidget(
      _app(
        _bottom(
          NewConversationModeSelector(
            selectedId: 'test-0',
            agentModes: [
              for (var index = 0; index < 14; index++)
                ChatAcpAgentModeOption(
                  id: 'test-$index',
                  name: 'A long Agent name $index',
                ),
            ],
            onSelected: selections.add,
          ),
        ),
      ),
    );
    await _open(tester);
    expect(tester.takeException(), isNull);
    final menuRect = tester.getRect(find.byKey(_menu));
    expect(menuRect.left, greaterThanOrEqualTo(8));
    expect(menuRect.right, lessThanOrEqualTo(312));
    expect(menuRect.bottom, lessThanOrEqualTo(400));
    await tester.drag(
      find.byKey(const ValueKey('new-conversation-mode-options')),
      const Offset(0, -1600),
    );
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(
        const ValueKey(
          'new-conversation-mode-option-$kNewConversationPureChatModeId',
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(selections, [kNewConversationPureChatModeId]);
    expect(tester.takeException(), isNull);
  });
}
