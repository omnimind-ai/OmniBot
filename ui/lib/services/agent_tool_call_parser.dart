import 'dart:convert';

class AgentToolCallInfo {
  const AgentToolCallInfo({
    required this.itemType,
    required this.toolType,
    required this.toolName,
    required this.displayName,
    required this.toolTitle,
    required this.status,
    required this.arguments,
    required this.argsJson,
    required this.resultPreviewJson,
    required this.rawResultJson,
    required this.terminalOutput,
    required this.summary,
    required this.progress,
    this.serverName,
  });

  final String itemType;
  final String toolType;
  final String toolName;
  final String displayName;
  final String toolTitle;
  final String status;
  final Map<String, dynamic> arguments;
  final String argsJson;
  final String resultPreviewJson;
  final String rawResultJson;
  final String terminalOutput;
  final String summary;
  final String progress;
  final String? serverName;
}

AgentToolCallInfo normalizeAgentToolCall(
  Map<String, dynamic> raw, {
  String? itemType,
  String? fallbackToolType,
  String? fallbackTitle,
  String fallbackStatus = 'running',
}) {
  final type = canonicalAgentItemType(_firstString([itemType, raw['type']]));
  final arguments = _normalizedArguments(raw);
  final rawToolName = canonicalAgentToolName(
    _resolveToolName(raw, itemType: type),
  );
  final toolType = _inferToolType(
    itemType: type,
    explicitToolType: _visualToolType(
      _firstString([raw['toolType'], raw['tool_type']]),
    ),
    fallbackToolType: fallbackToolType,
    toolName: rawToolName,
    arguments: arguments,
  );
  final status = normalizeAgentToolStatus(raw, fallbackStatus: fallbackStatus);
  final title = _resolveToolTitle(
    raw,
    itemType: type,
    toolType: toolType,
    toolName: rawToolName,
    arguments: arguments,
    fallbackTitle: fallbackTitle,
  );
  final toolName = rawToolName ?? _defaultToolName(type, toolType);
  final displayName =
      _firstString([raw['displayName'], raw['display_name'], raw['name']]) ??
      title;
  final serverName = _firstString([raw['serverName'], raw['server']]);
  final terminalOutput = _firstOutputString([
    raw['terminalOutput'],
    raw['aggregatedOutput'],
    raw['aggregated_output'],
    raw['output'],
    raw['stdout'],
    _asStringMap(raw['result'])?['stdout'],
    _asStringMap(raw['result'])?['output'],
  ]);
  final summary =
      _firstString([
        raw['summary'],
        raw['message'],
        raw['description'],
        if (type != 'commandExecution') raw['status'],
      ]) ??
      '';
  final progress =
      _firstString([raw['progress'], raw['message'], raw['delta']]) ?? '';

  return AgentToolCallInfo(
    itemType: type,
    toolType: toolType,
    toolName: toolName,
    displayName: displayName,
    toolTitle: title,
    status: status,
    arguments: arguments,
    // Official ACP rawInput can be an unfinished JSON string while streaming.
    // Display it losslessly; parsing is not a prerequisite for a progress update.
    argsJson: raw['rawInput'] is String
        ? raw['rawInput'] as String
        : arguments.isEmpty
        ? ''
        : _safeJson(arguments),
    resultPreviewJson: _resultPreviewJson(raw),
    rawResultJson: _safeJson(raw),
    terminalOutput: terminalOutput ?? '',
    summary: summary,
    progress: progress,
    serverName: serverName,
  );
}

String canonicalAgentItemType(String? itemType) {
  final normalized = itemType?.trim() ?? '';
  if (normalized.isEmpty) {
    return '';
  }
  return const <String, String>{
        'agent_message': 'agentMessage',
        'user_message': 'userMessage',
        'command_execution': 'commandExecution',
        'file_change': 'fileChange',
        'mcp_tool_call': 'mcpToolCall',
        'dynamic_tool_call': 'dynamicToolCall',
        'web_search': 'webSearch',
        'image_view': 'imageView',
        'image_generation': 'imageGeneration',
        'collab_agent_tool_call': 'collabAgentToolCall',
        'collab_tool_call': 'collabToolCall',
        'request_user_input': 'requestUserInput',
        'request_approval': 'requestApproval',
        'todo_list': 'plan',
      }[normalized] ??
      normalized;
}

