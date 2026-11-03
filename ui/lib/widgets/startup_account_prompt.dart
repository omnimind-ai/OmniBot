import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/my/pages/account/account_page.dart';
import 'package:ui/services/account_service.dart';
import 'package:ui/services/app_update_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';

typedef StartupAccountSessionLoader = Future<AccountSessionState> Function();
typedef StartupVersionRefresh = Future<void> Function();

/// Checks account state once per app launch and presents the shared account
/// form until the user signs in or explicitly chooses not to be reminded.
class StartupAccountPrompt extends StatefulWidget {
  const StartupAccountPrompt({
    super.key,
    required this.child,
    this.navigatorKey,
    this.routeListenable,
    this.loadSession,
    this.refreshVersionPolicy,
  });

  final Widget child;
  final GlobalKey<NavigatorState>? navigatorKey;
  final Listenable? routeListenable;
  final StartupAccountSessionLoader? loadSession;
  final StartupVersionRefresh? refreshVersionPolicy;

  @override
  State<StartupAccountPrompt> createState() => _StartupAccountPromptState();
}

class _StartupAccountPromptState extends State<StartupAccountPrompt> {
  bool _checked = false;

  @override
  void initState() {
    super.initState();
    widget.routeListenable?.addListener(_handleRouteChange);
    _scheduleAccountCheck();
  }

  @override
  void didUpdateWidget(covariant StartupAccountPrompt oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.routeListenable != widget.routeListenable) {
      oldWidget.routeListenable?.removeListener(_handleRouteChange);
      widget.routeListenable?.addListener(_handleRouteChange);
      _scheduleAccountCheck();
    }
  }

  @override
  void dispose() {
    widget.routeListenable?.removeListener(_handleRouteChange);
    super.dispose();
  }

  void _scheduleAccountCheck() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) unawaited(_checkAccount());
    });
  }

  void _handleRouteChange() {
    unawaited(_checkAccount());
  }

  Future<void> _checkAccount() async {
    if (_checked || GoRouterManager.isSubEngine) return;
    final welcomeCompleted =
        StorageService.getBool(
          StorageKeys.welcomeCompleted,
          defaultValue: false,
        ) ??
        false;
    // Keep the launch-level prompt out of the onboarding flow. Do not mark the
    // check as complete yet: navigation after the final tutorial action notifies
    // the route listener and checks the account again in the same app launch.
    if (!welcomeCompleted) return;
    _checked = true;
    final promptDismissed =
        StorageService.getBool(
          StorageKeys.startupAccountPromptDismissed,
          defaultValue: false,
        ) ??
        false;
    if (promptDismissed) return;

    try {
      final refreshVersionPolicy =
          widget.refreshVersionPolicy ??
          () async {
            await AppUpdateService.refreshIfNeeded();
          };
      await refreshVersionPolicy();
      final session =
          await (widget.loadSession ?? AccountService.getSessionState)();
      if (!mounted ||
          !session.configured ||
          !session.credentialStorageAvailable ||
          session.signedIn ||
          !session.cloudServicePolicyKnown ||
          !session.cloudServiceAccessAllowed) {
        return;
      }
      final navigator =
          widget.navigatorKey?.currentState ??
          GoRouterManager.rootNavigatorKey.currentState;
      final navigatorContext = navigator?.context;
      if (navigatorContext == null || !navigatorContext.mounted) return;
      final dialogResult = await showOmniGlassCardSheet<bool>(
        context: navigatorContext,
        barrierDismissible: true,
        fitsContent: true,
        showDragHandle: false,
        builder: (dialogContext) => _StartupAccountCard(
          onAuthenticated: () => Navigator.of(dialogContext).pop(true),
        ),
      );
      if (dialogResult != true) {
        await StorageService.setBool(
          StorageKeys.startupAccountPromptDismissed,
          true,
        );
      }
    } catch (_) {
      // Startup must stay non-blocking when the account or update service is
      // temporarily unavailable.
    }
  }

  @override
  Widget build(BuildContext context) => widget.child;
}

class _StartupAccountCard extends StatelessWidget {
  const _StartupAccountCard({required this.onAuthenticated});

  static const _darkHeader = 'assets/my/atmosphere-dark-satin-02.webp';
  static const _lightHeader = 'assets/my/atmosphere-light-mineral-02.webp';

