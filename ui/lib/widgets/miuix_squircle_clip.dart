// Corner geometry adapted from compose-miuix-ui/miuix 0.9.4-rc01,
// miuix-squircle/SquirclePath.kt (Apache-2.0).
// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

import 'dart:math' as math;

import 'package:flutter/widgets.dart';

/// Path equivalent of Miuix's shader silhouette: 1.1 extension, 0.643 control.
/// Flutter's RSuperellipse uses a different curve and does not match this shape.
class MiuixSquircleClipper extends CustomClipper<Path> {
  const MiuixSquircleClipper(this.borderRadius);

  final BorderRadius borderRadius;

  @override
  Path getClip(Size size) {
    final limit = math.min(size.width, size.height) / 2;
    double tile(Radius radius) => (radius.x * 1.1).clamp(0, limit);
    final tl = tile(borderRadius.topLeft);
    final tr = tile(borderRadius.topRight);
    final br = tile(borderRadius.bottomRight);
    final bl = tile(borderRadius.bottomLeft);
    const handle = 1 - 0.643;
    final w = size.width;
    final h = size.height;
    return Path()
      ..moveTo(tl, 0)
      ..lineTo(w - tr, 0)
      ..cubicTo(w - tr * handle, 0, w, tr * handle, w, tr)
      ..lineTo(w, h - br)
      ..cubicTo(w, h - br * handle, w - br * handle, h, w - br, h)
      ..lineTo(bl, h)
      ..cubicTo(bl * handle, h, 0, h - bl * handle, 0, h - bl)
      ..lineTo(0, tl)
      ..cubicTo(0, tl * handle, tl * handle, 0, tl, 0)
      ..close();
  }

  @override
  bool shouldReclip(MiuixSquircleClipper oldClipper) =>
      borderRadius != oldClipper.borderRadius;
}