String? canonicalAgentToolName(String? toolName) {
  final normalized = toolName?.trim();
  if (normalized == null || normalized.isEmpty) {
    return null;
  }
  if (normalized.startsWith('codex.')) {
    return 'agent.${normalized.substring('codex.'.length)}';
  }
  if (normalized.startsWith('codex/')) {
    return 'agent/${normalized.substring('codex/'.length)}';
  }
  return normalized;
}

String normalizeAgentToolStatus(
  Map<String, dynamic> raw, {
  String fallbackStatus = 'running',
}) {
  // ACP ToolCallStatus is the only lifecycle source of truth. rawOutput and
  // rawResult are opaque tool data; reading status-like fields from them
  // creates a second lifecycle and was the cause of the success/failure split
  // in the command transcript.
  final explicit = _firstString([raw['status'], raw['state']]);
  final normalized = explicit?.trim().toLowerCase();
  if (normalized != null && normalized.isNotEmpty) {
    if (normalized == 'pending') {
      return 'pending';
    }
    if (normalized == 'in_progress' ||
        normalized == 'running' ||
        normalized == 'progress' ||
        normalized == 'inprogress' ||
        normalized == 'executing' ||
        normalized == 'started') {
      return 'running';
    }
    if (normalized == 'completed' ||
        normalized == 'success' ||
        normalized == 'succeeded' ||
        normalized == 'complete' ||
        normalized == 'applied' ||
        normalized == 'done') {
      return 'success';
    }
    if (normalized == 'failed' ||
        normalized == 'error' ||
        normalized == 'failure' ||
        normalized == 'rejected') {
      return 'error';
    }
    if (normalized == 'cancelled' ||
        normalized == 'canceled' ||
        normalized == 'incomplete' ||
        normalized == 'interrupted' ||
        normalized == 'aborted') {
      return 'interrupted';
    }
    if (normalized == 'timeout' || normalized == 'timedout') {
      return 'timeout';
    }
  }
  // Non-ACP item snapshots do not always carry a status. Keep their
  // compatibility projection based on fields at the item boundary only;
  // never inspect rawOutput/rawResult for a lifecycle decision.
  if (raw['error'] != null || raw['success'] == false) {
    return 'error';
  }
  final exitCode = _asInt(raw['exitCode'] ?? raw['exit_code']);
  if (exitCode != null && exitCode != 0) {
    return 'error';
  }
  if (raw['success'] == true) {
    return 'success';
  }
  return fallbackStatus;
}

bool agentToolStatusIsExplicit(Map<String, dynamic> raw) {
  return _firstString([raw['status'], raw['state']]) != null ||
      raw.containsKey('success') ||
      raw.containsKey('error') ||
      raw.containsKey('exitCode') ||
      raw.containsKey('exit_code');
}

String agentToolCardSuffix(String toolType, {String? itemType}) {
  final canonicalItemType = canonicalAgentItemType(itemType);
  if (canonicalItemType == 'fileChange' || toolType == 'file') {
    return 'file';
  }
  if (canonicalItemType == 'plan' || toolType == 'plan') {
    return 'plan';
  }
  if (toolType == 'search') {
    return 'search';
  }
  if (toolType == 'workspace') {
    return 'workspace';
  }
  if (toolType == 'browser') {
    return 'browser';
  }
  if (toolType == 'image') {
    return 'image';
  }
  if (_isCommandLikeItemType(canonicalItemType) || toolType == 'terminal') {
    return 'command';
  }
  return 'tool';
}

bool isAgentToolItemType(String itemType) {
  final canonicalItemType = canonicalAgentItemType(itemType);
  return const <String>{
    'commandExecution',
    'local_shell_call',
    'commandExec',
    'processExecution',
    'fileChange',
    'tool',
    'mcpToolCall',
    'dynamicToolCall',
    'function_call',
    'function_call_output',
    'custom_tool_call',
    'custom_tool_call_output',
    'tool_search_call',
    'tool_search_output',
    'webSearch',
    'web_search_call',
    'imageView',
    'imageGeneration',
    'image_generation_call',
    'collabAgentToolCall',
    'collabToolCall',
    'plan',
  }.contains(canonicalItemType);
}

bool isAgentToolOutputItemType(String itemType) {
  return const <String>{
    'function_call_output',
    'custom_tool_call_output',
    'tool_search_output',
  }.contains(itemType);
}

bool _isCommandLikeItemType(String? itemType) {
  final canonicalItemType = canonicalAgentItemType(itemType);
  return canonicalItemType == 'commandExecution' ||
      itemType == 'local_shell_call' ||
      canonicalItemType == 'commandExec' ||
      canonicalItemType == 'processExecution';
}

