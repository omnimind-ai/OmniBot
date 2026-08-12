import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_section.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/device_service.dart';
import 'package:ui/services/permission_registry.dart';
import 'package:ui/services/special_permission.dart';

/// 权限管理服务
/// 提供统一的权限检查、状态管理和转换方法
class PermissionService {
  PermissionService._();

  static bool isPermissionAvailable(String id) {
    return switch (id) {
      kInstalledAppsPermissionId =>
        currentAppEditionCapabilitySnapshot.installedAppsQuery,
      kPublicStoragePermissionId =>
        currentAppEditionCapabilitySnapshot.publicStorageAccess,
      _ => true,
    };
  }

  static _PermissionDisplaySpec? _specialDisplaySpec(String id) {
    if (!isPermissionAvailable(id)) return null;
    return switch (id) {
      kWorkspaceStoragePermissionId => _PermissionDisplaySpec(
        id: kWorkspaceStoragePermissionId,
        iconPath: 'assets/welcome/permission_installed_apps.svg',
        iconWidth: 32,
        iconHeight: 32,
        name: LegacyTextLocalizer.isEnglish
            ? 'Built-in workspace'
            : '内置 workspace',
        description: LegacyTextLocalizer.isEnglish
            ? 'Omnibot maintains `/workspace` internally; public storage permission is usually unnecessary'
            : 'Omnibot 会在应用内部维护 `/workspace`，通常无需再单独授予公共存储权限',
      ),
      kPublicStoragePermissionId => _PermissionDisplaySpec(
        id: kPublicStoragePermissionId,
        iconPath: 'assets/welcome/permission_installed_apps.svg',
        iconWidth: 32,
        iconHeight: 32,
        name: LegacyTextLocalizer.isEnglish
            ? 'Public Storage Access'
            : '公共文件访问',
        description: LegacyTextLocalizer.isEnglish
            ? 'Allow agent to read/write files in public storage'
            : '允许 agent 读取和操作安卓公共存储中的文件与文件夹',
      ),
      kShizukuPermissionId => _PermissionDisplaySpec(
        id: kShizukuPermissionId,
        iconPath: 'assets/welcome/permission_installed_apps.svg',
        iconWidth: 32,
        iconHeight: 32,
        name: LegacyTextLocalizer.isEnglish
            ? 'Shizuku Permission'
            : 'Shizuku 权限',
        description: LegacyTextLocalizer.isEnglish
            ? 'Optional advanced system actions for the agent'
            : '可选的高级系统能力，用于扩展 agent 的系统级操作边界',
      ),
      _ => null,
    };
  }

  /// 根据品牌加载权限规格列表
  static List<PermissionSpec> loadSpecs({
    required String brand,
    bool includeOptionalAdvanced = false,
  }) {
    return PermissionRegistry.getPermissions(
      brand: brand,
      includeOptionalAdvanced: includeOptionalAdvanced,
    );
  }

  /// 将权限规格转换为 PermissionData（供 UI 组件使用）
  ///
  /// [specs] 权限规格列表
  /// [context] BuildContext，用于显示自定义授权弹窗
  static List<PermissionData> specsToPermissionData(
    List<PermissionSpec> specs, {
    required BuildContext context,
  }) {
    return specs.map((spec) {
      return PermissionData(
        id: spec.id,
        iconPath: spec.iconPath,
        iconWidth: spec.iconWidth,
        iconHeight: spec.iconHeight,
        name: spec.name,
        description: spec.description,
        onAuthorize: () async {
          // 优先使用 spec 中定义的自定义授权方法
          if (spec.customAuthMethod != null) {
            await spec.customAuthMethod!(context);
          } else {
            // 使用默认的原生方法
            await spePermission.invokeMethod(spec.openMethod);
          }
        },
        checkAuthorization: () async {
          // 优先使用 spec 中定义的自定义检查方法
          if (spec.customCheckMethod != null) {
            return await spec.customCheckMethod!();
          }

          // 使用默认的原生方法检查
          try {
            if (spec.checkMethod.isEmpty) {
              return false;
            }
            final result = await spePermission.invokeMethod(spec.checkMethod);
            return result ?? false;
          } catch (e) {
            return false;
          }
        },
        iconInfo: spec.infoLabel,
        iconClick: spec.onInfoClick,
      );
    }).toList();
  }

