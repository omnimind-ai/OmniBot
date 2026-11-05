import 'package:go_router/go_router.dart';
import 'package:ui/features/welcome/pages/onboarding/onboarding_choice_page.dart';

/// Optional tutorial routes, opened explicitly by the user.
List<GoRoute> welcomeRoutes = [
  GoRoute(
    path: '/welcome/choice',
    name: 'welcome/choice',
    builder: (context, state) => const OnboardingChoicePage(allowExit: true),
  ),
];