Map<String, dynamic> _normalizedArguments(Map<String, dynamic> raw) {
  final args = <String, dynamic>{};
  final parsed = _toolArguments(raw);
  args.addAll(parsed);
  for (final key in const ['command', 'cmd']) {
    final normalizedCommand = _commandFromValue(args[key]);
    if (normalizedCommand != null) {
      args[key] = normalizedCommand;
    }
  }
  final action = _asStringMap(raw['action']);

  void add(String key, dynamic value) {
    if (args.containsKey(key) || value == null) {
      return;
    }
    final text = value is String ? value.trim() : null;
    if (text != null && text.isEmpty) {
      return;
    }
    args[key] = value;
  }

  for (final key in const <String>[
    'command',
    'cmd',
    'cwd',
    'workingDirectory',
    'working_directory',
    'query',
    'q',
    'url',
    'uri',
    'path',
    'file',
    'target',
    'filePath',
    'file_path',
    'filename',
    'fileName',
    'pattern',
    'regex',
    'glob',
    'include',
    'queryText',
    'query_text',
    'action',
    'tool',
    'server',
    'namespace',
    'prompt',
    'execution',
    'items',
  ]) {
    final value = key == 'command' || key == 'cmd'
        ? _commandFromValue(raw[key])
        : raw[key];
    add(key, value);
  }
  add('command', _commandFromValue(action?['command']));
  add('cmd', _commandFromValue(raw['command']));
  add('workingDirectory', action?['working_directory']);
  add('workingDirectory', action?['workingDirectory']);
  add('cwd', action?['cwd']);
  if (raw['changes'] != null) {
    add('changes', raw['changes']);
  }
  if (raw['files'] != null) {
    add('files', raw['files']);
  }
  final parsedCommands = <Map<String, dynamic>>[
    ..._agentParsedCommands(raw),
    ..._agentParsedCommands(parsed),
    ..._agentParsedCommands(action),
  ];
  if (parsedCommands.isNotEmpty) {
    args['parsedCommands'] = parsedCommands;
  }
  return args;
}

List<Map<String, dynamic>> _agentParsedCommands(dynamic value) {
  if (value is List) {
    return value
        .whereType<Map>()
        .map(
          (entry) =>
              entry.map((key, nested) => MapEntry(key.toString(), nested)),
        )
        .toList(growable: false);
  }
  if (value is Map) {
    for (final key in const <String>[
      'parsedCommands',
      'parsed_commands',
      'parsedCmd',
      'parsed_cmd',
      'commandActions',
      'command_actions',
    ]) {
      final raw = value[key];
      if (raw is List) {
        return _agentParsedCommands(raw);
      }
    }
  }
  return const <Map<String, dynamic>>[];
}

AgentParsedCommandAction? _firstAgentCommandAction(
  Map<String, dynamic> arguments,
) {
  final list = _agentParsedCommands(arguments);
  if (list.isEmpty) {
    return null;
  }
  AgentParsedCommandAction? fallback;
  for (final entry in list) {
    final typeRaw = _firstString([entry['type']]);
    if (typeRaw == null) {
      continue;
    }
    final normalized = typeRaw.trim().toLowerCase().replaceAll(
      RegExp(r'[^a-z0-9]+'),
      '_',
    );
    final mappedType = switch (normalized) {
      'read' => 'read',
      'list_files' || 'listfiles' || 'list' => 'listFiles',
      'search' || 'grep' || 'find' => 'search',
      _ => 'unknown',
    };
    final action = AgentParsedCommandAction(
      type: mappedType,
      command:
          _commandFromValue(entry['command']) ??
          _commandFromValue(entry['cmd']),
      name: _firstString([entry['name']]),
      path: _firstString([entry['path']]),
      query: _firstString([entry['query']]),
    );
    if (mappedType != 'unknown') {
      return action;
    }
    fallback ??= action;
  }
  return fallback;
}

String? _titleFromParsedCommandAction(AgentParsedCommandAction action) {
  switch (action.type) {
    case 'read':
      final target =
          action.name ??
          (action.path == null ? null : _lastPathSegment(action.path!)) ??
          action.path;
      if (target != null && target.isNotEmpty) {
        return 'Read $target';
      }
      return null;
    case 'listFiles':
      final target = action.path;
      if (target == null || target.isEmpty) {
        return 'List files';
      }
      return 'List ${_lastPathSegment(target) ?? target}';
    case 'search':
      if (action.query != null && action.query!.isNotEmpty) {
        return 'Search: ${action.query}';
      }
      if (action.path != null && action.path!.isNotEmpty) {
        return 'Search ${_lastPathSegment(action.path!) ?? action.path}';
      }
      return null;
  }
  return null;
}

