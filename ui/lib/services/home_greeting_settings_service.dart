import 'dart:convert';

import 'package:flutter/widgets.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/storage_service.dart';

const String kHomeGreetingSettingsStorageKey = 'home_greeting_settings';

@immutable
class HomeQuickPrompt {
  final String id;
  final String title;
  final String prompt;
  final String? titleEn;
  final String? promptEn;
  final String iconKey;
  final bool builtIn;

  const HomeQuickPrompt({
    required this.id,
    required this.title,
    required this.prompt,
    this.titleEn,
    this.promptEn,
    required this.iconKey,
    required this.builtIn,
  });

  factory HomeQuickPrompt.fromJson(Map<String, dynamic> json) {
    return HomeQuickPrompt(
      id: (json['id'] ?? '').toString(),
      title: (json['title'] ?? '').toString(),
      prompt: (json['prompt'] ?? '').toString(),
      titleEn: json['titleEn']?.toString(),
      promptEn: json['promptEn']?.toString(),
      iconKey: (json['iconKey'] ?? 'spark').toString(),
      builtIn: json['builtIn'] == true,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'title': title,
      'prompt': prompt,
      if (titleEn != null) 'titleEn': titleEn,
      if (promptEn != null) 'promptEn': promptEn,
      'iconKey': iconKey,
      'builtIn': builtIn,
    };
  }

  HomeQuickPrompt copyWith({
    String? id,
    String? title,
    String? prompt,
    String? titleEn,
    String? promptEn,
    String? iconKey,
    bool? builtIn,
  }) {
    return HomeQuickPrompt(
      id: id ?? this.id,
      title: title ?? this.title,
      prompt: prompt ?? this.prompt,
      titleEn: titleEn ?? this.titleEn,
      promptEn: promptEn ?? this.promptEn,
      iconKey: iconKey ?? this.iconKey,
      builtIn: builtIn ?? this.builtIn,
    );
  }

  String resolveTitle(BuildContext context) {
    final languageCode = Localizations.localeOf(context).languageCode;
    if (languageCode == 'en' && titleEn?.trim().isNotEmpty == true) {
      return titleEn!.trim();
    }
    return LegacyTextLocalizer.localize(
      title.trim(),
      locale: Locale(languageCode),
    );
  }

  String resolvePrompt(BuildContext context) {
    final languageCode = Localizations.localeOf(context).languageCode;
    if (languageCode == 'en' && promptEn?.trim().isNotEmpty == true) {
      return promptEn!.trim();
    }
    return LegacyTextLocalizer.localize(
      prompt.trim(),
      locale: Locale(languageCode),
    );
  }
}

@immutable
class HomeGreetingSettings {
  final bool greetingEnabled;
  final List<HomeQuickPrompt> quickPrompts;
  final List<String> pinnedQuickPromptIds;

  const HomeGreetingSettings({
    required this.greetingEnabled,
    required this.quickPrompts,
    this.pinnedQuickPromptIds = const <String>[],
  });

  static HomeGreetingSettings get defaults => const HomeGreetingSettings(
    greetingEnabled: true,
    quickPrompts: HomeGreetingSettingsService.defaultQuickPrompts,
    pinnedQuickPromptIds: <String>[],
  );

  factory HomeGreetingSettings.fromJson(Map<String, dynamic> json) {
    final rawPrompts = json['quickPrompts'];
    final prompts = rawPrompts is List
        ? rawPrompts
              .whereType<Map>()
              .map(
                (item) =>
                    HomeQuickPrompt.fromJson(Map<String, dynamic>.from(item)),
              )
              .where((item) => item.id.trim().isNotEmpty)
              .toList(growable: false)
        : HomeGreetingSettingsService.defaultQuickPrompts;
    final rawPinnedIds = json['pinnedQuickPromptIds'];
    final pinnedIds = rawPinnedIds is List
        ? rawPinnedIds
              .map((item) => item.toString().trim())
              .where((item) => item.isNotEmpty)
              .take(2)
              .toList(growable: false)
        : const <String>[];
    return HomeGreetingSettings(
      greetingEnabled: json['greetingEnabled'] != false,
      quickPrompts: prompts,
      pinnedQuickPromptIds: pinnedIds,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'greetingEnabled': greetingEnabled,
      'quickPrompts': quickPrompts.map((item) => item.toJson()).toList(),
      'pinnedQuickPromptIds': pinnedQuickPromptIds,
    };
  }

  HomeGreetingSettings copyWith({
    bool? greetingEnabled,
    List<HomeQuickPrompt>? quickPrompts,
    List<String>? pinnedQuickPromptIds,
  }) {
    return HomeGreetingSettings(
      greetingEnabled: greetingEnabled ?? this.greetingEnabled,
      quickPrompts: quickPrompts ?? this.quickPrompts,
      pinnedQuickPromptIds: pinnedQuickPromptIds ?? this.pinnedQuickPromptIds,
    );
  }
}

