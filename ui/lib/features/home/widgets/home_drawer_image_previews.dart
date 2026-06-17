// ignore_for_file: invalid_use_of_protected_member

part of 'home_drawer.dart';

const int _kConversationImagePreviewPreloadBatchSize = 4;
const int _kConversationImagePreviewSnapshotLimit = 200;
const String _kConversationSnapshotCacheKey = 'home_drawer_snapshot_v1';

extension _HomeDrawerImagePreviews on HomeDrawerState {
  static final RegExp _markdownImageLocationPattern = RegExp(
    r'!\[[^\]]*\]\(\s*([^\s)]+)(?:\s+"[^"]*")?\s*\)',
  );
  static final RegExp _omnibotLinkLocationPattern = RegExp(
    r'\[[^\]]+\]\(\s*(omnibot://[^)\s]+)(?:\s+"[^"]*")?\s*\)',
  );

  void _pruneConversationImagePreviewState(Set<String> visibleThreadKeys) {
    _conversationImagePreviewCache.removeWhere(
      (threadKey, _) => !visibleThreadKeys.contains(threadKey),
    );
    _conversationImagePreviewFutures.removeWhere(
      (threadKey, _) => !visibleThreadKeys.contains(threadKey),
    );
    _conversationImagePreviewSignatures.removeWhere(
      (threadKey, _) => !visibleThreadKeys.contains(threadKey),
    );
    _conversationImagePreviewFailures.removeWhere(
      (threadKey, _) => !visibleThreadKeys.contains(threadKey),
    );
    _rememberConversationImagePreviewCacheSnapshot();
  }

  void _restoreDrawerSnapshotCache() {
    _scheduledTasks = ScheduledTaskStorageService.loadScheduledTasksSync();
    if (!HomeDrawerState._hasConversationSnapshotCache) {
      _hydrateDrawerSnapshotFromStorage();
    }
    if (!HomeDrawerState._hasConversationSnapshotCache) {
      return;
    }
    _restoreDrawerSnapshotFromMemory();
  }

  void _rememberConversationSnapshotCache(
    List<ConversationModel> conversations,
  ) {
    HomeDrawerState._hasConversationSnapshotCache = true;
    HomeDrawerState._conversationSnapshotCache = List<ConversationModel>.from(
      conversations,
    );
    _rememberConversationImagePreviewCacheSnapshot();
  }

  void _syncConversationSnapshotCache() {
    _rememberConversationSnapshotCache(_allConversations);
  }

  void _persistCurrentConversationSnapshot() {
    _scheduleDrawerSnapshotPersist(_allConversations);
  }

  void _scheduleDrawerSnapshotPersist(List<ConversationModel> conversations) {
    final snapshot = List<ConversationModel>.from(conversations);
    unawaited(
      Future<void>(() async {
        await _persistDrawerSnapshot(snapshot);
      }),
    );
  }

  void _restoreDrawerSnapshotFromMemory() {
    _allConversations = List<ConversationModel>.from(
      HomeDrawerState._conversationSnapshotCache,
    );
    _conversationImagePreviewCache
      ..clear()
      ..addEntries(
        HomeDrawerState._conversationImagePreviewCacheSnapshot.entries.map(
          (entry) => MapEntry(
            entry.key,
            List<_ConversationImagePreview>.from(entry.value),
          ),
        ),
      );
    _conversationImagePreviewSignatures
      ..clear()
      ..addAll(HomeDrawerState._conversationImagePreviewSignatureSnapshot);
    _conversationImagePreviewFailures
      ..clear()
      ..addEntries(
        HomeDrawerState._conversationImagePreviewFailureSnapshot.entries.map(
          (entry) => MapEntry(entry.key, Set<String>.from(entry.value)),
        ),
      );
    isLoadingConversations = false;
  }

