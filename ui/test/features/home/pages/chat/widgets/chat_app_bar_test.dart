import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_widgets.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/agent_brand_icon.dart';
import 'package:ui/widgets/omni_glass.dart';

class _SvgTestAssetBundle extends CachingAssetBundle {
  static final Uint8List _svgBytes = Uint8List.fromList(
    utf8.encode(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
      '<rect width="24" height="24" fill="#000000"/>'
      '</svg>',
    ),
  );

  @override
  Future<ByteData> load(String key) async {
    return ByteData.view(_svgBytes.buffer);
  }

  @override
  Future<String> loadString(String key, {bool cache = true}) async {
    return utf8.decode(_svgBytes);
  }
}

class _ChatAppBarHarness extends StatefulWidget {
  const _ChatAppBarHarness({
    this.showSurfaceSwitcher = true,
    this.petShowing = false,
  });

  final bool showSurfaceSwitcher;
  final bool petShowing;

  @override
  State<_ChatAppBarHarness> createState() => _ChatAppBarHarnessState();
}

class _ChatAppBarHarnessState extends State<_ChatAppBarHarness> {
  ChatIslandDisplayLayer _displayLayer = ChatIslandDisplayLayer.mode;
  ChatSurfaceMode _activeMode = ChatSurfaceMode.normal;
  int _browserTapCount = 0;
  int _envTapCount = 0;
  int _petTapCount = 0;
  int _terminalTapCount = 0;
  late bool _petShowing = widget.petShowing;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: DefaultAssetBundle(
        bundle: _SvgTestAssetBundle(),
        child: Scaffold(
          body: Column(
            children: [
              ChatAppBar(
                onMenuTap: () {},
                onPetTap: () {
                  setState(() {
                    _petTapCount += 1;
                    _petShowing = !_petShowing;
                  });
                },
                isPetShowing: _petShowing,
                activeMode: _activeMode,
                onModeChanged: (value) {
                  setState(() {
                    _activeMode = value;
                  });
                },
                displayLayer: _displayLayer,
                onDisplayLayerChanged: (value) {
                  setState(() {
                    _displayLayer = value;
                  });
                },
                onTerminalEnvironmentTap: (_) {
                  setState(() {
                    _envTapCount += 1;
                  });
                },
                onTerminalTap: () {
                  setState(() {
                    _terminalTapCount += 1;
                  });
                },
                onBrowserTap: () {
                  setState(() {
                    _browserTapCount += 1;
                  });
                },
                hasTerminalEnvironment: true,
                isBrowserEnabled: false,
                activeToolType: null,
                showSurfaceSwitcher: widget.showSurfaceSwitcher,
              ),
              Text('active:${_activeMode.name}'),
              Text('layer:${_displayLayer.wireName}'),
              Text('browserTaps:$_browserTapCount'),
              Text('envTaps:$_envTapCount'),
              Text('petTaps:$_petTapCount'),
              Text('petShowing:$_petShowing'),
              Text('terminalTaps:$_terminalTapCount'),
            ],
          ),
        ),
      ),
    );
  }
}

class _PureChatToggleHarness extends StatefulWidget {
  const _PureChatToggleHarness({
    this.selected = false,
    this.locked = false,
    this.showOmniAiTapCount = false,
    this.includeAgent = true,
    this.translucent = false,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
  });

  final bool selected;
  final bool locked;
  final bool showOmniAiTapCount;
  final bool includeAgent;
  final bool translucent;
  final AppBackgroundVisualProfile visualProfile;

  @override
  State<_PureChatToggleHarness> createState() => _PureChatToggleHarnessState();
}

class _PureChatToggleHarnessState extends State<_PureChatToggleHarness> {
  late bool _selected = widget.selected;
  late final bool _locked = widget.locked;
  int _toggleCount = 0;
  int _omniAiTapCount = 0;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: DefaultAssetBundle(
        bundle: _SvgTestAssetBundle(),
        child: Scaffold(
          body: Column(
            children: [
              ChatAppBar(
                onMenuTap: () {},
                onPetTap: () {},
                onOmniAiTap: () {
                  setState(() {
                    _omniAiTapCount += 1;
                  });
                },
                onPureChatToggleTap: () {
                  setState(() {
                    _selected = !_selected;
                    _toggleCount += 1;
                  });
                },
                onAgentTap: () {},
                onAcpAgentTap: widget.includeAgent ? (_) {} : null,
                acpAgentModes: widget.includeAgent
                    ? const <ChatAcpAgentModeOption>[
                        ChatAcpAgentModeOption(id: 'codex-acp', name: 'Codex'),
                      ]
                    : const <ChatAcpAgentModeOption>[],
                activeMode: ChatSurfaceMode.normal,
                onModeChanged: (_) {},
                displayLayer: ChatIslandDisplayLayer.mode,
                onDisplayLayerChanged: (_) {},
                onTerminalEnvironmentTap: (_) {},
                onTerminalTap: () {},
                onBrowserTap: () {},
                showPureChatToggle: true,
                isPureChatSelected: _selected,
                isPureChatToggleLocked: _locked,
                translucent: widget.translucent,
                visualProfile: widget.visualProfile,
              ),
              Text('selected:$_selected'),
              Text('locked:$_locked'),
              Text('toggles:$_toggleCount'),
              if (widget.showOmniAiTapCount)
                Text('omniAiTaps:$_omniAiTapCount'),
            ],
          ),
        ),
      ),
    );
  }
}

class _SurfaceTransitionHarness extends StatefulWidget {
  const _SurfaceTransitionHarness({
    this.applyDelayByMode = const <ChatSurfaceMode, Duration>{},
  });

  final Map<ChatSurfaceMode, Duration> applyDelayByMode;

  @override
  State<_SurfaceTransitionHarness> createState() =>
      _SurfaceTransitionHarnessState();
}

