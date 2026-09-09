// Motion formulas adapted from compose-miuix-ui/miuix 0.9.4-rc01,
// NavDriver.kt and NavSettleEasing.kt. See ../../third_party/miuix/LICENSE.
// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/physics.dart';
import 'package:ui/services/storage_service.dart';

/// Miuix 0.9.4-rc01 MiuixDefault / NavMotion.Default parameters.
/// The route animation is the page position, in both gesture and settle modes.
abstract final class PredictiveBackMotion {
  static const duration = Duration(milliseconds: 500);
  static const stiffness = 146.0;
  static const dampingRatio = 1.0;
  static const visibilityThreshold = 0.0025;
  static const maxFingerProgress = 0.999;
  static const coveredParallax = 0.25;
  static const coveredAlphaLoss = 0.1;
  static const dimAmount = 0.5;

  static bool get enabled =>
      defaultTargetPlatform == TargetPlatform.android &&
      StorageService.isPredictiveBackEnabled();

  static Simulation programmatic(double start, double target) =>
      _ProgrammaticSimulation(start, target);

  static Simulation spring(double start, double target, double velocity) {
    if (target < start) {
      velocity = math.max(velocity, -math.sqrt(stiffness) * (start - target));
    }
    return SpringSimulation(
      SpringDescription.withDampingRatio(
        mass: 1,
        stiffness: stiffness,
        ratio: dampingRatio,
      ),
      start,
      target,
      velocity,
      snapToEnd: true,
      // Compose's FloatSpringSpec estimates completion from displacement.
      // Requiring a second 0.0025 velocity threshold adds an unwanted long tail.
      tolerance: const Tolerance(
        distance: visibilityThreshold,
        velocity: double.infinity,
      ),
    );
  }
}

/// Fixed-duration Miuix NavProgrammaticEasing, applied from start to target.
/// In particular, a pop is 1 - easing(t), not easing(1 - t).
class _ProgrammaticSimulation extends Simulation {
  _ProgrammaticSimulation(this.start, this.target);

  final double start;
  final double target;
  static const _seconds = 0.5;
  static final _omega = 2 * math.pi / 0.8;
  static final _decay = -0.95 * _omega;
  static final _frequency = _omega * math.sqrt(1 - 0.95 * 0.95);

  @override
  double x(double time) {
    if (time <= 0) return start;
    if (isDone(time)) return target;
    final t = time / _seconds;
    final eased =
        1 +
        math.exp(_decay * t) *
            (-math.cos(_frequency * t) +
                _decay / _frequency * math.sin(_frequency * t));
    return start + (target - start) * eased;
  }

  @override
  double dx(double time) {
    if (time <= 0 || isDone(time)) return 0;
    final t = time / _seconds;
    return (target - start) /
        _seconds *
        math.exp(_decay * t) *
        (_frequency + _decay * _decay / _frequency) *
        math.sin(_frequency * t);
  }

  @override
  bool isDone(double time) => time >= _seconds;
}
