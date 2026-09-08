import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/chat/chat_page.dart';
import 'package:ui/features/my/pages/account/account_auth_page.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';

import 'onboarding_definitions.dart';
import 'onboarding_environment_controller.dart';
import 'onboarding_flow_controller.dart';
import 'onboarding_l10n.dart';
import 'onboarding_permission_controller.dart';
import 'onboarding_provider_controller.dart';
import 'pages/completion_page.dart';
import 'pages/development_page.dart';
import 'pages/environment_progress_page.dart';
import 'pages/model_inventory_page.dart';
import 'pages/permissions_page.dart';
import 'pages/provider_connection_page.dart';
import 'pages/provider_page.dart';
import 'pages/scene_models_page.dart';
import 'pages/system_page.dart';
import 'pages/tools_page.dart';
import 'widgets/onboarding_common.dart';
import 'widgets/onboarding_footer.dart';

/// First-use tutorial: local environment setup, model provider connection,
/// and scene model roles.
///
/// This widget is a thin orchestration layer — page content lives in
/// `pages/`, reusable UI in `widgets/`, and state in the three controllers.
class OnboardingChoicePage extends StatefulWidget {
  const OnboardingChoicePage({super.key, this.allowExit = false});

  final bool allowExit;

  @override
  State<OnboardingChoicePage> createState() => _OnboardingChoicePageState();
}

class _OnboardingChoicePageState extends State<OnboardingChoicePage> {
  final ScrollController _scrollController = ScrollController();
  final OnboardingFlowController _flow = OnboardingFlowController();
  final OnboardingEnvironmentController _environment =
      OnboardingEnvironmentController();
  final OnboardingProviderController _provider = OnboardingProviderController();
  final OnboardingPermissionController _permission =
      OnboardingPermissionController();

  bool _isReplay = false;
  bool _isCompletingTutorial = false;
  double _horizontalDragDistance = 0;

  String _t(String zh, String en) => onbTr(context, zh, en);

  @override
  void initState() {
    super.initState();
    _isReplay =
        widget.allowExit ||
        (StorageService.getBool(
              StorageKeys.welcomeCompleted,
              defaultValue: false,
            ) ??
            false);
    _flow.addListener(_onControllerChanged);
    _environment.addListener(_onControllerChanged);
    _provider.addListener(_onControllerChanged);
    _permission.addListener(_onControllerChanged);
    _permission.init();
    unawaited(_environment.loadDistribution());
  }

