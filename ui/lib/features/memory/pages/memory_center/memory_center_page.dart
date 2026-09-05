import 'dart:async';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:simple_gradient_text/simple_gradient_text.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/core/mixins/page_lifecycle_mixin.dart';
import 'package:ui/features/memory/models/mem0_memory_item.dart';
import 'package:ui/features/memory/pages/memory_center/widgets/mem0_memory_editor_sheet.dart';
import 'package:ui/features/memory/pages/memory_center/widgets/mem0_memory_section.dart';

import 'package:ui/features/memory/pages/memory_center/widgets/memory_card.dart';
import 'package:ui/features/memory/pages/memory_center/widgets/tag_section.dart';
import 'package:ui/features/memory/services/mem0_memory_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/selection_bottom_bar.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/workspace_memory_service.dart' as workspace_memory;

import '../../models/memory_model.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/features/memory/pages/memory_center/widgets/memory_card_list.dart';
import 'package:ui/theme/app_text_styles.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/widgets/common_app_bar.dart';

/// 记忆建议的前三条记录ID存储key
const String kMemorySuggestionTopThreeIdsKey =
    'memory_suggestion_top_three_ids';

/// 记忆建议的存储key
const String kMemorySuggestionKey = 'memory_suggestion';

class MemoryCenterPage extends StatefulWidget {
  const MemoryCenterPage({super.key});

  @override
  State<MemoryCenterPage> createState() => MemoryCenterPageState();
}