  void _hydrateDrawerSnapshotFromStorage() {
    try {
      final raw =
          StorageService.getString(
            _kConversationSnapshotCacheKey,
            defaultValue: '',
          )?.trim() ??
          '';
      if (raw.isEmpty) {
        return;
      }
      final decoded = jsonDecode(raw);
      if (decoded is! Map) {
        return;
      }
      final rawConversations = decoded['conversations'];
      if (rawConversations is! List) {
        return;
      }
      final restoredConversations = rawConversations
          .whereType<Map>()
          .map(
            (item) => ConversationModel.fromJson(
              Map<String, dynamic>.from(item.cast<String, dynamic>()),
            ),
          )
          .toList(growable: false);
      final deletedKeys = (StorageService.getStringList(
                ConversationService.deletedConversationKeysKey,
                defaultValue: const <String>[],
              ) ??
              const <String>[])
          .map((key) => key.trim())
          .where((key) => key.isNotEmpty)
          .toSet();
      final hiddenCodexIds = (StorageService.getStringList(
                ConversationService.hiddenCodexConversationIdsKey,
                defaultValue: const <String>[],
              ) ??
              const <String>[])
          .map((id) => id.trim())
          .map(int.tryParse)
          .whereType<int>()
          .toSet();
      final conversations = ConversationService.filterDeletedConversationsSync(
        restoredConversations,
        deletedKeys: deletedKeys,
        hiddenCodexConversationIds: hiddenCodexIds,
      );
      if (conversations.isEmpty) {
        return;
      }
      _hydrateConversationImagePreviewSnapshotsFromDecoded(
        decoded['imagePreviews'],
        conversations,
      );
      _rememberConversationSnapshotCache(conversations);
    } catch (error) {
      debugPrint('[HomeDrawer] Failed to restore drawer snapshot: $error');
    }
  }

  void _rememberConversationImagePreviewCacheSnapshot() {
    HomeDrawerState._conversationImagePreviewCacheSnapshot =
        _conversationImagePreviewCache.map(
          (threadKey, previews) => MapEntry(
            threadKey,
            List<_ConversationImagePreview>.from(previews),
          ),
        );
    HomeDrawerState._conversationImagePreviewSignatureSnapshot =
        Map<String, String>.from(_conversationImagePreviewSignatures);
    HomeDrawerState._conversationImagePreviewFailureSnapshot =
        _conversationImagePreviewFailures.map(
          (threadKey, failures) =>
              MapEntry(threadKey, Set<String>.from(failures)),
        );
  }

  Future<List<_ConversationImagePreview>> _conversationImagePreviewsFor(
    ConversationModel conversation, {
    bool notify = true,
  }) {
    final threadKey = conversation.threadKey;
    final signature = _conversationImagePreviewSignature(conversation);
    if (_conversationImagePreviewSignatures[threadKey] == signature) {
      final cached = _conversationImagePreviewCache[threadKey];
      if (cached != null) {
        return Future.value(
          _filterConversationImagePreviews(threadKey, cached),
        );
      }
      final pending = _conversationImagePreviewFutures[threadKey];
      if (pending != null) {
        return pending;
      }
    }

    _conversationImagePreviewSignatures[threadKey] = signature;
    _conversationImagePreviewCache.remove(threadKey);
    _conversationImagePreviewFailures.remove(threadKey);
    final future = _loadConversationImagePreviews(
      conversation,
      signature,
      notify: notify,
    );
    _conversationImagePreviewFutures[threadKey] = future;
    return future;
  }

  Future<void> _preloadConversationImagePreviews(
    List<ConversationModel> conversations,
  ) async {
    final targets = conversations
        .where(_needsConversationImagePreviewPreload)
        .toList(growable: false);
    if (targets.isEmpty) {
      return;
    }

    for (
      var index = 0;
      index < targets.length;
      index += _kConversationImagePreviewPreloadBatchSize
    ) {
      if (!mounted) {
        return;
      }
      final end =
          index + _kConversationImagePreviewPreloadBatchSize > targets.length
          ? targets.length
          : index + _kConversationImagePreviewPreloadBatchSize;
      final batch = targets.sublist(index, end);
      await Future.wait(
        batch.map(
          (conversation) =>
              _conversationImagePreviewsFor(conversation, notify: false),
        ),
      );
    }
    _rememberConversationImagePreviewCacheSnapshot();
  }

