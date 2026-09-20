import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';

class CodexBridgeQrScanResult {
  const CodexBridgeQrScanResult({
    required this.bridgeUrl,
    this.token = '',
    this.cwd = '',
    this.sessionId,
  });

  final String bridgeUrl;
  final String token;
  final String cwd;
  final String? sessionId;

  static CodexBridgeQrScanResult? tryParse(String rawValue) {
    final raw = rawValue.trim();
    if (raw.isEmpty) return null;

    final jsonResult = _tryParseJsonPayload(raw);
    if (jsonResult != null) return jsonResult;

    final uri = Uri.tryParse(raw);
    if (uri == null) return null;
    if (uri.scheme == 'omnibot' &&
        (uri.host == 'codex-bridge' || uri.path.contains('codex-bridge'))) {
      final bridgeUrl =
          _firstQueryValue(uri, const <String>[
            'bridgeUrl',
            'remoteBridgeUrl',
            'url',
            'wsUrl',
          ]) ??
          '';
      if (bridgeUrl.trim().isEmpty) return null;
      return CodexBridgeQrScanResult(
        bridgeUrl: bridgeUrl.trim(),
        token:
            _firstQueryValue(uri, const <String>[
              'token',
              'bridgeToken',
              'remoteBridgeToken',
            ]) ??
            '',
        cwd: _firstQueryValue(uri, const <String>['cwd', 'remoteCwd']) ?? '',
        sessionId: _firstQueryValue(uri, const <String>['sessionId']),
      );
    }

    if (_isBridgeUrl(uri)) {
      return CodexBridgeQrScanResult(
        bridgeUrl: _stripQuickConnectQuery(uri),
        token:
            _firstQueryValue(uri, const <String>[
              'token',
              'bridgeToken',
              'remoteBridgeToken',
            ]) ??
            '',
        cwd: _firstQueryValue(uri, const <String>['cwd', 'remoteCwd']) ?? '',
        sessionId: _firstQueryValue(uri, const <String>['sessionId']),
      );
    }
    return null;
  }

  static CodexBridgeQrScanResult? _tryParseJsonPayload(String raw) {
    final decoded = runCatchingJsonDecode(raw);
    if (decoded is! Map) return null;
    final map = decoded.map((key, value) => MapEntry(key.toString(), value));
    final type = map['type']?.toString().trim();
    final bridgeUrl =
        _stringValue(
          map['bridgeUrl'] ??
              map['remoteBridgeUrl'] ??
              map['url'] ??
              map['wsUrl'],
        ) ??
        '';
    if (bridgeUrl.isEmpty) return null;
    if (type != null &&
        type.isNotEmpty &&
        type != 'omnibot.codex_bridge' &&
        type != 'codex_bridge') {
      return null;
    }
    return CodexBridgeQrScanResult(
      bridgeUrl: bridgeUrl,
      token:
          _stringValue(
            map['token'] ?? map['bridgeToken'] ?? map['remoteBridgeToken'],
          ) ??
          '',
      cwd: _stringValue(map['cwd'] ?? map['remoteCwd']) ?? '',
      sessionId: _stringValue(map['sessionId']),
    );
  }

  static dynamic runCatchingJsonDecode(String raw) {
    try {
      return jsonDecode(raw);
    } catch (_) {
      return null;
    }
  }

  static String? _stringValue(dynamic value) {
    final text = value?.toString().trim() ?? '';
    return text.isEmpty ? null : text;
  }

  static String? _firstQueryValue(Uri uri, List<String> keys) {
    for (final key in keys) {
      final value = uri.queryParameters[key]?.trim();
      if (value != null && value.isNotEmpty) {
        return value;
      }
    }
    return null;
  }

  static bool _isBridgeUrl(Uri uri) {
    if (uri.host.trim().isEmpty) return false;
    if (const <String>{'ws', 'wss'}.contains(uri.scheme)) return true;
    if (!const <String>{'http', 'https'}.contains(uri.scheme)) return false;
    final path = uri.path.toLowerCase();
    return uri.port == 17321 ||
        path.contains('/codex') ||
        path.contains('/health') ||
        path.contains('/fs/list');
  }

