import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/features/welcome/pages/welcome_page/widgets/auto_start_guide_bottom_sheet.dart';
import 'package:ui/services/special_permission.dart';

/// 权限层级枚举
/// 用于按场景分层检查权限
enum PermissionLevel {
  /// 宠物、半屏聊天和提醒的悬浮显示。
  overlayDisplay,

  /// 聊天任务执行：全量权限
  fullExecution,
}

/// 权限规格定义
/// 包含权限的基础信息和操作方法名
class PermissionSpec {
  /// 权限唯一标识
  final String id;

  /// 图标路径
  final String iconPath;

  /// 图标宽度
  final double iconWidth;

  /// 图标高度
  final double iconHeight;

  /// 权限名称
  final String name;

  /// 权限描述
  final String description;

  /// 打开权限设置的方法名（用于 spePermission.invokeMethod）
  final String openMethod;

  /// 检查权限状态的方法名（用于 spePermission.invokeMethod）
  /// 如果为空且未提供 customCheckMethod，则默认返回 false
  final String checkMethod;

  /// 自定义检查权限状态的方法（优先于 checkMethod）
  /// 如果提供，将使用此方法检查权限状态，而不是调用原生方法
  final Future<bool> Function()? customCheckMethod;

  /// 自定义授权处理方法（优先于 openMethod）
  /// 如果提供，将使用此方法处理授权，需要 BuildContext 来显示弹窗
  final Future<void> Function(BuildContext context)? customAuthMethod;

  /// 额外信息标签（如"持久化"）
  final String? infoLabel;

  /// 信息标签点击回调
  final void Function()? onInfoClick;

  /// 该权限适用的分层场景（用于补充分层ID白名单）
  /// 当此字段非空时，getPermissionsByLevel 会额外按层级匹配该权限
  final Set<PermissionLevel>? applicableLevels;

  const PermissionSpec({
    required this.id,
    required this.iconPath,
    required this.iconWidth,
    required this.iconHeight,
    required this.name,
    required this.description,
    required this.openMethod,
    this.checkMethod = '',
    this.customCheckMethod,
    this.customAuthMethod,
    this.infoLabel,
    this.onInfoClick,
    this.applicableLevels,
  });
}

/// 权限注册中心
/// 根据设备品牌返回相应的权限列表
class PermissionRegistry {
  PermissionRegistry._();

  /// 获取指定品牌的权限列表
  ///
  /// [brand] 设备品牌，如 'huawei', 'xiaomi', 'oppo', 'vivo' 等
  /// 返回该品牌需要的权限规格列表
  static List<PermissionSpec> getPermissions({
    required String brand,
    bool includeOptionalAdvanced = false,
  }) {
    // 基础权限列表（所有品牌通用）
    final basePermissions = [
      PermissionSpec(
        id: 'overlay',
        iconPath: 'assets/welcome/permission_overlay.svg',
        iconWidth: 32.0,
        iconHeight: 32.0,
        name: LegacyTextLocalizer.isEnglish ? 'Overlay Permission' : '悬浮窗权限',
        description: LegacyTextLocalizer.isEnglish
            ? 'Desktop overlay for quick access'
            : '桌面悬浮显示，快速唤起小万',
        openMethod: 'openOverlaySettings',
        checkMethod: 'isOverlayPermission',
      ),
      PermissionSpec(
        id: 'battery',
        iconPath: 'assets/welcome/permission_battery.svg',
        iconWidth: 32.0,
        iconHeight: 32.0,
        name: LegacyTextLocalizer.isEnglish
            ? 'Allow background running'
            : '允许后台运行',
        description: LegacyTextLocalizer.isEnglish
            ? 'Keep running in background'
            : '后台持续运行，切出APP不中断服务',
        openMethod: 'openBatteryOptimizationSettings',
        checkMethod: 'isBackgroundRunAllowed',
      ),
      if (currentAppEditionCapabilitySnapshot.installedAppsQuery)
        PermissionSpec(
          id: 'installed_apps',
          iconPath: 'assets/welcome/permission_installed_apps.svg',
          iconWidth: 32.0,
          iconHeight: 32.0,
          name: LegacyTextLocalizer.isEnglish
              ? 'Installed Apps Access'
              : '应用列表读取',
          description: LegacyTextLocalizer.isEnglish
              ? 'Identify installed apps for app context'
              : '识别已安装应用并提供应用上下文',
          openMethod: 'openInstalledAppsSettings',
          checkMethod: 'isInstalledAppsPermissionGranted',
        ),
    ];
    final optionalPermissions = <PermissionSpec>[
      PermissionSpec(
        id: 'shizuku',
        iconPath: 'assets/welcome/permission_installed_apps.svg',
        iconWidth: 32.0,
        iconHeight: 32.0,
        name: LegacyTextLocalizer.isEnglish
            ? 'Shizuku Permission'
            : 'Shizuku 权限',
        description: LegacyTextLocalizer.isEnglish
            ? 'Optional advanced system actions for the agent'
            : '可选的高级系统能力，用于扩展 agent 的系统级操作边界',
        openMethod: 'openShizukuDownloadOrApp',
        customCheckMethod: () async {
          final status = await getShizukuStatus();
          return status.isGranted;
        },
        customAuthMethod: (BuildContext context) async {
          await ensureShizukuPermission(context);
        },
      ),
    ];

    // 根据品牌追加额外权限
    final extraPermissions = _getExtraPermissions(brand);

    return [
      ...basePermissions,
      if (includeOptionalAdvanced) ...optionalPermissions,
      ...extraPermissions,
    ];
  }