  void _hydrateConversationImagePreviewSnapshotsFromDecoded(
    dynamic decoded,
    List<ConversationModel> conversations,
  ) {
    final items = decoded is Map ? decoded['items'] : decoded;
    if (items is! Map) {
      return;
    }
    for (final conversation in conversations) {
      final threadKey = conversation.threadKey;
      final snapshot = items[threadKey];
      if (snapshot is! Map) {
        continue;
      }
      final signature = (snapshot['signature'] ?? '').toString();
      if (signature != _conversationImagePreviewSignature(conversation)) {
        continue;
      }
      final rawPreviews = snapshot['previews'];
      final previews = rawPreviews is List
          ? rawPreviews
                .map(_previewFromSnapshotJson)
                .whereType<_ConversationImagePreview>()
                .toList(growable: false)
          : const <_ConversationImagePreview>[];
      _conversationImagePreviewSignatures[threadKey] = signature;
      _conversationImagePreviewCache[threadKey] = previews;
    }
    _rememberConversationImagePreviewCacheSnapshot();
  }

  Map<String, dynamic> _conversationImagePreviewSnapshotsJson(
    List<ConversationModel> conversations,
  ) {
    final items = <String, dynamic>{};
    for (final conversation in conversations.take(
      _kConversationImagePreviewSnapshotLimit,
    )) {
      final threadKey = conversation.threadKey;
      final signature = _conversationImagePreviewSignature(conversation);
      if (_conversationImagePreviewSignatures[threadKey] != signature ||
          !_conversationImagePreviewCache.containsKey(threadKey)) {
        continue;
      }
      final previews = _conversationImagePreviewCache[threadKey]!
          .map(_previewToSnapshotJson)
          .whereType<Map<String, dynamic>>()
          .toList(growable: false);
      items[threadKey] = <String, dynamic>{
        'signature': signature,
        'previews': previews,
      };
    }
    return <String, dynamic>{'items': items};
  }

  Future<void> _persistDrawerSnapshot(
    List<ConversationModel> conversations,
  ) async {
    try {
      await StorageService.setString(
        _kConversationSnapshotCacheKey,
        jsonEncode(<String, dynamic>{
          'conversations': conversations
              .take(_kConversationImagePreviewSnapshotLimit)
              .map((conversation) => conversation.toJson())
              .toList(growable: false),
          'imagePreviews': _conversationImagePreviewSnapshotsJson(
            conversations,
          ),
        }),
      );
    } catch (error) {
      debugPrint('[HomeDrawer] Failed to persist drawer snapshot: $error');
    }
  }

  void _refreshConversationImagePreviewsInBackground(
    List<ConversationModel> conversations, {
    required int generation,
  }) {
    unawaited(() async {
      await _preloadConversationImagePreviews(conversations);
      if (!mounted || generation != _conversationLoadGeneration) {
        return;
      }
      _rememberConversationSnapshotCache(_allConversations);
      await _persistDrawerSnapshot(
        List<ConversationModel>.from(_allConversations),
      );
    }());
  }

  bool _needsConversationImagePreviewPreload(ConversationModel conversation) {
    final threadKey = conversation.threadKey;
    final signature = _conversationImagePreviewSignature(conversation);
    if (_conversationImagePreviewSignatures[threadKey] != signature) {
      return true;
    }
    return !_conversationImagePreviewCache.containsKey(threadKey);
  }