class _SurfaceTransitionHarnessState extends State<_SurfaceTransitionHarness> {
  late final PageController _pageController = PageController(
    initialPage: _pageIndexForSurface(ChatSurfaceMode.openclaw),
  );
  ChatSurfaceMode _activeMode = ChatSurfaceMode.openclaw;
  ChatIslandDisplayLayer _normalDisplayLayer = ChatIslandDisplayLayer.mode;
  int _surfaceSwitchRequestId = 0;

  int _pageIndexForSurface(ChatSurfaceMode mode) => switch (mode) {
    ChatSurfaceMode.normal => 0,
    ChatSurfaceMode.workspace => 1,
    ChatSurfaceMode.openclaw => 2,
  };

  ChatSurfaceMode _surfaceForPageIndex(int pageIndex) => switch (pageIndex) {
    1 => ChatSurfaceMode.workspace,
    2 => ChatSurfaceMode.openclaw,
    _ => ChatSurfaceMode.normal,
  };

  Future<void> _switchMode(
    ChatSurfaceMode targetMode, {
    bool syncPage = true,
  }) async {
    final requestId = ++_surfaceSwitchRequestId;
    bool isStaleRequest() => !mounted || requestId != _surfaceSwitchRequestId;
    if (_activeMode == targetMode) return;

    final delay = widget.applyDelayByMode[targetMode] ?? Duration.zero;
    if (delay > Duration.zero) {
      await Future<void>.delayed(delay);
    }
    if (isStaleRequest()) return;

    setState(() {
      _activeMode = targetMode;
      if (targetMode == ChatSurfaceMode.workspace) {
        _normalDisplayLayer = ChatIslandDisplayLayer.mode;
      }
    });
    if (syncPage) {
      await _pageController.animateToPage(
        _pageIndexForSurface(targetMode),
        duration: const Duration(milliseconds: 240),
        curve: Curves.easeOutCubic,
      );
    }
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final displayLayer = _activeMode == ChatSurfaceMode.normal
        ? _normalDisplayLayer
        : ChatIslandDisplayLayer.mode;
    return MaterialApp(
      home: DefaultAssetBundle(
        bundle: _SvgTestAssetBundle(),
        child: Scaffold(
          body: Column(
            children: [
              ChatAppBar(
                onMenuTap: () {},
                activeMode: _activeMode,
                onModeChanged: (value) {
                  _switchMode(value);
                },
                displayLayer: displayLayer,
                onDisplayLayerChanged: (value) {
                  setState(() {
                    _normalDisplayLayer = value;
                  });
                },
                onTerminalEnvironmentTap: (_) {},
                onTerminalTap: () {},
                onBrowserTap: () {},
                hasTerminalEnvironment: false,
                isBrowserEnabled: true,
                activeToolType: null,
              ),
              Text('active:${_activeMode.name}'),
              Text('layer:${displayLayer.wireName}'),
              TextButton(
                key: const ValueKey('request-normal'),
                onPressed: () {
                  _switchMode(ChatSurfaceMode.normal, syncPage: false);
                },
                child: const Text('request-normal'),
              ),
              TextButton(
                key: const ValueKey('request-openclaw'),
                onPressed: () {
                  _switchMode(ChatSurfaceMode.openclaw, syncPage: false);
                },
                child: const Text('request-openclaw'),
              ),
              TextButton(
                key: const ValueKey('request-workspace'),
                onPressed: () {
                  _switchMode(ChatSurfaceMode.workspace, syncPage: false);
                },
                child: const Text('request-workspace'),
              ),
              Expanded(
                child: NotificationListener<ScrollNotification>(
                  onNotification: (notification) {
                    if (notification.depth != 0 ||
                        notification.metrics.axis != Axis.horizontal) {
                      return false;
                    }
                    if (notification is ScrollEndNotification) {
                      final pageMetrics = notification.metrics;
                      final rawPage = pageMetrics is PageMetrics
                          ? pageMetrics.page
                          : (_pageController.hasClients
                                ? _pageController.page
                                : null);
                      final settledIndex =
                          (rawPage ??
                                  _pageIndexForSurface(_activeMode).toDouble())
                              .round();
                      _switchMode(
                        _surfaceForPageIndex(settledIndex),
                        syncPage: false,
                      );
                    }
                    return false;
                  },
                  child: PageView(
                    controller: _pageController,
                    onPageChanged: (pageIndex) {
                      _switchMode(
                        _surfaceForPageIndex(pageIndex),
                        syncPage: false,
                      );
                    },
                    children: const [
                      ColoredBox(color: Colors.white),
                      ColoredBox(color: Colors.white),
                      ColoredBox(color: Colors.white),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

Future<void> _tapModeSegment(WidgetTester tester, int index) async {
  final slider = find.byType(ChatModeSlider);
  final box = tester.renderObject<RenderBox>(slider);
  final topLeft = box.localToGlobal(Offset.zero);
  final segmentWidth = box.size.width / 2;
  final tapOffset =
      topLeft + Offset(segmentWidth * (index + 0.5), box.size.height / 2);
  await tester.tapAt(tapOffset);
}

Future<void> _pumpSurfaceSwitch(WidgetTester tester) async {
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 260));
}

Finder _hitTestableIslandToolButton(String key) =>
    find.byKey(ValueKey<String>(key)).hitTestable();

void _setTestViewport(WidgetTester tester, Size size) {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = size;
}

void main() {
  testWidgets('keeps dynamic island free of model text in normal chat', (
    tester,
  ) async {
    await tester.pumpWidget(const _ChatAppBarHarness());

    expect(find.text('layer:mode'), findsOneWidget);
    expect(find.text('gpt-5.4'), findsNothing);
    expect(
      _hitTestableIslandToolButton('chat-island-terminal-button'),
      findsNothing,
    );
  });

  testWidgets('keeps pet shortcut between menu and island', (tester) async {
    await tester.pumpWidget(const _PureChatToggleHarness());

    final menuRect = tester.getRect(
      find.byKey(const ValueKey('chat-app-bar-menu-button')),
    );
    final petRect = tester.getRect(
      find.byKey(const ValueKey('chat-app-bar-pet-button')),
    );
    final modeMenuRect = tester.getRect(
      find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
    );
    final islandRect = tester.getRect(
      find.byKey(const ValueKey('chat-app-bar-island')),
    );
    final expectedGapMidpoint = (menuRect.right + islandRect.left) / 2;

    expect(menuRect.right, lessThan(petRect.left));
    expect(petRect.right, lessThan(islandRect.left));
    expect(petRect.center.dx, closeTo(expectedGapMidpoint, 1));
    expect(islandRect.right, lessThan(modeMenuRect.left));
  });

  testWidgets('pet shortcut only invokes its pet callback', (tester) async {
    await tester.pumpWidget(const _ChatAppBarHarness());

    await tester.tap(find.byKey(const ValueKey('chat-app-bar-pet-button')));
    await tester.pump();

    expect(find.text('petTaps:1'), findsOneWidget);
  });

  testWidgets('pet shortcut uses theme color while showing and toggles off', (
    tester,
  ) async {
    await tester.pumpWidget(const _ChatAppBarHarness(petShowing: true));

    final petButton = find.byKey(const ValueKey('chat-app-bar-pet-button'));
    final appBarContext = tester.element(find.byType(ChatAppBar));
    Icon petIcon() => tester.widget<Icon>(
      find.descendant(of: petButton, matching: find.byType(Icon)),
    );

    expect(find.text('petShowing:true'), findsOneWidget);
    expect(petIcon().color, appBarContext.omniPalette.accentPrimary);

    await tester.tap(petButton);
    await tester.pump();

    expect(find.text('petTaps:1'), findsOneWidget);
    expect(find.text('petShowing:false'), findsOneWidget);
    expect(petIcon().color, Colors.grey[800]!);
  });

  testWidgets('does not expose a conversation ID copy shortcut', (
    tester,
  ) async {
    await tester.pumpWidget(const _ChatAppBarHarness());

    expect(
      find.byKey(const ValueKey('chat-app-bar-copy-conversation-id-button')),
      findsNothing,
    );
  });

  testWidgets('uses page background when surface switcher is visible', (
    tester,
  ) async {
    await tester.pumpWidget(const _ChatAppBarHarness());

    final appBarContext = tester.element(find.byType(ChatAppBar));
    final rootSurface = tester.widget<ColoredBox>(
      find.byKey(const ValueKey('chat-app-bar-background')),
    );

    expect(rootSurface.color, appBarContext.omniPalette.pageBackground);
  });

  testWidgets('shows workspace restore button before the right mode menu', (
    tester,
  ) async {
    var tapCount = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: Scaffold(
            body: ChatAppBar(
              onMenuTap: () {},
              activeMode: ChatSurfaceMode.normal,
              onModeChanged: (_) {},
              displayLayer: ChatIslandDisplayLayer.mode,
              onDisplayLayerChanged: (_) {},
              onTerminalEnvironmentTap: (_) {},
              onTerminalTap: () {},
              onBrowserTap: () {},
              showPureChatToggle: true,
              showWorkspacePaneButton: true,
              onWorkspacePaneTap: () {
                tapCount += 1;
              },
            ),
          ),
        ),
      ),
    );

    final workspaceButton = find.byKey(
      const ValueKey('chat-app-bar-workspace-pane-button'),
    );
    final island = find.byKey(const ValueKey('chat-app-bar-island'));
    final modeMenu = find.byKey(
      const ValueKey('chat-app-bar-pure-chat-button'),
    );
    final workspaceRect = tester.getRect(workspaceButton);
    final islandRect = tester.getRect(island);
    final modeMenuRect = tester.getRect(modeMenu);

    expect(workspaceButton, findsOneWidget);
    expect(islandRect.right, lessThan(workspaceRect.left));
    expect(workspaceRect.right, lessThanOrEqualTo(modeMenuRect.left));

    await tester.tap(workspaceButton);
    expect(tapCount, 1);
  });

  testWidgets('keeps pet and mode controls clear of island on narrow screens', (
    tester,
  ) async {
    _setTestViewport(tester, const Size(390, 844));
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    await tester.pumpWidget(const _PureChatToggleHarness());

    final petRect = tester.getRect(
      find.byKey(const ValueKey('chat-app-bar-pet-button')),
    );
    final modeMenuRect = tester.getRect(
      find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
    );
    final islandRect = tester.getRect(
      find.byKey(const ValueKey('chat-app-bar-island')),
    );

    expect(petRect.right, lessThanOrEqualTo(islandRect.left));
    expect(islandRect.right, lessThanOrEqualTo(modeMenuRect.left));
  });

  testWidgets('highlights pure chat toggle when selected', (tester) async {
    await tester.pumpWidget(const _PureChatToggleHarness(selected: true));

    final pureChatButton = find.byKey(
      const ValueKey('chat-app-bar-pure-chat-button'),
    );
    final pureChatIcon = tester.widget<SvgPicture>(
      find.descendant(of: pureChatButton, matching: find.byType(SvgPicture)),
    );

    expect(
      pureChatIcon.colorFilter,
      const ColorFilter.mode(Color(0xFF2C7FEB), BlendMode.srcIn),
    );
  });

  testWidgets('respects pure chat toggle lock flag', (tester) async {
    await tester.pumpWidget(const _PureChatToggleHarness(locked: true));

    expect(find.text('selected:false'), findsOneWidget);
    expect(find.text('toggles:0'), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
    );
    await tester.pumpAndSettle();

    expect(find.text('selected:false'), findsOneWidget);
    expect(find.text('toggles:0'), findsOneWidget);
  });

  testWidgets('opens mode menu with Xiaowan and pure chat actions', (
    tester,
  ) async {
    await tester.pumpWidget(
      const _PureChatToggleHarness(
        showOmniAiTapCount: true,
        includeAgent: false,
      ),
    );

    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-omni-ai')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-acp-codex-acp')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-pure-chat')),
      findsOneWidget,
    );
    final omniAiMenuIcon = tester.widget<SvgPicture>(
      find.descendant(
        of: find.byKey(const ValueKey('chat-app-bar-mode-menu-omni-ai')),
        matching: find.byType(SvgPicture),
      ),
    );
    expect(
      omniAiMenuIcon.bytesLoader.toString(),
      contains('assets/home/avatar.svg'),
    );
    expect(omniAiMenuIcon.width, 23);
    expect(omniAiMenuIcon.height, 23);
    final pureChatMenuIcon = tester.widget<SvgPicture>(
      find.descendant(
        of: find.byKey(const ValueKey('chat-app-bar-mode-menu-pure-chat')),
        matching: find.byType(SvgPicture),
      ),
    );
    expect(pureChatMenuIcon.width, 20);
    expect(pureChatMenuIcon.height, 20);
    expect(find.text('Agent 模式'), findsNothing);
    expect(find.text('OmniAi 模式'), findsNothing);
    expect(find.text('纯聊天模式'), findsNothing);

    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-omni-ai')),
    );
    await tester.pumpAndSettle();

    expect(find.text('omniAiTaps:1'), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
    );
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-pure-chat')),
    );
    await tester.pumpAndSettle();

    expect(find.text('selected:true'), findsOneWidget);
    expect(find.text('toggles:1'), findsOneWidget);
  });

  testWidgets(
    'updates ACP Agent availability while the mode menu remains open',
    (tester) async {
      final isAgentLoading = ValueNotifier<bool>(true);
      addTearDown(isAgentLoading.dispose);
      String? selectedAgentId;

      await tester.pumpWidget(
        MaterialApp(
          home: DefaultAssetBundle(
            bundle: _SvgTestAssetBundle(),
            child: ValueListenableBuilder<bool>(
              valueListenable: isAgentLoading,
              builder: (context, loading, _) => Scaffold(
                body: ChatAppBar(
                  onMenuTap: () {},
                  onOmniAiTap: () {},
                  onPureChatToggleTap: () {},
                  onAcpAgentTap: (agentId) {
                    selectedAgentId = agentId;
                  },
                  acpAgentModes: const <ChatAcpAgentModeOption>[
                    ChatAcpAgentModeOption(id: 'codex-acp', name: 'Codex'),
                  ],
                  isAgentLoading: loading,
                  activeMode: ChatSurfaceMode.normal,
                  onModeChanged: (_) {},
                  onDisplayLayerChanged: (_) {},
                  onTerminalEnvironmentTap: (_) {},
                  onTerminalTap: () {},
                  onBrowserTap: () {},
                ),
              ),
            ),
          ),
        ),
      );

      await tester.tap(
        find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
      );
      await tester.pumpAndSettle();

      final agentRow = find.byKey(
        const ValueKey('chat-app-bar-mode-menu-acp-codex-acp'),
      );
      GestureDetector agentGesture() => tester.widget<GestureDetector>(
        find.descendant(of: agentRow, matching: find.byType(GestureDetector)),
      );

      // Harness rows stay actionable while the catalog is refreshing.  The
      // selector cancels superseded handshakes, so a slow Agent must not
      // freeze the whole switcher.
      expect(agentGesture().onTap, isNotNull);

      isAgentLoading.value = false;
      await tester.pumpAndSettle();

      expect(agentRow, findsOneWidget);
      expect(agentGesture().onTap, isNotNull);

      await tester.tap(agentRow);
      await tester.pumpAndSettle();

      expect(selectedAgentId, 'codex-acp');
    },
  );

  testWidgets('shows enabled installed ACP Agents in the mode menu', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: Scaffold(
            body: ChatAppBar(
              onMenuTap: () {},
              onOmniAiTap: () {},
              onPureChatToggleTap: () {},
              onAcpAgentTap: (_) {},
              acpAgentModes: const <ChatAcpAgentModeOption>[
                ChatAcpAgentModeOption(id: 'codex-acp', name: 'Codex'),
                ChatAcpAgentModeOption(
                  id: 'disabled-agent',
                  name: 'Disabled',
                  enabled: false,
                ),
                ChatAcpAgentModeOption(
                  id: 'unchecked-agent',
                  name: 'Unchecked',
                  status: 'unchecked',
                ),
                ChatAcpAgentModeOption(
                  id: 'offline-agent',
                  name: 'Offline',
                  status: 'offline',
                ),
                ChatAcpAgentModeOption(
                  id: 'missing-agent',
                  name: 'Missing',
                  status: 'missing',
                ),
                ChatAcpAgentModeOption(
                  id: 'not-installed-agent',
                  name: 'Not installed',
                  installed: false,
                ),
                ChatAcpAgentModeOption(
                  id: 'codex-remote',
                  name: 'Agent Remote',
                  enabled: false,
                ),
              ],
              activeMode: ChatSurfaceMode.normal,
              onModeChanged: (_) {},
              onDisplayLayerChanged: (_) {},
              onTerminalEnvironmentTap: (_) {},
              onTerminalTap: () {},
              onBrowserTap: () {},
            ),
          ),
        ),
      ),
    );

    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-acp-codex-acp')),
      findsOneWidget,
    );
    for (final agentId in const <String>['unchecked-agent', 'offline-agent']) {
      expect(
        find.byKey(ValueKey('chat-app-bar-mode-menu-acp-$agentId')),
        findsOneWidget,
      );
    }
    for (final agentId in const <String>[
      'disabled-agent',
      'missing-agent',
      'not-installed-agent',
      'codex-remote',
    ]) {
      expect(
        find.byKey(ValueKey('chat-app-bar-mode-menu-acp-$agentId')),
        findsNothing,
      );
    }
    expect(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-acp-generic-agent')),
      findsNothing,
    );
  });

  testWidgets('does not synthesize an Agent when none is available', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: Scaffold(
            body: ChatAppBar(
              onMenuTap: () {},
              onOmniAiTap: () {},
              onAgentTap: () {},
              onPureChatToggleTap: () {},
              acpAgentModes: const <ChatAcpAgentModeOption>[
                ChatAcpAgentModeOption(
                  id: 'unchecked-agent',
                  name: 'Unchecked',
                  installed: false,
                  status: 'unchecked',
                ),
              ],
              activeMode: ChatSurfaceMode.normal,
              onModeChanged: (_) {},
              onDisplayLayerChanged: (_) {},
              onTerminalEnvironmentTap: (_) {},
              onTerminalTap: () {},
              onBrowserTap: () {},
            ),
          ),
        ),
      ),
    );

    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-acp-unchecked-agent')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-acp-generic-agent')),
      findsNothing,
    );
  });

  testWidgets('shows every ACP Agent with its brand icon and selects it', (
    tester,
  ) async {
    String? selectedAgentId;
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: Scaffold(
            body: ChatAppBar(
              onMenuTap: () {},
              onOmniAiTap: () {},
              onPureChatToggleTap: () {},
              onAcpAgentTap: (agentId) {
                selectedAgentId = agentId;
              },
              acpAgentModes: const <ChatAcpAgentModeOption>[
                ChatAcpAgentModeOption(id: 'codex-acp', name: 'Codex'),
                ChatAcpAgentModeOption(
                  id: 'claude-code-acp',
                  name: 'Claude Code',
                ),
                ChatAcpAgentModeOption(id: 'opencode-acp', name: 'OpenCode'),
                ChatAcpAgentModeOption(
                  id: 'agent-remote',
                  name: 'Agent Remote',
                ),
              ],
              activeAcpAgentId: 'codex-acp',
              activeMode: ChatSurfaceMode.normal,
              onModeChanged: (_) {},
              onDisplayLayerChanged: (_) {},
              onTerminalEnvironmentTap: (_) {},
              onTerminalTap: () {},
              onBrowserTap: () {},
            ),
          ),
        ),
      ),
    );

    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
    );
    await tester.pumpAndSettle();

    final omniAiRow = find.byKey(
      const ValueKey('chat-app-bar-mode-menu-omni-ai'),
    );
    final omniAiIconFinder = find.descendant(
      of: omniAiRow,
      matching: find.byType(SvgPicture),
    );
    final omniAiIcon = tester.widget<SvgPicture>(omniAiIconFinder);
    final pureChatIcon = tester.widget<SvgPicture>(
      find.descendant(
        of: find.byKey(const ValueKey('chat-app-bar-mode-menu-pure-chat')),
        matching: find.byType(SvgPicture),
      ),
    );
    expect(omniAiIcon.width, 23);
    expect(omniAiIcon.height, 23);
    final omniAiRowCenter = tester.getCenter(omniAiRow);
    final omniAiIconCenter = tester.getCenter(omniAiIconFinder);
    expect(omniAiIconCenter.dx, closeTo(omniAiRowCenter.dx + 1, 0.01));
    expect(omniAiIconCenter.dy, closeTo(omniAiRowCenter.dy, 0.01));
    expect(pureChatIcon.width, 20);
    expect(pureChatIcon.height, 20);

    for (final agentId in const <String>[
      'codex-acp',
      'claude-code-acp',
      'opencode-acp',
      'agent-remote',
    ]) {
      final row = find.byKey(ValueKey('chat-app-bar-mode-menu-acp-$agentId'));
      expect(row, findsOneWidget);
      final icon = tester.widget<AgentBrandIcon>(
        find.descendant(of: row, matching: find.byType(AgentBrandIcon)),
      );
      expect(icon.agentId, agentId);
      expect(icon.size, switch (agentId) {
        'codex-acp' => 19,
        'claude-code-acp' => 21,
        'opencode-acp' => 22,
        _ => 20,
      });
    }

    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-acp-claude-code-acp')),
    );
    await tester.pumpAndSettle();

    expect(selectedAgentId, 'claude-code-acp');
  });

  testWidgets('keeps a long ACP Agent list scrollable and selectable', (
    tester,
  ) async {
    _setTestViewport(tester, const Size(390, 640));
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });
    String? selectedAgentId;
    final agents = List<ChatAcpAgentModeOption>.generate(
      14,
      (index) => ChatAcpAgentModeOption(
        id: 'custom-agent-$index',
        name: 'Custom Agent $index',
      ),
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ChatAppBar(
            onMenuTap: () {},
            onOmniAiTap: () {},
            onPureChatToggleTap: () {},
            onAcpAgentTap: (agentId) {
              selectedAgentId = agentId;
            },
            acpAgentModes: agents,
            activeAcpAgentId: agents.first.id,
            activeMode: ChatSurfaceMode.normal,
            onModeChanged: (_) {},
            onDisplayLayerChanged: (_) {},
            onTerminalEnvironmentTap: (_) {},
            onTerminalTap: () {},
            onBrowserTap: () {},
          ),
        ),
      ),
    );

    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
    );
    await tester.pumpAndSettle();

    final lastAgent = find.byKey(
      const ValueKey('chat-app-bar-mode-menu-acp-custom-agent-13'),
    );
    expect(lastAgent, findsOneWidget);
    expect(find.byType(SingleChildScrollView), findsOneWidget);
    await tester.ensureVisible(lastAgent);
    await tester.pumpAndSettle();
    await tester.tap(lastAgent);
    await tester.pumpAndSettle();

    expect(selectedAgentId, 'custom-agent-13');
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'uses popup palette colors for unselected modes on a light capsule',
    (tester) async {
      const lightIconsForDarkBackground = AppBackgroundVisualProfile(
        sampledImageLuminance: 0.2,
        effectiveLuminance: 0.2,
        textTone: AppBackgroundTextTone.light,
      );
      await tester.pumpWidget(
        const _PureChatToggleHarness(
          translucent: true,
          visualProfile: lightIconsForDarkBackground,
        ),
      );

      await tester.tap(
        find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
      );
      await tester.pumpAndSettle();

      final capsule = tester.widget<OmniGlassPanel>(
        find.byKey(const ValueKey('chat-app-bar-mode-menu-capsule')),
      );
      final selectedAgentIcon = tester.widget<SvgPicture>(
        find.descendant(
          of: find.byKey(const ValueKey('chat-app-bar-mode-menu-omni-ai')),
          matching: find.byType(SvgPicture),
        ),
      );
      final unselectedAgentIcon = tester.widget<SvgPicture>(
        find.descendant(
          of: find.byKey(
            const ValueKey('chat-app-bar-mode-menu-acp-codex-acp'),
          ),
          matching: find.byType(SvgPicture),
        ),
      );
      final unselectedPureChatIcon = tester.widget<SvgPicture>(
        find.descendant(
          of: find.byKey(const ValueKey('chat-app-bar-mode-menu-pure-chat')),
          matching: find.byType(SvgPicture),
        ),
      );

      expect(capsule.surfaceColor, OmniThemePalette.light.surfaceElevated);
      expect(
        selectedAgentIcon.colorFilter,
        ColorFilter.mode(OmniThemePalette.light.accentPrimary, BlendMode.srcIn),
      );
      expect(
        unselectedAgentIcon.colorFilter,
        ColorFilter.mode(OmniThemePalette.light.textSecondary, BlendMode.srcIn),
      );
      expect(
        unselectedPureChatIcon.colorFilter,
        ColorFilter.mode(OmniThemePalette.light.textSecondary, BlendMode.srcIn),
      );
      expect(
        unselectedAgentIcon.colorFilter,
        isNot(
          ColorFilter.mode(
            lightIconsForDarkBackground.appBarIconColor,
            BlendMode.srcIn,
          ),
        ),
      );
    },
  );

  testWidgets('omits the clipped top highlight on the 40px mode capsule', (
    tester,
  ) async {
    await tester.pumpWidget(const _PureChatToggleHarness());

    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-pure-chat-button')),
    );
    await tester.pumpAndSettle();

    final capsuleFinder = find.byKey(
      const ValueKey('chat-app-bar-mode-menu-capsule'),
    );
    final capsule = tester.widget<OmniGlassPanel>(capsuleFinder);

    expect(tester.getSize(capsuleFinder).width, 40);
    expect(capsule.borderRadius, BorderRadius.circular(20));
    expect(capsule.showTopHighlight, isFalse);
  });

  testWidgets('expands and collapses the mode menu as one anchored capsule', (
    tester,
  ) async {
    await tester.pumpWidget(const _PureChatToggleHarness());

    final trigger = find.byKey(const ValueKey('chat-app-bar-pure-chat-button'));
    final triggerRect = tester.getRect(trigger);

    await tester.tap(trigger);
    await tester.pump();

    final capsule = find.byKey(
      const ValueKey('chat-app-bar-mode-menu-capsule'),
    );
    final closeButton = find.byKey(
      const ValueKey('chat-app-bar-mode-menu-close'),
    );
    final clip = find
        .ancestor(of: capsule, matching: find.byType(ClipRect))
        .first;

    expect(capsule, findsOneWidget);
    expect(find.descendant(of: capsule, matching: closeButton), findsOneWidget);
    for (final action in const <String>[
      'omni-ai',
      'acp-codex-acp',
      'pure-chat',
    ]) {
      expect(
        find.descendant(
          of: capsule,
          matching: find.byKey(ValueKey('chat-app-bar-mode-menu-$action')),
        ),
        findsOneWidget,
      );
    }

    final capsuleRect = tester.getRect(capsule);
    expect(capsuleRect.top, closeTo(triggerRect.top, 0.01));
    expect(capsuleRect.left, closeTo(triggerRect.left, 0.01));
    expect(capsuleRect.right, closeTo(triggerRect.right, 0.01));

    double visibleClipHeight() {
      final clipWidget = tester.widget<ClipRect>(clip);
      return clipWidget.clipper!.getClip(tester.getSize(clip)).height;
    }

    final initialHeight = visibleClipHeight();
    await tester.pump(const Duration(milliseconds: 130));
    final openingHeight = visibleClipHeight();
    await tester.pump(const Duration(milliseconds: 130));
    final expandedHeight = visibleClipHeight();

    expect(openingHeight, greaterThan(initialHeight));
    expect(expandedHeight, greaterThan(openingHeight));

    await tester.tap(closeButton);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 90));

    expect(visibleClipHeight(), lessThan(expandedHeight));

    await tester.pumpAndSettle();
    expect(capsule, findsNothing);
    expect(trigger, findsOneWidget);
  });

  testWidgets('uses chat-left workspace-right surface order', (tester) async {
    await tester.pumpWidget(const _SurfaceTransitionHarness());

    await _tapModeSegment(tester, 1);
    await _pumpSurfaceSwitch(tester);
    expect(find.text('active:workspace'), findsOneWidget);

    await _tapModeSegment(tester, 0);
    await _pumpSurfaceSwitch(tester);
    expect(find.text('active:normal'), findsOneWidget);
  });

  testWidgets('content swipe matches chat-left workspace-right order', (
    tester,
  ) async {
    await tester.pumpWidget(const _SurfaceTransitionHarness());

    await _tapModeSegment(tester, 0);
    await _pumpSurfaceSwitch(tester);
    expect(find.text('active:normal'), findsOneWidget);

    await tester.fling(find.byType(PageView), const Offset(-640, 0), 1200);
    await tester.pumpAndSettle();
    expect(find.text('active:workspace'), findsOneWidget);

    await tester.fling(find.byType(PageView), const Offset(640, 0), 1200);
    await tester.pumpAndSettle();
    expect(find.text('active:normal'), findsOneWidget);
  });

  testWidgets('shows update indicator next to mode menu without direct agent', (
    tester,
  ) async {
    var tapCount = 0;
    var agentTapCount = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: Scaffold(
            body: ChatAppBar(
              onMenuTap: () {},
              activeMode: ChatSurfaceMode.normal,
              onModeChanged: (_) {},
              displayLayer: ChatIslandDisplayLayer.mode,
              onDisplayLayerChanged: (_) {},
              onTerminalEnvironmentTap: (_) {},
              onTerminalTap: () {},
              onBrowserTap: () {},
              showAppUpdateIndicator: true,
              showPureChatToggle: true,
              appUpdateTooltip: '发现新版本 v9.9.9',
              onAgentTap: () {
                agentTapCount += 1;
              },
              onAcpAgentTap: (_) {
                agentTapCount += 1;
              },
              acpAgentModes: const <ChatAcpAgentModeOption>[
                ChatAcpAgentModeOption(id: 'codex-acp', name: 'Codex'),
              ],
              onAppUpdateTap: () {
                tapCount += 1;
              },
            ),
          ),
        ),
      ),
    );

    final indicator = find.byKey(const ValueKey('chat-app-update-button'));
    final agent = find.byKey(const ValueKey('chat-app-agent-button'));
    final modeMenu = find.byKey(
      const ValueKey('chat-app-bar-pure-chat-button'),
    );
    final island = find.byKey(const ValueKey('chat-app-bar-island'));
    expect(indicator, findsOneWidget);
    expect(agent, findsNothing);
    expect(modeMenu, findsOneWidget);
    expect(island, findsOneWidget);
    expect(
      tester.getRect(indicator).right,
      lessThanOrEqualTo(tester.getRect(modeMenu).left),
    );

    await tester.tap(indicator);
    await tester.pumpAndSettle();

    expect(tapCount, 1);

    await tester.tap(modeMenu);
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const ValueKey('chat-app-bar-mode-menu-acp-codex-acp')),
    );
    await tester.pumpAndSettle();

    expect(agentTapCount, 1);
  });

  testWidgets('hides update indicator when no update is available', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: Scaffold(
            body: ChatAppBar(
              onMenuTap: () {},
              activeMode: ChatSurfaceMode.normal,
              onModeChanged: (_) {},
              displayLayer: ChatIslandDisplayLayer.mode,
              onDisplayLayerChanged: (_) {},
              onTerminalEnvironmentTap: (_) {},
              onTerminalTap: () {},
              onBrowserTap: () {},
              showAppUpdateIndicator: false,
              onAppUpdateTap: () {},
            ),
          ),
        ),
      ),
    );

    expect(find.byKey(const ValueKey('chat-app-update-button')), findsNothing);
  });

  testWidgets('tints and enlarges agent icon with theme color when selected', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: Scaffold(
            body: ChatAppBar(
              onMenuTap: () {},
              activeMode: ChatSurfaceMode.normal,
              onModeChanged: (_) {},
              displayLayer: ChatIslandDisplayLayer.mode,
              onDisplayLayerChanged: (_) {},
              onTerminalEnvironmentTap: (_) {},
              onTerminalTap: () {},
              onBrowserTap: () {},
              isAgentReady: true,
              isAgentConnected: true,
              isAgentSelected: true,
              showPureChatToggle: true,
            ),
          ),
        ),
      ),
    );

    final agent = find.byKey(const ValueKey('chat-app-bar-pure-chat-button'));
    final agentIcon = tester.widget<SvgPicture>(
      find.descendant(of: agent, matching: find.byType(SvgPicture)),
    );

    expect(
      agentIcon.colorFilter,
      const ColorFilter.mode(Color(0xFF2C7FEB), BlendMode.srcIn),
    );
    expect(agentIcon.width, 22);
    expect(agentIcon.height, 22);
  });

  testWidgets('keeps the surface slider icon independent from Harness brand', (
    tester,
  ) async {
    Future<void> pumpAppBar({
      bool isOmniAiSelected = false,
      bool isAgentSelected = false,
      bool isPureChatSelected = false,
    }) async {
      await tester.pumpWidget(
        MaterialApp(
          home: DefaultAssetBundle(
            bundle: _SvgTestAssetBundle(),
            child: Scaffold(
              body: ChatAppBar(
                onMenuTap: () {},
                activeMode: ChatSurfaceMode.normal,
                onModeChanged: (_) {},
                displayLayer: ChatIslandDisplayLayer.mode,
                onDisplayLayerChanged: (_) {},
                onTerminalEnvironmentTap: (_) {},
                onTerminalTap: () {},
                onBrowserTap: () {},
                isOmniAiSelected: isOmniAiSelected,
                isAgentSelected: isAgentSelected,
                isPureChatSelected: isPureChatSelected,
              ),
            ),
          ),
        ),
      );
      await tester.pump();
    }

    String primaryIconIdentity() {
      final widget = tester.widget(
        find.byKey(const ValueKey('chat-mode-slider-primary-icon')),
      );
      if (widget is AgentBrandIcon) {
        return 'agent:${widget.agentId}';
      }
      return (widget as SvgPicture).bytesLoader.toString();
    }

    await pumpAppBar(isOmniAiSelected: true);
    expect(primaryIconIdentity(), contains('assets/home/chat/agent.svg'));

    await pumpAppBar(isAgentSelected: true);
    expect(primaryIconIdentity(), contains('assets/home/chat/agent.svg'));

    await pumpAppBar(isPureChatSelected: true);
    expect(primaryIconIdentity(), contains('assets/home/chat/pure_chat.svg'));
  });

  testWidgets('switches island directly between mode and tools layers', (
    tester,
  ) async {
    await tester.pumpWidget(const _ChatAppBarHarness());

    await tester.drag(
      find.byKey(const ValueKey('chat-app-bar-island')),
      const Offset(0, 42),
    );
    await tester.pumpAndSettle();

    expect(find.text('layer:tools'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('chat-island-terminal-button')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('chat-island-terminal-env-button')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('chat-island-browser-button')),
      findsOneWidget,
    );

    final envWidth = tester
        .renderObject<RenderBox>(
          find.byKey(const ValueKey('chat-island-terminal-env-button')),
        )
        .size
        .width;
    final terminalWidth = tester
        .renderObject<RenderBox>(
          find.byKey(const ValueKey('chat-island-terminal-button')),
        )
        .size
        .width;
    final browserWidth = tester
        .renderObject<RenderBox>(
          find.byKey(const ValueKey('chat-island-browser-button')),
        )
        .size
        .width;

    expect(envWidth, moreOrLessEquals(terminalWidth, epsilon: 0.1));
    expect(envWidth, moreOrLessEquals(browserWidth, epsilon: 0.1));

    await tester.tap(
      find.byKey(const ValueKey('chat-island-terminal-env-button')),
    );
    await tester.pumpAndSettle();

    expect(find.text('envTaps:1'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('chat-island-terminal-button')));
    await tester.pumpAndSettle();

    expect(find.text('terminalTaps:1'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('chat-island-browser-button')));
    await tester.pumpAndSettle();

    expect(find.text('browserTaps:0'), findsOneWidget);

    await tester.drag(
      find.byKey(const ValueKey('chat-app-bar-island')),
      const Offset(0, -42),
    );
    await tester.pumpAndSettle();

    expect(find.text('layer:mode'), findsOneWidget);
  });

  testWidgets('hides surface switcher without forcing tools layer', (
    tester,
  ) async {
    await tester.pumpWidget(
      const _ChatAppBarHarness(showSurfaceSwitcher: false),
    );

    expect(find.byType(ChatModeSlider), findsNothing);
    expect(find.text('layer:mode'), findsOneWidget);
    expect(find.text('gpt-5.4'), findsNothing);
    expect(
      find.byKey(const ValueKey('chat-island-single-mode-icon')),
      findsOneWidget,
    );
    expect(
      _hitTestableIslandToolButton('chat-island-terminal-button'),
      findsNothing,
    );

    await tester.drag(
      find.byKey(const ValueKey('chat-app-bar-island')),
      const Offset(0, 42),
    );
    await tester.pumpAndSettle();

    expect(find.text('layer:tools'), findsOneWidget);
    expect(
      _hitTestableIslandToolButton('chat-island-terminal-button'),
      findsOneWidget,
    );

    final appBarContext = tester.element(find.byType(ChatAppBar));
    final rootSurface = tester.widget<ColoredBox>(
      find.byKey(const ValueKey('chat-app-bar-background')),
    );

    expect(rootSurface.color, appBarContext.omniPalette.surfacePrimary);
  });

  testWidgets('normal surface preserves island layer while idle', (
    tester,
  ) async {
    await tester.pumpWidget(const _SurfaceTransitionHarness());

    expect(find.text('active:openclaw'), findsOneWidget);

    await _tapModeSegment(tester, 0);
    await _pumpSurfaceSwitch(tester);

    expect(find.text('active:normal'), findsOneWidget);
    expect(find.text('layer:mode'), findsOneWidget);

    await tester.pump(const Duration(milliseconds: 2500));

    expect(find.text('layer:mode'), findsOneWidget);
  });

  testWidgets('workspace visit resets tool-triggered island layer', (
    tester,
  ) async {
    await tester.pumpWidget(const _SurfaceTransitionHarness());

    await _tapModeSegment(tester, 0);
    await _pumpSurfaceSwitch(tester);
    expect(find.text('active:normal'), findsOneWidget);
    expect(find.text('layer:mode'), findsOneWidget);

    await tester.drag(
      find.byKey(const ValueKey('chat-app-bar-island')),
      const Offset(0, 42),
    );
    await tester.pumpAndSettle();
    expect(find.text('layer:tools'), findsOneWidget);

    await tester.fling(find.byType(PageView), const Offset(-640, 0), 1200);
    await tester.pumpAndSettle();
    expect(find.text('active:workspace'), findsOneWidget);
    expect(find.text('layer:mode'), findsOneWidget);

    await tester.fling(find.byType(PageView), const Offset(640, 0), 1200);
    await tester.pumpAndSettle();
    expect(find.text('active:normal'), findsOneWidget);
    expect(find.text('layer:mode'), findsOneWidget);

    await tester.drag(
      find.byKey(const ValueKey('chat-app-bar-island')),
      const Offset(0, 42),
    );
    await tester.pumpAndSettle();
    expect(find.text('layer:tools'), findsOneWidget);

    await tester.fling(find.byType(PageView), const Offset(-640, 0), 1200);
    await tester.pumpAndSettle();
    expect(find.text('active:workspace'), findsOneWidget);
    expect(find.text('layer:mode'), findsOneWidget);

    await tester.fling(find.byType(PageView), const Offset(640, 0), 1200);
    await tester.pumpAndSettle();
    expect(find.text('active:normal'), findsOneWidget);
    expect(find.text('layer:mode'), findsOneWidget);
  });

  testWidgets('ignores stale async surface switch requests', (tester) async {
    await tester.pumpWidget(
      const _SurfaceTransitionHarness(
        applyDelayByMode: <ChatSurfaceMode, Duration>{
          ChatSurfaceMode.normal: Duration(milliseconds: 120),
        },
      ),
    );

    await tester.tap(find.byKey(const ValueKey('request-normal')));
    await tester.pump(const Duration(milliseconds: 10));
    await tester.tap(find.byKey(const ValueKey('request-openclaw')));
    await tester.pump(const Duration(milliseconds: 140));

    expect(find.text('active:openclaw'), findsOneWidget);
    expect(find.text('layer:mode'), findsOneWidget);
  });
}