class AgentParsedCommandAction {
  const AgentParsedCommandAction({
    required this.type,
    this.command,
    this.name,
    this.path,
    this.query,
  });

  final String type;
  final String? command;
  final String? name;
  final String? path;
  final String? query;
}

Map<String, dynamic> _toolArguments(Map<String, dynamic> raw) {
  for (final key in const <String>[
    'arguments',
    'args',
    'input',
    'rawInput',
    'raw_input',
  ]) {
    final map = _asStringMap(raw[key]);
    if (map != null) {
      return map;
    }
    final text = _string(raw[key]);
    if (text == null || text.trim().isEmpty) {
      continue;
    }
    try {
      final decoded = jsonDecode(text);
      final decodedMap = _asStringMap(decoded);
      if (decodedMap != null) {
        return decodedMap;
      }
    } catch (_) {
      continue;
    }
  }
  return const <String, dynamic>{};
}

String? _resolveToolName(Map<String, dynamic> raw, {required String itemType}) {
  final toolValue = raw['tool'];
  final toolString = toolValue is String ? toolValue : null;
  final actionType = _firstString([_asStringMap(raw['action'])?['type']]);
  if (itemType == 'local_shell_call' && actionType != null) {
    return 'local_shell.$actionType';
  }
  return _firstString([
    raw['toolName'],
    raw['tool_name'],
    raw['name'],
    raw['functionName'],
    raw['function_name'],
    _asStringMap(raw['function'])?['name'],
    _asStringMap(raw['tool'])?['name'],
    raw['execution'],
    toolString,
  ]);
}

/// Some adapters use `context` as a result-envelope name rather than a UI
/// capability. Keep that protocol detail out of every event source and infer
/// the actual shared card route from the tool's concrete facts instead.
String? _visualToolType(String? value) {
  final normalized = value?.trim();
  if (normalized == null || normalized.isEmpty) {
    return null;
  }
  return normalized.toLowerCase() == 'context' ? null : normalized;
}

String _inferToolType({
  required String itemType,
  required String? explicitToolType,
  required String? fallbackToolType,
  required String? toolName,
  required Map<String, dynamic> arguments,
}) {
  final canonicalItemType = canonicalAgentItemType(itemType);
  final explicit = explicitToolType?.trim();
  if (explicit != null && explicit.isNotEmpty) {
    return explicit;
  }
  switch (canonicalItemType) {
    case 'commandExecution':
    case 'local_shell_call':
    case 'commandExec':
    case 'processExecution':
      final action = _firstAgentCommandAction(arguments);
      if (action != null) {
        switch (action.type) {
          case 'read':
          case 'listFiles':
            return 'workspace';
          case 'search':
            return 'search';
        }
      }
      return _inferToolTypeFromCommand(arguments) ?? 'terminal';
    case 'fileChange':
      return 'file';
    case 'webSearch':
    case 'web_search_call':
    case 'tool_search_call':
    case 'tool_search_output':
      return 'search';
    case 'imageView':
    case 'imageGeneration':
    case 'image_generation_call':
      return 'image';
    case 'collabAgentToolCall':
    case 'collabToolCall':
      return 'subagent';
    case 'plan':
      return 'plan';
  }

  final fullName = (toolName ?? '').trim().toLowerCase();
  final shortName = _shortToolName(fullName).toLowerCase();
  final name = '$fullName $shortName';
  // Subagent dispatch is a distinct collaboration capability. Resolve it
  // before generic read/file/name heuristics so labels such as
  // `subagent_dispatch` can never be rendered as a file or workspace tool.
  if (_containsAny(name, const ['subagent', 'sub_agent', 'delegate_agent'])) {
    return 'subagent';
  }
  final commandToolType = _inferToolTypeFromCommand(arguments);
  if (commandToolType != null && _looksLikeCommandToolName(name)) {
    return commandToolType;
  }
  if (_containsAny(name, const [
    'terminal',
    'shell',
    'exec',
    'command',
    'bash',
    'zsh',
    'powershell',
  ])) {
    return 'terminal';
  }
  if (_containsAny(shortName, const [
    'edit',
    'write',
    'patch',
    'apply_patch',
  ])) {
    return 'file';
  }
  if (_containsAny(name, const [
    'read',
    'view',
    'open_file',
    'read_file',
    'read_text',
    'read_many_files',
    'cat',
    'sed',
    'list',
    'glob',
    'grep',
    'workspace',
    'file_search',
    'search_file',
  ])) {
    return 'workspace';
  }
  if (_containsAny(name, const ['web', 'browser', 'fetch', 'open_url'])) {
    return 'browser';
  }
  if (_containsAny(name, const ['search', 'query'])) {
    return 'search';
  }
  if (_containsAny(name, const ['image', 'screenshot', 'view_image'])) {
    return 'image';
  }
  if (_containsAny(name, const ['memory'])) {
    return 'memory';
  }
  if (_containsAny(name, const ['alarm', 'reminder'])) {
    return 'alarm';
  }
  if (_containsAny(name, const ['schedule', 'scheduled', 'timer'])) {
    return 'schedule';
  }
  if (_containsAny(name, const ['calendar', 'calendar_event'])) {
    return 'calendar';
  }
  if (canonicalItemType == 'mcpToolCall') {
    return 'mcp';
  }
  final fallback = fallbackToolType?.trim();
  if (fallback != null && fallback.isNotEmpty) {
    return fallback;
  }
  return 'tool';
}

