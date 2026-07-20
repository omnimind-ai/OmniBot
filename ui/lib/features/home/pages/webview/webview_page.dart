import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:dio/dio.dart';
import 'package:gal/gal.dart';
import 'package:path_provider/path_provider.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';

class WebViewPage extends StatefulWidget {
  final String url;
  final String? title;
  final bool showAppBar;
  final bool enableJavaScript;
  final bool enableZoom;
  final bool showRefreshButton;
  final bool appBarBackClosesPage;

  const WebViewPage({
    super.key,
    required this.url,
    this.title,
    this.showAppBar = true,
    this.enableJavaScript = true,
    this.enableZoom = true,
    this.showRefreshButton = false,
    this.appBarBackClosesPage = false,
  });

  @override
  State<WebViewPage> createState() => _WebViewPageState();
}

class _WebViewPageState extends State<WebViewPage> {
  late final WebViewController _controller;
  bool _isLoading = true;
  String? _errorMessage;
  int _loadingProgress = 0;
  bool _isDownloading = false;

  /// 保存到的相册名称
  static const String _albumName = '小万';

  /// 可下载的文件扩展名
  static const _downloadableExtensions = [
    // 图片
    '.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.svg',
    // // 文档
    // '.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx', '.txt',
    // // 压缩包
    // '.zip', '.rar', '.7z', '.tar', '.gz',
    // // 音视频
    // '.mp3', '.mp4', '.avi', '.mov', '.wav', '.flac',
    // // 安装包
    // '.apk',
  ];

  /// 图片扩展名（保存到相册）
  static const _imageExtensions = [
    '.jpg',
    '.jpeg',
    '.png',
    '.gif',
    '.webp',
    '.bmp',
  ];

  @override
  void initState() {
    super.initState();
    _initializeWebView();
  }

  /// 初始化WebView控制器
  void _initializeWebView() {
    _controller = WebViewController()
      ..setJavaScriptMode(
        widget.enableJavaScript
            ? JavaScriptMode.unrestricted
            : JavaScriptMode.disabled,
      )
      ..setBackgroundColor(Colors.white)
      ..setNavigationDelegate(
        NavigationDelegate(
          onProgress: (int progress) {
            setState(() {
              _loadingProgress = progress;
            });
          },
          onPageStarted: (String url) {
            debugPrint('WebView onPageStarted: $url');
            setState(() {
              _isLoading = true;
              _errorMessage = null;
            });
          },
          onPageFinished: (String url) {
            debugPrint('WebView onPageFinished: $url');
            setState(() {
              _isLoading = false;
            });
          },
          onWebResourceError: (WebResourceError error) {
            debugPrint(
              'WebView onWebResourceError: ${error.description} (${error.url})',
            );
            if (error.isForMainFrame == false) {
              return;
            }
            setState(() {
              _isLoading = false;
              _errorMessage = error.description;
            });
          },
          onNavigationRequest: (NavigationRequest request) {
            // 检查是否为可下载文件
            if (_isDownloadableUrl(request.url)) {
              _handleDownload(request.url);
              return NavigationDecision.prevent;
            }
            return NavigationDecision.navigate;
          },
        ),
      )
      ..enableZoom(widget.enableZoom)
      ..setUserAgent(
        'Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36',
      );

    _loadInitialSource();

    // 打印加载的 URL
    debugPrint('WebViewPage 加载 URL: ${widget.url}');
  }