  /// 检查指定权限列表中缺失的权限
  ///
  /// [specs] 权限规格列表
  /// 返回未授权的权限规格列表
  static Future<List<PermissionSpec>> getMissing(
    List<PermissionSpec> specs,
  ) async {
    final missing = <PermissionSpec>[];

    for (final spec in specs) {
      try {
        // 优先使用自定义检查方法
        if (spec.customCheckMethod != null) {
          final granted = await spec.customCheckMethod!();
          if (!granted) {
            missing.add(spec);
          }
          continue;
        }

        final granted = await spePermission.invokeMethod(spec.checkMethod);
        if (granted != true) {
          missing.add(spec);
        }
      } catch (e) {
        missing.add(spec);
      }
    }

    return missing;
  }

  /// 检查是否所有权限都已授权
  static Future<bool> allGranted(List<PermissionSpec> specs) async {
    final missing = await getMissing(specs);
    return missing.isEmpty;
  }

  /// 检查指定的权限规格列表
  ///
  /// 为每个 PermissionData 检查授权状态并更新其 notifier
  /// [permissions] 需要检查的权限数据列表
  static Future<void> checkPermissions(List<PermissionData> permissions) async {
    for (var item in permissions) {
      bool granted = await item.checkAuthorization();
      item.notifier.value = granted;
    }
  }

  /// 检查所有权限是否已授权
  ///
  /// [permissions] 权限数据列表
  /// 返回是否全部授权
  static bool checkAllAuthorized(List<PermissionData> permissions) {
    return permissions.every((item) => item.notifier.value);
  }

  static bool checkAuthorizedByIds(
    List<PermissionData> permissions,
    Set<String> requiredIds,
  ) {
    if (requiredIds.isEmpty) {
      return checkAllAuthorized(permissions);
    }

    final matchedPermissions = permissions
        .where((item) => requiredIds.contains(item.id))
        .toList(growable: false);
    final matchedIds = matchedPermissions.map((item) => item.id).toSet();

    if (!requiredIds.every(matchedIds.contains)) {
      return false;
    }

    return matchedPermissions.every((item) => item.notifier.value);
  }

  /// 检查指定层级的缺失权限
  ///
  /// [brand] 设备品牌
  /// [level] 权限层级
  /// 返回该层级下未授权的权限规格列表
  static Future<List<PermissionSpec>> getMissingByLevel({
    required String brand,
    required PermissionLevel level,
    bool includeOptionalAdvanced = false,
  }) async {
    final specs = PermissionRegistry.getPermissionsByLevel(
      brand: brand,
      level: level,
      includeOptionalAdvanced: includeOptionalAdvanced,
    );
    return getMissing(specs);
  }

  /// 检查指定层级权限是否全部授权
  ///
  /// [brand] 设备品牌
  /// [level] 权限层级
  /// 返回是否全部授权
  static Future<bool> allGrantedByLevel({
    required String brand,
    required PermissionLevel level,
    bool includeOptionalAdvanced = false,
  }) async {
    final missing = await getMissingByLevel(
      brand: brand,
      level: level,
      includeOptionalAdvanced: includeOptionalAdvanced,
    );
    return missing.isEmpty;
  }

  static PermissionData? buildSpecialPermissionData(
    String id, {
    required BuildContext context,
  }) {
    final spec = _specialDisplaySpec(id);
    if (spec == null) {
      return null;
    }
    switch (id) {
      case kWorkspaceStoragePermissionId:
        return _buildDisplayPermissionData(
          spec,
          onAuthorize: openWorkspaceStorageSettings,
          checkAuthorization: isWorkspaceStorageAccessGranted,
        );
      case kPublicStoragePermissionId:
        return _buildDisplayPermissionData(
          spec,
          onAuthorize: openPublicStorageSettings,
          checkAuthorization: isPublicStorageAccessGranted,
        );
      case kShizukuPermissionId:
        return _buildDisplayPermissionData(
          spec,
          onAuthorize: () async {
            await ensureShizukuPermission(context);
          },
          checkAuthorization: () async {
            final status = await getShizukuStatus();
            return status.isGranted;
          },
        );
    }
    return null;
  }

