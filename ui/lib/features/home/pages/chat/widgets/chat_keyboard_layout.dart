import 'package:flutter/widgets.dart';

typedef ChatKeyboardLayoutMetrics = ({
  double bottomInset,
  double composerBottomOffset,
  double composerReservedInset,
  double commandPanelBottomOffset,
  double inputBottomPadding,
  double keyboardSpacer,
  bool liftEmptyGreeting,
});

/// Keeps IME/focus/composer-height updates in the layout consumers. The page,
/// toolbar, workspace and editor remain the same child widgets during motion.
/// The page's existing trackers still own how raw metrics become geometry.
class ChatKeyboardLayout extends StatelessWidget {
  const ChatKeyboardLayout({
    super.key,
    required this.changes,
    required this.resolve,
    required this.child,
  });

  final Listenable changes;
  final ChatKeyboardLayoutMetrics Function(MediaQueryData) resolve;
  final Widget child;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: changes,
    child: child,
    builder: (context, child) => _ChatKeyboardLayoutScope(
      metrics: resolve(MediaQuery.of(context)),
      child: child!,
    ),
  );
}

class ChatKeyboardLayoutBuilder extends StatelessWidget {
  const ChatKeyboardLayoutBuilder({
    super.key,
    required this.builder,
    this.child,
  });

  final Widget Function(BuildContext, ChatKeyboardLayoutMetrics, Widget?)
  builder;
  final Widget? child;

  @override
  Widget build(BuildContext context) => builder(
    context,
    context
        .dependOnInheritedWidgetOfExactType<_ChatKeyboardLayoutScope>()!
        .metrics,
    child,
  );
}

class _ChatKeyboardLayoutScope extends InheritedWidget {
  const _ChatKeyboardLayoutScope({required this.metrics, required super.child});

  final ChatKeyboardLayoutMetrics metrics;

  @override
  bool updateShouldNotify(_ChatKeyboardLayoutScope oldWidget) =>
      metrics != oldWidget.metrics;
}
