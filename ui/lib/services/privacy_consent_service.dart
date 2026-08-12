import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

enum PrivacyConsentDecision {
  pending('pending'),
  granted('granted'),
  declined('declined');

  const PrivacyConsentDecision(this.value);

  final String value;

  static PrivacyConsentDecision fromRaw(String? raw) {
    return values.firstWhere(
      (decision) => decision.value == raw?.trim().toLowerCase(),
      orElse: () => pending,
    );
  }
}

class PrivacyConsentService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/privacy_consent',
  );

  static final ValueNotifier<PrivacyConsentDecision> decisionNotifier =
      ValueNotifier<PrivacyConsentDecision>(PrivacyConsentDecision.pending);

  static Future<PrivacyConsentDecision> refresh() async {
    try {
      final raw = await _channel.invokeMethod<String>('getDecision');
      final decision = PrivacyConsentDecision.fromRaw(raw);
      decisionNotifier.value = decision;
      return decision;
    } catch (_) {
      decisionNotifier.value = PrivacyConsentDecision.pending;
      return PrivacyConsentDecision.pending;
    }
  }

  static Future<PrivacyConsentDecision> setDecision(
    PrivacyConsentDecision decision,
  ) async {
    if (decision == PrivacyConsentDecision.pending) {
      throw ArgumentError('An explicit privacy decision is required');
    }
    final raw = await _channel.invokeMethod<String>('setDecision', {
      'decision': decision.value,
    });
    final persisted = PrivacyConsentDecision.fromRaw(raw);
    if (persisted == PrivacyConsentDecision.pending) {
      throw StateError('The privacy decision was not persisted');
    }
    decisionNotifier.value = persisted;
    return persisted;
  }
}
