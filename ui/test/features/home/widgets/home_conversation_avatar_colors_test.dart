import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/widgets/home_conversation_avatar_colors.dart';

void main() {
  test('long mixed-mode lists never repeat adjacent colors', () {
    final keys = List.generate(
      1000,
      (index) => '${index.isEven ? 'agent' : 'chat_only'}:$index',
    );
    final colors = HomeConversationAvatarColors.assign(keys);

    expect(colors.length, keys.length);
    for (var index = 1; index < keys.length; index++) {
      expect(colors[keys[index]], isNot(colors[keys[index - 1]]));
    }
    expect(colors.values.toSet().length, greaterThanOrEqualTo(6));
  });

  test('refresh and a new iterable preserve the assigned colors', () {
    final keys = List.generate(100, (index) => 'agent:${index * 17}');
    final first = HomeConversationAvatarColors.assign(keys);

    expect(HomeConversationAvatarColors.assign(List.of(keys)), first);
    expect(HomeConversationAvatarColors.assign(keys.map((key) => key)), first);
    expect(
      HomeConversationAvatarColors.indexFor(keys.first),
      first[keys.first],
    );
  });

  test('mode is part of identity and duplicates do not disturb adjacency', () {
    const keys = ['agent:7', 'chat_only:7', 'openclaw:7'];
    final colors = HomeConversationAvatarColors.assign(keys);

    expect(colors.keys, orderedEquals(keys));
    expect(colors[keys[0]], isNot(colors[keys[1]]));
    expect(colors[keys[1]], isNot(colors[keys[2]]));
    expect(
      HomeConversationAvatarColors.assign([
        keys[0],
        keys[0],
        keys[1],
        keys[0],
        keys[2],
      ]),
      colors,
    );
    expect(HomeConversationAvatarColors.assign(const []), isEmpty);
  });

  test('base colors remain varied before adjacency correction', () {
    final indices = List.generate(
      1000,
      (index) => HomeConversationAvatarColors.indexFor('agent:$index'),
    );
    expect(indices.toSet().length, 8);
    for (var color = 0; color < 8; color++) {
      expect(indices.where((index) => index == color).length, greaterThan(70));
    }
  });

  test(
    'every fill is opaque and keeps dark artwork readable in both themes',
    () {
      const ink = Color(0xFF20242A);
      final inkLuminance = ink.computeLuminance();
      for (final brightness in Brightness.values) {
        final fills = List.generate(
          8,
          (index) => HomeConversationAvatarColors.colorAt(index, brightness),
        );
        expect(fills.toSet().length, 8);
        for (final fill in fills) {
          expect(fill.a, 1);
          final contrast =
              (fill.computeLuminance() + 0.05) / (inkLuminance + 0.05);
          expect(contrast, greaterThanOrEqualTo(4.5));
        }
      }
    },
  );
}
