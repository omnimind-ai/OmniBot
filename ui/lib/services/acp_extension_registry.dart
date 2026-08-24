/// Shared ACP extension projection.
///
/// ACP standard events are intentionally kept separate from presentation
/// metadata.  Agents may put additional, provider-specific information in
/// `_meta`; this registry translates common shapes into the presentation
/// vocabulary already consumed by the shared reducer while retaining every
/// namespace for diagnostics and future adapters.

typedef AcpExtensionProjector =
    Map<String, dynamic>? Function(Map<String, dynamic> payload);

class AcpExtensionProjection {
  const AcpExtensionProjection({
    required this.presentation,
    required this.extensions,
  });

  final Map<String, dynamic> presentation;

  /// Original namespace payloads, including scalar/list extensions. ACP
  /// metadata is forward-compatible JSON, so an unknown extension must not
  /// be discarded merely because this client does not know its shape yet.
  final Map<String, dynamic> extensions;

  bool get isEmpty => presentation.isEmpty && extensions.isEmpty;
}

class AcpExtensionRegistry {
  AcpExtensionRegistry({
    Map<String, AcpExtensionProjector> projectors =
        const <String, AcpExtensionProjector>{},
  }) : _projectors = <String, AcpExtensionProjector>{...projectors};

  /// The process-wide registry used by the ACP reducer.  Harness adapters can
  /// register a namespace once during startup; the chat page does not need to
  /// know which Harness produced the metadata.
  static final AcpExtensionRegistry shared = AcpExtensionRegistry();

  final Map<String, AcpExtensionProjector> _projectors;

  void register(String namespace, AcpExtensionProjector projector) {
    final key = namespace.trim();
    if (key.isEmpty) {
      throw ArgumentError.value(namespace, 'namespace', 'must not be empty');
    }
    _projectors[key] = projector;
  }

  void unregister(String namespace) {
    _projectors.remove(namespace.trim());
  }

  AcpExtensionProjection project(Map<String, dynamic> update) {
    final meta = _asStringMap(update['_meta']) ?? _asStringMap(update['meta']);
    if (meta == null || meta.isEmpty) {
      return const AcpExtensionProjection(
        presentation: <String, dynamic>{},
        extensions: <String, dynamic>{},
      );
    }

    final presentation = <String, dynamic>{};
    final extensions = <String, dynamic>{};

    for (final entry in meta.entries) {
      final namespace = entry.key.trim();
      final payload = _asStringMap(entry.value);
      if (namespace.isEmpty) continue;
      extensions[namespace] = entry.value;
      // Typed projection is opt-in for object payloads. Unknown scalar and
      // list extensions are still retained above for replay/diagnostics.
      if (payload == null) continue;

      final projector = _projectors[namespace];
      if (projector != null) {
        _mergeIfAbsent(presentation, projector(payload));
      }

      // The shared namespace is kept for backwards compatibility with the
      // Xiaowan ACP bridge.  It is still just one registered extension from
      // the reducer's point of view, not a page-specific Harness branch.
      if (_isSharedPresentationNamespace(namespace)) {
        _mergeIfAbsent(presentation, payload);
      }

      // Generic ACP clients commonly use a `presentation` object inside
      // their own namespace.  Accept it without requiring an adapter for
      // every provider name.
      _mergeIfAbsent(presentation, _asStringMap(payload['presentation']));
      _projectCommonAliases(payload, presentation);
    }

    return AcpExtensionProjection(
      presentation: presentation,
      extensions: extensions,
    );
  }

  static bool _isSharedPresentationNamespace(String namespace) {
    final normalized = namespace.toLowerCase().replaceAll(
      RegExp(r'[^a-z0-9]'),
      '',
    );
    return normalized == 'cncomomnimindagent' ||
        normalized == 'omnimindagent' ||
        normalized == 'acppresentation';
  }

  static void _projectCommonAliases(
    Map<String, dynamic> payload,
    Map<String, dynamic> presentation,
  ) {
    const aliases = <String, String>{
      'usage': 'usage',
      'reasoning': 'reasoning',
      'thinking': 'reasoning',
      'deepthinking': 'reasoning',
      'deepthought': 'reasoning',
      'thought': 'reasoning',
      'tool': 'tool',
      'toolcall': 'tool',
      'artifact': 'artifacts',
      'artifacts': 'artifacts',
      'compaction': 'compaction',
      'contextcompaction': 'compaction',
      'retry': 'retry',
      'media': 'media',
      'clarify': 'clarification',
      'clarification': 'clarification',
      'recovery': 'recovery',
      'permission': 'permission',
      'permissions': 'permission',
      'plan': 'plan',
      'task': 'task',
      'subtask': 'task',
      'memory': 'memory',
    };
    const reasoningFields = <String, String>{
      'taskdescription': 'taskDescription',
      'tasktitle': 'taskTitle',
      'subtasks': 'subTasks',
      'preparation': 'preparation',
      'memoryactions': 'memoryActions',
    };
    for (final entry in payload.entries) {
      final normalized = entry.key.toLowerCase().replaceAll(
        RegExp(r'[^a-z0-9]'),
        '',
      );
      final reasoningField = reasoningFields[normalized];
      if (reasoningField != null) {
        final reasoning =
            _asStringMap(presentation['reasoning']) ?? <String, dynamic>{};
        reasoning.putIfAbsent(reasoningField, () => entry.value);
        presentation['reasoning'] = reasoning;
        continue;
      }
      final canonical = aliases[normalized];
      if (canonical == null) continue;
      if (canonical == 'reasoning') {
        final existingValue = presentation['reasoning'];
        final existing = _asStringMap(existingValue) ?? <String, dynamic>{};
        if (existingValue != null && existing.isEmpty) {
          existing['text'] = existingValue;
        }
        final incoming = _asStringMap(entry.value);
        if (incoming != null) {
          _mergeIfAbsent(existing, incoming);
        } else if (!existing.containsKey('text')) {
          existing['text'] = entry.value;
        }
        presentation['reasoning'] = existing;
        continue;
      }
      if (presentation.containsKey(canonical)) continue;
      presentation[canonical] = entry.value;
    }
  }

  static void _mergeIfAbsent(
    Map<String, dynamic> target,
    Map<String, dynamic>? source,
  ) {
    if (source == null) return;
    for (final entry in source.entries) {
      if (!target.containsKey(entry.key)) {
        target[entry.key] = entry.value;
      }
    }
  }

  static Map<String, dynamic>? _asStringMap(Object? value) {
    if (value is Map<String, dynamic>) {
      return value;
    }
    if (value is Map) {
      return <String, dynamic>{
        for (final entry in value.entries)
          if (entry.key != null) entry.key.toString(): entry.value,
      };
    }
    return null;
  }
}
