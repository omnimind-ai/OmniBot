class RemoteMcpServer {
  final String id;
  final String name;
  final String endpointUrl;

  /// A replacement entered during this UI session; never populated on read.
  final String bearerToken;
  final bool hasBearerToken;
  final bool clearBearerToken;
  final bool enabled;
  final String lastHealth;
  final String? lastError;
  final int toolCount;
  final int? lastSyncedAt;
  final int generation;

  const RemoteMcpServer({
    required this.id,
    required this.name,
    required this.endpointUrl,
    required this.bearerToken,
    this.hasBearerToken = false,
    this.clearBearerToken = false,
    required this.enabled,
    required this.lastHealth,
    this.lastError,
    required this.toolCount,
    this.lastSyncedAt,
    this.generation = 0,
  });

  factory RemoteMcpServer.fromMap(Map<dynamic, dynamic> raw) {
    return RemoteMcpServer(
      id: (raw['id'] ?? '').toString(),
      name: (raw['name'] ?? '').toString(),
      endpointUrl: (raw['endpointUrl'] ?? '').toString(),
      bearerToken: '',
      hasBearerToken:
          raw['hasBearerToken'] == true ||
          (raw['bearerToken'] ?? '').toString().isNotEmpty,
      enabled: raw['enabled'] == true,
      lastHealth: (raw['lastHealth'] ?? 'unknown').toString(),
      lastError: raw['lastError']?.toString(),
      toolCount: raw['toolCount'] is int
          ? raw['toolCount'] as int
          : int.tryParse((raw['toolCount'] ?? '0').toString()) ?? 0,
      lastSyncedAt: raw['lastSyncedAt'] is int
          ? raw['lastSyncedAt'] as int
          : int.tryParse((raw['lastSyncedAt'] ?? '').toString()),
      generation: raw['generation'] is int
          ? raw['generation'] as int
          : int.tryParse((raw['generation'] ?? '0').toString()) ?? 0,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'name': name,
      'endpointUrl': endpointUrl,
      'bearerToken': bearerToken,
      'clearBearerToken': clearBearerToken,
      'enabled': enabled,
      'lastHealth': lastHealth,
      'lastError': lastError,
      'toolCount': toolCount,
      'lastSyncedAt': lastSyncedAt,
      'generation': generation,
    };
  }

  RemoteMcpServer copyWith({
    String? id,
    String? name,
    String? endpointUrl,
    String? bearerToken,
    bool? hasBearerToken,
    bool? clearBearerToken,
    bool? enabled,
    String? lastHealth,
    String? lastError,
    int? toolCount,
    int? lastSyncedAt,
    int? generation,
  }) {
    return RemoteMcpServer(
      id: id ?? this.id,
      name: name ?? this.name,
      endpointUrl: endpointUrl ?? this.endpointUrl,
      bearerToken: bearerToken ?? this.bearerToken,
      hasBearerToken: hasBearerToken ?? this.hasBearerToken,
      clearBearerToken: clearBearerToken ?? this.clearBearerToken,
      enabled: enabled ?? this.enabled,
      lastHealth: lastHealth ?? this.lastHealth,
      lastError: lastError ?? this.lastError,
      toolCount: toolCount ?? this.toolCount,
      lastSyncedAt: lastSyncedAt ?? this.lastSyncedAt,
      generation: generation ?? this.generation,
    );
  }
}
