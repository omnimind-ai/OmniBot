import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/permission_section_card.dart';

void main() {
  testWidgets('前往开启 remains tappable on the permission card', (tester) async {
    List<String>? receivedPermissionIds;

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: PermissionSectionCard(
            cardData: const <String, dynamic>{
              'type': 'permission_section',
              'requiredPermissionIds': <String>['accessibility'],
            },
            onRequestAuthorize: (ids) => receivedPermissionIds = ids,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('前往开启'));

    expect(receivedPermissionIds, <String>['accessibility']);
  });
}