  Future<List<_ConversationImagePreview>> _loadConversationImagePreviews(
    ConversationModel conversation,
    String signature, {
    required bool notify,
  }) async {
    final threadKey = conversation.threadKey;
    try {
      await OmnibotResourceService.ensureWorkspacePathsLoaded();
      final messages = await ConversationHistoryService.getConversationMessages(
        conversation.id,
        mode: conversation.mode,
        expectedMessageCount: conversation.messageCount,
      );
      final previews = _filterConversationImagePreviews(
        threadKey,
        _collectConversationImagePreviews(messages),
      );
      if (mounted &&
          _conversationImagePreviewSignatures[threadKey] == signature) {
        void commit() {
          _conversationImagePreviewCache[threadKey] = previews;
          _conversationImagePreviewFutures.remove(threadKey);
          _rememberConversationImagePreviewCacheSnapshot();
        }

        if (notify) {
          setState(commit);
        } else {
          commit();
        }
      }
      return previews;
    } catch (error) {
      debugPrint(
        '[HomeDrawer] Failed to load image previews for $threadKey: $error',
      );
      if (mounted &&
          _conversationImagePreviewSignatures[threadKey] == signature) {
        void commit() {
          _conversationImagePreviewCache[threadKey] =
              const <_ConversationImagePreview>[];
          _conversationImagePreviewFutures.remove(threadKey);
          _rememberConversationImagePreviewCacheSnapshot();
        }

        if (notify) {
          setState(commit);
        } else {
          commit();
        }
      }
      return const <_ConversationImagePreview>[];
    }
  }

  String _conversationImagePreviewSignature(ConversationModel conversation) {
    return [
      conversation.threadKey,
      conversation.updatedAt,
      conversation.messageCount,
      conversation.lastMessage ?? '',
    ].join('|');
  }

  List<_ConversationImagePreview> _collectConversationImagePreviews(
    List<ChatMessageModel> messages,
  ) {
    final previews = <_ConversationImagePreview>[];
    final seen = <String>{};

    void addPreview(_ConversationImagePreview? preview) {
      if (preview == null || !seen.add(preview.identity)) {
        return;
      }
      previews.add(preview);
    }

    for (final message in messages) {
      final text = message.text;
      if (text != null) {
        _collectConversationImagePreviewsFromText(text, addPreview: addPreview);
      }

      for (final attachment in _extractVisibleImageAttachments(message)) {
        addPreview(_previewFromAttachment(attachment));
      }

      for (final preview in _extractCardImagePreviews(message)) {
        addPreview(preview);
      }

      for (final linkPreview in message.linkPreviews) {
        if (linkPreview.imageUrl.trim().isNotEmpty) {
          addPreview(
            _previewFromImageLocation(linkPreview.imageUrl, trustImage: true),
          );
        }
      }
    }

    return previews;
  }

  void _collectConversationImagePreviewsFromText(
    String rawText, {
    required void Function(_ConversationImagePreview?) addPreview,
  }) {
    final text = rawText.trim();
    if (text.isEmpty) {
      return;
    }

    for (final match in _markdownImageLocationPattern.allMatches(text)) {
      addPreview(
        _previewFromImageLocation(
          _sanitizeImageLocation(match.group(1) ?? ''),
          trustImage: true,
        ),
      );
    }

    for (final match in _omnibotLinkLocationPattern.allMatches(text)) {
      addPreview(
        _previewFromImageLocation(
          _sanitizeImageLocation(match.group(1) ?? ''),
          trustImage: true,
        ),
      );
    }

    for (final line in text.split('\n')) {
      final trimmed = line.trim();
      if (trimmed.startsWith('omnibot://') &&
          !trimmed.contains(' ') &&
          !trimmed.contains('[') &&
          !trimmed.contains(']')) {
        addPreview(
          _previewFromImageLocation(
            _sanitizeImageLocation(trimmed),
            trustImage: false,
          ),
        );
      }
    }
  }

  List<Map<String, dynamic>> _extractVisibleImageAttachments(
    ChatMessageModel message,
  ) {
    final raw = message.content?['attachments'];
    if (raw is! List) {
      return const [];
    }
    return raw
        .whereType<Map>()
        .map(
          (item) => item.map((key, value) => MapEntry(key.toString(), value)),
        )
        .where(_isConversationImageAttachment)
        .toList(growable: false);
  }

