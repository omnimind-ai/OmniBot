import 'package:flutter/foundation.dart';

import 'onboarding_definitions.dart';

/// Tracks the current tutorial page, the back-history stack, and which
/// pagination pages have been visited.
class OnboardingFlowController extends ChangeNotifier {
  TutorialPage _page = TutorialPage.permissions;
  final List<TutorialPage> _pageHistory = <TutorialPage>[];

  /// 1 when navigating forward, -1 when navigating back. Used to pick the
  /// slide direction of the page transition.
  int _transitionDirection = 1;

  final Set<TutorialPage> _visitedTutorialPages = <TutorialPage>{
    TutorialPage.permissions,
  };

  TutorialPage get page => _page;

  int get transitionDirection => _transitionDirection;

  bool get hasHistory => _pageHistory.isNotEmpty;

  bool get isOnNavigationPage => tutorialNavigationPages.contains(_page);

  bool isVisited(TutorialPage page) => _visitedTutorialPages.contains(page);

  int get navigationIndex => tutorialNavigationPages.indexOf(_page);

  /// Navigates to [target], pushing the current page onto the history stack.
  void goTo(TutorialPage target) {
    if (_page == target) return;
    _transitionDirection = 1;
    _pageHistory.add(_page);
    _page = target;
    if (tutorialNavigationPages.contains(target)) {
      _visitedTutorialPages.add(target);
    }
    notifyListeners();
  }

  /// Pops the history stack. Returns false when there is nothing to pop.
  bool goBack() {
    if (_pageHistory.isEmpty) return false;
    _transitionDirection = -1;
    _page = _pageHistory.removeLast();
    notifyListeners();
    return true;
  }

  /// Jumps back to a previously visited pagination page, trimming the
  /// history stack so back-navigation stays consistent.
  void jumpToVisited(TutorialPage target) {
    if (target == _page || !_visitedTutorialPages.contains(target)) return;
    final historyIndex = _pageHistory.lastIndexOf(target);
    if (historyIndex < 0) {
      goTo(target);
      return;
    }
    _transitionDirection =
        tutorialNavigationPages.indexOf(target) < navigationIndex ? -1 : 1;
    _pageHistory.removeRange(historyIndex, _pageHistory.length);
    _page = target;
    notifyListeners();
  }
}
