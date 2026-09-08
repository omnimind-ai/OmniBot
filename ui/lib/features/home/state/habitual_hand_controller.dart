import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';
import 'package:ui/models/habitual_hand.dart';
import 'package:ui/services/storage_service.dart';

final habitualHandProvider =
    StateNotifierProvider<HabitualHandController, HabitualHand>(
      (ref) => HabitualHandController(),
    );

class HabitualHandController extends StateNotifier<HabitualHand> {
  HabitualHandController({HabitualHand? initial})
    : super(initial ?? StorageService.getHabitualHand());

  Future<bool> setHabitualHand(HabitualHand hand) async {
    if (state == hand) {
      return true;
    }

    final previous = state;
    state = hand;
    final saved = await StorageService.setHabitualHand(hand);
    if (!saved) {
      state = previous;
    }
    return saved;
  }
}
