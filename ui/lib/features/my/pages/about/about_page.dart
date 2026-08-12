import 'package:flutter/material.dart';
import 'package:flutter_switch/flutter_switch.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/app_update_service.dart';
import 'package:ui/services/device_service.dart';
import 'package:ui/services/privacy_consent_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/app_text_styles.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/app_update_dialog.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/gradient_button.dart';
import 'package:ui/widgets/settings_section_title.dart';

class AboutPage extends StatefulWidget {
  const AboutPage({super.key});

  @override
  State<AboutPage> createState() => _AboutPageState();
}

class _AboutPageState extends State<AboutPage> {
  static const String _zhUserGuideUrl =
      'https://omnimind-ai.github.io/OmniBot-Docs';
  static const String _enUserGuideUrl =
      'https://omnimind-ai.github.io/OmniBot-Docs/en/';

  String _version = '';
  bool _selfUpdateAvailable = AppUpdateService.isSelfUpdateAvailable;
  AppUpdateStatus? _updateStatus = AppUpdateService.statusNotifier.value;
  bool _betaOptIn = AppUpdateService.betaOptInNotifier.value;
  AppUpdateDownloadSource _downloadSource =
      AppUpdateService.downloadSourceNotifier.value;
  bool _isCheckingUpdate = false;
  bool _isUpdatingBetaOptIn = false;
  bool _isUpdatingDownloadSource = false;
  PrivacyConsentDecision _privacyDecision =
      PrivacyConsentService.decisionNotifier.value;
  bool _isUpdatingPrivacyDecision = false;
  bool _hasSyncedUpdatePreferences = false;

  @override
  void initState() {
    super.initState();
    AppUpdateService.availabilityNotifier.addListener(
      _handleUpdateAvailabilityChanged,
    );
    AppUpdateService.statusNotifier.addListener(_handleUpdateStatusChanged);
    AppUpdateService.betaOptInNotifier.addListener(_handleBetaOptInChanged);
    AppUpdateService.downloadSourceNotifier.addListener(
      _handleDownloadSourceChanged,
    );
    PrivacyConsentService.decisionNotifier.addListener(
      _handlePrivacyDecisionChanged,
    );
    _loadVersion();
    _loadUpdateStatus();
    _loadPrivacyDecision();
  }

  @override
  void dispose() {
    AppUpdateService.availabilityNotifier.removeListener(
      _handleUpdateAvailabilityChanged,
    );
    AppUpdateService.statusNotifier.removeListener(_handleUpdateStatusChanged);
    AppUpdateService.betaOptInNotifier.removeListener(_handleBetaOptInChanged);
    AppUpdateService.downloadSourceNotifier.removeListener(
      _handleDownloadSourceChanged,
    );
    PrivacyConsentService.decisionNotifier.removeListener(
      _handlePrivacyDecisionChanged,
    );
    super.dispose();
  }

  Future<void> _loadVersion() async {
    try {
      final versionInfo = await DeviceService.getAppVersion();
      if (!mounted) return;
      if (versionInfo != null) {
        final versionName = versionInfo['versionName'] as String?;
        setState(() {
          _version = 'Version ${versionName ?? '-'}';
        });
        return;
      }
      setState(() {
        _version = 'Version -';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _version = 'Version -';
      });
    }
  }