class HomeGreetingSettingsService {
  static const List<HomeQuickPrompt> defaultQuickPrompts = <HomeQuickPrompt>[
    HomeQuickPrompt(
      id: 'builtin_summarize',
      title: '总结一下',
      titleEn: 'Summarize',
      prompt: '请帮我总结下面内容，并列出关键要点：',
      promptEn:
          'Please summarize the following content and list the key points:',
      iconKey: 'summarize',
      builtIn: true,
    ),
    HomeQuickPrompt(
      id: 'builtin_search',
      title: '帮我查一下',
      titleEn: 'Look Up',
      prompt: '请帮我查一下下面内容，并给出可靠来源和简明结论：',
      promptEn:
          'Please look up the following topic, then provide reliable sources and a concise conclusion:',
      iconKey: 'search',
      builtIn: true,
    ),
    HomeQuickPrompt(
      id: 'builtin_execute',
      title: '执行任务',
      titleEn: 'Execute',
      prompt: '请帮我执行这个任务：',
      promptEn: 'Please help me execute this task:',
      iconKey: 'execute',
      builtIn: true,
    ),
    HomeQuickPrompt(
      id: 'builtin_explore',
      title: '探索想法',
      titleEn: 'Explore',
      prompt: '我想探索一个想法，请先帮我梳理可能方向：',
      promptEn:
          'I want to explore an idea. Please help map possible directions first:',
      iconKey: 'explore',
      builtIn: true,
    ),
    HomeQuickPrompt(
      id: 'builtin_install_minis_skills',
      title: '安装技能',
      titleEn: 'Install Skills',
      prompt: '帮我安装这些skills：https://github.com/OpenMinis/MinisSkills',
      promptEn:
          'Help me install these skills: https://github.com/OpenMinis/MinisSkills',
      iconKey: 'install',
      builtIn: true,
    ),
  ];

  static final ValueNotifier<HomeGreetingSettings> notifier =
      ValueNotifier<HomeGreetingSettings>(HomeGreetingSettings.defaults);

  static bool _loaded = false;

  static Future<void> load() async {
    if (_loaded) {
      return;
    }
    _loaded = true;
    final raw = StorageService.getString(kHomeGreetingSettingsStorageKey);
    if (raw == null || raw.trim().isEmpty) {
      notifier.value = HomeGreetingSettings.defaults;
      return;
    }
    try {
      final decoded = jsonDecode(raw);
      if (decoded is Map<String, dynamic>) {
        notifier.value = HomeGreetingSettings.fromJson(decoded);
      } else if (decoded is Map) {
        notifier.value = HomeGreetingSettings.fromJson(
          Map<String, dynamic>.from(decoded),
        );
      }
    } catch (error) {
      notifier.value = HomeGreetingSettings.defaults;
    }
  }

  static Future<bool> setGreetingEnabled(bool enabled) {
    return _save(notifier.value.copyWith(greetingEnabled: enabled));
  }

  static Future<bool> addQuickPrompt({
    required String title,
    required String prompt,
  }) {
    final normalizedTitle = title.trim();
    final normalizedPrompt = prompt.trim();
    if (normalizedTitle.isEmpty || normalizedPrompt.isEmpty) {
      return Future.value(false);
    }
    final nextPrompt = HomeQuickPrompt(
      id: 'custom_${DateTime.now().microsecondsSinceEpoch}',
      title: normalizedTitle,
      prompt: normalizedPrompt,
      iconKey: 'spark',
      builtIn: false,
    );
    return _save(
      notifier.value.copyWith(
        quickPrompts: [nextPrompt, ...notifier.value.quickPrompts],
      ),
    );
  }

  static Future<bool> updateQuickPrompt(HomeQuickPrompt prompt) {
    final nextPrompt = prompt.copyWith(
      title: prompt.title.trim(),
      prompt: prompt.prompt.trim(),
    );
    if (nextPrompt.title.isEmpty || nextPrompt.prompt.isEmpty) {
      return Future.value(false);
    }
    final nextPrompts = notifier.value.quickPrompts
        .map((item) => item.id == nextPrompt.id ? nextPrompt : item)
        .toList(growable: false);
    return _save(notifier.value.copyWith(quickPrompts: nextPrompts));
  }

  static Future<bool> deleteQuickPrompt(String id) {
    final nextPrompts = notifier.value.quickPrompts
        .where((item) => item.id != id)
        .toList(growable: false);
    final nextPinnedIds = notifier.value.pinnedQuickPromptIds
        .where((item) => item != id)
        .toList(growable: false);
    return _save(
      notifier.value.copyWith(
        quickPrompts: nextPrompts,
        pinnedQuickPromptIds: nextPinnedIds,
      ),
    );
  }

  static Future<bool> resetQuickPrompts() {
    return _save(
      notifier.value.copyWith(
        quickPrompts: defaultQuickPrompts,
        pinnedQuickPromptIds: const <String>[],
      ),
    );
  }

  static Future<bool> togglePinnedQuickPrompt(String id) {
    final normalizedId = id.trim();
    if (normalizedId.isEmpty) {
      return Future.value(false);
    }
    final existingIds = notifier.value.quickPrompts
        .map((prompt) => prompt.id)
        .toSet();
    if (!existingIds.contains(normalizedId)) {
      return Future.value(false);
    }
    final currentPinnedIds = notifier.value.pinnedQuickPromptIds;
    final nextPinnedIds = currentPinnedIds.contains(normalizedId)
        ? currentPinnedIds
              .where((item) => item != normalizedId)
              .toList(growable: false)
        : currentPinnedIds.length >= 2
        ? null
        : <String>[...currentPinnedIds, normalizedId];
    if (nextPinnedIds == null) {
      return Future.value(false);
    }
    return _save(notifier.value.copyWith(pinnedQuickPromptIds: nextPinnedIds));
  }

  static Future<bool> _save(HomeGreetingSettings settings) async {
    final saved = await StorageService.setString(
      kHomeGreetingSettingsStorageKey,
      jsonEncode(settings.toJson()),
    );
    if (saved) {
      notifier.value = settings;
    }
    return saved;
  }
}