String? _inferToolTypeFromCommand(Map<String, dynamic> arguments) {
  final command = _firstString([arguments['command'], arguments['cmd']]);
  if (command == null) {
    return null;
  }
  final normalized = command.trim().toLowerCase();
  if (normalized.isEmpty) {
    return null;
  }
  if (RegExp(
    r'(^|[;&|]\s*)(git\s+grep|rg|grep|fd|find|ag|ack)\b',
  ).hasMatch(normalized)) {
    return 'search';
  }
  return null;
}

bool _looksLikeCommandToolName(String name) {
  return _containsAny(name, const [
    'terminal',
    'shell',
    'exec',
    'command',
    'bash',
    'zsh',
    'powershell',
  ]);
}

bool _shouldUseSearchTitle({
  required String itemType,
  required String toolType,
  required String? toolName,
  required Map<String, dynamic> arguments,
}) {
  if (toolType != 'search') {
    return false;
  }
  if (_inferToolTypeFromCommand(arguments) == 'search') {
    return true;
  }
  final canonicalItemType = canonicalAgentItemType(itemType);
  if (canonicalItemType == 'webSearch' ||
      canonicalItemType == 'tool_search_call' ||
      canonicalItemType == 'tool_search_output') {
    return true;
  }
  final shortName = toolName == null
      ? ''
      : _shortToolName(toolName).trim().toLowerCase();
  return const <String>{
    'rg',
    'grep',
    'git_grep',
    'search',
    'search_files',
    'file_search',
    'tool_search',
  }.contains(shortName);
}