  /// 获取特定品牌的额外权限
  static List<PermissionSpec> _getExtraPermissions(String brand) {
    // 将品牌名转为小写统一处理
    final normalizedBrand = brand.toLowerCase();

    switch (normalizedBrand) {
      /// 构建自启动权限（仅华为和荣耀机型）
      case 'honor':
        return [
          PermissionSpec(
            id: "appLaunch",
            iconPath: 'assets/welcome/permission_autostart.svg',
            iconWidth: 32.0,
            iconHeight: 32.0,
            name: LegacyTextLocalizer.isEnglish
                ? 'App launch management'
                : '应用启动管理',
            description: LegacyTextLocalizer.isEnglish
                ? 'Prevent Omnibot from being killed by system'
                : '防止小万被系统关闭',
            openMethod: 'openAutoStartSettings',
            applicableLevels: const {PermissionLevel.fullExecution},
            customCheckMethod: () async {
              return StorageService.getBool(
                    StorageKeys.autoStartPermissionGranted,
                  ) ??
                  false;
            },
            customAuthMethod: (BuildContext context) async {
              await AutoStartGuideBottomSheet.show(
                context,
                onGoToSettings: () async {
                  await spePermission.invokeMethod('openAutoStartSettings');
                },
                onCompleted: () async {
                  await StorageService.setBool(
                    StorageKeys.autoStartPermissionGranted,
                    true,
                  );
                },
              );
            },
          ),
        ];
      default:
        // 其他品牌无额外权限
        return [];
    }
  }

  /// 各权限层级对应的权限ID列表
  static const Map<PermissionLevel, List<String>> _levelPermissionIds = {
    PermissionLevel.overlayDisplay: ['overlay'],
    PermissionLevel.fullExecution: ['overlay', 'battery', 'installed_apps'],
  };

  /// 根据权限层级获取权限规格列表
  ///
  /// [brand] 设备品牌
  /// [level] 权限层级
  /// 返回该层级所需的权限规格列表
  static List<PermissionSpec> getPermissionsByLevel({
    required String brand,
    required PermissionLevel level,
    bool includeOptionalAdvanced = false,
  }) {
    final allPermissions = getPermissions(
      brand: brand,
      includeOptionalAdvanced: includeOptionalAdvanced,
    );
    final requiredIds = _levelPermissionIds[level] ?? [];
    return allPermissions
        .where(
          (spec) =>
              requiredIds.contains(spec.id) ||
              (spec.applicableLevels?.contains(level) ?? false),
        )
        .toList();
  }
}
