import 'package:flutter/material.dart';
import 'package:ui/features/home/widgets/home_drawer.dart';

/// The home surface reuses the conversation list's existing data owner.
class ConversationHomePage extends StatelessWidget {
  const ConversationHomePage({super.key, this.active = true});

  final bool active;

  @override
  Widget build(BuildContext context) => HomeDrawer(
    homePage: true,
    embedded: true,
    closeOnNavigate: false,
    active: active,
  );
}
