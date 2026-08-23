import 'package:ui/utils/data_parser.dart';

String formatErrorMessageForUser(
  String? raw, {
  required String fallback,
  int maxLength = 400,
}) {
  final input = (raw ?? '').trim();
  if (input.isEmpty) return fallback;

  // 1) 优先尝试把错误当 JSON 解析（服务端/Native 常会塞一段 JSON）
  final decoded = safeJsonDecodeV2(input, fallback: null);

  String? message;
  String? code;
  int? statusCode;

  if (decoded is Map) {
    final map = decoded.cast<String, dynamic>();
    message = _extractNestedErrorMessage(map);

    final dynamic codeValue = map['code'] ?? map['errorCode'];
    if (codeValue is String) code = codeValue;
    if (codeValue is num) code = codeValue.toInt().toString();

    final dynamic sc = map['statusCode'] ?? map['status'];
    if (sc is num) statusCode = sc.toInt();
  } else if (decoded is String) {
    message = decoded;
  } else {
    message = input;
  }

  final normalized = _sanitizeErrorMessage((message ?? input).trim());
  if (normalized.isEmpty) return fallback;

  final prefixParts = <String>[];
  if (statusCode != null && statusCode > 0) prefixParts.add('HTTP $statusCode');
  if (code != null && code.trim().isNotEmpty) prefixParts.add(code.trim());

  final prefix = prefixParts.isEmpty ? '' : '${prefixParts.join(' / ')}：';

  var out = '$prefix$normalized';
  if (out.length > maxLength) out = '${out.substring(0, maxLength)}…';
  return out;
}

String? _extractNestedErrorMessage(dynamic value, [int depth = 0]) {
  if (value == null || depth > 5) return null;
  if (value is String) {
    final text = value.trim();
    if (text.isEmpty) return null;
    if ((text.startsWith('{') && text.endsWith('}')) ||
        (text.startsWith('[') && text.endsWith(']'))) {
      final decoded = safeJsonDecodeV2(text, fallback: null);
      final nested = _extractNestedErrorMessage(decoded, depth + 1);
      if (nested != null) return nested;
    }
    return text;
  }
  if (value is Map) {
    for (final key in const <String>[
      'message',
      'detail',
      'text',
      'msg',
      'error',
    ]) {
      final nested = _extractNestedErrorMessage(value[key], depth + 1);
      if (nested != null) return nested;
    }
    return null;
  }
  if (value is List) {
    for (final item in value) {
      final nested = _extractNestedErrorMessage(item, depth + 1);
      if (nested != null) return nested;
    }
  }
  return null;
}

String _sanitizeErrorMessage(String s) {
  var out = s;

  // 去掉一些常见的包装（避免把整段 PlatformException(...) 原样展示）
  out = out.replaceAll(RegExp(r'^PlatformException\('), '');
  out = out.replaceAll(RegExp(r'\)\s*$'), '');

  // 脱敏：Bearer token / api_key / token / authorization 等
  out = out.replaceAll(
    RegExp(r'Bearer\s+[A-Za-z0-9\-\._~\+/]+=*', caseSensitive: false),
    'Bearer ***',
  );
  out = out.replaceAll(
    RegExp(
      r'(api[_-]?key|token|authorization)\s*[:=]\s*[^,\s]+',
      caseSensitive: false,
    ),
    r'$1=***',
  );

  // 把过多空白压缩一下
  out = out.replaceAll(RegExp(r'\s+'), ' ').trim();
  return out;
}
