import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';

/// Keeps a page header above its body when a control paints past the header.
///
/// Layout, element order and semantics remain a header followed by an expanded
/// body. Only painting and hit testing give the header the foreground position.
class OmniHeaderBody extends Column {
  OmniHeaderBody({super.key, required Widget header, required Widget body})
    : super(
        children: [
          header,
          Expanded(child: body),
        ],
      );

  @override
  RenderFlex createRenderObject(BuildContext context) =>
      _RenderHeaderBody(textDirection: Directionality.maybeOf(context));
}

class _RenderHeaderBody extends RenderFlex {
  _RenderHeaderBody({required super.textDirection})
    : super(direction: Axis.vertical);

  @override
  void defaultPaint(PaintingContext context, Offset offset) {
    for (var child = lastChild; child != null;) {
      final parentData = child.parentData! as FlexParentData;
      context.paintChild(child, offset + parentData.offset);
      child = parentData.previousSibling;
    }
  }

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) {
    for (var child = firstChild; child != null;) {
      final target = child;
      final parentData = target.parentData! as FlexParentData;
      if (result.addWithPaintOffset(
        offset: parentData.offset,
        position: position,
        hitTest: (result, position) =>
            target.hitTest(result, position: position),
      )) {
        return true;
      }
      child = parentData.nextSibling;
    }
    return false;
  }
}
