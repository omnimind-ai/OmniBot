import 'dart:io';
import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:ui/models/app_icons.dart';
import 'package:ui/utils/cache_util.dart';


class ImageUtil {
    /// 构建图片组件，支持本地文件路径和 asset 资源
  static Image buildImage(String imagePath, {double? width, BoxFit fit = BoxFit.cover}) {
    return isLocalFilePath(imagePath)
            ? Image.file(
                File(imagePath),
                width: width,
                fit: fit,
                errorBuilder: (context, error, stackTrace) {
                  return Container(
                    color: Colors.grey.withOpacity(0.3),
                    child: Icon(
                      Icons.image,
                      color: Colors.grey.shade600,
                      size: 32,
                    ),
                  );
                },
              )
            : Image.asset(
                imagePath,
                width: width,
                fit: fit,
                errorBuilder: (context, error, stackTrace) {
                  return Container(
                    color: Colors.grey.withOpacity(0.3),
                    child: Icon(
                      Icons.image,
                      color: Colors.grey.shade600,
                      size: 32,
                    ),
                  );
                },

    );
  }    

  static ImageProvider buildImageProvider(String imagePath) {
    return isLocalFilePath(imagePath)
        ? FileImage(File(imagePath))
        : AssetImage(imagePath);
  }

  /// 判断是否为本地文件路径
  static bool isLocalFilePath(String path) {
    return path.startsWith('/') || path.startsWith('file://');
  }

  /// 批量获取应用图标
  /// 
  /// [packageNames] 应用包名集合
  /// [context] BuildContext,用于预缓存图标
  /// 
  /// 返回 Map<包名, ImageProvider?>
  static Future<Map<String, ImageProvider?>> batchLoadAppIcons(
    Set<String> packageNames,
    BuildContext context,
  ) async {
    final Map<String, ImageProvider?> iconProviders = {};

    try {
      // 批量获取所有图标数据以提升性能
      final appIcons = await CacheUtil.getAppIconsByPackageNames(packageNames.toList());
      
      // 将获取到的图标转换为 Map 以便快速查找
      final Map<String, AppIcons> iconMap = {
        for (var icon in appIcons) icon.packageName: icon
      };

      for (final pkg in packageNames) {
        final raw = iconMap[pkg];
        
        // 优先使用 icon_path
        final iconPath = raw?.icon_path;
        if (iconPath != null && iconPath.isNotEmpty) {
          iconProviders[pkg] = buildImageProvider(iconPath);
          continue;
        }

        // 其次使用 icon_base64
        final iconBase64 = raw?.icon_base64;
        if (iconBase64 != null && iconBase64.isNotEmpty) {
          final bytes = _getBytesFromBase64(iconBase64);
          if (bytes.isNotEmpty) {
            iconProviders[pkg] = MemoryImage(bytes) as ImageProvider;
            continue;
          }
        }
        
        iconProviders[pkg] = null;
      }
    } catch (e) {
      // 发生错误时，确保所有包名都有对应的 null 值
      for (final pkg in packageNames) {
        iconProviders[pkg] ??= null;
      }
    }

    // 预缓存图标
    if (context.mounted) {
      for (final entry in iconProviders.entries) {
        final provider = entry.value;
        if (provider != null) {
          try {
            await precacheImage(provider, context);
          } catch (e) {
          }
        }
      }
    }

    return iconProviders;
  }

  /// 从 base64 字符串解码为字节数组
  static Uint8List _getBytesFromBase64(String base64String) {
    // 移除可能的前缀（例如：data:image/png;base64,）
    String cleaned = base64String;
    if (base64String.contains(',')) {
      cleaned = base64String.split(',').last;
    }
    try {
      return base64Decode(cleaned);
    } catch (e) {
      // 返回空字节数组作为降级方案
      return Uint8List(0);
    }
  }
}
