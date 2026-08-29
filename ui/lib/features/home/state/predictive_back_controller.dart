import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';
import 'package:ui/services/storage_service.dart';

final predictiveBackEnabledProvider =
    StateNotifierProvider<PredictiveBackEnabledController, bool>(
      (ref) => PredictiveBackEnabledController(),
    );

class PredictiveBackEnabledController extends StateNotifier<bool> {
  PredictiveBackEnabledController({bool? initial})
    : super(initial ?? StorageService.isPredictiveBackEnabled());

  Future<bool> setEnabled(bool enabled) async {
    if (state == enabled) {
      return true;
    }

    final previous = state;
    state = enabled;
    final saved = await StorageService.setPredictiveBackEnabled(enabled);
    if (!saved) {
      state = previous;
    }
    return saved;
  }
}
