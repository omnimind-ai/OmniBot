/// Effective capabilities of one ACP Agent/Session as seen by the host.
///
/// The wire payload is intentionally kept as a map for forward compatibility,
/// but callers should use this typed view instead of checking Harness names or
/// reaching into nested capability maps themselves.
class AcpCapabilities {
  const AcpCapabilities({
    this.loadSession = false,
    this.promptImage = false,
    this.promptAudio = false,
    this.promptEmbeddedContext = false,
    this.sessionList = false,
    this.sessionFork = false,
    this.sessionResume = false,
    this.sessionDelete = false,
    this.sessionClose = false,
    this.sessionAdditionalDirectories = false,
    this.authMethods = const <Map<String, dynamic>>[],
    this.authLogout = false,
    this.authProviders = false,
    this.clientFsRead = false,
    this.clientFsWrite = false,
    this.clientTerminal = false,
    this.clientPlan = false,
    this.clientElicitationForm = false,
    this.clientElicitationUrl = false,
    this.mcpHttp = false,
    this.mcpSse = false,
    this.pluginSupported = false,
    this.pluginAuthoring = false,
    this.pluginInstallViaHarness = false,
    this.pluginHostInstallApi = false,
    this.steering = false,
    this.raw = const <String, dynamic>{},
  });

  final bool loadSession;
  final bool promptImage;
  final bool promptAudio;
  final bool promptEmbeddedContext;
  final bool sessionList;
  final bool sessionFork;
  final bool sessionResume;
  final bool sessionDelete;
  final bool sessionClose;
  final bool sessionAdditionalDirectories;
  final List<Map<String, dynamic>> authMethods;
  final bool authLogout;
  final bool authProviders;
  final bool clientFsRead;
  final bool clientFsWrite;
  final bool clientTerminal;
  final bool clientPlan;
  final bool clientElicitationForm;
  final bool clientElicitationUrl;
  final bool mcpHttp;
  final bool mcpSse;
  final bool pluginSupported;
  final bool pluginAuthoring;
  final bool pluginInstallViaHarness;
  final bool pluginHostInstallApi;
  final bool steering;
  final Map<String, dynamic> raw;

  factory AcpCapabilities.fromMap(Object? value) {
    final source = _asStringMap(value) ?? const <String, dynamic>{};
    final prompt = _asStringMap(source['prompt']);
    final session = _asStringMap(source['session']);
    final auth = _asStringMap(source['auth']);
    final mcp = _asStringMap(source['mcp']);
    final plugin = _asStringMap(source['plugin']);
    final client = _asStringMap(source['client']);
    final clientFs = _asStringMap(client?['fs']);
    final clientElicitation = _asStringMap(client?['elicitation']);
    return AcpCapabilities(
      loadSession: _bool(source['loadSession']),
      promptImage: _bool(prompt?['image']),
      promptAudio: _bool(prompt?['audio']),
      promptEmbeddedContext: _bool(prompt?['embeddedContext']),
      sessionList: _bool(session?['list']),
      sessionFork: _bool(session?['fork']),
      sessionResume: _bool(session?['resume']),
      sessionDelete: _bool(session?['delete']),
      sessionClose: _bool(session?['close']),
      sessionAdditionalDirectories: _bool(session?['additionalDirectories']),
      authMethods: _mapList(auth?['methods']),
      authLogout: _bool(auth?['logout']),
      authProviders: _bool(auth?['providers']),
      clientFsRead:
          _bool(clientFs?['readTextFile']) ||
          _bool(clientFs?['read_text_file']),
      clientFsWrite:
          _bool(clientFs?['writeTextFile']) ||
          _bool(clientFs?['write_text_file']),
      clientTerminal: _bool(client?['terminal']),
      clientPlan: _bool(client?['plan']),
      clientElicitationForm: _bool(clientElicitation?['form']),
      clientElicitationUrl: _bool(clientElicitation?['url']),
      mcpHttp: _bool(mcp?['http']),
      mcpSse: _bool(mcp?['sse']),
      pluginSupported: _bool(plugin?['supported']),
      pluginAuthoring: _bool(plugin?['authoring']),
      pluginInstallViaHarness: _bool(plugin?['installViaHarness']),
      pluginHostInstallApi: _bool(plugin?['hostInstallApi']),
      steering: _bool(source['steering']),
      raw: Map<String, dynamic>.from(source),
    );
  }

  bool supports(String capability) {
    switch (capability.trim().toLowerCase()) {
      case 'loadsession':
      case 'session/load':
        return loadSession;
      case 'image':
      case 'prompt/image':
        return promptImage;
      case 'audio':
      case 'prompt/audio':
        return promptAudio;
      case 'embeddedcontext':
      case 'prompt/embeddedcontext':
        return promptEmbeddedContext;
      case 'session/list':
        return sessionList;
      case 'session/fork':
        return sessionFork;
      case 'session/resume':
        return sessionResume;
      case 'session/delete':
        return sessionDelete;
      case 'session/close':
        return sessionClose;
      case 'session/additionaldirectories':
      case 'session/additional_directories':
        return sessionAdditionalDirectories;
      case 'auth/logout':
      case 'logout':
        return authLogout;
      case 'auth/providers':
      case 'providers/list':
        return authProviders;
      case 'terminal':
      case 'client/terminal':
        return clientTerminal;
      case 'fs/read_text_file':
      case 'client/fs/read_text_file':
        return clientFsRead;
      case 'fs/write_text_file':
      case 'client/fs/write_text_file':
        return clientFsWrite;
      case 'plan':
      case 'client/plan':
        return clientPlan;
      case 'elicitation/form':
      case 'client/elicitation/form':
        return clientElicitationForm;
      case 'elicitation/url':
      case 'client/elicitation/url':
        return clientElicitationUrl;
      case 'plugin':
      case 'plugins':
      case 'plugin/supported':
        return pluginSupported;
      case 'plugin/authoring':
      case 'plugin/create':
        return pluginAuthoring;
      case 'plugin/install':
      case 'plugin/installviaharness':
        return pluginInstallViaHarness;
      default:
        return _bool(raw[capability]) || _bool(raw[_camelCase(capability)]);
    }
  }

  static String _camelCase(String value) {
    final parts = value.split(RegExp(r'[/._-]+'));
    if (parts.isEmpty) return value;
    return parts.first +
        parts.skip(1).map((part) {
          if (part.isEmpty) return '';
          return part[0].toUpperCase() + part.substring(1);
        }).join();
  }

  static bool _bool(Object? value) => value == true;

  static List<Map<String, dynamic>> _mapList(Object? value) {
    if (value is! List) return const <Map<String, dynamic>>[];
    return value
        .map(_asStringMap)
        .whereType<Map<String, dynamic>>()
        .map(Map<String, dynamic>.from)
        .toList(growable: false);
  }

  static Map<String, dynamic>? _asStringMap(Object? value) {
    if (value is Map<String, dynamic>) return value;
    if (value is Map) {
      return <String, dynamic>{
        for (final entry in value.entries)
          if (entry.key != null) entry.key.toString(): entry.value,
      };
    }
    return null;
  }
}
