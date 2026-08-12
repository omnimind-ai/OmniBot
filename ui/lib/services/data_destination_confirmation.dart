import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

/// A parsed endpoint whose public representation is intentionally limited to origin data.
/// The full normalized endpoint is kept private and is used only to bind a one-operation grant.
class DataDestination {
  DataDestination._({
    required this.displayOrigin,
    required this.isLoopback,
    required String canonicalEndpoint,
  }) : _canonicalEndpoint = canonicalEndpoint;

  final String displayOrigin;
  final bool isLoopback;
  final String _canonicalEndpoint;

  static DataDestination parse(
    String rawEndpoint, {
    bool allowInsecureDebugLoopback = kDebugMode,
  }) {
    final raw = rawEndpoint.trim();
    if (raw.isEmpty) {
      throw const FormatException('Destination endpoint is empty.');
    }
    final Uri uri;
    try {
      uri = Uri.parse(raw);
    } on FormatException {
      throw const FormatException('Destination endpoint is invalid.');
    }
    final scheme = uri.scheme.toLowerCase();
    if (!const {'http', 'https', 'ws', 'wss'}.contains(scheme) ||
        !uri.hasAuthority ||
        uri.host.trim().isEmpty) {
      throw const FormatException('Destination endpoint is invalid.');
    }
    if (uri.userInfo.isNotEmpty || uri.fragment.isNotEmpty) {
      throw const FormatException('Destination endpoint is unsafe.');
    }
    final host = uri.host.toLowerCase();
    final loopback = _isLoopbackHost(host);
    final literalLoopback = _isLiteralLoopback(host);
    final secure = scheme == 'https' || scheme == 'wss';
    if (!secure && !(allowInsecureDebugLoopback && literalLoopback)) {
      throw const FormatException('Destination requires HTTPS or WSS.');
    }

    final defaultPort = (scheme == 'https' || scheme == 'wss') ? 443 : 80;
    final effectivePort = uri.hasPort ? uri.port : defaultPort;
    if (effectivePort < 1 || effectivePort > 65535) {
      throw const FormatException('Destination endpoint has an invalid port.');
    }
    final hostForDisplay = host.contains(':') ? '[$host]' : host;
    final normalizedPath = uri.path.isEmpty ? '/' : uri.normalizePath().path;
    final canonicalEndpoint = StringBuffer()
      ..write(scheme)
      ..write('://')
      ..write(hostForDisplay)
      ..write(':')
      ..write(effectivePort)
      ..write(normalizedPath);
    if (uri.hasQuery) {
      canonicalEndpoint
        ..write('?')
        ..write(uri.query);
    }
    return DataDestination._(
      displayOrigin: '$scheme://$hostForDisplay:$effectivePort',
      isLoopback: loopback,
      canonicalEndpoint: canonicalEndpoint.toString(),
    );
  }

  bool binds({
    required String rawEndpoint,
    required String capability,
    required String operation,
    required DataDestinationGrant grant,
  }) {
    final reparsed = DataDestination.parse(rawEndpoint);
    return grant._canonicalEndpoint == reparsed._canonicalEndpoint &&
        grant._capability == capability &&
        grant._operation == operation;
  }

  static bool _isLoopbackHost(String host) {
    return host.toLowerCase() == 'localhost' || _isLiteralLoopback(host);
  }

  static bool _isLiteralLoopback(String host) {
    final normalized = host.toLowerCase();
    if (normalized == '::1' ||
        normalized == '0:0:0:0:0:0:0:1') {
      return true;
    }
    final parts = normalized.split('.');
    if (parts.length != 4) return false;
    final octets = <int>[];
    for (final part in parts) {
      if (part.isEmpty || part.length > 3 || !RegExp(r'^\d+$').hasMatch(part)) {
        return false;
      }
      final value = int.tryParse(part);
      if (value == null || value > 255) return false;
      octets.add(value);
    }
    return octets.first == 127;
  }
}

class DataDestinationGrant {
  const DataDestinationGrant._({
    required String canonicalEndpoint,
    required String capability,
    required String operation,
  }) : _canonicalEndpoint = canonicalEndpoint,
       _capability = capability,
       _operation = operation;

  final String _canonicalEndpoint;
  final String _capability;
  final String _operation;
}

/// Process-local approval state for repeated use of one explicitly confirmed
/// destination. It is deliberately not persisted across app restarts.
/// Any endpoint change (including path or query) invalidates the old approval.
class DataDestinationSessionApprovals {
  DataDestinationSessionApprovals._();