String _resolveToolTitle(
  Map<String, dynamic> raw, {
  required String itemType,
  required String toolType,
  required String? toolName,
  required Map<String, dynamic> arguments,
  required String? fallbackTitle,
}) {
  final canonicalItemType = canonicalAgentItemType(itemType);
  final explicit = _firstString([
    raw['toolTitle'],
    raw['tool_title'],
    raw['displayName'],
    raw['display_name'],
    arguments['toolTitle'],
    arguments['tool_title'],
    arguments['displayName'],
    arguments['display_name'],
    arguments['description'],
    raw['description'],
  ]);
  if (explicit != null) {
    return _compactTitle(explicit, maxLength: 48);
  }

  if (_isCommandLikeItemType(canonicalItemType) || toolType == 'terminal') {
    final action = _firstAgentCommandAction(arguments);
    if (action != null) {
      final actionTitle = _titleFromParsedCommandAction(action);
      if (actionTitle != null) {
        return _compactTitle(actionTitle, maxLength: 48);
      }
    }
    final command = _firstString([
      raw['command'],
      arguments['command'],
      raw['cmd'],
      arguments['cmd'],
      _commandFromValue(_asStringMap(raw['action'])?['command']),
      raw['processId'],
      raw['processHandle'],
    ]);
    if (command != null) {
      return _compactTitle(command, maxLength: 48);
    }
    final acpTitle = _firstString([raw['title']]);
    if (acpTitle != null) {
      return _compactTitle(acpTitle, maxLength: 48);
    }
    return fallbackTitle?.trim().isNotEmpty == true
        ? _compactTitle(fallbackTitle!, maxLength: 48)
        : 'Agent command';
  }

  if (canonicalItemType == 'fileChange' || toolType == 'file') {
    final acpTitle = _firstString([raw['title']]);
    if (acpTitle != null) {
      return _compactTitle(acpTitle, maxLength: 48);
    }
    final path = _resolvePath(raw, arguments);
    if (path != null) {
      return _compactTitle(
        'Edit ${_lastPathSegment(path) ?? path}',
        maxLength: 42,
      );
    }
    return fallbackTitle?.trim().isNotEmpty == true
        ? _compactTitle(fallbackTitle!, maxLength: 48)
        : 'Agent file change';
  }

  if (canonicalItemType == 'webSearch' || itemType == 'web_search_call') {
    final query = _firstString([
      raw['query'],
      arguments['query'],
      arguments['q'],
      _asStringMap(raw['action'])?['query'],
    ]);
    if (query != null) {
      return _compactTitle('Search: $query', maxLength: 48);
    }
    return 'Web search';
  }

  if (_shouldUseSearchTitle(
    itemType: itemType,
    toolType: toolType,
    toolName: toolName,
    arguments: arguments,
  )) {
    final command = _firstString([arguments['command'], arguments['cmd']]);
    if (command != null) {
      return _compactTitle(command, maxLength: 48);
    }
    final query = _firstString([
      raw['query'],
      arguments['query'],
      arguments['q'],
      raw['execution'],
      arguments['execution'],
    ]);
    if (query != null) {
      return _compactTitle('Search: $query', maxLength: 48);
    }
    return fallbackTitle?.trim().isNotEmpty == true
        ? _compactTitle(fallbackTitle!, maxLength: 48)
        : 'Agent search';
  }

  if (canonicalItemType == 'imageView') {
    final path = _resolvePath(raw, arguments);
    if (path != null) {
      return _compactTitle(
        'View ${_lastPathSegment(path) ?? path}',
        maxLength: 48,
      );
    }
    return 'View image';
  }

  if (canonicalItemType == 'imageGeneration') {
    return 'Generate image';
  }

  if (canonicalItemType == 'collabAgentToolCall' ||
      canonicalItemType == 'collabToolCall') {
    final prompt = _firstString([raw['prompt'], arguments['prompt']]);
    if (prompt != null) {
      return _compactTitle('Subagent: $prompt', maxLength: 48);
    }
    final name = toolName == null ? 'Subagent' : _shortToolName(toolName);
    return _compactTitle(name, maxLength: 48);
  }

  if (canonicalItemType == 'plan' || toolType == 'plan') {
    return 'Agent plan';
  }

  if (isAgentToolOutputItemType(itemType)) {
    final outputName = toolName == null ? null : _shortToolName(toolName);
    if (outputName != null && outputName.isNotEmpty) {
      return _compactTitle('$outputName output', maxLength: 48);
    }
    return fallbackTitle?.trim().isNotEmpty == true
        ? _compactTitle(fallbackTitle!, maxLength: 48)
        : 'Agent tool output';
  }

  // node_repl/js and other MCP/dynamic/custom/function invocations frequently
  // carry a human-readable `title` (or `description`) in arguments — surface
  // that as the card title so the user sees "Refine flavor parsing" instead
  // of bare `js`. Applies to BOTH the raw `function_call` ResponseItem path
  // (rawResponseItem/completed) AND the projected MCP/dynamic notification
  // path; falls through to the catch-all below when no title is provided.
  if (canonicalItemType == 'mcpToolCall' ||
      canonicalItemType == 'dynamicToolCall' ||
      canonicalItemType == 'function_call' ||
      itemType == 'custom_tool_call') {
    final invocationTitle = _firstString([
      arguments['title'],
      raw['title'],
      arguments['description'],
      arguments['summary'],
      raw['description'],
    ]);
    if (invocationTitle != null) {
      return _compactTitle(invocationTitle, maxLength: 64);
    }
  }

  final shortName = toolName == null ? null : _shortToolName(toolName);
  final command = _firstString([arguments['command'], arguments['cmd']]);
  if (command != null) {
    return _compactTitle(command, maxLength: 48);
  }
  final detail = _firstString([
    arguments['query'],
    arguments['q'],
    arguments['url'],
    arguments['uri'],
    arguments['path'],
    arguments['file'],
    arguments['target'],
    arguments['filePath'],
    arguments['file_path'],
    arguments['filename'],
    arguments['fileName'],
    arguments['pattern'],
    arguments['regex'],
    arguments['glob'],
    arguments['include'],
    raw['query'],
    raw['url'],
    raw['path'],
    raw['file'],
    raw['target'],
  ]);
  if (detail != null) {
    final operationTitle = _operationTitle(shortName, detail);
    if (operationTitle != null) {
      return _compactTitle(operationTitle, maxLength: 48);
    }
    final detailTitle = _looksLikePath(detail)
        ? (_lastPathSegment(detail) ?? detail)
        : detail;
    if (shortName != null && shortName.isNotEmpty) {
      return _compactTitle('$shortName: $detailTitle', maxLength: 48);
    }
    return _compactTitle(detailTitle, maxLength: 48);
  }

  if (fallbackTitle?.trim().isNotEmpty == true) {
    return _compactTitle(fallbackTitle!, maxLength: 48);
  }
  final acpTitle = _firstString([raw['title']]);
  if (acpTitle != null) {
    return _compactTitle(acpTitle, maxLength: 48);
  }
  if (shortName != null && shortName.isNotEmpty) {
    return _compactTitle(shortName, maxLength: 48);
  }
  return 'Agent tool';
}