  List<_ConversationImagePreview> _extractCardImagePreviews(
    ChatMessageModel message,
  ) {
    final cardData = message.cardData;
    if (cardData == null) {
      return const [];
    }
    final cardType = (cardData['type'] ?? '').toString().trim();
    if (cardType != 'openclaw_attachment') {
      return const [];
    }
    final rawAttachment = cardData['attachment'];
    if (rawAttachment is! Map) {
      return const [];
    }
    final attachment = rawAttachment.map(
      (key, value) => MapEntry(key.toString(), value),
    );
    if (!_isConversationImageAttachment(attachment)) {
      return const [];
    }
    final preview = _previewFromAttachment(attachment);
    return preview == null ? const [] : <_ConversationImagePreview>[preview];
  }

  bool _isConversationImageAttachment(Map<String, dynamic> item) {
    final explicit = item['isImage'];
    if (explicit is bool && explicit) return true;
    final mimeType = (item['mimeType'] as String? ?? '').trim().toLowerCase();
    if (mimeType.startsWith('image/')) return true;
    final previewKind = (item['previewKind'] as String? ?? '')
        .trim()
        .toLowerCase();
    final embedKind = (item['embedKind'] as String? ?? '').trim().toLowerCase();
    if (previewKind == 'image' || embedKind == 'image') return true;
    final path = (item['path'] as String? ?? '').toLowerCase();
    final uri = (item['uri'] as String? ?? '').toLowerCase();
    final url = (item['url'] as String? ?? '').toLowerCase();
    final imageUrl = (item['imageUrl'] as String? ?? '').toLowerCase();
    final thumbnailUrl = (item['thumbnailUrl'] as String? ?? '').toLowerCase();
    final dataUrl = (item['dataUrl'] as String? ?? '').toLowerCase();
    return _looksLikeConversationImage(path) ||
        _looksLikeConversationImage(uri) ||
        _looksLikeConversationImage(url) ||
        _looksLikeConversationImage(imageUrl) ||
        _looksLikeConversationImage(thumbnailUrl) ||
        dataUrl.startsWith('data:image/');
  }

  bool _looksLikeConversationImage(String value) {
    if (value.isEmpty) return false;
    final pure = value.split('?').first.split('#').first.toLowerCase();
    return pure.endsWith('.png') ||
        pure.endsWith('.jpg') ||
        pure.endsWith('.jpeg') ||
        pure.endsWith('.gif') ||
        pure.endsWith('.webp') ||
        pure.endsWith('.bmp') ||
        pure.endsWith('.heic') ||
        pure.endsWith('.heif');
  }

  _ConversationImagePreview? _previewFromAttachment(Map<String, dynamic> item) {
    final dataUrl = (item['dataUrl'] as String? ?? '').trim();
    if (dataUrl.startsWith('data:image/')) {
      return _previewFromDataUrl(dataUrl);
    }

    final candidates = <String>[
      (item['thumbnailUrl'] as String? ?? '').trim(),
      (item['imageUrl'] as String? ?? '').trim(),
      (item['url'] as String? ?? '').trim(),
      (item['uri'] as String? ?? '').trim(),
      (item['path'] as String? ?? '').trim(),
    ];
    for (final candidate in candidates) {
      final preview = _previewFromImageLocation(candidate, trustImage: true);
      if (preview != null) {
        return preview;
      }
    }
    return null;
  }

  _ConversationImagePreview? _previewFromImageLocation(
    String rawValue, {
    bool trustImage = false,
  }) {
    final value = _sanitizeImageLocation(rawValue);
    if (value.isEmpty) return null;
    if (value.startsWith('data:image/')) {
      return _previewFromDataUrl(value);
    }
    if (value.startsWith('omnibot://')) {
      return _previewFromOmnibotUri(value);
    }
    if (value.startsWith('http://') || value.startsWith('https://')) {
      return trustImage || _looksLikeConversationImage(value)
          ? _ConversationImagePreview.network(value)
          : null;
    }

    final path = _normalizeImageFilePath(value);
    if (!trustImage && !_looksLikeConversationImage(path)) {
      return null;
    }
    if (!_isRenderableLocalImagePath(path)) {
      return null;
    }
    return _ConversationImagePreview.file(path);
  }