  static final Map<String, String> _endpoints = <String, String>{};

  static bool isConfirmed({
    required String subject,
    required String rawEndpoint,
    required String capability,
    required String operation,
  }) {
    final destination = DataDestination.parse(rawEndpoint);
    return _endpoints[_key(subject, capability, operation)] ==
        destination._canonicalEndpoint;
  }

  static void remember({
    required String subject,
    required String rawEndpoint,
    required String capability,
    required String operation,
  }) {
    final destination = DataDestination.parse(rawEndpoint);
    _endpoints[_key(subject, capability, operation)] =
        destination._canonicalEndpoint;
  }

  static void revoke({
    required String subject,
    required String capability,
    required String operation,
  }) {
    _endpoints.remove(_key(subject, capability, operation));
  }

  @visibleForTesting
  static void clearForTesting() => _endpoints.clear();

  static String _key(String subject, String capability, String operation) =>
      '${subject.trim()}\u0000${capability.trim()}\u0000${operation.trim()}';
}

class DataDestinationActionResult<T> {
  const DataDestinationActionResult.rejected()
    : confirmed = false,
      value = null;

  const DataDestinationActionResult.completed(this.value) : confirmed = true;

  final bool confirmed;
  final T? value;
}

/// Shows a fresh, operation-scoped disclosure and invokes [action] only after explicit consent.
/// No approval is persisted or reused for another endpoint, capability, or operation.
Future<DataDestinationActionResult<T>> confirmDataDestinationAndRun<T>({
  required BuildContext context,
  required String rawEndpoint,
  required String capability,
  required String operation,
  required List<String> dataTypes,
  required Future<T> Function() action,
}) async {
  final destination = DataDestination.parse(rawEndpoint);
  final english = Localizations.localeOf(context).languageCode == 'en';
  var acknowledged = false;
  final grant = await showDialog<DataDestinationGrant>(
    context: context,
    barrierDismissible: false,
    builder: (dialogContext) => StatefulBuilder(
      builder: (context, setDialogState) => AlertDialog(
        key: const Key('data-destination-confirmation-dialog'),
        title: Text(
          english ? 'Confirm data destination' : '确认数据接收方',
        ),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                destination.isLoopback
                    ? (english
                          ? 'This is a local service you selected. It may forward data to its own upstream service.'
                          : '这是你选择的本机服务。它仍可能按自身配置把数据继续转发到上游。')
                    : (english
                          ? 'This receiver was selected by you. Its own retention, training, and billing rules apply.'
                          : '此接收方由你自行选择；数据保留、训练使用和计费规则由该接收方决定。'),
              ),
              const SizedBox(height: 12),
              Text(english ? 'Destination' : '接收地址'),
              SelectableText(
                destination.displayOrigin,
                key: const Key('data-destination-safe-origin'),
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 12),
              Text(english ? 'Data sent for this action:' : '本次操作会发送：'),
              const SizedBox(height: 4),
              for (final item in dataTypes)
                Padding(
                  padding: const EdgeInsets.only(bottom: 3),
                  child: Text('• $item'),
                ),
              const SizedBox(height: 8),
              Text(
                english
                    ? 'Capability: $capability · Operation: $operation'
                    : '能力：$capability · 本次操作：$operation',
              ),
              CheckboxListTile(
                key: const Key('data-destination-acknowledgement'),
                contentPadding: EdgeInsets.zero,
                value: acknowledged,
                onChanged: (value) =>
                    setDialogState(() => acknowledged = value == true),
                title: Text(
                  english
                      ? 'I understand and want to continue this one action.'
                      : '我已了解，并只同意继续本次操作。',
                ),
                controlAffinity: ListTileControlAffinity.leading,
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            key: const Key('data-destination-cancel'),
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: Text(english ? 'Cancel' : '取消'),
          ),
          FilledButton(
            key: const Key('data-destination-confirm'),
            onPressed: acknowledged
                ? () => Navigator.of(dialogContext).pop(
                    DataDestinationGrant._(
                      canonicalEndpoint: destination._canonicalEndpoint,
                      capability: capability,
                      operation: operation,
                    ),
                  )
                : null,
            child: Text(english ? 'Continue once' : '仅本次继续'),
          ),
        ],
      ),
    ),
  );
  if (grant == null ||
      !destination.binds(
        rawEndpoint: rawEndpoint,
        capability: capability,
        operation: operation,
        grant: grant,
      )) {
    return DataDestinationActionResult<T>.rejected();
  }
  return DataDestinationActionResult<T>.completed(await action());
}
