import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:ui/services/cache_service.dart';
import 'package:ui/widgets/image/cached_image.dart';

/// 图片预热缓存管理
/// 使用 MMKV 存储 URL 列表，支持 TTL 过期机制
class ImagePrewarmCacheService {
  static const String _urlsCacheKey = 'image_prewarm_urls';
  static const String _timestampCacheKey = 'image_prewarm_timestamp';
  static const int _ttlMinutes = 10; // 缓存有效期 10 分钟

  /// 获取缓存的 URL 列表
  /// 如果缓存过期或不存在，返回 null
  static Future<List<String>?> getCachedUrls() async {
    try {
      // 检查时间戳（使用 int 存储）
      final timestamp = await CacheService.getInt(
        _timestampCacheKey,
        defaultValue: 0,
      );


      if (timestamp == 0) {
        return null;
      }

      // 检查是否过期
      final cachedAt = DateTime.fromMillisecondsSinceEpoch(timestamp);
      final now = DateTime.now();
      if (now.difference(cachedAt).inMinutes >= _ttlMinutes) {
        // 缓存已过期
        return null;
      }

      // 返回缓存的 URL 列表
      final urls = await CacheService.getStringList(
        _urlsCacheKey,
        defaultValue: [],
      );

      return urls.isNotEmpty ? urls : null;
    } catch (_) {
      return null;
    }
  }

  /// 缓存 URL 列表
  static Future<void> cacheUrls(List<String> urls) async {
    try {
      await CacheService.setStringList(_urlsCacheKey, urls);
      await CacheService.setInt(
        _timestampCacheKey,
        DateTime.now().millisecondsSinceEpoch,
      );
    } catch (_) {
      // Cache writes are best effort.
    }
  }

  /// 清除缓存
  static Future<void> clearCache() async {
    try {
      await CacheService.setStringList(_urlsCacheKey, []);
      await CacheService.setInt(_timestampCacheKey, 0);
    } catch (_) {
      // Cache cleanup is best effort.
    }
  }

  /// 检查缓存是否有效
  static Future<bool> isCacheValid() async {
    final urls = await getCachedUrls();
    return urls != null && urls.isNotEmpty;
  }
}

/// 预热单张图片到内存缓存
Future<void> prewarmImageToMemory(BuildContext context, String imageUrl) async {
  try {
    final imageProvider = CachedNetworkImageProvider(
      imageUrl,
      cacheManager: AppCacheManager.instance,
    );
    await precacheImage(imageProvider, context);
  } catch (_) {
    // Image prewarming is best effort.
  }
}

/// 批量预热图片到内存缓存
Future<void> prewarmImagesToMemory(
  BuildContext context,
  List<String> imageUrls,
) async {
  const batchSize = 3;
  for (int i = 0; i < imageUrls.length; i += batchSize) {
    // 获取当前批次的图片
    var end = (i + batchSize < imageUrls.length)
        ? i + batchSize
        : imageUrls.length;
    var batch = imageUrls.sublist(i, end);

    // 等待当前批次全部完成，再开始下一批
    await Future.wait(batch.map((url) => prewarmImageToMemory(context, url)));
  }
}

/// Suggestion 图标预热服务
/// 使用 MMKV 缓存 URL 列表，10 分钟内不重复请求 API
class SuggestionImagePrewarmService {
  static bool _isPrewarming = false;

  /// 预热 Suggestion 图标
  /// [context] BuildContext
  /// [tag] 日志标签，用于区分调用来源
  static Future<void> prewarm(
    BuildContext context, {
    String tag = 'Prewarm',
  }) async {
    // 防止并行调用
    if (_isPrewarming) return;
    _isPrewarming = true;

    try {
      List<String> iconUrls;

      // 先尝试从缓存获取
      final cachedUrls = await ImagePrewarmCacheService.getCachedUrls();
      if (cachedUrls != null && cachedUrls.isNotEmpty) {
        iconUrls = cachedUrls;
      } else {
        // 开源版不拉取远端推荐任务图标
        iconUrls = const [];
      }

      if (iconUrls.isNotEmpty) {
        if (!context.mounted) return;
        await prewarmImagesToMemory(context, iconUrls);
      }
    } catch (_) {
      // Suggestion icon prewarming is best effort.
    } finally {
      _isPrewarming = false;
    }
  }
}