  Future<void> _loadInitialSource() async {
    final localFilePath = _resolveLocalFilePath(widget.url);
    try {
      final platformController = _controller.platform;
      if (localFilePath != null &&
          platformController is AndroidWebViewController) {
        await Future.wait(<Future<void>>[
          platformController.setAllowFileAccess(true),
          platformController.setAllowContentAccess(true),
        ]);
      }
      if (localFilePath != null) {
        await _controller.loadFile(localFilePath);
        return;
      }
      await _controller.loadRequest(Uri.parse(widget.url));
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _errorMessage = '$error';
      });
    }
  }

  /// 检查URL是否为可下载文件
  bool _isDownloadableUrl(String url) {
    final lowerUrl = url.toLowerCase();
    // 移除URL参数后检查扩展名
    final urlWithoutParams = lowerUrl.split('?').first;
    return _downloadableExtensions.any((ext) => urlWithoutParams.endsWith(ext));
  }

  /// 检查URL是否为图片文件
  bool _isImageUrl(String url) {
    final lowerUrl = url.toLowerCase();
    final urlWithoutParams = lowerUrl.split('?').first;
    return _imageExtensions.any((ext) => urlWithoutParams.endsWith(ext));
  }

  /// 从URL中提取文件名
  String _getFileNameFromUrl(String url) {
    final uri = Uri.parse(url);
    final pathSegments = uri.pathSegments;
    if (pathSegments.isNotEmpty) {
      return pathSegments.last.split('?').first;
    }
    // 生成默认文件名
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    return 'download_$timestamp';
  }

  /// 处理文件下载
  Future<void> _handleDownload(String url) async {
    if (_isDownloading) {
      showToast('正在下载中，请稍候...', type: ToastType.warning);
      return;
    }

    setState(() {
      _isDownloading = true;
    });

    try {
      showToast('开始下载...', type: ToastType.info);

      final dio = Dio();
      final fileName = _getFileNameFromUrl(url);

      // 获取临时目录
      final tempDir = await getTemporaryDirectory();
      final savePath = '${tempDir.path}/$fileName';

      // 下载文件
      await dio.download(
        url,
        savePath,
        onReceiveProgress: (received, total) {
          if (total != -1) {
            final progress = (received / total * 100).toStringAsFixed(0);
            debugPrint('下载进度: $progress%');
          }
        },
      );

      // 如果是图片，保存到相册
      if (_isImageUrl(url)) {
        await Gal.putImage(savePath, album: _albumName);
        showToast('图片已保存到相册', type: ToastType.success);
      } else {
        // 非图片文件，移动到下载目录
        final downloadDir = Directory('/storage/emulated/0/Download');
        if (await downloadDir.exists()) {
          final destPath = '${downloadDir.path}/$fileName';
          await File(savePath).copy(destPath);
          showToast('文件已保存到下载目录', type: ToastType.success);
        } else {
          showToast('文件已下载: $fileName', type: ToastType.success);
        }
      }
    } catch (e) {
      debugPrint('下载失败: $e');
      showToast('下载失败', type: ToastType.error);
    } finally {
      setState(() {
        _isDownloading = false;
      });
    }
  }

  /// 重新加载页面
  void _reload() {
    setState(() {
      _errorMessage = null;
      _isLoading = true;
    });
    _controller.reload();
  }

  /// 处理返回按钮点击
  /// 优先尝试在 WebView 内后退，如果不能后退则关闭页面
  Future<void> _handleBackPress() async {
    final canGoBack = await _controller.canGoBack();
    if (canGoBack) {
      // WebView 可以后退，执行网页内后退
      await _controller.goBack();
    } else {
      // WebView 不能后退，执行页面关闭
      if (mounted) {
        Navigator.of(context).pop();
      }
    }
  }

  void _handleAppBarBackPress() {
    if (widget.appBarBackClosesPage) {
      Navigator.of(context).pop();
      return;
    }
    unawaited(_handleBackPress());
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false, // 禁止默认返回行为，由我们自己处理
      onPopInvokedWithResult: (didPop, result) async {
        if (didPop) return;
        await _handleBackPress();
      },
      child: Scaffold(
        appBar: widget.showAppBar
            ? CommonAppBar(
                primary: true,
                title: widget.title ?? '网页浏览',
                onBackPressed: _handleAppBarBackPress,
                trailing: widget.showRefreshButton
                    ? IconButton(
                        tooltip: \'Action\', icon: const Icon(Icons.refresh),
                        onPressed: _reload,
                        tooltip: '刷新',
                      )
                    : null,
              )
            : null,
        body: SafeArea(
          top: !widget.showAppBar,
          child: Stack(
            children: [
              // WebView内容
              if (_errorMessage == null)
                WebViewWidget(controller: _controller)
              else
                _buildErrorWidget(),

              // 加载进度条
              if (_isLoading && _loadingProgress < 100)
                Positioned(
                  top: 0,
                  left: 0,
                  right: 0,
                  child: LinearProgressIndicator(
                    value: _loadingProgress / 100,
                    backgroundColor: Colors.grey[200],
                    valueColor: AlwaysStoppedAnimation<Color>(
                      Theme.of(context).primaryColor,
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  /// 构建错误提示界面
  Widget _buildErrorWidget() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.error_outline, size: 64, color: Colors.grey[400]),
            const SizedBox(height: 16),
            Text(
              '页面加载失败',
              style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.bold,
                color: Colors.grey[700],
              ),
            ),
            const SizedBox(height: 8),
            Text(
              _errorMessage ?? '未知错误',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 14, color: Colors.grey[600]),
            ),
            const SizedBox(height: 24),
            ElevatedButton.icon(
              onPressed: _reload,
              icon: const Icon(Icons.refresh),
              label: const Text('重新加载'),
            ),
          ],
        ),
      ),
    );
  }
}

String? _resolveLocalFilePath(String source) {
  if (source.isEmpty) return null;
  final uri = Uri.tryParse(source);
  if (uri == null) {
    return source.startsWith('/') ? source : null;
  }
  if (uri.scheme == 'file') {
    return uri.toFilePath();
  }
  if (uri.scheme.isEmpty && source.startsWith('/')) {
    return source;
  }
  return null;
}