  @override
  void dispose() {
    _flow.removeListener(_onControllerChanged);
    _environment.removeListener(_onControllerChanged);
    _provider.removeListener(_onControllerChanged);
    _permission.removeListener(_onControllerChanged);
    _flow.dispose();
    _environment.dispose();
    _provider.dispose();
    _permission.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _onControllerChanged() {
    if (mounted) setState(() {});
  }

  // -------------------------------------------------------------------------
  // Navigation
  // -------------------------------------------------------------------------

  void _goToPage(TutorialPage page) {
    if (_flow.page == page) return;
    _flow.goTo(page);
    _afterPageChange(page);
  }

  void _jumpToVisitedTutorialPage(TutorialPage page) {
    if (page == _flow.page || !_flow.isVisited(page)) return;
    _flow.jumpToVisited(page);
    _afterPageChange(page);
  }

  void _afterPageChange(TutorialPage page) {
    if (_scrollController.hasClients) {
      _scrollController.jumpTo(0);
    }
    if (page == TutorialPage.provider) {
      unawaited(_provider.loadData(t: _t));
    }
    if (page == TutorialPage.permissions) {
      unawaited(_permission.refresh());
    }
  }

  void _handleBack() {
    if (_environment.isBusy || _provider.busy || _provider.sceneModelsSaving) {
      return;
    }
    if (_flow.goBack()) {
      if (_scrollController.hasClients) {
        _scrollController.jumpTo(0);
      }
      return;
    }
    if (_isReplay) {
      Navigator.of(context).maybePop();
    }
  }

  bool get _showBottomBack => _flow.hasHistory || _isReplay;

  VoidCallback? get _tutorialNextAction {
    return switch (_flow.page) {
      TutorialPage.system when !_environment.isDistributionLoading =>
        () => _goToPage(TutorialPage.development),
      TutorialPage.development => () => _goToPage(TutorialPage.tools),
      TutorialPage.tools => () => _goToPage(TutorialPage.permissions),
      TutorialPage.permissions => () => _goToPage(TutorialPage.provider),
      TutorialPage.provider => () => unawaited(_openChatTour()),
      TutorialPage.providerConnection when _provider.connected =>
        () => _goToPage(TutorialPage.modelInventory),
      TutorialPage.modelInventory when _provider.modelOptions.isNotEmpty =>
        () => _goToPage(TutorialPage.primaryScenes),
      TutorialPage.primaryScenes when _provider.modelOptions.isNotEmpty =>
        () => _goToPage(TutorialPage.memoryScenes),
      _ => null,
    };
  }

  String get _tutorialNextTooltip {
    return switch (_flow.page) {
      TutorialPage.tools => _t('暂不配置环境，先设置模型', 'Set up the environment later'),
      TutorialPage.provider => _t(
        '暂不配置模型，先了解聊天界面',
        'Learn the chat interface first',
      ),
      _ =>
        _tutorialNextAction == null
            ? _t('请先完成本页操作', 'Complete this page first')
            : _t('下一步', 'Next'),
    };
  }

  Key get _tutorialNextKey {
    return switch (_flow.page) {
      TutorialPage.system => const ValueKey('tutorial-system-next'),
      TutorialPage.development => const ValueKey('tutorial-development-next'),
      TutorialPage.tools => const ValueKey('tutorial-skip-environment'),
      TutorialPage.permissions => const ValueKey('tutorial-permissions-next'),
      TutorialPage.provider => const ValueKey('tutorial-skip-models'),
      TutorialPage.modelInventory => const ValueKey('tutorial-models-next'),
      TutorialPage.primaryScenes => const ValueKey(
        'tutorial-primary-scenes-next',
      ),
      _ => const ValueKey('tutorial-page-next'),
    };
  }

  void _goToNextTutorialPage() {
    _tutorialNextAction?.call();
  }

  // -------------------------------------------------------------------------
  // Swipe navigation
  // -------------------------------------------------------------------------

  void _handleTutorialDragStart(DragStartDetails details) {
    _horizontalDragDistance = 0;
  }

  void _handleTutorialDragUpdate(DragUpdateDetails details) {
    _horizontalDragDistance += details.primaryDelta ?? 0;
  }

  void _handleTutorialDragEnd(DragEndDetails details) {
    final velocity = details.primaryVelocity ?? 0;
    final direction = _horizontalDragDistance.abs() >= 64
        ? _horizontalDragDistance
        : velocity.abs() >= 260
        ? velocity
        : 0;
    _horizontalDragDistance = 0;
    if (direction < 0) {
      _goToNextTutorialPage();
    } else if (direction > 0 && _showBottomBack) {
      _handleBack();
    }
  }

  // -------------------------------------------------------------------------
  // Flow orchestration
  // -------------------------------------------------------------------------

  Future<void> _startEnvironmentSetup() async {
    if (_environment.isBusy || _environment.isDistributionLoading) return;
    if (_flow.page != TutorialPage.environmentProgress) {
      _flow.goTo(TutorialPage.environmentProgress);
    }
    await _environment.runSetup(
      t: _t,
      reduceMotion: () =>
          mounted && (MediaQuery.maybeOf(context)?.disableAnimations ?? false),
    );
  }

  Future<void> _configureProvider() async {
    final success = await _provider.configure(t: _t);
    if (success && mounted) {
      _goToPage(TutorialPage.modelInventory);
    }
  }

  Future<void> _openAccountAuth() async {
    if (!mounted) return;
    await Navigator.of(context).push<bool>(
      MaterialPageRoute<bool>(
        builder: (_) => const AccountAuthPage(),
        settings: const RouteSettings(name: 'onboarding-account-auth'),
      ),
    );
  }

  Future<void> _requestShizuku() async {
    if (!mounted) return;
    await ensureShizukuPermission(context);
    if (mounted) {
      await _permission.refresh();
    }
  }

  Future<void> _saveSceneModels() async {
    final saved = await _provider.saveSceneModels(t: _t);
    if (saved && mounted) {
      await _openChatTour();
    }
  }

  Future<void> _openChatTour() async {
    if (!mounted) return;
    final tourCompleted = await Navigator.of(context).push<bool>(
      MaterialPageRoute<bool>(
        builder: (_) => const ChatPage(showFirstUseTour: true),
        settings: const RouteSettings(name: 'first-use-chat-spotlight-tour'),
      ),
    );
    if (tourCompleted == true && mounted) {
      _goToPage(TutorialPage.completion);
    }
  }

  Future<void> _startExploring() async {
    if (_isCompletingTutorial) return;
    setState(() => _isCompletingTutorial = true);
    await StorageService.setBool(StorageKeys.welcomeCompleted, true);
    if (!mounted) return;
    setState(() => _isCompletingTutorial = false);
    GoRouterManager.clearAndNavigateTo('/home/chat');
  }

  // -------------------------------------------------------------------------
  // Build
  // -------------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final reduceMotion =
        MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    final canPop = !_flow.hasHistory && _isReplay;

    return PopScope(
      canPop: canPop,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && (_flow.hasHistory || _isReplay)) {
          _handleBack();
        }
      },
      child: Scaffold(
        backgroundColor: palette.pageBackground,
        body: SafeArea(
          child: Column(
            children: [
              Expanded(
                child: GestureDetector(
                  key: const ValueKey('tutorial-page-swipe-surface'),
                  behavior: HitTestBehavior.translucent,
                  onHorizontalDragStart: _flow.isOnNavigationPage
                      ? _handleTutorialDragStart
                      : null,
                  onHorizontalDragUpdate: _flow.isOnNavigationPage
                      ? _handleTutorialDragUpdate
                      : null,
                  onHorizontalDragEnd: _flow.isOnNavigationPage
                      ? _handleTutorialDragEnd
                      : null,
                  child: AnimatedSwitcher(
                    duration: reduceMotion
                        ? Duration.zero
                        : const Duration(milliseconds: 240),
                    switchInCurve: Curves.easeOutCubic,
                    switchOutCurve: Curves.easeInCubic,
                    transitionBuilder: (child, animation) {
                      final slide =
                          Tween<Offset>(
                            begin: Offset(0.06 * _flow.transitionDirection, 0),
                            end: Offset.zero,
                          ).animate(
                            CurvedAnimation(
                              parent: animation,
                              curve: Curves.easeOutCubic,
                            ),
                          );
                      return FadeTransition(
                        opacity: animation,
                        child: SlideTransition(position: slide, child: child),
                      );
                    },
                    child: KeyedSubtree(
                      key: ValueKey<TutorialPage>(_flow.page),
                      child: _buildCurrentPage(),
                    ),
                  ),
                ),
              ),
              if (_flow.isOnNavigationPage) _buildTutorialFooter(),
              if (_flow.page == TutorialPage.completion)
                _buildCompletionFooter(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildCurrentPage() {
    return switch (_flow.page) {
      TutorialPage.system => OnboardingSystemPage(controller: _environment),
      TutorialPage.development => OnboardingDevelopmentPage(
        controller: _environment,
        scrollController: _scrollController,
      ),
      TutorialPage.tools => OnboardingToolsPage(
        controller: _environment,
        scrollController: _scrollController,
      ),
      TutorialPage.environmentProgress => OnboardingEnvironmentProgressPage(
        controller: _environment,
        onBack: _handleBack,
        onContinue: () => _goToPage(TutorialPage.permissions),
        onRetry: _startEnvironmentSetup,
      ),
      TutorialPage.permissions => OnboardingPermissionsPage(
        controller: _permission,
        scrollController: _scrollController,
        onRequestShizuku: _requestShizuku,
      ),
      TutorialPage.provider => OnboardingProviderPage(
        controller: _provider,
        scrollController: _scrollController,
        onOpenAccount: () => unawaited(_openAccountAuth()),
      ),
      TutorialPage.providerConnection => OnboardingProviderConnectionPage(
        controller: _provider,
        scrollController: _scrollController,
        onConnect: _configureProvider,
      ),
      TutorialPage.modelInventory => OnboardingModelInventoryPage(
        controller: _provider,
        scrollController: _scrollController,
        onAddManualModel: () => _provider.addManualModel(t: _t),
      ),
      TutorialPage.primaryScenes => OnboardingSceneModelsPage(
        controller: _provider,
        scrollController: _scrollController,
        icon: LucideIcons.workflow,
        title: _t('配置对话与 Agent 模型', 'Configure chat and agent models'),
        description: _t(
          '先使用同一个通用模型也没有问题，之后可在“场景模型”设置中调整。',
          'Using one general model for now is fine. You can refine these roles later.',
        ),
        scenes: sceneDefinitions
            .where((scene) => !scene.id.startsWith('scene.memory.'))
            .toList(growable: false),
      ),
      TutorialPage.memoryScenes => OnboardingSceneModelsPage(
        controller: _provider,
        scrollController: _scrollController,
        icon: LucideIcons.database,
        title: _t('配置记忆模型', 'Configure memory models'),
        description: _t(
          '嵌入模型负责检索，整理模型负责归纳长期记忆。',
          'The embedding model powers retrieval, while the rollup model consolidates long-term memory.',
        ),
        scenes: sceneDefinitions
            .where((scene) => scene.id.startsWith('scene.memory.'))
            .toList(growable: false),
        showError: true,
      ),
      TutorialPage.completion => const OnboardingCompletionPage(),
    };
  }

  Widget _buildTutorialFooter() {
    return OnboardingFooter(
      currentPage: _flow.page,
      isVisited: _flow.isVisited,
      canGoBack: _showBottomBack,
      onBack: _handleBack,
      nextKey: _tutorialNextKey,
      nextTooltip: _tutorialNextTooltip,
      onNext: _tutorialNextAction == null ? null : _goToNextTutorialPage,
      onJumpToPage: _jumpToVisitedTutorialPage,
      primaryAction: _buildTutorialPrimaryAction(),
    );
  }

  Widget? _buildTutorialPrimaryAction() {
    return switch (_flow.page) {
      TutorialPage.tools => OnboardingPrimaryButton(
        buttonKey: const ValueKey('tutorial-environment-primary'),
        label: _t('开始配置', 'Start setup'),
        icon: LucideIcons.download,
        onPressed: _environment.isDistributionLoading
            ? null
            : _startEnvironmentSetup,
      ),
      TutorialPage.provider => Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          OnboardingPrimaryButton(
            buttonKey: const ValueKey('tutorial-skip-models-visible'),
            label: _t('跳过模型配置，查看聊天指南', 'Skip model setup and view chat guide'),
            icon: LucideIcons.arrowRight,
            onPressed: _provider.loading ? null : _openChatTour,
          ),
          const SizedBox(height: 2),
          TextButton.icon(
            key: const ValueKey('tutorial-provider-next'),
            onPressed: _provider.loading
                ? null
                : () => _goToPage(TutorialPage.providerConnection),
            icon: const Icon(LucideIcons.settings2, size: 17),
            label: Text(_t('可选：配置自己的模型', 'Optional: configure your own model')),
          ),
        ],
      ),
      TutorialPage.providerConnection => OnboardingPrimaryButton(
        buttonKey: const ValueKey('tutorial-provider-connect'),
        label: _provider.busy
            ? _t('正在连接并读取模型…', 'Connecting and fetching models…')
            : _provider.connected
            ? _t('重新连接并读取模型', 'Reconnect and fetch models')
            : _t('连接并读取模型', 'Connect and fetch models'),
        icon: _provider.connected ? LucideIcons.refreshCw : LucideIcons.plugZap,
        onPressed: _provider.busy ? null : _configureProvider,
      ),
      TutorialPage.memoryScenes => OnboardingPrimaryButton(
        buttonKey: const ValueKey('tutorial-save-scenes'),
        label: _provider.sceneModelsSaving
            ? _t('正在保存场景配置…', 'Saving model roles…')
            : _t('保存并了解聊天界面', 'Save and learn the chat interface'),
        icon: LucideIcons.arrowRight,
        onPressed:
            _provider.sceneModelsSaving ||
                _provider.activeProfile == null ||
                _provider.modelOptions.isEmpty
            ? null
            : _saveSceneModels,
      ),
      _ => null,
    };
  }

  Widget _buildCompletionFooter() {
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 760),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 2, 20, 8),
          child: SizedBox(
            key: const ValueKey('tutorial-completion-footer'),
            height: 58,
            child: Row(
              children: [
                OnboardingArrowButton(
                  buttonKey: const ValueKey('tutorial-bottom-back'),
                  icon: LucideIcons.arrowLeft,
                  tooltip: _t('返回上一步', 'Back'),
                  onPressed: _isCompletingTutorial ? null : _handleBack,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: OnboardingPrimaryButton(
                    buttonKey: const ValueKey('tutorial-start-exploring'),
                    label: _isCompletingTutorial
                        ? _t('正在进入…', 'Opening…')
                        : _t('开始探索', 'Start exploring'),
                    icon: LucideIcons.rocket,
                    onPressed: _isCompletingTutorial ? null : _startExploring,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