  final VoidCallback onAuthenticated;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return OmniGlassCardBody(
      key: const ValueKey('startup-account-card'),
      child: LayoutBuilder(
        builder: (context, constraints) {
          final sloganFontSize = (constraints.maxWidth * 0.05).clamp(
            18.0,
            22.0,
          );
          // The sheet owns the insets. Its available height also contracts
          // above the keyboard, leaving more room for the active form.
          final headerHeight = (constraints.maxHeight * 0.18).clamp(
            72.0,
            112.0,
          );
          return AnimatedSize(
            duration: MediaQuery.disableAnimationsOf(context)
                ? Duration.zero
                : const Duration(milliseconds: 220),
            curve: Curves.easeOutCubic,
            alignment: Alignment.bottomCenter,
            child: Column(
              key: const ValueKey('startup-account-card-content'),
              mainAxisSize: MainAxisSize.min,
              children: [
                SizedBox(
                  key: const ValueKey('startup-account-card-header'),
                  height: headerHeight,
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      Image.asset(
                        context.isDarkTheme ? _darkHeader : _lightHeader,
                        key: const ValueKey('startup-account-header-image'),
                        fit: BoxFit.cover,
                        alignment: context.isDarkTheme
                            ? Alignment.centerRight
                            : Alignment.center,
                      ),
                      DecoratedBox(
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                            begin: Alignment.topCenter,
                            end: Alignment.bottomCenter,
                            colors: <Color>[
                              Colors.transparent,
                              (context.isDarkTheme
                                      ? Colors.black
                                      : Colors.white)
                                  .withValues(alpha: 0.32),
                            ],
                            stops: const <double>[0.42, 1],
                          ),
                        ),
                      ),
                      Positioned(
                        top: 12,
                        left: 0,
                        right: 0,
                        child: Center(
                          child: Container(
                            width: 32,
                            height: 4,
                            decoration: BoxDecoration(
                              color: palette.textSecondary.withValues(
                                alpha: 0.3,
                              ),
                              borderRadius: BorderRadius.circular(99),
                            ),
                          ),
                        ),
                      ),
                      Positioned(
                        left: 20,
                        right: 64,
                        bottom: 16,
                        child: _StartupAccountSlogan(fontSize: sloganFontSize),
                      ),
                      Positioned(
                        top: 4,
                        right: 8,
                        child: IconButton(
                          key: const ValueKey('startup-account-close'),
                          tooltip:
                              Localizations.localeOf(context).languageCode ==
                                  'zh'
                              ? '关闭并不再提醒'
                              : 'Close and don\'t remind me again',
                          onPressed: () => Navigator.of(context).pop(false),
                          style: IconButton.styleFrom(
                            foregroundColor: palette.textPrimary,
                            fixedSize: const Size.square(48),
                            padding: const EdgeInsets.all(8),
                            shape: const CircleBorder(),
                          ),
                          icon: Ink(
                            width: 32,
                            height: 32,
                            decoration: BoxDecoration(
                              color: palette.surfacePrimary.withValues(
                                alpha: 0.78,
                              ),
                              shape: BoxShape.circle,
                            ),
                            child: const Icon(LucideIcons.x, size: 18),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                Flexible(
                  child: AccountPage.authOnly(
                    key: const ValueKey('startup-account-auth-form'),
                    showAuthHeading: false,
                    fitAuthContent: true,
                    onAuthenticated: onAuthenticated,
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}

class _StartupAccountSlogan extends StatelessWidget {
  const _StartupAccountSlogan({required this.fontSize});

  static const _duration = Duration(milliseconds: 420);

  final double fontSize;

  @override
  Widget build(BuildContext context) {
    final isDark = context.isDarkTheme;
    final slogan = Semantics(
      header: true,
      child: Text(
        '小万通灵，云启大千',
        key: const ValueKey('startup-account-slogan'),
        maxLines: 1,
        overflow: TextOverflow.fade,
        softWrap: false,
        style: TextStyle(
          color: isDark ? const Color(0xFFF7F5F0) : const Color(0xFF273247),
          fontSize: fontSize,
          fontWeight: FontWeight.w700,
          letterSpacing: 1,
          shadows: <Shadow>[
            Shadow(
              color: (isDark ? Colors.black : Colors.white).withValues(
                alpha: 0.42,
              ),
              blurRadius: 8,
              offset: const Offset(0, 1),
            ),
          ],
        ),
      ),
    );
    final reduceMotion =
        MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    if (reduceMotion) return slogan;
    return TweenAnimationBuilder<double>(
      key: const ValueKey('startup-account-slogan-animation'),
      tween: Tween<double>(begin: 0, end: 1),
      duration: _duration,
      curve: Curves.easeOutCubic,
      child: slogan,
      builder: (context, value, child) => Opacity(
        opacity: value,
        child: Transform.translate(
          offset: Offset(0, 8 * (1 - value)),
          child: child,
        ),
      ),
    );
  }
}
