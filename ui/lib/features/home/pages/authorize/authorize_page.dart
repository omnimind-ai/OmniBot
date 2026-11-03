import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_section.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';

import '../../../../services/device_service.dart';
import '../../../../services/permission_service.dart';
import 'authorize_page_args.dart';

class AuthorizePage extends StatefulWidget {
  final AuthorizePageArgs? args;

  const AuthorizePage({super.key, this.args});

  @override
  State<AuthorizePage> createState() => _AuthorizePageState();
}

class _AuthorizePageState extends State<AuthorizePage>
    with WidgetsBindingObserver {
  final ValueNotifier<bool> _canContinue = ValueNotifier(false);

  List<PermissionData> items = <PermissionData>[];
  bool _isLoading = false;
  bool _isCheckingPermissions = false;
  bool _didFinish = false;
  bool _autoAuthorizationEnabled = false;
  String? _autoPermissionInFlightId;

  Set<String> get _requiredPermissionIds =>
      widget.args?.requiredPermissionIds.toSet() ?? const <String>{};

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadDeviceBrandAndPermissions();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _canContinue.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && !_isLoading) {
      _checkPermissions().then((_) {
        if (mounted) {
          return _authorizeNextRequiredPermission();
        }
      });
    }
  }

  Future<void> _loadDeviceBrandAndPermissions() async {
    try {
      if (!mounted) return;
      setState(() {
        _isLoading = true;
      });

      final deviceInfo = await DeviceService.getDeviceInfo();
      final brand = (deviceInfo?['brand'] as String?)?.toLowerCase() ?? 'other';
      if (!mounted) return;

      final specs = PermissionService.loadSpecs(
        brand: brand,
        includeOptionalAdvanced: true,
      );
      final loadedPermissions = PermissionService.specsToPermissionData(
        specs,
        context: context,
      );
      _appendSpecialPermissionsIfNeeded(loadedPermissions);

      await PermissionService.checkPermissions(loadedPermissions);
      if (!mounted) return;

      _updateContinueState(loadedPermissions);

      setState(() {
        items = loadedPermissions;
        _isLoading = false;
      });
      await _startAutomaticRequiredAuthorization();
    } catch (e) {
      debugPrint('获取设备品牌失败: $e');
      if (!mounted) return;

      final specs = PermissionService.loadSpecs(
        brand: 'other',
        includeOptionalAdvanced: true,
      );
      final fallbackPermissions = PermissionService.specsToPermissionData(
        specs,
        context: context,
      );
      _appendSpecialPermissionsIfNeeded(fallbackPermissions);

      await PermissionService.checkPermissions(fallbackPermissions);
      if (!mounted) return;

      _updateContinueState(fallbackPermissions);

      setState(() {
        items = fallbackPermissions;
        _isLoading = false;
      });
      await _startAutomaticRequiredAuthorization();
    }
  }

  Future<void> _checkPermissions() async {
    if (_isCheckingPermissions || _didFinish || _isLoading) return;

    _isCheckingPermissions = true;
    try {
      await PermissionService.checkPermissions(items);
      if (!mounted || _didFinish) return;
      _updateContinueState(items);
      if (_canContinue.value) {
        _finish(true);
      }
    } finally {
      _isCheckingPermissions = false;
    }
  }

  /// Task-triggered authorization should only open the permissions explicitly
  /// required by that task. The regular onboarding page passes no required
  /// IDs, so its existing manual setup flow remains unchanged.
  Future<void> _startAutomaticRequiredAuthorization() async {
    if (_requiredPermissionIds.isEmpty || !mounted || _didFinish) return;
    _autoAuthorizationEnabled = true;
    await _checkPermissions();
    if (!mounted || _didFinish) return;
    await _authorizeNextRequiredPermission();
  }

  Future<void> _authorizeNextRequiredPermission() async {
    if (!_autoAuthorizationEnabled ||
        _isLoading ||
        _isCheckingPermissions ||
        !mounted ||
        _didFinish) {
      return;
    }

    final inFlightId = _autoPermissionInFlightId;
    if (inFlightId != null) {
      final inFlight = items.where((item) => item.id == inFlightId).firstOrNull;
      if (inFlight == null || !inFlight.notifier.value) {
        // Settings-based permissions return before the user changes the
        // system toggle. Wait for the next resume rather than repeatedly
        // opening the same settings page.
        return;
      }
      _autoPermissionInFlightId = null;
    }

    final next = items
        .where(
          (item) =>
              _requiredPermissionIds.contains(item.id) && !item.notifier.value,
        )
        .firstOrNull;
    if (next == null) {
      if (_canContinue.value) _finish(true);
      return;
    }

    _autoPermissionInFlightId = next.id;
    await next.authorize();
    if (!mounted || _didFinish) return;
    if (!next.notifier.value) return;

    _autoPermissionInFlightId = null;
    await _checkPermissions();
    if (!mounted || _didFinish) return;
    await _authorizeNextRequiredPermission();
  }

  void _finish(bool result) {
    if (_didFinish || !mounted) return;

    final navigator = Navigator.of(context);
    if (!navigator.canPop()) return;

    _didFinish = true;
    navigator.pop(result);
  }

  void _updateContinueState(List<PermissionData> permissions) {
    _canContinue.value = PermissionService.checkAuthorizedByIds(
      permissions,
      _requiredPermissionIds,
    );
  }

  void _appendSpecialPermissionsIfNeeded(List<PermissionData> permissions) {
    for (final requiredId in _requiredPermissionIds) {
      final exists = permissions.any((item) => item.id == requiredId);
      if (exists) {
        continue;
      }
      final special = PermissionService.buildSpecialPermissionData(
        requiredId,
        context: context,
      );
      if (special != null) {
        permissions.add(special);
      }
    }
  }

  String? _requiredPermissionHint() {
    if (_requiredPermissionIds.isEmpty) return null;

    final labels = items
        .where((item) => _requiredPermissionIds.contains(item.id))
        .map((item) => item.name.trim())
        .where((item) => item.isNotEmpty)
        .toList(growable: false);

    if (labels.isEmpty) return null;
    if (LegacyTextLocalizer.isEnglish) {
      return 'Continue requires only: ${labels.join(', ')}';
    }
    return LegacyTextLocalizer.localize('继续任务仅要求：${labels.join('、')}');
  }

  @override
  Widget build(BuildContext context) {
    final requiredPermissionHint = _requiredPermissionHint();
    final palette = context.omniPalette;

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: LegacyTextLocalizer.localize('设置权限'),
        primary: true,
        onBackPressed: () => _finish(false),
      ),
      body: SafeArea(
        top: false,
        bottom: false,
        child: OmniContentWidth(
          key: const ValueKey('authorize-content-column'),
          child: Column(
            children: [
              Expanded(
                child: SingleChildScrollView(
                  key: const ValueKey('authorize-permission-scroll'),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 22),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const SizedBox(height: 12),
                        Text(
                          LegacyTextLocalizer.localize('请放心，这些权限你随时可以收回'),
                          style: TextStyle(
                            color: palette.textSecondary,
                            fontSize: 15,
                            fontWeight: FontWeight.w400,
                            height: 1.57,
                          ),
                        ),
                        const SizedBox(height: 24),
                        if (requiredPermissionHint != null)
                          Text(
                            requiredPermissionHint,
                            style: TextStyle(
                              color: palette.accentPrimary,
                              fontSize: 15,
                              fontWeight: FontWeight.w500,
                              height: 1.57,
                            ),
                          ),
                        const SizedBox(height: 24),
                        if (_isLoading)
                          const Center(child: CircularProgressIndicator())
                        else
                          PermissionSection(
                            permissions: items,
                            onPermissionChanged: _checkPermissions,
                          ),
                        const SizedBox(height: 20),
                      ],
                    ),
                  ),
                ),
              ),
              SafeArea(
                top: false,
                minimum: const EdgeInsets.fromLTRB(22, 16, 22, 16),
                child: ValueListenableBuilder<bool>(
                  valueListenable: _canContinue,
                  builder: (context, authorized, child) {
                    return GestureDetector(
                      key: const ValueKey('authorize-continue'),
                      onTap: () async {
                        if (authorized) {
                          _finish(true);
                          return;
                        }
                        await _checkPermissions();
                      },
                      child: Opacity(
                        opacity: authorized ? 1.0 : 0.5,
                        child: Container(
                          width: double.infinity,
                          height: 48,
                          decoration: BoxDecoration(
                            color: palette.accentPrimary,
                            borderRadius: BorderRadius.circular(14),
                          ),
                          child: Center(
                            child: Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                if (_isLoading) ...[
                                  SizedBox(
                                    width: 16,
                                    height: 16,
                                    child: CircularProgressIndicator(
                                      color: Theme.of(context)
                                          .colorScheme
                                          .onPrimary,
                                      strokeWidth: 2,
                                    ),
                                  ),
                                  const SizedBox(width: 8),
                                  Text(
                                    LegacyTextLocalizer.localize('权限检查中...'),
                                    style: TextStyle(
                                      color: Theme.of(context)
                                          .colorScheme
                                          .onPrimary,
                                      fontSize: 15,
                                      fontWeight: FontWeight.w600,
                                      height: 1.29,
                                    ),
                                  ),
                                ] else
                                  Text(
                                    LegacyTextLocalizer.localize('继续任务'),
                                    style: TextStyle(
                                      color: Theme.of(context)
                                          .colorScheme
                                          .onPrimary,
                                      fontSize: 15,
                                      fontWeight: FontWeight.w600,
                                      height: 1.29,
                                    ),
                                  ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
