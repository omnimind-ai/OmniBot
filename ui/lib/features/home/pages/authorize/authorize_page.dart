import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_section.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/common_app_bar.dart';

import '../../../../services/device_service.dart';
import '../../../../services/permission_service.dart';
import '../../../../services/special_permission.dart';
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
      _checkPermissions();
    }
  }

  Future<void> _loadDeviceBrandAndPermissions() async {
    try {
      if (!mounted) return;
      setState(() {
        _isLoading = true;
      });

      await refreshAppEditionCapabilitySnapshot();
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
    } catch (e) {
      if (!mounted) return;

      await refreshAppEditionCapabilitySnapshot();
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
    }
  }

  Future<void> _checkPermissions() async {
    await PermissionService.checkPermissions(items);
    if (!mounted) return;
    _updateContinueState(items);
    if (_canContinue.value) {
      Navigator.of(context).pop(true);
    }
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
        primary: true,
        onBackPressed: () => Navigator.of(context).pop(false),
      ),
      body: SafeArea(
        top: false,
        bottom: false,
        child: Column(
          children: [
            Expanded(
              child: SingleChildScrollView(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 24.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const SizedBox(height: 35),
                      Text(
                        LegacyTextLocalizer.localize('设置权限'),
                        style: TextStyle(
                          color: palette.textPrimary,
                          fontSize: 35,
                          fontWeight: FontWeight.w500,
                          height: 0.86,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        LegacyTextLocalizer.localize('请放心，这些权限你随时可以收回'),
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 14,
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
                            fontSize: 14,
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
              minimum: const EdgeInsets.fromLTRB(62, 16, 62, 16),
              child: ValueListenableBuilder<bool>(
                valueListenable: _canContinue,
                builder: (context, authorized, child) {
                  return GestureDetector(
                    onTap: () async {
                      if (authorized) {
                        if (!context.mounted) return;
                        Navigator.of(context).pop(true);
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
                          gradient: const LinearGradient(
                            begin: Alignment(0.14, -1.09),
                            end: Alignment(1.10, 1.26),
                            colors: [Color(0xFF1930D9), Color(0xFF2CA5F0)],
                          ),
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Center(
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              if (_isLoading) ...[
                                const SizedBox(
                                  width: 16,
                                  height: 16,
                                  child: CircularProgressIndicator(
                                    color: Colors.white,
                                    strokeWidth: 2,
                                  ),
                                ),
                                const SizedBox(width: 8),
                                Text(
                                  LegacyTextLocalizer.localize('权限检查中...'),
                                  style: TextStyle(
                                    color: Colors.white,
                                    fontSize: 17,
                                    fontWeight: FontWeight.w600,
                                    height: 1.29,
                                  ),
                                ),
                              ] else
                                Text(
                                  LegacyTextLocalizer.localize('继续任务'),
                                  style: TextStyle(
                                    color: Colors.white,
                                    fontSize: 17,
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
    );
  }
}