  bool _isRenderableLocalImagePath(String path) {
    if (path.isEmpty || !_looksLikeConversationImage(path)) {
      return false;
    }
    return File(path).existsSync();
  }

  _ConversationImagePreview? _previewFromOmnibotUri(String uri) {
    final metadata = OmnibotResourceService.resolveUri(uri);
    if (metadata == null || !_metadataLooksLikeConversationImage(metadata)) {
      return null;
    }
    if (!metadata.exists) {
      return null;
    }
    return _ConversationImagePreview.file(metadata.path);
  }

  bool _metadataLooksLikeConversationImage(OmnibotResourceMetadata metadata) {
    return metadata.previewKind == 'image' ||
        metadata.embedKind == 'image' ||
        metadata.mimeType.toLowerCase().startsWith('image/') ||
        _looksLikeConversationImage(metadata.path) ||
        _looksLikeConversationImage(metadata.uri ?? '');
  }

  _ConversationImagePreview? _previewFromDataUrl(String dataUrl) {
    try {
      final bytes = UriData.parse(dataUrl).contentAsBytes();
      if (bytes.isEmpty) {
        return null;
      }
      return _ConversationImagePreview.memory(
        identity: 'data:${dataUrl.hashCode}:${bytes.length}',
        bytes: bytes,
      );
    } catch (_) {
      return null;
    }
  }

  _ConversationImagePreview? _previewFromSnapshotJson(dynamic raw) {
    if (raw is! Map) {
      return null;
    }
    final type = (raw['type'] ?? '').toString();
    final value = (raw['value'] ?? '').toString().trim();
    if (value.isEmpty) {
      return null;
    }
    if (type == 'file') {
      return _isRenderableLocalImagePath(value)
          ? _ConversationImagePreview.file(value)
          : null;
    }
    if (type == 'network') {
      return _ConversationImagePreview.network(value);
    }
    return null;
  }

  Map<String, dynamic>? _previewToSnapshotJson(
    _ConversationImagePreview preview,
  ) {
    final path = preview.path;
    if (path != null && path.trim().isNotEmpty) {
      return <String, dynamic>{'type': 'file', 'value': path};
    }
    final url = preview.url;
    if (url != null && url.trim().isNotEmpty) {
      return <String, dynamic>{'type': 'network', 'value': url};
    }
    return null;
  }

  List<_ConversationImagePreview> _filterConversationImagePreviews(
    String threadKey,
    List<_ConversationImagePreview> previews,
  ) {
    final failures = _conversationImagePreviewFailures[threadKey];
    if (failures == null || failures.isEmpty) {
      return previews;
    }
    return previews
        .where((preview) => !failures.contains(preview.identity))
        .toList(growable: false);
  }

  void _markConversationImagePreviewFailed(String threadKey, String identity) {
    final failures = _conversationImagePreviewFailures.putIfAbsent(
      threadKey,
      () => <String>{},
    );
    if (!failures.add(identity)) {
      return;
    }
    final cached = _conversationImagePreviewCache[threadKey];
    if (cached != null) {
      final filtered = cached
          .where((preview) => preview.identity != identity)
          .toList(growable: false);
      _conversationImagePreviewCache[threadKey] = filtered;
    }
    _rememberConversationImagePreviewCacheSnapshot();
    _persistCurrentConversationSnapshot();
    if (mounted) {
      setState(() {});
    }
  }

  String _sanitizeImageLocation(String rawValue) {
    var value = rawValue.trim();
    if (value.length >= 2 && value.startsWith('<') && value.endsWith('>')) {
      value = value.substring(1, value.length - 1).trim();
    }
    while (value.isNotEmpty &&
        (value.endsWith('.') ||
            value.endsWith(',') ||
            value.endsWith(';') ||
            value.endsWith(':'))) {
      value = value.substring(0, value.length - 1).trimRight();
    }
    return value;
  }

  String _normalizeImageFilePath(String value) {
    if (!value.startsWith('file://')) {
      return value;
    }
    final uri = Uri.tryParse(value);
    if (uri == null) {
      return value.replaceFirst('file://', '');
    }
    try {
      return uri.toFilePath();
    } catch (_) {
      return value.replaceFirst('file://', '');
    }
  }

