import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:ui/services/storage_service.dart';

bool isProviderModelIdsCall(MethodCall call) =>
    call.method == 'getProviderModelIds' ||
    call.method == 'saveProviderModelIds';

Future<List<String>> handleProviderModelIdsCall(MethodCall call) async {
  final args = call.arguments as Map;
  final kind = args['kind'] as String;
  final profileId = args['profileId'] as String;
  final key = kind == 'manual'
      ? 'manual_provider_model_ids_v2'
      : 'hidden_chat_provider_model_ids_v1';
  final raw = StorageService.getString(key);
  final parsed = raw == null
      ? <String, dynamic>{}
      : jsonDecode(raw) as Map<String, dynamic>;
  if (call.method == 'saveProviderModelIds') {
    parsed[profileId] = (args['ids'] as List).cast<String>();
    await StorageService.setString(key, jsonEncode(parsed));
  }
  return (parsed[profileId] as List?)?.cast<String>() ?? <String>[];
}
