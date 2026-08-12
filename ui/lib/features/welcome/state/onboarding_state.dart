import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ui/services/model_provider_config_service.dart';

/// Shared state across the onboarding flow.
///
/// Tracks whether the user has completed cloud AI configuration.
class OnboardingState extends ChangeNotifier {
  bool _cloudConfigured = false;
  String? _configuredProfileId;
  bool _initialized = false;

  bool get cloudConfigured => _cloudConfigured;
  String? get configuredProfileId => _configuredProfileId;
  bool get initialized => _initialized;

  void markCloudConfigured(String profileId) {
    _cloudConfigured = true;
    _configuredProfileId = profileId;
    notifyListeners();
  }

  /// Check persisted state to recover from app restart mid-onboarding.
  Future<void> checkExistingState() async {
    try {
      // Check if user already has a cloud provider profile
      final profiles = await ModelProviderConfigService.listProfiles();
      final hasUserProfile = profiles.profiles.any(
        (p) => !p.readOnly && p.apiKey.isNotEmpty,
      );
      if (hasUserProfile) {
        _cloudConfigured = true;
        _configuredProfileId = profiles.editingProfileId;
      }
    } catch (e) {
    }
    _initialized = true;
    notifyListeners();
  }
}

/// Auto-disposed provider scoped to the onboarding flow lifetime.
final onboardingStateProvider =
    ChangeNotifierProvider.autoDispose<OnboardingState>(
      (ref) => OnboardingState(),
    );
