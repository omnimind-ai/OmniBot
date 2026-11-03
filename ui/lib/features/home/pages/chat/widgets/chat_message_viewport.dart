import 'package:flutter/widgets.dart';

/// Geometry shared by the transcript and its loading/empty presentation.
/// Only the padding and empty alignment consumers subscribe; message content
/// keeps its elements and sliver delegates while the keyboard moves.
class ChatMessageViewport extends InheritedWidget {
  const ChatMessageViewport({
    super.key,
    required this.bottomOverlayInset,
    required this.liftEmptyGreeting,
    required super.child,
  });

  final double bottomOverlayInset;
  final bool liftEmptyGreeting;

  static ChatMessageViewport? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<ChatMessageViewport>();

  @override
  bool updateShouldNotify(ChatMessageViewport oldWidget) =>
      bottomOverlayInset != oldWidget.bottomOverlayInset ||
      liftEmptyGreeting != oldWidget.liftEmptyGreeting;
}