  static String _stripQuickConnectQuery(Uri uri) {
    final filtered = <String, List<String>>{};
    for (final entry in uri.queryParametersAll.entries) {
      if (const <String>{
        'token',
        'bridgeToken',
        'remoteBridgeToken',
        'cwd',
        'remoteCwd',
        'sessionId',
      }.contains(entry.key)) {
        continue;
      }
      filtered[entry.key] = entry.value;
    }
    final lowerPath = uri.path.toLowerCase();
    final bridgePath = lowerPath == '/health' || lowerPath == '/fs/list'
        ? '/codex'
        : uri.path;
    return Uri(
      scheme: uri.scheme,
      userInfo: uri.userInfo,
      host: uri.host,
      port: uri.hasPort ? uri.port : null,
      path: bridgePath,
      queryParameters: filtered.isEmpty
          ? null
          : {for (final entry in filtered.entries) entry.key: entry.value.last},
      fragment: uri.fragment.isEmpty ? null : uri.fragment,
    ).toString();
  }
}

class CodexBridgeQrScannerPage extends StatefulWidget {
  const CodexBridgeQrScannerPage({super.key});

  @override
  State<CodexBridgeQrScannerPage> createState() =>
      _CodexBridgeQrScannerPageState();
}

class _CodexBridgeQrScannerPageState extends State<CodexBridgeQrScannerPage> {
  late final MobileScannerController _controller;
  bool _handled = false;
  DateTime? _lastInvalidToastAt;

  bool get _isEnglish => Localizations.localeOf(context).languageCode == 'en';

  @override
  void initState() {
    super.initState();
    _controller = MobileScannerController(
      formats: const <BarcodeFormat>[BarcodeFormat.qrCode],
      detectionSpeed: DetectionSpeed.noDuplicates,
      autoZoom: true,
    );
  }

  @override
  void dispose() {
    unawaited(_controller.dispose());
    super.dispose();
  }

  void _handleDetect(BarcodeCapture capture) {
    if (_handled) return;
    for (final barcode in capture.barcodes) {
      final raw = barcode.rawValue?.trim() ?? '';
      if (raw.isEmpty) continue;
      final result = CodexBridgeQrScanResult.tryParse(raw);
      if (result == null) {
        _showInvalidToast();
        continue;
      }
      _handled = true;
      unawaited(_controller.stop());
      Navigator.of(context).pop(result);
      return;
    }
  }

  void _showInvalidToast() {
    final now = DateTime.now();
    final last = _lastInvalidToastAt;
    if (last != null && now.difference(last) < const Duration(seconds: 2)) {
      return;
    }
    _lastInvalidToastAt = now;
    showToast(
      _isEnglish
          ? 'This is not an Omnibot Codex Bridge QR code.'
          : '这不是 Omnibot Codex Bridge 二维码。',
      type: ToastType.warning,
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        elevation: 0,
        title: Text(
          LegacyTextLocalizer.localize('扫描 Codex Bridge'),
          style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
        ),
        actions: [
          IconButton(
            tooltip: _isEnglish ? 'Torch' : '手电筒',
            onPressed: () => unawaited(_controller.toggleTorch()),
            icon: const Icon(LucideIcons.zap),
          ),
          IconButton(
            tooltip: _isEnglish ? 'Switch camera' : '切换摄像头',
            onPressed: () => unawaited(_controller.switchCamera()),
            icon: const Icon(LucideIcons.switchCamera),
          ),
        ],
      ),
      body: Stack(
        fit: StackFit.expand,
        children: [
          MobileScanner(
            controller: _controller,
            onDetect: _handleDetect,
            errorBuilder: (context, error) {
              return Center(
                child: Padding(
                  padding: const EdgeInsets.all(28),
                  child: Text(
                    _isEnglish
                        ? 'Camera unavailable: ${error.errorCode.name}'
                        : '摄像头不可用：${error.errorCode.name}',
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: Colors.white, fontSize: 14),
                  ),
                ),
              );
            },
          ),
          Container(color: Colors.black.withValues(alpha: 0.24)),
          Center(
            child: Container(
              width: 248,
              height: 248,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: Colors.white, width: 2),
              ),
            ),
          ),
          Positioned(
            left: 24,
            right: 24,
            bottom: 34,
            child: Container(
              padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
              decoration: BoxDecoration(
                color: Colors.black.withValues(alpha: 0.62),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.white24),
              ),
              child: Row(
                children: [
                  Icon(
                    LucideIcons.scanQrCode,
                    color: palette.accentPrimary,
                    size: 18,
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      _isEnglish
                          ? 'Scan the QR code printed by the PC Bridge terminal.'
                          : '扫描 PC Bridge 终端打印的二维码。',
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 13,
                        height: 1.35,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