class MemoryCenterPageState extends State<MemoryCenterPage>
    with
        SingleTickerProviderStateMixin,
        WidgetsBindingObserver,
        PageLifecycleMixin<MemoryCenterPage> {
  Set<String> selectedTagIds = {}; // 支持多选（all/type/app）

  List<AppTag> localMemoryTags = [
    // AppTag(
    //   id: 'picture',
    //   label: '识图',
    //   count: 2,
    //   svgPath: 'assets/memory/memory_context_icon_dark.svg',
    //   iconBgColor: const Color(0xFFE6F0FE),
    // ),
    // AppTag(
    //   id: 'note',
    //   label: '笔记',
    //   count: 0,
    //   icon: Icons.dashboard,
    //   iconBgColor: const Color(0xFFFFF3E0),
    // ),
    // AppTag(
    //   id: 'chat',
    //   label: '聊天',
    //   count: 0,
    //   icon: Icons.dashboard,
    //   iconBgColor: const Color(0xFFE6F7FF),
    // ),
    // AppTag(
    //   id: 'document',
    //   label: '文档',
    //   count: 0,
    //   icon: Icons.dashboard,
    //   iconBgColor: const Color(0xFFE6F7ED),
    // ),
    // AppTag(
    //   id: 'video',
    //   label: '视频',
    //   count: 0,
    //   icon: Icons.dashboard,
    //   iconBgColor: const Color(0xFFEFF8E6),
    // ),
  ];

  List<MemoryCardModel> localMemoryCards = [
    // MemoryCardModel(
    //   id: 2,
    //   title: '购物优惠券学习',
    //   description:
    //   '书页间满是陕北的风沙气息，满是普通人在时代浪潮中挣扎、奋斗的喘息声。这部横跨十年的长篇小说，没有惊天动地的英雄史诗，没有跌宕起伏的奇幻剧情，却用孙少安、孙少平兄弟的人生轨迹，写尽了平凡人最动人的生命力 —— 那是在苦难里扎根，在挫折中抬头，在日复一日的奔波里，始终对生活抱有热望的力量。书页间满是陕北的风沙气息，满是普通人在时代浪潮中挣扎、奋斗的喘息声。这部横跨十年的长篇小说，没有惊天动地的英雄史诗，没有跌宕起伏的奇幻剧情，却用孙少安、孙少平兄弟的人生轨迹，写尽了平凡人最动人的生命力 —— 那是在苦难里扎根，在挫折中抬头，在日复一日的奔波里，始终对生活抱有热望的力量。\n书页间满是陕北的风沙气息，满是普通人在时代浪潮中挣扎、奋斗的喘息声。这部横跨十年的长篇小说，没有惊天动地的英雄史诗，没有跌宕起伏的奇幻剧情，却用孙少安、孙少平兄弟的人生轨迹，写尽了平凡人最动人的生命力 —— 那是在苦难里扎根，在挫折中抬头，在日复一日的奔波里，始终对生活抱有热望的力量。\n书页间满是陕北的风沙气息，满是普通人在时代浪潮中挣扎、奋斗的喘息声。这部横跨十年的长篇小说，没有惊天动地的英雄史诗，没有跌宕起伏的奇幻剧情，却用孙少安、孙少平兄弟的人生轨迹，写尽了平凡人最动人的生命力 —— 那是在苦难里扎根，在挫折中抬头，在日复一日的奔波里，始终对生活抱有热望的力量。\n书页间满是陕北的风沙气息，满是普通人在时代浪潮中挣扎、奋斗的喘息声。这部横跨十年的长篇小说，没有惊天动地的英雄史诗，没有跌宕起伏的奇幻剧情，却用孙少安、孙少平兄弟的人生轨迹，写尽了平凡人最动人的生命力 —— 那是在苦难里扎根，在挫折中抬头，在日复一日的奔波里，始终对生活抱有热望的力量。',
    //   // 时间戳表示时间
    //   createdAt: 1694857200000,
    //   updatedAt: 1694943600000,
    //   imagePath: 'assets/images/scene1.png',
    //   tags: [
    //     AppTag(
    //       id: 'picture',
    //       label: '识图',
    //       count: 5,
    //       svgPath: 'assets/memory/memory_context_icon.svg',
    //       iconBgColor: const Color(0xFFE6F0FE),
    //     ),
    //   ]
    // ),
  ];

  bool _isLoading = true;
  bool _hasLoadedOnce = false;
  bool _isBlurred = false;
  MemoryCardModel? _longPressedCard;
  Rect? _longPressedCardRect;
  String? _longPressedCardTime;

  // 选择模式状态
  bool _isSelectionMode = false;
  Set<int> _selectedCardIds = {};

  // LLM 生成的记忆建议
  String? _memorySuggestion;
  bool _isSuggestionLoading = false;
  late AnimationController _shimmerController;
  // 用于跟踪上次生成建议时的前三条记录ID
  List<String> _lastTopThreeIds = [];
  Mem0MemorySnapshot _mem0Snapshot = Mem0MemorySnapshot.unconfigured();
  bool _isMem0Loading = false;
  bool _isMem0Mutating = false;
  static const int _localMemoryTab = 0;
  static const int _cloudMemoryTab = 1;
  int _currentMemoryTab = _localMemoryTab;
  late PageController _memoryPageController;

  void _safeSetState(void Function() fn) {
    if (!mounted) {
      return;
    }
    setState(fn);
  }

  // ignore: unused_element
  void _onTagSelectionChanged(Set<String> next, String triggerId) {
    _safeSetState(() {
      if (next.contains('all')) {
        if (triggerId != 'all' && next.length > 1) {
          next.remove('all');
        } else if (triggerId == 'all') {
          next = {'all'};
        }
      } else if (next.isEmpty) {
        next = {'all'};
      }
      selectedTagIds = next;
    });
  }

  @override
  void initState() {
    super.initState();

    // 初始化shimmer动画控制器
    _shimmerController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1500),
    )..repeat();
    _memoryPageController = PageController(initialPage: _currentMemoryTab);

    _loadData();
  }

  @override
  void dispose() {
    _memoryPageController.dispose();
    _shimmerController.dispose();
    super.dispose();
  }

  @override
  void onPageResumed() {
    if (_hasLoadedOnce) {
      // 应用从后台返回前台，静默刷新
      print('MemoryCenterPage resumed - reloading data silently');
      _loadData(silent: true);
    }
  }

  Future<void> refreshData() async {
    print('Refreshing memory center data...');
    await _loadData(forceMem0Refresh: true);
  }

  // 从数据库加载数据
  /// [silent] 是否静默刷新（不显示loading）
  Future<void> _loadData({
    bool silent = false,
    bool forceMem0Refresh = false,
  }) async {
    print('MemoryCenterPage loading data... (silent: $silent)');

    if (!silent) {
      _safeSetState(() {
        _isLoading = true;
      });
    }

    try {
      await _loadShortMemories();
      await _loadMem0Memories(forceRefresh: forceMem0Refresh);
      await _loadMemorySuggestion();

      _safeSetState(() {
        if (!silent) {
          _isLoading = false;
        }
        selectedTagIds = localMemoryTags.isNotEmpty
            ? {localMemoryTags[0].id}
            : {};
      });
      _hasLoadedOnce = true;
    } catch (e) {
      print('Error loading data: $e');
      if (!silent) {
        _safeSetState(() {
          _isLoading = false;
        });
      }
    }
  }

  Future<void> _loadMem0Memories({bool forceRefresh = false}) async {
    if (_isMem0Loading) {
      return;
    }

    final shouldShowLoading = !_mem0Snapshot.hasData || forceRefresh;
    if (shouldShowLoading) {
      _safeSetState(() {
        _isMem0Loading = true;
      });
    }

    try {
      final snapshot = await Mem0MemoryService.getMemories(
        forceRefresh: forceRefresh,
      );
      _safeSetState(() {
        _mem0Snapshot = snapshot;
      });
    } catch (e) {
      _safeSetState(() {
        _mem0Snapshot = Mem0MemorySnapshot(
          configured: true,
          errorMessage: 'Long-term memory load failed: $e',
        );
      });
    } finally {
      _safeSetState(() {
        _isMem0Loading = false;
      });
    }
  }

  Future<void> _loadMemorySuggestion() async {
    await StorageService.remove(kMemorySuggestionKey);
    await StorageService.remove(kMemorySuggestionTopThreeIdsKey);
    _safeSetState(() {
      _memorySuggestion = null;
      _isSuggestionLoading = false;
      _lastTopThreeIds = <String>[];
    });
    return;
    // 先加载持久化的建议
    String? savedSuggestion;
    try {
      savedSuggestion = StorageService.getString(kMemorySuggestionKey);
      print('加载持久化的记忆建议: $savedSuggestion');
    } catch (e) {
      print('加载记忆建议失败: $e');
    }

    // 检查本地+云端的前三条是否变化，只在变化时生成建议
    final suggestionContext = _buildMemorySuggestionContext();
    final currentTopThreeIds = suggestionContext.ids;

    // 加载持久化的前三条记录ID
    await _loadLastTopThreeIds();

    final hasChanged = _hasTopThreeChanged(currentTopThreeIds);

    if (hasChanged) {
      _lastTopThreeIds = currentTopThreeIds;
      // 保存到持久化存储
      await _saveLastTopThreeIds(currentTopThreeIds);
      if (suggestionContext.records.isEmpty) {
        await StorageService.remove(kMemorySuggestionKey);
        _safeSetState(() {
          _memorySuggestion = null;
          _isSuggestionLoading = false;
        });
        return;
      }
      // 异步生成记忆建议（不阻塞主界面）
      unawaited(_generateMemorySuggestion(suggestionContext.records));
    } else {
      _safeSetState(() {
        _memorySuggestion = _sanitizeMemorySuggestion(savedSuggestion);
        _isSuggestionLoading = false;
        print('记忆建议未变化，使用持久化数据: $_memorySuggestion');
      });
    }
  }

  ({List<String> ids, List<Map<String, String>> records})
  _buildMemorySuggestionContext() {
    final candidates = <Map<String, dynamic>>[];

    for (final card in localMemoryCards) {
      final sortTs = card.updatedAt > 0 ? card.updatedAt : card.createdAt;
      candidates.add({
        'id': 'short:${card.id}',
        'sortTs': sortTs,
        'title': _normalizeSuggestionText(card.title),
        'description': _normalizeSuggestionText(card.description ?? ''),
        'appName': _normalizeSuggestionText(
          card.appName ?? context.l10n.memoryShortTermTitle,
        ),
      });
    }

    for (final item in _mem0Snapshot.items) {
      final memory = _normalizeSuggestionText(item.memory);
      if (memory.isEmpty) continue;
      candidates.add({
        'id': 'cloud:${item.id}',
        'sortTs': item.displayTime?.millisecondsSinceEpoch ?? 0,
        'title': _clipSuggestionText(memory, maxLength: 24),
        'description': memory,
        'appName': context.l10n.memoryLongTermTitle,
      });
    }

    candidates.sort((a, b) {
      final lhs = (a['sortTs'] as int?) ?? 0;
      final rhs = (b['sortTs'] as int?) ?? 0;
      final byTime = rhs.compareTo(lhs);
      if (byTime != 0) {
        return byTime;
      }
      return (a['id'] as String).compareTo((b['id'] as String));
    });

    final topCandidates = candidates.take(3).toList();
    return (
      ids: topCandidates.map((item) => item['id'] as String).toList(),
      records: topCandidates
          .map(
            (item) => <String, String>{
              'title': (item['title'] as String?) ?? '',
              'description': (item['description'] as String?) ?? '',
              'appName': (item['appName'] as String?) ?? '',
            },
          )
          .toList(),
    );
  }

  String _normalizeSuggestionText(String text) {
    return text.replaceAll(RegExp(r'\s+'), ' ').trim();
  }

  String? _sanitizeMemorySuggestion(String? rawText) {
    if (rawText == null) {
      return null;
    }

    final normalized = rawText
        .replaceAll('\r\n', '\n')
        .replaceAll(RegExp(r'[ \t]+'), ' ')
        .replaceAll(RegExp(r'\s*\n\s*'), '\n')
        .trim();
    if (normalized.isEmpty) {
      return null;
    }

    final sanitizedLines = normalized
        .split('\n')
        .map(_trimDanglingSuggestionSuffixAscii)
        .where((line) => line.isNotEmpty)
        .toList();
    if (sanitizedLines.isEmpty) {
      return null;
    }

    final sanitized = sanitizedLines.join('\n').trim();
    if (_looksBrokenMemorySuggestionAscii(sanitized)) {
      return null;
    }
    return sanitized;
  }

  String _trimDanglingSuggestionSuffixAscii(String text) {
    var result = text.trim();
    final trailingPatterns = <RegExp>[
      RegExp(r',\s*(?:a|an|the|and|or|but|to)$', caseSensitive: false),
      RegExp(r"[-\u2013\u2014]\s*[A-Za-z'\u2019]{1,6}$"),
      RegExp(r"\b(?:you['\u2019]?v?)$", caseSensitive: false),
      RegExp(r'[,:;]\s*$'),
    ];

    var changed = true;
    while (changed && result.isNotEmpty) {
      changed = false;
      for (final pattern in trailingPatterns) {
        final next = result.replaceFirst(pattern, '').trimRight();
        if (next != result) {
          result = next;
          changed = true;
        }
      }
    }
    return result.trim();
  }

  bool _looksBrokenMemorySuggestionAscii(String text) {
    return RegExp(
          r"(?:\b(?:a|an|the|and|or|but|to)|\byou['\u2019]?v?)$",
          caseSensitive: false,
        ).hasMatch(text) ||
        RegExp(r'[-\u2013\u2014,:;]$').hasMatch(text);
  }

  /*
  String _trimDanglingSuggestionSuffix(String text) {
    var result = text.trim();
    final trailingPatterns = <RegExp>[
      RegExp(r'[,，]\s*(?:a|an|the|and|or|but|to)$', caseSensitive: false),
      RegExp(r'[-–—]\s*[A-Za-z\'’]{1,6}$'),
      RegExp(r'\b(?:you[\'’]?v?)$', caseSensitive: false),
      RegExp(r'[,:;，、]\s*$'),
    ];

    var changed = true;
    while (changed && result.isNotEmpty) {
      changed = false;
      for (final pattern in trailingPatterns) {
        final next = result.replaceFirst(pattern, '').trimRight();
        if (next != result) {
          result = next;
          changed = true;
        }
      }
    }
    return result.trim();
  }

  bool _looksBrokenMemorySuggestion(String text) {
    return RegExp(
          r'(?:\b(?:a|an|the|and|or|but|to)|\byou[\'’]?v?)$',
          caseSensitive: false,
        ).hasMatch(text) ||
        RegExp(r'[-–—,:;，、]$').hasMatch(text);
  }

  */

  String _trimDanglingSuggestionSuffix(String text) =>
      _trimDanglingSuggestionSuffixAscii(text);

  bool _looksBrokenMemorySuggestion(String text) =>
      _looksBrokenMemorySuggestionAscii(text);

  String _clipSuggestionText(String text, {int maxLength = 24}) {
    if (text.length <= maxLength) {
      return text;
    }
    return '${text.substring(0, maxLength)}...';
  }

  /// 从持久化存储加载上次的前三条记录ID
  Future<void> _loadLastTopThreeIds() async {
    try {
      final savedIds = StorageService.getJson<List<dynamic>>(
        kMemorySuggestionTopThreeIdsKey,
      );
      if (savedIds != null && savedIds.isNotEmpty) {
        _lastTopThreeIds = savedIds
            .map((id) => id.toString().trim())
            .where((id) => id.isNotEmpty)
            .toList();
        print('加载持久化的前三条ID: $_lastTopThreeIds');
      }
    } catch (e) {
      print('加载前三条记录ID失败: $e');
    }
  }

  /// 保存前三条记录ID到持久化存储
  Future<void> _saveLastTopThreeIds(List<String> ids) async {
    try {
      await StorageService.setJson(kMemorySuggestionTopThreeIdsKey, ids);
      print('保存前三条ID到持久化存储: $ids');
    } catch (e) {
      print('保存前三条记录ID失败: $e');
    }
  }

  /// 检查前三条记录是否变化
  bool _hasTopThreeChanged(List<String> currentTopThreeIds) {
    // 如果是第一次加载（_lastTopThreeIds为空），返回true
    if (_lastTopThreeIds.isEmpty) {
      return true;
    }

    // 如果数量不同，说明有变化
    if (currentTopThreeIds.length != _lastTopThreeIds.length) {
      return true;
    }

    // 逐个比较ID，如果有任何不同则返回true
    for (int i = 0; i < currentTopThreeIds.length; i++) {
      if (currentTopThreeIds[i] != _lastTopThreeIds[i]) {
        return true;
      }
    }

    // 完全相同，无需更新
    return false;
  }

  /// 使用 LLM 生成记忆建议
  Future<void> _generateMemorySuggestion(
    List<Map<String, String>> topRecords,
  ) async {
    if (topRecords.isEmpty) {
      _safeSetState(() {
        _isSuggestionLoading = false;
      });
      return;
    }
    _safeSetState(() {
      _isSuggestionLoading = true;
    });

    try {
      final response = await AssistsMessageService.generateMemoryGreeting(
        records: topRecords,
        model: 'scene.compactor.context.chat',
      );

      if (response != null && response.isNotEmpty && mounted) {
        final sanitized = _sanitizeMemorySuggestion(response);
        _safeSetState(() {
          _memorySuggestion = sanitized;
          if (sanitized != null) {
            StorageService.setString(kMemorySuggestionKey, sanitized);
          } else {
            StorageService.remove(kMemorySuggestionKey);
          }
          _isSuggestionLoading = false;
        });
      } else {
        _safeSetState(() {
          _isSuggestionLoading = false;
        });
      }
    } catch (e) {
      print('生成记忆建议失败: $e');
      _safeSetState(() {
        _isSuggestionLoading = false;
      });
    }
  }

  Future<void> _loadShortMemories() async {
    try {
      final items =
          await workspace_memory.WorkspaceMemoryService.getShortMemories();
      final cards = items.map((item) {
        final text = _normalizeShortMemoryText(item.content);
        final isTruncated = text.length > 26;
        final title = isTruncated ? '${text.substring(0, 26)}...' : text;
        final timestamp = item.timestampMillis > 0
            ? item.timestampMillis
            : DateTime.now().millisecondsSinceEpoch;
        return MemoryCardModel(
          id: item.id.hashCode,
          title: title,
          description: isTruncated ? text : null,
          createdAt: timestamp,
          updatedAt: timestamp,
          appName: context.l10n.memoryShortTermTitle,
          appSvgPath: 'assets/memory/memory_context_icon.svg',
          tags: const [],
        );
      }).toList()..sort((a, b) => b.updatedAt.compareTo(a.updatedAt));

      _safeSetState(() {
        localMemoryCards = cards;
        localMemoryTags = [
          AppTag(
            id: 'all',
            label: context.trLegacy('全部'),
            count: 0,
            svgPath: 'assets/common/all_icon.svg',
            iconBgColor: Colors.black,
            iconColor: Colors.white,
          ),
        ];
        selectedTagIds = {'all'};
      });
    } catch (e) {
      print('Error loading short memories: $e');
    }
  }

  String _normalizeShortMemoryText(String raw) {
    final text = raw.trim();
    if (LegacyTextLocalizer.isEnglish) {
      return text;
    }
    final quickLogPrefix = RegExp(r'^Quick log[:：]?\s*');
    if (quickLogPrefix.hasMatch(text)) {
      final content = text.replaceFirst(quickLogPrefix, '');
      return content.isEmpty ? '日志速记' : '日志速记：$content';
    }
    return text;
  }

  // ignore: unused_element
  void _enterSelectionMode(MemoryCardModel vm) async {
    // 进入选择模式，选中当前卡片
    _safeSetState(() {
      _isSelectionMode = true;
      _selectedCardIds.add(vm.id);
    });
  }

  // 切换卡片选中状态
  void _toggleCardSelection(int cardId) {
    _safeSetState(() {
      if (_selectedCardIds.contains(cardId)) {
        _selectedCardIds.remove(cardId);
      } else {
        _selectedCardIds.add(cardId);
      }
    });
  }

  // 退出选择模式
  void _exitSelectionMode() {
    _safeSetState(() {
      _isSelectionMode = false;
      _selectedCardIds.clear();
    });
  }

  // 全选/全不选
  void _toggleSelectAll(List<MemoryCardModel> cards) {
    _safeSetState(() {
      if (_selectedCardIds.length == cards.length) {
        // 已全选，取消全选
        _selectedCardIds.clear();
      } else {
        // 未全选，全选
        _selectedCardIds = cards.map((c) => c.id).toSet();
      }
    });
  }

  Future<void> _batchDeleteSelectedCards() async {
    if (_selectedCardIds.isEmpty) return;
    _exitSelectionMode();
    await _deleteShortMemoryUnsupported(0);
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    // 若selectedId对应tag为空，则显示全部
    // 清理不存在的 tag 选择
    selectedTagIds = selectedTagIds
        .where((id) => localMemoryTags.any((t) => t.id == id))
        .toSet();

    final filteredCards = localMemoryCards;
    final hasMem0Section = _mem0Snapshot.shouldShowSection || _isMem0Loading;

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: _isSelectionMode
          ? _buildSelectionAppBar(filteredCards)
          : CommonAppBar(title: context.l10n.memoryCenterTitle, primary: true),
      body: Stack(
        children: [
          // 主内容区域
          Positioned.fill(
            child: ImageFiltered(
              imageFilter: _isBlurred
                  ? ImageFilter.blur(sigmaX: 10, sigmaY: 10)
                  : ImageFilter.blur(sigmaX: 0, sigmaY: 0),
              child: Stack(
                children: [
                  // Positioned(
                  //   top: 0,
                  //   left: 0,
                  //   right: 0,
                  //   height: 344,
                  //   child: Image.asset(
                  //     'assets/memory/bg.png',
                  //     alignment: Alignment.center,
                  //     height: 344,
                  //     width: double.infinity,
                  //     fit: BoxFit.cover,
                  //   ),
                  // ),
                  SafeArea(
                    top: false,
                    child: Column(
                      children: [
                        // 主内容
                        Expanded(
                          child: _isLoading
                              ? _buildLoadingIndicator()
                              : localMemoryCards.isEmpty && !hasMem0Section
                              ? _buildEmptyState()
                              : _buildContent(filteredCards),
                        ),
                        // 选择模式下的底部删除按钮栏
                        SelectionBottomBar(
                          isActive: _isSelectionMode,
                          onDeletePressed: _selectedCardIds.isNotEmpty
                              ? _batchDeleteSelectedCards
                              : null,
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
          // 模糊时的半透明遮罩层
          if (_isBlurred)
            Positioned.fill(
              child: GestureDetector(
                onTap: () {
                  // 点击遮罩层取消模糊
                  Navigator.of(context).pop();
                },
                child: Container(color: palette.overlayScrim),
              ),
            ),
          // 被长按的卡片（不模糊，显示在遮罩层上方）
          if (_isBlurred &&
              _longPressedCard != null &&
              _longPressedCardRect != null)
            Positioned(
              left: _longPressedCardRect!.left,
              top: _longPressedCardRect!.top,
              width: _longPressedCardRect!.width,
              child: IgnorePointer(
                child: MemoryCard(
                  title: _longPressedCard!.title,
                  description: _longPressedCard!.description,
                  time: _longPressedCardTime ?? '',
                  isFavorite: _longPressedCard!.isFavorite,
                  imagePath: _longPressedCard!.imagePath,
                ),
              ),
            ),
        ],
      ),
    );
  }

  /// 构建Shimmer加载占位符
  Widget _buildShimmerPlaceholder({
    required double width,
    required double height,
    BorderRadius? borderRadius,
  }) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return AnimatedBuilder(
      animation: _shimmerController,
      builder: (context, child) {
        return Container(
          width: width,
          height: height,
          decoration: BoxDecoration(
            borderRadius: borderRadius ?? BorderRadius.circular(4),
            gradient: LinearGradient(
              begin: Alignment.centerLeft,
              end: Alignment.centerRight,
              colors: isDark
                  ? [
                      palette.surfaceSecondary,
                      Color.lerp(
                        palette.surfaceSecondary,
                        palette.accentPrimary,
                        0.18,
                      )!,
                      palette.surfaceSecondary,
                    ]
                  : [
                      Color(0xFF2DA5F0).withValues(alpha: 0.1),
                      Color(0xFF1930D9).withValues(alpha: 0.25),
                      Color(0xFF2DA5F0).withValues(alpha: 0.1),
                    ],
              stops: [0.0, _shimmerController.value, 1.0],
            ),
          ),
        );
      },
    );
  }

  Widget _buildMemorySuggestion() {
    final palette = context.omniPalette;
    // 默认文案
    final defaultText = context.l10n.memoryGreeting;
    // 使用 LLM 生成的建议或默认文案
    final suggestionText = defaultText;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Align(
        alignment: Alignment.centerLeft,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (_isSuggestionLoading)
              // 背景骨架屏
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(height: 2),
                  _buildShimmerPlaceholder(
                    width: 150,
                    height: 20,
                    borderRadius: BorderRadius.circular(4),
                  ),
                  SizedBox(height: 4),
                  _buildShimmerPlaceholder(
                    width: 250,
                    height: 20,
                    borderRadius: BorderRadius.circular(4),
                  ),
                  SizedBox(height: 2),
                ],
              )
            else
              context.isDarkTheme
                  ? Text(
                      suggestionText,
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w500,
                        height: 1.5,
                        color: palette.textPrimary,
                      ),
                    )
                  : GradientText(
                      suggestionText,
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w500,
                        height: 1.5,
                        color: Colors.black,
                      ),
                      colors: [Color(0xFF2DA5F0), Color(0xFF1930D9)],
                    ),
          ],
        ),
      ),
    );
  }

  // 选择模式下的 AppBar
  PreferredSizeWidget _buildSelectionAppBar(
    List<MemoryCardModel> filteredCards,
  ) {
    final palette = context.omniPalette;
    final isAllSelected =
        _selectedCardIds.length == filteredCards.length &&
        filteredCards.isNotEmpty;
    return CommonAppBar(
      primary: true,
      title: context.l10n.memorySelectedCount(_selectedCardIds.length),
      titleStyle: TextStyle(
        fontSize: 17,
        fontWeight: FontWeight.w600,
        color: palette.textPrimary,
        fontFamily: 'SF Pro',
      ),
      leadingWidth: 64,
      leading: TextButton(
        onPressed: _exitSelectionMode,
        child: Text(
          context.trLegacy('取消'),
          style: TextStyle(
            color: palette.accentPrimary,
            fontSize: 14,
            fontWeight: FontWeight.w400,
          ),
        ),
      ),
      actions: [
        SizedBox(
          width: 72,
          child: TextButton(
            onPressed: () => _toggleSelectAll(filteredCards),
            child: Text(
              isAllSelected
                  ? context.l10n.memoryDeselectAll
                  : context.trLegacy('全选'),
              style: TextStyle(
                color: palette.accentPrimary,
                fontSize: 14,
                fontWeight: FontWeight.w400,
              ),
            ),
          ),
        ),
      ],
    );
  }

  void _switchMemoryTab(int tabIndex) {
    if (_isSelectionMode || _currentMemoryTab == tabIndex) {
      return;
    }
    _safeSetState(() {
      _currentMemoryTab = tabIndex;
    });
    _memoryPageController.animateToPage(
      tabIndex,
      duration: const Duration(milliseconds: 240),
      curve: Curves.easeOutCubic,
    );
  }

  Widget _buildMemoryTabSwitcher() {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Container(
        height: 40,
        padding: const EdgeInsets.all(3),
        decoration: BoxDecoration(
          color: isDark ? palette.segmentTrack : palette.surfacePrimary,
          borderRadius: BorderRadius.circular(999),
          border: Border.all(color: palette.borderSubtle),
        ),
        child: Stack(
          children: [
            AnimatedAlign(
              duration: const Duration(milliseconds: 280),
              curve: Curves.easeOutCubic,
              alignment: _currentMemoryTab == _localMemoryTab
                  ? Alignment.centerLeft
                  : Alignment.centerRight,
              child: FractionallySizedBox(
                widthFactor: 0.5,
                child: Container(
                  margin: const EdgeInsets.symmetric(horizontal: 1),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(999),
                    gradient: isDark
                        ? LinearGradient(
                            begin: Alignment.topLeft,
                            end: Alignment.bottomRight,
                            colors: [
                              Color.lerp(
                                palette.surfaceElevated,
                                palette.accentPrimary,
                                0.18,
                              )!,
                              Color.lerp(
                                palette.surfaceSecondary,
                                palette.accentPrimary,
                                0.30,
                              )!,
                            ],
                          )
                        : const LinearGradient(
                            begin: Alignment.topLeft,
                            end: Alignment.bottomRight,
                            colors: [Color(0xFF2DA5F0), Color(0xFF1930D9)],
                          ),
                    boxShadow: isDark
                        ? null
                        : const [
                            BoxShadow(
                              color: Color(0x1F1930D9),
                              blurRadius: 10,
                              offset: Offset(0, 4),
                            ),
                          ],
                    border: isDark
                        ? Border.all(color: palette.borderSubtle)
                        : null,
                  ),
                ),
              ),
            ),
            Row(
              children: [
                _buildMemoryTabButton(
                  label: LegacyTextLocalizer.localize('短期记忆'),
                  tabIndex: _localMemoryTab,
                ),
                _buildMemoryTabButton(
                  label: LegacyTextLocalizer.localize('长期记忆'),
                  tabIndex: _cloudMemoryTab,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildMemoryTabButton({required String label, required int tabIndex}) {
    final palette = context.omniPalette;
    final selected = _currentMemoryTab == tabIndex;

    return Expanded(
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: _isSelectionMode ? null : () => _switchMemoryTab(tabIndex),
        child: Center(
          child: AnimatedScale(
            duration: const Duration(milliseconds: 220),
            curve: Curves.easeOutCubic,
            scale: selected ? 1 : 0.97,
            child: AnimatedDefaultTextStyle(
              duration: const Duration(milliseconds: 220),
              curve: Curves.easeOutCubic,
              style: TextStyle(
                color: selected
                    ? (context.isDarkTheme ? palette.textPrimary : Colors.white)
                    : palette.textSecondary,
                fontSize: AppTextStyles.fontSizeMain,
                fontWeight: selected
                    ? AppTextStyles.fontWeightSemiBold
                    : AppTextStyles.fontWeightMedium,
              ),
              child: Text(label),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildLocalMemoryPage(
    List<MemoryCardModel> filteredCards,
    bool hasLocalMemories,
  ) {
    return MemoryCardList(
      cards: filteredCards,
      header: Column(
        children: [
          const SizedBox(height: 8),
          if (hasLocalMemories)
            Padding(
              padding: const EdgeInsets.fromLTRB(18, 0, 18, 8),
              child: Row(
                children: [
                  Text(
                    LegacyTextLocalizer.localize('短期记忆'),
                    style: TextStyle(
                      color: context.omniPalette.textPrimary,
                      fontSize: AppTextStyles.fontSizeMain,
                      fontWeight: AppTextStyles.fontWeightSemiBold,
                      height: AppTextStyles.lineHeightH2,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    '${filteredCards.length}',
                    style: TextStyle(
                      color: context.omniPalette.textSecondary,
                      fontSize: AppTextStyles.fontSizeSmall,
                      height: AppTextStyles.lineHeightH2,
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
      emptyState: _buildLocalMemoryPlaceholder(),
      onRefresh: () => _loadData(silent: true, forceMem0Refresh: true),
      onEdit: _editShortMemoryUnsupported,
      onDelete: _deleteShortMemoryUnsupported,
      isSelectionMode: _isSelectionMode,
      selectedCardIds: _selectedCardIds,
      onToggleSelection: _toggleCardSelection,
      onLongPress: (_) {},
    );
  }

  Widget _buildCloudMemoryPage(bool hasMem0Section) {
    if (!hasMem0Section) {
      return RefreshIndicator(
        onRefresh: () => _loadMem0Memories(forceRefresh: true),
        child: SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 20),
            child: _buildCloudMemoryPlaceholder(),
          ),
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: () => _loadMem0Memories(forceRefresh: true),
      child: SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        child: Column(
          children: [
            const SizedBox(height: 8),
            Mem0MemorySection(
              isLoading: _isMem0Loading,
              isMutating: _isMem0Mutating,
              snapshot: _mem0Snapshot,
              onRefresh: () => _loadMem0Memories(forceRefresh: true),
              onAddTap: _createMem0Memory,
              onMemoryTap: _showMem0MemoryDetail,
            ),
            const SizedBox(height: 20),
          ],
        ),
      ),
    );
  }

  Widget _buildContent(List<MemoryCardModel> filteredCards) {
    final hasLocalMemories = localMemoryCards.isNotEmpty;
    final hasMem0Section = _mem0Snapshot.shouldShowSection || _isMem0Loading;

    return Column(
      children: [
        ImageFiltered(
          imageFilter: _isSelectionMode
              ? ImageFilter.blur(sigmaX: 10, sigmaY: 10)
              : ImageFilter.blur(sigmaX: 0, sigmaY: 0),
          child: Container(
            width: double.infinity,
            color: context.omniPalette.pageBackground,
            child: Column(
              children: [
                const SizedBox(height: 12),
                _buildMemorySuggestion(),
                const SizedBox(height: 12),
                _buildMemoryTabSwitcher(),
                const SizedBox(height: 12),
              ],
            ),
          ),
        ),
        Expanded(
          child: ClipRect(
            child: PageView(
              controller: _memoryPageController,
              physics: _isSelectionMode
                  ? const NeverScrollableScrollPhysics()
                  : const BouncingScrollPhysics(),
              onPageChanged: (index) {
                _safeSetState(() {
                  _currentMemoryTab = index;
                });
              },
              children: [
                _buildLocalMemoryPage(filteredCards, hasLocalMemories),
                _buildCloudMemoryPage(hasMem0Section),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildCloudMemoryPlaceholder() {
    final palette = context.omniPalette;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: palette.borderSubtle),
        boxShadow: [
          BoxShadow(
            color: palette.shadowColor.withValues(
              alpha: context.isDarkTheme ? 0.30 : 0.08,
            ),
            blurRadius: 16,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            context.l10n.memoryNoLongTerm,
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: AppTextStyles.fontSizeMain,
              fontWeight: AppTextStyles.fontWeightMedium,
            ),
          ),
          SizedBox(height: 6),
          Text(
            context.l10n.memoryNoLongTermDesc,
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: AppTextStyles.fontSizeSmall,
              height: AppTextStyles.lineHeightH2,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildLocalMemoryPlaceholder() {
    final palette = context.omniPalette;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: palette.borderSubtle),
        boxShadow: [
          BoxShadow(
            color: palette.shadowColor.withValues(
              alpha: context.isDarkTheme ? 0.30 : 0.08,
            ),
            blurRadius: 16,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            context.l10n.memoryNoShortTerm,
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: AppTextStyles.fontSizeMain,
              fontWeight: AppTextStyles.fontWeightMedium,
            ),
          ),
          SizedBox(height: 6),
          Text(
            context.l10n.memoryNoShortTermDesc,
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: AppTextStyles.fontSizeSmall,
              height: AppTextStyles.lineHeightH2,
            ),
          ),
        ],
      ),
    );
  }

  // ignore: unused_element
  Widget _buildLocalFilterEmptyState() {
    final palette = context.omniPalette;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: palette.borderSubtle),
        boxShadow: [
          BoxShadow(
            color: palette.shadowColor.withValues(
              alpha: context.isDarkTheme ? 0.30 : 0.08,
            ),
            blurRadius: 16,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            context.l10n.memoryFilteredNoShortTerm,
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: AppTextStyles.fontSizeMain,
              fontWeight: AppTextStyles.fontWeightMedium,
            ),
          ),
          SizedBox(height: 6),
          Text(
            context.l10n.memoryFilteredNoShortTermDesc,
            style: TextStyle(
              color: palette.textSecondary,
              fontSize: AppTextStyles.fontSizeSmall,
              height: AppTextStyles.lineHeightH2,
            ),
          ),
        ],
      ),
    );
  }

  void _showMem0MemoryDetail(Mem0MemoryItem item) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) {
        final palette = context.omniPalette;
        final metadataEntries = item.metadata.entries
            .where((entry) => entry.key != 'categories')
            .where((entry) => entry.value != null)
            .toList();

        return Container(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.of(context).size.height * 0.78,
          ),
          decoration: BoxDecoration(
            color: palette.surfacePrimary,
            borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
          ),
          child: Column(
            children: [
              const SizedBox(height: 12),
              Container(
                width: 42,
                height: 4,
                decoration: BoxDecoration(
                  color: palette.borderStrong,
                  borderRadius: BorderRadius.circular(999),
                ),
              ),
              Expanded(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(20, 20, 20, 28),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        LegacyTextLocalizer.localize('长期记忆'),
                        style: TextStyle(
                          color: palette.accentPrimary,
                          fontSize: AppTextStyles.fontSizeSmall,
                          fontWeight: AppTextStyles.fontWeightMedium,
                        ),
                      ),
                      const SizedBox(height: 10),
                      Text(
                        item.memory,
                        style: TextStyle(
                          color: palette.textPrimary,
                          fontSize: AppTextStyles.fontSizeH3,
                          fontWeight: AppTextStyles.fontWeightSemiBold,
                          height: AppTextStyles.lineHeightH2,
                        ),
                      ),
                      const SizedBox(height: 14),
                      Wrap(
                        spacing: 10,
                        runSpacing: 10,
                        children: [
                          OutlinedButton.icon(
                            onPressed: _isMem0Mutating
                                ? null
                                : () async {
                                    Navigator.of(context).pop();
                                    await Future<void>.delayed(
                                      const Duration(milliseconds: 120),
                                    );
                                    await _editMem0Memory(item);
                                  },
                            style: OutlinedButton.styleFrom(
                              foregroundColor: AppColors.buttonPrimary,
                              side: const BorderSide(color: Color(0x332C7FEB)),
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(12),
                              ),
                              padding: const EdgeInsets.symmetric(
                                horizontal: 12,
                                vertical: 8,
                              ),
                            ),
                            icon: const Icon(Icons.edit_outlined, size: 16),
                            label: Text(context.l10n.memoryEditTitle),
                          ),
                          TextButton.icon(
                            onPressed: _isMem0Mutating
                                ? null
                                : () async {
                                    Navigator.of(context).pop();
                                    await Future<void>.delayed(
                                      const Duration(milliseconds: 120),
                                    );
                                    await _deleteMem0Memory(item);
                                  },
                            style: TextButton.styleFrom(
                              foregroundColor: AppColors.alertRed,
                              padding: const EdgeInsets.symmetric(
                                horizontal: 6,
                                vertical: 8,
                              ),
                            ),
                            icon: const Icon(Icons.delete_outline, size: 16),
                            label: Text(context.trLegacy('删除')),
                          ),
                        ],
                      ),
                      const SizedBox(height: 14),
                      if (item.categories.isNotEmpty)
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: item.categories
                              .map(_buildMem0DetailPill)
                              .toList(),
                        ),
                      if (item.categories.isNotEmpty)
                        const SizedBox(height: 18),
                      _buildMem0DetailRow(context.l10n.memoryIdLabel, item.id),
                      if ((item.userId ?? '').isNotEmpty)
                        _buildMem0DetailRow(
                          context.l10n.skillUser,
                          item.userId!,
                        ),
                      if ((item.agentId ?? '').isNotEmpty)
                        _buildMem0DetailRow('Agent', item.agentId!),
                      if (item.score != null)
                        _buildMem0DetailRow(
                          context.l10n.memoryMatchScore,
                          '${(item.score! * 100).round()}%',
                        ),
                      if (metadataEntries.isNotEmpty) ...[
                        const SizedBox(height: 18),
                        Text(
                          context.l10n.memoryAdditionalInfo,
                          style: TextStyle(
                            color: palette.textPrimary,
                            fontSize: AppTextStyles.fontSizeMain,
                            fontWeight: AppTextStyles.fontWeightSemiBold,
                          ),
                        ),
                        const SizedBox(height: 10),
                        ...metadataEntries.map((entry) {
                          final value =
                              entry.value is List || entry.value is Map
                              ? entry.value.toString()
                              : '${entry.value}';
                          return _buildMem0DetailRow(entry.key, value);
                        }),
                      ],
                    ],
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildMem0DetailPill(String label) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: palette.segmentThumb.withValues(
          alpha: context.isDarkTheme ? 0.72 : 0.9,
        ),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: palette.accentPrimary,
          fontSize: AppTextStyles.fontSizeSmall,
          fontWeight: AppTextStyles.fontWeightMedium,
        ),
      ),
    );
  }

  Future<Mem0MemoryEditorResult?> _showMem0MemoryEditor({
    Mem0MemoryItem? initialItem,
  }) async {
    return showModalBottomSheet<Mem0MemoryEditorResult>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) {
        return Mem0MemoryEditorSheet(
          title: initialItem == null
              ? context.l10n.memoryAddLongTerm
              : context.l10n.memoryEditLongTerm,
          submitLabel: initialItem == null
              ? context.l10n.memorySaveToLongTerm
              : context.l10n.memorySaveChanges,
          initialMemory: initialItem?.memory,
          initialCategories: initialItem?.categories ?? const [],
        );
      },
    );
  }

  Future<void> _createMem0Memory() async {
    if (_isMem0Mutating) {
      return;
    }
    final result = await _showMem0MemoryEditor();
    if (result == null) {
      return;
    }
    await _runMem0Mutation(
      action: () async {
        await Mem0MemoryService.createMemory(
          memory: result.memory,
          categories: result.categories,
        );
      },
      successMessage: context.l10n.memoryLongTermAdded,
    );
  }

  Future<void> _editMem0Memory(Mem0MemoryItem item) async {
    if (_isMem0Mutating) {
      return;
    }
    final result = await _showMem0MemoryEditor(initialItem: item);
    if (result == null) {
      return;
    }
    await _runMem0Mutation(
      action: () async {
        await Mem0MemoryService.updateMemory(
          memoryId: item.id,
          memory: result.memory,
          categories: result.categories,
        );
      },
      successMessage: context.l10n.memorySaveChanges,
    );
  }

  Future<void> _deleteMem0Memory(Mem0MemoryItem item) async {
    if (_isMem0Mutating) {
      return;
    }
    final confirmed = await AppDialog.confirm(
      context,
      title: context.l10n.memoryDeleteLongTermConfirm,
      content:
          '${context.l10n.memoryDeleteWarning}:\n${_clipMem0Memory(item.memory)}',
      confirmText: context.trLegacy('删除'),
      confirmButtonColor: AppColors.alertRed,
    );
    if (confirmed != true) {
      return;
    }
    await _runMem0Mutation(
      action: () async {
        await Mem0MemoryService.deleteMemory(memoryId: item.id);
      },
      successMessage: context.l10n.memoryLongTermDeleted,
    );
  }

  Future<void> _runMem0Mutation({
    required Future<void> Function() action,
    required String successMessage,
  }) async {
    _safeSetState(() {
      _isMem0Mutating = true;
    });
    try {
      await action();
      await _loadMem0Memories(forceRefresh: true);
      showToast(successMessage, type: ToastType.success);
    } catch (e) {
      showToast(
        context.l10n.memoryLongTermFailed(
          e.toString().replaceFirst('Exception: ', ''),
        ),
        type: ToastType.error,
      );
    } finally {
      _safeSetState(() {
        _isMem0Mutating = false;
      });
    }
  }

  void _editShortMemoryUnsupported(String cardTitle, int cardId) {
    showToast(context.l10n.memoryEditDisabled, type: ToastType.warning);
  }

  Future<bool> _deleteShortMemoryUnsupported(int cardId) async {
    showToast(context.l10n.memoryDeleteDisabled, type: ToastType.warning);
    return false;
  }

  String _clipMem0Memory(String memory, {int maxLength = 36}) {
    final text = memory.trim();
    if (text.length <= maxLength) {
      return text;
    }
    return '${text.substring(0, maxLength)}...';
  }

  Widget _buildMem0DetailRow(String label, String value) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 72,
            child: Text(
              label,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: AppTextStyles.fontSizeSmall,
                height: AppTextStyles.lineHeightH2,
              ),
            ),
          ),
          Expanded(
            child: Text(
              value,
              style: TextStyle(
                color: palette.textPrimary,
                fontSize: AppTextStyles.fontSizeMain,
                height: AppTextStyles.lineHeightH2,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildEmptyState() {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          SvgPicture.asset(
            'assets/common/empty_record.svg',
            fit: BoxFit.contain,
            errorBuilder: (ctx, err, stack) => Icon(
              Icons.favorite_border,
              size: 72,
              color: Colors.grey.shade400,
            ),
          ),
          const SizedBox(height: 12),
          Text(
            context.l10n.memoryNoMemories,
            style: TextStyle(
              fontSize: AppTextStyles.fontSizeH3,
              fontWeight: AppTextStyles.fontWeightMedium,
              color: AppColors.primaryBlue,
              height: AppTextStyles.lineHeightH1,
              letterSpacing: AppTextStyles.letterSpacingWide,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            context.l10n.memoryNoMemoriesDesc,
            style: TextStyle(
              fontSize: AppTextStyles.fontSizeMain,
              fontWeight: AppTextStyles.fontWeightRegular,
              color: context.omniPalette.textSecondary,
              height: AppTextStyles.lineHeightH3,
              letterSpacing: AppTextStyles.letterSpacingWide,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildLoadingIndicator() {
    return const Center(
      child: Padding(
        padding: EdgeInsets.all(24.0),
        child: SizedBox(
          width: 32,
          height: 32,
          child: CircularProgressIndicator(strokeWidth: 2.5),
        ),
      ),
    );
  }
}