  Widget _buildConversationImagePreviewStrip(ConversationModel conversation) {
    final threadKey = conversation.threadKey;
    final signature = _conversationImagePreviewSignature(conversation);
    final cached = _conversationImagePreviewSignatures[threadKey] == signature
        ? _conversationImagePreviewCache[threadKey]
        : null;
    final previews = _filterConversationImagePreviews(
      threadKey,
      cached ?? const <_ConversationImagePreview>[],
    );
    return AnimatedSize(
      duration: HomeDrawerState._imagePreviewStripTransitionDuration,
      curve: Curves.easeOutCubic,
      alignment: Alignment.topLeft,
      child: previews.isEmpty
          ? const SizedBox.shrink()
          : Padding(
              padding: const EdgeInsets.only(top: 7),
              child: LayoutBuilder(
                builder: (context, constraints) {
                  const thumbSize = 34.0;
                  const spacing = 6.0;
                  final visibleCount =
                      ((constraints.maxWidth + spacing) / (thumbSize + spacing))
                          .floor()
                          .clamp(1, previews.length)
                          .toInt();
                  final visiblePreviews = previews
                      .take(visibleCount)
                      .toList(growable: false);
                  return SizedBox(
                    height: thumbSize,
                    child: ClipRect(
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          for (final entry
                              in visiblePreviews.asMap().entries) ...[
                            if (entry.key > 0) const SizedBox(width: spacing),
                            _ConversationImageThumbnail(
                              key: ValueKey(entry.value.identity),
                              preview: entry.value,
                              size: thumbSize,
                              onFailed: () =>
                                  _markConversationImagePreviewFailed(
                                    threadKey,
                                    entry.value.identity,
                                  ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  );
                },
              ),
            ),
    );
  }
}

class _ConversationImageThumbnail extends StatefulWidget {
  const _ConversationImageThumbnail({
    super.key,
    required this.preview,
    required this.size,
    required this.onFailed,
  });

  final _ConversationImagePreview preview;
  final double size;
  final VoidCallback onFailed;

  @override
  State<_ConversationImageThumbnail> createState() =>
      _ConversationImageThumbnailState();
}

class _ConversationImageThumbnailState
    extends State<_ConversationImageThumbnail> {
  bool _failed = false;
  bool _failureReported = false;

  @override
  Widget build(BuildContext context) {
    if (_failed) {
      return const SizedBox.shrink();
    }

    final palette = context.omniPalette;
    final fallbackColor = context.isDarkTheme
        ? palette.surfaceSecondary
        : palette.previewFallback;
    final image = switch (widget.preview) {
      _ConversationImagePreview(bytes: final bytes?) => Image.memory(
        bytes,
        fit: BoxFit.cover,
        gaplessPlayback: true,
        errorBuilder: (_, __, ___) => _markFailed(),
      ),
      _ConversationImagePreview(url: final url?) => Image.network(
        url,
        fit: BoxFit.cover,
        gaplessPlayback: true,
        errorBuilder: (_, __, ___) => _markFailed(),
      ),
      _ConversationImagePreview(path: final path?) => Image.file(
        File(path),
        fit: BoxFit.cover,
        gaplessPlayback: true,
        errorBuilder: (_, __, ___) => _markFailed(),
      ),
      _ => _markFailed(),
    };

    return Container(
      width: widget.size,
      height: widget.size,
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        color: fallbackColor,
        borderRadius: BorderRadius.circular(7),
        border: Border.all(color: palette.borderSubtle.withValues(alpha: 0.8)),
      ),
      child: image,
    );
  }

  Widget _markFailed() {
    if (!_failureReported) {
      _failureReported = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) {
          return;
        }
        widget.onFailed();
      });
    }
    if (!mounted || _failed) {
      return const SizedBox.shrink();
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && !_failed) {
        setState(() {
          _failed = true;
        });
      }
    });
    return const SizedBox.shrink();
  }
}
