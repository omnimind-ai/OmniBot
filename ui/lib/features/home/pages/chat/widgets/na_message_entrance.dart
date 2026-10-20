import 'package:flutter/material.dart';

/// Runs once for newly appended messages; history and remounted rows stay still.
class NaMessageEntrance extends StatefulWidget {
  const NaMessageEntrance({
    super.key,
    required this.animate,
    required this.isUser,
    required this.child,
  });
  final bool animate;
  final bool isUser;
  final Widget child;

  @override
  State<NaMessageEntrance> createState() => _NaMessageEntranceState();
}

class _NaMessageEntranceState extends State<NaMessageEntrance>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 320),
    value: 1,
  );
  late final Animation<double> _progress = CurvedAnimation(
    parent: _controller,
    curve: Curves.easeOutCubic,
  );
  bool _started = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_started) {
      _started = true;
      if (widget.animate && !MediaQuery.disableAnimationsOf(context)) {
        _controller.forward(from: 0);
      }
    } else if (MediaQuery.disableAnimationsOf(context)) {
      _controller.value = 1;
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _progress,
    child: widget.child,
    builder: (context, child) {
      final remaining = 1 - _progress.value;
      return Opacity(
        opacity: 1 - remaining * 0.45,
        child: Transform.translate(
          offset: Offset(widget.isUser ? remaining * 12 : 0, remaining * 22),
          child: Transform.scale(
            scale: 1 - remaining * 0.035,
            alignment: widget.isUser
                ? Alignment.bottomRight
                : Alignment.bottomLeft,
            child: child,
          ),
        ),
      );
    },
  );
}