String? _operationTitle(String? shortName, String detail) {
  final name = (shortName ?? '').trim().toLowerCase();
  if (name.isEmpty) {
    return null;
  }
  final target = _looksLikePath(detail)
      ? (_lastPathSegment(detail) ?? detail)
      : detail;
  if (name == 'read' ||
      name == 'read_file' ||
      name == 'readfile' ||
      name == 'view_file' ||
      name == 'open_file' ||
      name == 'read_text' ||
      name == 'read_many_files' ||
      name == 'cat' ||
      name == 'sed') {
    return 'Read $target';
  }
  if (name == 'list' ||
      name == 'list_files' ||
      name == 'list_directory' ||
      name == 'ls') {
    return 'List $target';
  }
  if (name == 'write' || name == 'write_file' || name == 'writefile') {
    return 'Write $target';
  }
  if (name == 'edit' || name == 'edit_file' || name == 'apply_patch') {
    return 'Edit $target';
  }
  if (name == 'grep' ||
      name == 'rg' ||
      name == 'fd' ||
      name == 'find' ||
      name == 'glob' ||
      name == 'search' ||
      name == 'search_files' ||
      name == 'file_search') {
    return 'Search $target';
  }
  return null;
}

String? _resolvePath(Map<String, dynamic> raw, Map<String, dynamic> args) {
  return _firstString([
    raw['path'],
    raw['file'],
    raw['target'],
    raw['filePath'],
    raw['file_path'],
    raw['filename'],
    raw['fileName'],
    args['path'],
    args['file'],
    args['target'],
    args['filePath'],
    args['file_path'],
    args['filename'],
    args['fileName'],
    _firstPathFromList(raw['files']),
    _firstPathFromList(raw['changes']),
    _firstPathFromList(args['files']),
    _firstPathFromList(args['changes']),
  ]);
}

String? _firstPathFromList(dynamic value) {
  if (value is String) {
    final decoded = _decodeJson(value);
    return _firstPathFromList(decoded);
  }
  if (value is! List) {
    return null;
  }
  for (final item in value) {
    if (item is String && item.trim().isNotEmpty) {
      final decoded = _decodeJson(item);
      final decodedPath = _firstPathFromList(decoded);
      if (decodedPath != null) {
        return decodedPath;
      }
      return item.trim();
    }
    final map = _asStringMap(item);
    final path = _firstString([
      map?['path'],
      map?['filePath'],
      map?['file_path'],
      map?['filename'],
      map?['fileName'],
    ]);
    if (path != null) {
      return path;
    }
  }
  return null;
}

String _defaultToolName(String itemType, String toolType) {
  final canonicalItemType = canonicalAgentItemType(itemType);
  if (canonicalItemType == 'local_shell_call') {
    return 'agent.localShell';
  }
  if (canonicalItemType == 'commandExec') {
    return 'agent.commandExec';
  }
  if (canonicalItemType == 'processExecution') {
    return 'agent.process';
  }
  if (canonicalItemType == 'function_call') {
    return 'agent.functionCall';
  }
  if (canonicalItemType == 'function_call_output') {
    return 'agent.functionOutput';
  }
  if (canonicalItemType == 'custom_tool_call') {
    return 'agent.customTool';
  }
  if (canonicalItemType == 'custom_tool_call_output') {
    return 'agent.customToolOutput';
  }
  if (canonicalItemType == 'tool_search_call') {
    return 'agent.toolSearch';
  }
  if (canonicalItemType == 'tool_search_output') {
    return 'agent.toolSearchOutput';
  }
  if (canonicalItemType == 'web_search_call') {
    return 'agent.webSearch';
  }
  if (canonicalItemType == 'image_generation_call') {
    return 'agent.imageGeneration';
  }
  if (canonicalItemType == 'mcpToolCall') {
    return 'agent.mcp';
  }
  if (canonicalItemType == 'dynamicToolCall') {
    return 'agent.dynamicTool';
  }
  if (canonicalItemType == 'webSearch') {
    return 'agent.webSearch';
  }
  if (canonicalItemType == 'imageView') {
    return 'agent.imageView';
  }
  if (canonicalItemType == 'imageGeneration') {
    return 'agent.imageGeneration';
  }
  if (canonicalItemType == 'collabAgentToolCall' ||
      canonicalItemType == 'collabToolCall') {
    return 'agent.collabAgent';
  }
  return 'agent.$toolType';
}

