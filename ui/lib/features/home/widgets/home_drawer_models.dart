part of 'home_drawer.dart';

class _DrawerShortcutAction {
  const _DrawerShortcutAction({
    required this.label,
    required this.onTap,
    this.assetPath,
    this.svgString,
  }) : assert(
         assetPath != null || svgString != null,
         'assetPath or svgString is required',
       );

  final String label;
  final VoidCallback onTap;
  final String? assetPath;
  final String? svgString;
}

class _ConversationSection {
  _ConversationSection({
    required this.label,
    required this.sectionKey,
    required this.results,
  });

  final String label;
  final String sectionKey;
  final List<_ConversationSearchResult> results;
}

class _ScheduledConversationGroup {
  const _ScheduledConversationGroup({
    required this.parent,
    required this.children,
    required this.taskCount,
  });

  final ConversationModel parent;
  final List<_ConversationSearchResult> children;
  final int taskCount;
}

class _ConversationSearchIndex {
  const _ConversationSearchIndex({
    required this.signature,
    required this.candidates,
    required this.searchableText,
  });

  final String signature;
  final List<String> candidates;
  final String searchableText;
}

class _ConversationSearchResult {
  const _ConversationSearchResult({
    required this.conversation,
    this.matchedPreview,
  });

  final ConversationModel conversation;
  final String? matchedPreview;

  _ConversationSearchResult copyWith({
    ConversationModel? conversation,
    String? matchedPreview,
  }) {
    return _ConversationSearchResult(
      conversation: conversation ?? this.conversation,
      matchedPreview: matchedPreview ?? this.matchedPreview,
    );
  }
}

class _ConversationImagePreview {
  const _ConversationImagePreview._({
    required this.identity,
    this.path,
    this.url,
    this.bytes,
  });

  factory _ConversationImagePreview.file(String path) {
    return _ConversationImagePreview._(identity: 'file:$path', path: path);
  }

  factory _ConversationImagePreview.network(String url) {
    return _ConversationImagePreview._(identity: 'network:$url', url: url);
  }

  factory _ConversationImagePreview.memory({
    required String identity,
    required Uint8List bytes,
  }) {
    return _ConversationImagePreview._(identity: identity, bytes: bytes);
  }

  final String identity;
  final String? path;
  final String? url;
  final Uint8List? bytes;
}