  Future<void> _loadUpdateStatus() async {
    await AppUpdateService.initialize();
    if (!mounted) return;
    setState(() {
      _selfUpdateAvailable = AppUpdateService.isSelfUpdateAvailable;
      _betaOptIn = AppUpdateService.betaOptInNotifier.value;
      _updateStatus = AppUpdateService.statusNotifier.value;
      _downloadSource = AppUpdateService.downloadSourceNotifier.value;
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _hasSyncedUpdatePreferences) {
        return;
      }
      setState(() {
        _hasSyncedUpdatePreferences = true;
      });
    });
  }

  void _handleBetaOptInChanged() {
    if (!mounted) return;
    setState(() {
      _betaOptIn = AppUpdateService.betaOptInNotifier.value;
    });
  }

  void _handleUpdateStatusChanged() {
    if (!mounted) return;
    setState(() {
      _updateStatus = AppUpdateService.statusNotifier.value;
    });
  }

  void _handleDownloadSourceChanged() {
    if (!mounted) return;
    setState(() {
      _downloadSource = AppUpdateService.downloadSourceNotifier.value;
    });
  }

  Future<void> _loadPrivacyDecision() async {
    final decision = await PrivacyConsentService.refresh();
    if (!mounted) return;
    setState(() {
      _privacyDecision = decision;
    });
  }

  void _handleUpdateAvailabilityChanged() {
    if (!mounted) return;
    setState(() {
      _selfUpdateAvailable = AppUpdateService.isSelfUpdateAvailable;
    });
  }

  void _handlePrivacyDecisionChanged() {
    if (!mounted) return;
    setState(() {
      _privacyDecision = PrivacyConsentService.decisionNotifier.value;
    });
  }

  Future<void> _handleSelectPrivacyDecision(
    PrivacyConsentDecision? decision,
  ) async {
    if (decision == null ||
        decision == PrivacyConsentDecision.pending ||
        decision == _privacyDecision ||
        _isUpdatingPrivacyDecision) {
      return;
    }
    setState(() {
      _isUpdatingPrivacyDecision = true;
    });
    try {
      final persisted = await PrivacyConsentService.setDecision(decision);
      if (!mounted) return;
      setState(() {
        _privacyDecision = persisted;
      });
    } catch (_) {
      if (!mounted) return;
      showToast(
        context.l10n.aboutPrivacyConsentUpdateFailed,
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() {
          _isUpdatingPrivacyDecision = false;
        });
      }
    }
  }

  Future<void> _handleToggleBetaOptIn(bool enabled) async {
    if (!_selfUpdateAvailable || _isUpdatingBetaOptIn) return;
    setState(() {
      _isUpdatingBetaOptIn = true;
    });

    try {
      final updated = await AppUpdateService.setBetaOptIn(enabled);
      if (!mounted) return;
      setState(() {
        _betaOptIn = updated;
        _updateStatus = AppUpdateService.statusNotifier.value;
      });
    } catch (_) {
      if (!mounted) return;
      showToast(
        context.l10n.aboutBetaProgramToggleFailed,
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() {
          _isUpdatingBetaOptIn = false;
        });
      }
    }
  }

  Future<void> _handleCheckUpdate() async {
    if (!_selfUpdateAvailable || _isCheckingUpdate) return;
    setState(() {
      _isCheckingUpdate = true;
    });

    try {
      final status = await AppUpdateService.checkNow();
      if (!mounted) return;
      if (status == null) {
        showToast(context.trLegacy('检查更新失败'), type: ToastType.error);
        return;
      }
      if (status.hasUpdate) {
        await showAppUpdateDialog(context, status);
        return;
      }
      showToast(context.trLegacy('已是最新版'), type: ToastType.success);
    } catch (_) {
      if (!mounted) return;
      showToast(context.trLegacy('检查更新失败'), type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() {
          _isCheckingUpdate = false;
        });
      }
    }
  }

  Future<void> _handlePrimaryAction() async {
    if (!_selfUpdateAvailable) return;
    final status = _updateStatus;
    if (status != null && status.hasUpdate) {
      await showAppUpdateDialog(context, status);
      return;
    }
    await _handleCheckUpdate();
  }

  Future<void> _handleSelectDownloadSource(
    AppUpdateDownloadSource? source,
  ) async {
    if (source == null ||
        !_selfUpdateAvailable ||
        _isUpdatingDownloadSource ||
        source == _downloadSource) {
      return;
    }
    setState(() {
      _isUpdatingDownloadSource = true;
    });

    try {
      final updatedSource = await AppUpdateService.setDownloadSource(source);
      if (!mounted) return;
      setState(() {
        _downloadSource = updatedSource;
        _updateStatus = AppUpdateService.statusNotifier.value;
      });
    } catch (_) {
      if (!mounted) return;
      showToast(context.l10n.aboutApkSourceSwitchFailed, type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() {
          _isUpdatingDownloadSource = false;
        });
      }
    }
  }

  String? _buildUpdateHint() {
    if (!_selfUpdateAvailable) return null;
    final status = _updateStatus;
    if (status?.hasUpdate != true) return null;
    return '${context.trLegacy('发现新版本')} ${status!.latestVersionLabel}';
  }

  void _openUserGuide() {
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    GoRouterManager.push(
      '/webview/webview_page',
      extra: <String, dynamic>{
        'url': isEnglish ? _enUserGuideUrl : _zhUserGuideUrl,
        'title': context.trLegacy('使用手册'),
        'appBarBackClosesPage': true,
      },
    );
  }

  String _downloadSourceLabel(AppUpdateDownloadSource source) {
    switch (source) {
      case AppUpdateDownloadSource.worker:
        return context.l10n.aboutApkSourceOptionCnb;
      case AppUpdateDownloadSource.github:
        return context.l10n.aboutApkSourceOptionGithub;
    }
  }

  String _downloadSourceDescription(AppUpdateDownloadSource source) {
    switch (source) {
      case AppUpdateDownloadSource.worker:
        return context.l10n.aboutApkSourceOptionCnbDescription;
      case AppUpdateDownloadSource.github:
        return context.l10n.aboutApkSourceOptionGithubDescription;
    }
  }

  Widget _buildHero(bool compact) {
    final palette = context.omniPalette;
    return Column(
      children: [
        SizedBox(
          width: compact ? 112 : 144,
          height: compact ? 80 : 102,
          child: Image.asset(
            'assets/my/about_icon.png',
            fit: BoxFit.contain,
            errorBuilder: (context, error, stackTrace) {
              return const Icon(
                LucideIcons.image,
                size: 72,
                color: AppColors.primaryBlue,
              );
            },
          ),
        ),
        SizedBox(height: compact ? 8 : 14),
        Text(
          context.l10n.brandName,
          textAlign: TextAlign.center,
          style: TextStyle(
            fontFamily: AppTextStyles.fontFamily,
            fontSize: compact ? 26 : 32,
            fontWeight: FontWeight.w700,
            letterSpacing: compact ? 0.2 : 0.4,
            color: context.isDarkTheme ? palette.textPrimary : AppColors.text,
          ),
        ),
        SizedBox(height: compact ? 8 : 14),
        ConstrainedBox(
          constraints: BoxConstraints(maxWidth: compact ? 300 : 320),
          child: Text(
            context.l10n.aboutDescription,
            textAlign: TextAlign.center,
            style: TextStyle(
              fontFamily: AppTextStyles.fontFamily,
              fontSize: compact ? 10.5 : 12,
              fontWeight: FontWeight.w400,
              height: compact ? 1.35 : 1.5,
              letterSpacing: compact ? 0.16 : 0.22,
              color: context.isDarkTheme
                  ? palette.textSecondary
                  : AppColors.text70,
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildUpdateSection(
    bool compact,
    List<Color> updateButtonGradient,
    Color updateButtonTextColor,
  ) {
    final palette = context.omniPalette;
    final updateHint = _buildUpdateHint();

    return Column(
      children: [
        Text(
          _version,
          textAlign: TextAlign.center,
          style: TextStyle(
            fontFamily: AppTextStyles.fontFamily,
            fontSize: compact ? 11 : 12,
            fontWeight: FontWeight.w400,
            letterSpacing: 0.33,
            height: 1.5,
            color: context.isDarkTheme
                ? palette.textSecondary
                : AppColors.text70,
          ),
        ),
        if (_selfUpdateAvailable) ...[
          SizedBox(height: updateHint == null ? 12 : 10),
          if (updateHint != null) ...[
            Text(
              updateHint,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontFamily: AppTextStyles.fontFamily,
                fontSize: compact ? 10.5 : 11,
                fontWeight: FontWeight.w500,
                letterSpacing: 0.3,
                height: 1.5,
                color: context.isDarkTheme
                    ? palette.textTertiary
                    : AppColors.text50,
              ),
            ),
            const SizedBox(height: 12),
          ],
          GradientButton(
            text: _isCheckingUpdate
                ? context.trLegacy('检查中...')
                : (_updateStatus?.hasUpdate == true
                      ? context.trLegacy('查看新版本')
                      : context.trLegacy('检查更新')),
            width: 180,
            height: compact ? 40 : 44,
            gradientColors: updateButtonGradient,
            textStyle: TextStyle(
              color: updateButtonTextColor,
              fontSize: compact ? 15 : 16,
              fontFamily: AppTextStyles.fontFamily,
              fontWeight: FontWeight.w500,
              height: 1.5,
              letterSpacing: 0.5,
            ),
            enabled: !_isCheckingUpdate,
            onTap: _handlePrimaryAction,
          ),
          const SizedBox(height: 12),
        ] else
          const SizedBox(height: 12),
        _buildAboutActionButton(
          icon: LucideIcons.receiptText,
          label: context.trLegacy('请求日志'),
          compact: compact,
          onPressed: () {
            GoRouterManager.push('/my/about/request-logs');
          },
        ),
        SizedBox(height: compact ? 6 : 8),
        _buildAboutActionButton(
          icon: LucideIcons.bug,
          label: context.trLegacy('运行日志'),
          compact: compact,
          onPressed: () {
            GoRouterManager.push('/my/about/runtime-logs');
          },
        ),
        SizedBox(height: compact ? 6 : 8),
        _buildAboutActionButton(
          icon: LucideIcons.bookOpen,
          label: context.trLegacy('使用手册'),
          compact: compact,
          onPressed: _openUserGuide,
        ),
      ],
    );
  }

  Widget _buildAboutActionButton({
    required IconData icon,
    required String label,
    required VoidCallback onPressed,
    required bool compact,
  }) {
    final palette = context.omniPalette;
    return OutlinedButton.icon(
      onPressed: onPressed,
      icon: Icon(icon, size: 18),
      label: Text(label),
      style: OutlinedButton.styleFrom(
        minimumSize: Size(180, compact ? 40 : 44),
        foregroundColor: context.isDarkTheme
            ? palette.textPrimary
            : AppColors.text,
        side: BorderSide(
          color: context.isDarkTheme
              ? const Color(0xFF2B3444)
              : const Color(0xFFD6E0EE),
        ),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        textStyle: const TextStyle(
          fontFamily: AppTextStyles.fontFamily,
          fontSize: 14.5,
          fontWeight: FontWeight.w500,
          height: 1.4,
        ),
      ),
    );
  }

  Widget _buildFlatSettingRow({
    required String title,
    required String subtitle,
    required Widget trailing,
    VoidCallback? onTap,
    bool isLast = false,
    bool compact = false,
    String? bottomNote,
  }) {
    final palette = context.omniPalette;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        splashColor: palette.accentPrimary.withValues(alpha: 0.08),
        highlightColor: Colors.transparent,
        child: Padding(
          padding: EdgeInsets.fromLTRB(
            4,
            compact ? 12 : 14,
            2,
            compact ? (isLast ? 12 : 11) : (isLast ? 14 : 13),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          title,
                          style: TextStyle(
                            fontFamily: AppTextStyles.fontFamily,
                            fontSize: 14,
                            fontWeight: FontWeight.w500,
                            color: context.isDarkTheme
                                ? palette.textPrimary
                                : AppColors.text,
                            height: 1.5,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          subtitle,
                          style: TextStyle(
                            fontFamily: AppTextStyles.fontFamily,
                            fontSize: 11,
                            fontWeight: FontWeight.w400,
                            height: 1.55,
                            color: context.isDarkTheme
                                ? palette.textSecondary
                                : AppColors.text70,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 12),
                  trailing,
                ],
              ),
              if (bottomNote != null) ...[
                SizedBox(height: compact ? 6 : 8),
                Text(
                  bottomNote,
                  style: TextStyle(
                    fontFamily: AppTextStyles.fontFamily,
                    fontSize: compact ? 10 : 10.5,
                    fontWeight: FontWeight.w400,
                    height: 1.45,
                    color: context.isDarkTheme
                        ? palette.textTertiary
                        : AppColors.text50,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildFlatSectionDivider() {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.only(left: 12),
      child: Divider(
        height: 1,
        thickness: 1,
        color: palette.borderSubtle.withValues(
          alpha: context.isDarkTheme ? 0.5 : 0.78,
        ),
      ),
    );
  }

  Widget _buildDownloadSourceTrailing() {
    final palette = context.omniPalette;
    return DropdownButtonHideUnderline(
      child: DropdownButton<AppUpdateDownloadSource>(
        key: const ValueKey('about-download-source-dropdown'),
        value: _downloadSource,
        isDense: true,
        itemHeight: null,
        menuMaxHeight: 260,
        dropdownColor: context.isDarkTheme
            ? palette.surfacePrimary
            : Colors.white,
        borderRadius: BorderRadius.circular(16),
        icon: Icon(
          LucideIcons.chevronDown,
          size: 18,
          color: context.isDarkTheme ? palette.textTertiary : AppColors.text50,
        ),
        style: TextStyle(
          color: context.isDarkTheme ? palette.textPrimary : AppColors.text,
          fontWeight: FontWeight.w600,
          fontSize: 13,
          fontFamily: AppTextStyles.fontFamily,
        ),
        selectedItemBuilder: (context) {
          return AppUpdateDownloadSource.values.map((source) {
            return Align(
              alignment: Alignment.centerRight,
              child: Text(
                _downloadSourceLabel(source),
                style: TextStyle(
                  color: context.isDarkTheme
                      ? palette.textPrimary
                      : AppColors.text,
                  fontWeight: FontWeight.w600,
                  fontSize: 13,
                  fontFamily: AppTextStyles.fontFamily,
                ),
              ),
            );
          }).toList();
        },
        items: AppUpdateDownloadSource.values.map((source) {
          return DropdownMenuItem<AppUpdateDownloadSource>(
            value: source,
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _downloadSourceLabel(source),
                    style: TextStyle(
                      color: context.isDarkTheme
                          ? palette.textPrimary
                          : AppColors.text,
                      fontWeight: FontWeight.w600,
                      fontSize: 14,
                      fontFamily: AppTextStyles.fontFamily,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    _downloadSourceDescription(source),
                    style: TextStyle(
                      color: context.isDarkTheme
                          ? palette.textSecondary
                          : AppColors.text70,
                      fontWeight: FontWeight.w400,
                      fontSize: 11,
                      height: 1.35,
                      fontFamily: AppTextStyles.fontFamily,
                    ),
                  ),
                ],
              ),
            ),
          );
        }).toList(),
        onChanged: _isUpdatingDownloadSource
            ? null
            : _handleSelectDownloadSource,
      ),
    );
  }

  String _privacyDecisionLabel(PrivacyConsentDecision decision) {
    return switch (decision) {
      PrivacyConsentDecision.pending => context.l10n.aboutPrivacyConsentPending,
      PrivacyConsentDecision.granted => context.l10n.aboutPrivacyConsentGranted,
      PrivacyConsentDecision.declined =>
        context.l10n.aboutPrivacyConsentDeclined,
    };
  }

  Widget _buildPrivacyConsentTrailing() {
    final palette = context.omniPalette;
    return DropdownButtonHideUnderline(
      child: DropdownButton<PrivacyConsentDecision>(
        key: const ValueKey('about-privacy-consent-dropdown'),
        value: _privacyDecision,
        isDense: true,
        dropdownColor: context.isDarkTheme
            ? palette.surfacePrimary
            : Colors.white,
        borderRadius: BorderRadius.circular(16),
        style: TextStyle(
          color: context.isDarkTheme ? palette.textPrimary : AppColors.text,
          fontWeight: FontWeight.w600,
          fontSize: 13,
          fontFamily: AppTextStyles.fontFamily,
        ),
        items: PrivacyConsentDecision.values.map((decision) {
          return DropdownMenuItem<PrivacyConsentDecision>(
            value: decision,
            enabled: decision != PrivacyConsentDecision.pending,
            child: Text(_privacyDecisionLabel(decision)),
          );
        }).toList(),
        onChanged: _isUpdatingPrivacyDecision
            ? null
            : _handleSelectPrivacyDecision,
      ),
    );
  }

  Widget _buildPreferenceSection(bool compact) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SettingsSectionTitle(
          label: context.l10n.aboutPreferencesSectionTitle,
          bottomPadding: compact ? 6 : 8,
        ),
        AnimatedOpacity(
          duration: const Duration(milliseconds: 180),
          opacity: _isUpdatingPrivacyDecision ? 0.72 : 1,
          child: _buildFlatSettingRow(
            title: context.l10n.aboutPrivacyConsentTitle,
            subtitle: context.l10n.aboutPrivacyConsentDescription,
            compact: compact,
            trailing: _buildPrivacyConsentTrailing(),
            isLast: !_selfUpdateAvailable,
          ),
        ),
        if (_selfUpdateAvailable) ...[
          _buildFlatSectionDivider(),
          AnimatedOpacity(
            duration: const Duration(milliseconds: 180),
            opacity: _isUpdatingBetaOptIn ? 0.72 : 1,
            child: _buildFlatSettingRow(
              title: context.l10n.aboutBetaProgramTitle,
              subtitle: context.l10n.aboutBetaProgramDescription,
              compact: compact,
              onTap: _isUpdatingBetaOptIn
                  ? null
                  : () => _handleToggleBetaOptIn(!_betaOptIn),
              trailing: IgnorePointer(
                child: FlutterSwitch(
                  width: 44.8,
                  height: 25.0,
                  toggleSize: 15.3,
                  padding: 4.8,
                  activeColor: context.omniPalette.accentPrimary,
                  inactiveColor: context.omniPalette.borderStrong,
                  value: _betaOptIn,
                  borderRadius: 28.75,
                  duration: _hasSyncedUpdatePreferences
                      ? const Duration(milliseconds: 200)
                      : Duration.zero,
                  onToggle: (_) {},
                ),
              ),
            ),
          ),
          _buildFlatSectionDivider(),
          AnimatedOpacity(
            duration: const Duration(milliseconds: 180),
            opacity: _isUpdatingDownloadSource ? 0.72 : 1,
            child: _buildFlatSettingRow(
              title: context.l10n.aboutApkSourceTitle,
              subtitle: context.l10n.aboutApkSourceDescription,
              bottomNote: context.l10n.aboutApkSourceDisclaimer,
              compact: compact,
              trailing: _buildDownloadSourceTrailing(),
              isLast: true,
            ),
          ),
        ],
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final darkAccent = HSLColor.fromColor(palette.accentPrimary);
    final updateButtonGradient = context.isDarkTheme
        ? <Color>[
            darkAccent
                .withSaturation((darkAccent.saturation * 0.72).clamp(0.0, 1.0))
                .withLightness((darkAccent.lightness - 0.08).clamp(0.0, 1.0))
                .toColor(),
            darkAccent
                .withSaturation((darkAccent.saturation * 0.66).clamp(0.0, 1.0))
                .withLightness((darkAccent.lightness + 0.02).clamp(0.0, 1.0))
                .toColor(),
          ]
        : const <Color>[Color(0xFF1930D9), Color(0xFF2DA5F0)];
    final updateButtonTextColor = context.isDarkTheme
        ? (ThemeData.estimateBrightnessForColor(updateButtonGradient.last) ==
                  Brightness.dark
              ? Colors.white
              : const Color(0xFF171916))
        : Colors.white;

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: context.l10n.settingsAboutTitle,
        primary: true,
      ),
      body: SafeArea(
        top: false,
        bottom: false,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final compact = constraints.maxHeight < 760;
            return Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 468),
                child: SingleChildScrollView(
                  padding: edgeToEdgeScrollPadding(
                    context,
                    EdgeInsets.fromLTRB(
                      24,
                      compact ? 8 : 18,
                      24,
                      compact ? 12 : 22,
                    ),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      SizedBox(height: compact ? 4 : 12),
                      _buildHero(compact),
                      SizedBox(height: compact ? 16 : 20),
                      _buildUpdateSection(
                        compact,
                        updateButtonGradient,
                        updateButtonTextColor,
                      ),
                      SizedBox(height: compact ? 18 : 24),
                      _buildPreferenceSection(compact),
                      SizedBox(height: compact ? 8 : 16),
                    ],
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}
