import 'package:flutter/material.dart';
import 'package:ui/config/jackery_ces_config.dart';

/// 应用颜色系统 - 基于 Figma 设计令牌
class AppColors {
  // 品牌色系
  static const Color primaryBlue = JackeryCesConfig.enabled
      ? Color(0xFFFF6727)
      : Color(0xFF00AEFF);
  static const Color onBrandPrimary = Color(0xFFFFFFFF); // 品牌色上的文本 - 白色
  static const Color primaryBlue03 = JackeryCesConfig.enabled
      ? Color(0x08FF6727)
      : Color(0x0800AEFF); // 3% 品牌蓝
  static const Color gradientAux = JackeryCesConfig.enabled
      ? Color(0xFFF8F7F5)
      : Color(0xFFC5E0E6);
  static const Color iconPrimary = JackeryCesConfig.enabled
      ? Color(0xFF303030)
      : Color(0xFF1A1A1A);
  static const Color buttonPrimary = JackeryCesConfig.enabled
      ? Color(0xFFFF6727)
      : Color(0xFF2C7FEB);
  static const Color buttonSmall = JackeryCesConfig.enabled
      ? Color(0xFFFFFFFF)
      : Color(0xCCF1F8FF);

  // IP 色系
  static const Color ipBluePurple = JackeryCesConfig.enabled
      ? Color(0xFFFF6727)
      : Color(0xFF4658FF);
  static const Color ipBlueGreen = JackeryCesConfig.enabled
      ? Color(0xFFFF6727)
      : Color(0xFF00CFE1);

  // 特殊提示色
  static const Color alertRed = Color(0xFFFF6464);
  static const Color linkBlue = JackeryCesConfig.enabled
      ? Color(0xFFFF6727)
      : Color(0xFF86A7D9);

  // 文本颜色 - 黑色系
  static const Color text90 = JackeryCesConfig.enabled
      ? Color(0xFF303030)
      : Color(0xE5000000); // 90% 黑
  static const Color text10 = Color(0x1A000000); // 10% 黑
  static const Color text05 = Color(0x0C000000); // 5% 黑
  static const Color text03 = Color(0x08000000); // 3% 黑

  static const Color text = JackeryCesConfig.enabled
      ? Color(0xFF303030)
      : Color(0xFF353E53); // 字色
  static const Color text70 = JackeryCesConfig.enabled
      ? Color(0xB2303030)
      : Color(0xB2353E53); // 70% 字色
  static const Color text50 = JackeryCesConfig.enabled
      ? Color(0x80303030)
      : Color(0x80353E53); // 50% 字色
  static const Color text20 = JackeryCesConfig.enabled
      ? Color(0x33303030)
      : Color(0x33353E53); // 20% 字色

  // 边框颜色
  static const Color borderStandard = JackeryCesConfig.enabled
      ? Color(0xFFE8E5E1)
      : Color(0x1A000000);

  // 按钮文本颜色 - 白色系
  static const Color buttonText100 = Color(0xFFFFFFFF); // 100% 白
  static const Color buttonText90 = Color(0xE5FFFFFF); // 90% 白
  static const Color buttonText50 = Color(0x80FFFFFF); // 50% 白

  static const Color fillStandardSecondary = JackeryCesConfig.enabled
      ? Color(0xFFE8E5E1)
      : Color(0x1A000000); // 标准填充 - 次级

  // 实底色
  static const Color solid20Black = Color(0xFFC6C6C6); // 20% 黑实底

  // 背景色
  static const Color white = Colors.white;
  static const Color background = JackeryCesConfig.enabled
      ? Color(0xFFF8F7F5)
      : Color(0xFFF5F5F5);

  // 品牌渐变（基于设计系统）
  static const LinearGradient primaryGradient = LinearGradient(
    colors: JackeryCesConfig.enabled
        ? [Color(0xFFFFFFFF), Color(0xFFFFFFFF)]
        : [primaryBlue, gradientAux],
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
  );

  // 按钮渐变
  static LinearGradient buttonGradient = LinearGradient(
    begin: Alignment(0.47, 0.41),
    end: Alignment(1.08, 1.42),
    colors: JackeryCesConfig.enabled
        ? [primaryBlue, primaryBlue]
        : [primaryBlue, gradientAux.withValues(alpha: 0.7)],
  );

  static BoxShadow boxShadow = BoxShadow(
    color: Colors.black.withValues(alpha: 0.01),
    blurRadius: 4,
    spreadRadius: 2,
  );
}
