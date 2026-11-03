// Third-party attribution and license details are in docs/third-party/.
// SPDX-License-Identifier: GPL-3.0-only

import 'package:flutter/widgets.dart';

import 'deferred_push.dart';
import 'drag_back.dart';
import 'keyboard_inset_hold.dart';
import 'page_transition.dart';

/// The shared page entry for GoRouter, including home -> chat.
class OmniPage<T> extends Page<T> {
  const OmniPage({
    required this.child,
    super.key,
    super.name,
    super.arguments,
    super.restorationId,
  });

  final Widget child;

  @override
  Route<T> createRoute(BuildContext context) => OmniPageRoute<T>._page(this);
}

/// The ordinary page route, shared by declarative and imperative pushes.
/// System back commits a normal pop; only pointer drags drive page position.
class OmniPageRoute<T> extends PageRoute<T>
    with
        DragBackRouteMixin<T>,
        DeferredPushRouteMixin<T>,
        SharedXPageRouteMixin<T> {
  OmniPageRoute({
    required WidgetBuilder this._builder,
    super.settings,
    super.fullscreenDialog,
  });

  OmniPageRoute._page(OmniPage<T> page)
    : _builder = null,
      super(settings: page);

  final WidgetBuilder? _builder;

  bool get _reduceMotion =>
      navigator != null &&
      (MediaQuery.maybeDisableAnimationsOf(navigator!.context) ?? false);

  @override
  bool get reduceDragBackMotion => _reduceMotion;

  @override
  Duration get transitionDuration =>
      _reduceMotion ? Duration.zero : SharedXPageTransition.duration;

  @override
  bool sharesMotionWith(Route<dynamic>? route) => route is OmniPageRoute;

  @override
  Color backdropOf(Color surface, {required bool dragging}) =>
      dragging ? const Color(0x00000000) : surface;

  @override
  Widget buildPage(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
  ) {
    // Read the current Page: GoRouter can update a conversation target while
    // retaining its route and ChatPage state. Never capture the initial child.
    final page = settings;
    final child = page is OmniPage<T> ? page.child : _builder!(context);
    return Semantics(
      scopesRoute: true,
      explicitChildNodes: true,
      child: KeyboardInsetHold(child: child),
    );
  }

  @override
  Widget buildTransitions(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) => _reduceMotion
      ? dragBackDetector(child)
      : super.buildTransitions(context, animation, secondaryAnimation, child);
}
