import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_history_loading_transition.dart';

void main() {
  testWidgets('pending history only mounts the inset-aware shimmer label', (
    tester,
  ) async {
    var childBuilds = 0;
    await tester.pumpWidget(
      _app(
        ChatHistoryLoadingTransition(
          isLoading: true,
          bottomInset: 120,
          textColor: Colors.grey,
          child: Builder(
            builder: (_) {
              childBuilds++;
              return const Text('new conversation greeting');
            },
          ),
        ),
      ),
    );

    expect(childBuilds, 0);
    expect(find.text('new conversation greeting'), findsNothing);
    expect(find.text('OmniBot'), findsOneWidget);
    expect(find.byType(ShaderMask), findsOneWidget);
    final text = tester.widget<Text>(find.text('OmniBot'));
    expect(text.style?.fontSize, 28);
    expect(text.style?.color, Colors.grey);
    final labelHeight = tester.getSize(find.text('OmniBot')).height;
    expect(
      tester.getCenter(find.text('OmniBot')).dy,
      closeTo((600 - 120 - labelHeight) * 0.41 + labelHeight / 2, 0.1),
    );
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('history fades in once and retains child state on updates', (
    tester,
  ) async {
    final loading = ValueNotifier(true);
    final label = ValueNotifier('history one');
    addTearDown(loading.dispose);
    addTearDown(label.dispose);
    await tester.pumpWidget(
      _app(
        ValueListenableBuilder(
          valueListenable: loading,
          builder: (context, isLoading, _) => ValueListenableBuilder(
            valueListenable: label,
            builder: (context, text, _) => ChatHistoryLoadingTransition(
              isLoading: isLoading,
              child: _StateProbe(label: text),
            ),
          ),
        ),
      ),
    );
    loading.value = false;
    await tester.pump();
    expect(_contentOpacity(tester), 0);
    expect(find.text('OmniBot'), findsOneWidget);
    final originalState = tester.state(find.byType(_StateProbe));

    await tester.pump(const Duration(milliseconds: 120));
    expect(_contentOpacity(tester), greaterThan(0));
    expect(_contentOpacity(tester), lessThan(1));
    await tester.pumpAndSettle();
    expect(find.text('OmniBot'), findsNothing);
    expect(_contentOpacity(tester), 1);

    label.value = 'history update';
    await tester.pump();
    expect(tester.state(find.byType(_StateProbe)), same(originalState));
    expect(find.text('history update'), findsOneWidget);
    expect(_contentOpacity(tester), 1);
    expect(find.text('OmniBot'), findsNothing);
  });

  testWidgets('a new load immediately removes history during its fade', (
    tester,
  ) async {
    final loading = ValueNotifier(true);
    addTearDown(loading.dispose);
    await tester.pumpWidget(
      _app(
        ValueListenableBuilder(
          valueListenable: loading,
          builder: (context, isLoading, _) => ChatHistoryLoadingTransition(
            isLoading: isLoading,
            child: const Text('history'),
          ),
        ),
      ),
    );
    loading.value = false;
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 80));
    expect(find.text('history'), findsOneWidget);

    loading.value = true;
    await tester.pump();
    expect(find.text('history'), findsNothing);
    expect(find.text('OmniBot'), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('history'), findsNothing);

    loading.value = false;
    await tester.pump();
    await tester.pumpAndSettle();
    expect(find.text('history'), findsOneWidget);
    expect(find.text('OmniBot'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('reduced motion has neither shimmer ticks nor a reveal fade', (
    tester,
  ) async {
    final loading = ValueNotifier(true);
    addTearDown(loading.dispose);
    await tester.pumpWidget(
      _app(
        ValueListenableBuilder(
          valueListenable: loading,
          builder: (context, isLoading, _) => ChatHistoryLoadingTransition(
            isLoading: isLoading,
            child: const Text('history'),
          ),
        ),
        reduceMotion: true,
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('OmniBot'), findsOneWidget);
    expect(find.byType(ShaderMask), findsNothing);
    expect(tester.binding.hasScheduledFrame, isFalse);

    loading.value = false;
    await tester.pump();
    expect(find.text('history'), findsOneWidget);
    expect(find.text('OmniBot'), findsNothing);
    expect(_contentOpacity(tester), 1);
    await tester.pump();
    expect(tester.binding.hasScheduledFrame, isFalse);
  });

  testWidgets('history reveal follows the home-to-chat route entrance', (
    tester,
  ) async {
    final loading = ValueNotifier(true);
    final navigatorKey = GlobalKey<NavigatorState>();
    addTearDown(loading.dispose);
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigatorKey,
        home: const Scaffold(body: Text('home')),
      ),
    );
    navigatorKey.currentState!.push(
      PageRouteBuilder<void>(
        transitionDuration: const Duration(milliseconds: 300),
        pageBuilder: (context, animation, secondaryAnimation) => Scaffold(
          body: ValueListenableBuilder(
            valueListenable: loading,
            builder: (context, isLoading, _) => ChatHistoryLoadingTransition(
              isLoading: isLoading,
              child: const Text('history'),
            ),
          ),
        ),
        transitionsBuilder: (context, animation, secondaryAnimation, child) =>
            FadeTransition(opacity: animation, child: child),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    loading.value = false;
    await tester.pump();
    expect(_contentOpacity(tester), 0);
    expect(find.text('OmniBot'), findsOneWidget);

    await tester.pump(const Duration(milliseconds: 100));
    expect(_contentOpacity(tester), 0);
    await tester.pump(const Duration(milliseconds: 100));
    expect(_contentOpacity(tester), 0);
    await tester.pump(const Duration(milliseconds: 100));
    await tester.pump(const Duration(milliseconds: 100));
    expect(_contentOpacity(tester), greaterThan(0));
    expect(_contentOpacity(tester), lessThan(1));
    await tester.pumpAndSettle();
    expect(find.text('OmniBot'), findsNothing);
    expect(_contentOpacity(tester), 1);
  });

  testWidgets(
    'ready content starts visible without an artificial loading phase',
    (tester) async {
      await tester.pumpWidget(
        _app(
          const ChatHistoryLoadingTransition(
            isLoading: false,
            child: Text('new chat'),
          ),
        ),
      );
      expect(find.text('new chat'), findsOneWidget);
      expect(find.text('OmniBot'), findsNothing);
      expect(_contentOpacity(tester), 1);
      await tester.pumpAndSettle();
      expect(tester.binding.hasScheduledFrame, isFalse);
    },
  );

  testWidgets('disposing during reveal leaves no active ticker', (
    tester,
  ) async {
    final loading = ValueNotifier(true);
    addTearDown(loading.dispose);
    await tester.pumpWidget(
      _app(
        ValueListenableBuilder(
          valueListenable: loading,
          builder: (context, isLoading, _) => ChatHistoryLoadingTransition(
            isLoading: isLoading,
            child: const Text('history'),
          ),
        ),
      ),
    );
    loading.value = false;
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 80));
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 1));
    expect(tester.takeException(), isNull);
    expect(tester.binding.hasScheduledFrame, isFalse);
  });
}

Widget _app(Widget child, {bool reduceMotion = false}) => MaterialApp(
  home: Scaffold(
    body: MediaQuery(
      data: MediaQueryData(disableAnimations: reduceMotion),
      child: child,
    ),
  ),
);

double _contentOpacity(WidgetTester tester) => tester
    .widget<FadeTransition>(find.byKey(const ValueKey('chat-history-content')))
    .opacity
    .value;

class _StateProbe extends StatefulWidget {
  const _StateProbe({required this.label});

  final String label;

  @override
  State<_StateProbe> createState() => _StateProbeState();
}

class _StateProbeState extends State<_StateProbe> {
  @override
  Widget build(BuildContext context) => Text(widget.label);
}