  static List<PermissionData> buildDisplayPermissionsForIds(
    Iterable<String> ids,
  ) {
    return ids
        .where(isPermissionAvailable)
        .map((id) {
          final special = _specialDisplaySpec(id);
          if (special != null) {
            return _buildDisplayPermissionData(
              special,
              onAuthorize: () async {},
              checkAuthorization: () async => false,
            );
          }
          return switch (id) {
            kOverlayPermissionId => PermissionData(
              id: kOverlayPermissionId,
              iconPath: 'assets/welcome/permission_overlay.svg',
              iconWidth: 32,
              iconHeight: 32,
              name: LegacyTextLocalizer.isEnglish
                  ? 'Overlay Permission'
                  : '悬浮窗权限',
              description: LegacyTextLocalizer.isEnglish
                  ? 'Desktop overlay for quick access'
                  : '桌面悬浮显示，快速唤起小万',
              onAuthorize: () async {},
              checkAuthorization: () async => false,
            ),
            kInstalledAppsPermissionId => PermissionData(
              id: kInstalledAppsPermissionId,
              iconPath: 'assets/welcome/permission_installed_apps.svg',
              iconWidth: 32,
              iconHeight: 32,
              name: LegacyTextLocalizer.isEnglish
                  ? 'Installed Apps Access'
                  : '应用列表读取',
              description: LegacyTextLocalizer.isEnglish
                  ? 'Identify installed apps for app context'
                  : '识别已安装应用并提供应用上下文',
              onAuthorize: () async {},
              checkAuthorization: () async => false,
            ),
            kShizukuPermissionId => PermissionData(
              id: kShizukuPermissionId,
              iconPath: 'assets/welcome/permission_installed_apps.svg',
              iconWidth: 32,
              iconHeight: 32,
              name: LegacyTextLocalizer.isEnglish
                  ? 'Shizuku Permission'
                  : 'Shizuku 权限',
              description: LegacyTextLocalizer.isEnglish
                  ? 'Optional advanced system actions for the agent'
                  : '可选的高级系统能力，用于扩展 agent 的系统级操作边界',
              onAuthorize: () async {},
              checkAuthorization: () async => false,
            ),
            _ => null,
          };
        })
        .whereType<PermissionData>()
        .toList(growable: false);
  }

  static PermissionData _buildDisplayPermissionData(
    _PermissionDisplaySpec spec, {
    required Future<void> Function() onAuthorize,
    required Future<bool> Function() checkAuthorization,
  }) {
    return PermissionData(
      id: spec.id,
      iconPath: spec.iconPath,
      iconWidth: spec.iconWidth,
      iconHeight: spec.iconHeight,
      name: spec.name,
      description: spec.description,
      onAuthorize: onAuthorize,
      checkAuthorization: checkAuthorization,
    );
  }
}

class _PermissionDisplaySpec {
  final String id;
  final String iconPath;
  final double iconWidth;
  final double iconHeight;
  final String name;
  final String description;

  const _PermissionDisplaySpec({
    required this.id,
    required this.iconPath,
    required this.iconWidth,
    required this.iconHeight,
    required this.name,
    required this.description,
  });
}

/// 快速检查缺失权限数量的辅助函数
///
/// [brand] 设备品牌，如 'huawei', 'xiaomi', 'oppo', 'vivo' 等
/// 如果 brand 为 null 或空字符串，则自动检测设备品牌
Future<int> checkMissingPermissions(String? brand) async {
  await refreshAppEditionCapabilitySnapshot();
  if (brand == null || brand.isEmpty) {
    final deviceInfo = await DeviceService.getDeviceInfo();
    brand = (deviceInfo?['brand'] as String?)?.toLowerCase() ?? 'other';
  }
  final specs = PermissionService.loadSpecs(brand: brand);
  final missing = await PermissionService.getMissing(specs);
  return missing.length;
}