String _resultPreviewJson(Map<String, dynamic> raw) {
  final result =
      raw['result'] ??
      raw['output'] ??
      raw['contentItems'] ??
      raw['content_items'];
  if (result != null) {
    return _safeJson(result);
  }
  final error = raw['error'];
  if (error != null) {
    return _safeJson({'error': error});
  }
  return '';
}

dynamic _decodeJson(String text) {
  final normalized = text.trim();
  if (normalized.isEmpty) {
    return null;
  }
  try {
    return jsonDecode(normalized);
  } catch (_) {
    return null;
  }
}

Map<String, dynamic>? _asStringMap(dynamic value) {
  if (value is String) {
    return _asStringMap(_decodeJson(value));
  }
  if (value is! Map) {
    return null;
  }
  return value.map((key, nestedValue) => MapEntry(key.toString(), nestedValue));
}

String? _firstString(Iterable<dynamic> values) {
  for (final value in values) {
    final text = _string(value);
    if (text != null && text.trim().isNotEmpty) {
      return text.trim();
    }
  }
  return null;
}

String? _firstOutputString(Iterable<dynamic> values) {
  for (final value in values) {
    if (value == null) {
      continue;
    }
    if (value is String) {
      if (value.isNotEmpty) {
        return value;
      }
      continue;
    }
    if (value is num || value is bool) {
      return value.toString();
    }
  }
  return null;
}

String? _string(dynamic value) {
  if (value == null) {
    return null;
  }
  if (value is String) {
    return value;
  }
  if (value is num || value is bool) {
    return value.toString();
  }
  return null;
}

String? _commandFromValue(dynamic value) {
  if (value == null) {
    return null;
  }
  if (value is String) {
    return value.trim().isEmpty ? null : value;
  }
  if (value is List) {
    final parts = value
        .map(_string)
        .whereType<String>()
        .map((part) => part.trim())
        .where((part) => part.isNotEmpty)
        .toList(growable: false);
    return parts.isEmpty ? null : parts.join(' ');
  }
  return _string(value);
}

int? _asInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse((value ?? '').toString().trim());
}

String _shortToolName(String value) {
  final normalized = value.trim();
  if (normalized.isEmpty) {
    return '';
  }
  final withoutNamespace = normalized.split(RegExp(r'[./:]')).last;
  final parts = withoutNamespace
      .split('__')
      .where((part) => part.isNotEmpty)
      .toList(growable: false);
  return parts.isEmpty ? withoutNamespace : parts.last;
}

String? _lastPathSegment(String path) {
  final normalized = path.trim().replaceAll(RegExp(r'[/\\]+$'), '');
  if (normalized.isEmpty) {
    return null;
  }
  final parts = normalized
      .split(RegExp(r'[/\\]+'))
      .where((part) => part.isNotEmpty)
      .toList(growable: false);
  return parts.isEmpty ? normalized : parts.last;
}

bool _looksLikePath(String value) {
  return value.contains('/') || value.contains('\\');
}

bool _containsAny(String haystack, List<String> needles) {
  return needles.any(haystack.contains);
}

String _compactTitle(String value, {required int maxLength}) {
  final normalized = value
      .trim()
      .split('\n')
      .first
      .trim()
      .replaceAll(RegExp(r'\s+'), ' ');
  if (normalized.length <= maxLength) {
    return normalized;
  }
  return '${normalized.substring(0, maxLength)}...';
}

String _safeJson(dynamic value) {
  try {
    return const JsonEncoder.withIndent('  ').convert(value);
  } catch (_) {
    return value?.toString() ?? '';
  }
}
