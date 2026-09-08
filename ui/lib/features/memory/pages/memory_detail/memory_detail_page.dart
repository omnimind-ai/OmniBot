import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_svg/svg.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/memory/models/memory_model.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/widgets/auto_invert_icon.dart';
import 'widgets/detail_content.dart';
import 'package:ui/utils/image_util.dart';
import 'package:ui/utils/ui.dart';
import 'package:gal/gal.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:ui/widgets/context_menu.dart';
import 'package:ui/widgets/common_app_bar.dart';

class MemoryDetailPage extends StatelessWidget {
  final MemoryCardModel memory;
  final Future<bool> Function(int cardId) onDelete;

  const MemoryDetailPage({
    Key? key,
    required this.memory,
    required this.onDelete,
  }) : super(key: key);

  void _showImageDialog(BuildContext context) {
    showDialog(
      context: context,
      barrierColor: AppColors.text70,
      builder: (context) {
        return Column(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Expanded(
              child: Dialog(
                backgroundColor: Colors.transparent,
                insetPadding: EdgeInsets.all(0),
                child: ImageUtil.buildImage(
                  memory.imagePath ?? '',
                  width: MediaQuery.of(context).size.width,
                  fit: BoxFit.contain,
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.only(bottom: 40, top: 10),
              child: GestureDetector(
                onTap: () => _downloadImage(context),
                child: Container(
                  height: 40,
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.center,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Container(
                        width: 18,
                        height: 40,
                        alignment: Alignment.center,
                        child: SvgPicture.asset(
                          'assets/memory/download.svg',
                          width: 12,
                          height: 12,
                          alignment: Alignment.center,
                          colorFilter: ColorFilter.mode(
                            Color(0xff808080),
                            BlendMode.srcIn,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Container(
                        height: 40,
                        alignment: Alignment.center,
                        child: Text(
                          '保存图片',
                          style: TextStyle(
                            color: Color(0xff808080),
                            fontSize: 14,
                            fontWeight: FontWeight.w500,
                            decoration: TextDecoration.none,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        );
      },
    );
  }

  void _showContextMenu(
    MemoryCardModel vm,
    BuildContext context,
    Offset position,
  ) async {
    final action = await showRecordContextMenu(
      context: context,
      position: position,
      deleteLabel: '删除',
      deleteIconAsset: 'assets/memory/memory_delete.svg',
      showEdit: false,
    );
    switch (action) {
      case RecordMenuAction.delete:
        final res = await onDelete(vm.id);
        if (res && context.mounted) {
          Navigator.pop(context); // 返回记忆中心
        }
        break;
      default:
        break;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      extendBodyBehindAppBar: true,
      backgroundColor: AppColors.background,
      appBar: CommonAppBar(
        primary: true,
        backgroundColor: Colors.transparent,
        leadingWidth: 60,
        leading: GestureDetector(
          onTap: () => Navigator.of(context).pop(),
          child: Container(
            width: 60,
            height: 44,
            alignment: Alignment.center,
            child: AutoInvertSvgIcon.asset(
              'assets/common/chevron_left.svg',
              width: 24,
              height: 24,
              blendMode: BlendMode.difference,
            ),
          ),
        ),
        trailing: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTapDown: (details) {
            _showContextMenu(memory, context, details.globalPosition);
          },
          child: Container(
            width: 60,
            height: 44,
            alignment: Alignment.center,
            child: AutoInvertIcon(
              Icons.more_vert,
              size: 24,
              blendMode: BlendMode.difference,
            ),
          ),
        ),
      ),
      body: SafeArea(
        top: false,
        bottom: false,
        child: Stack(
          children: [
            // 上半部分图片：添加点击弹窗
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              height: 370,
              child: GestureDetector(
                onTap: () => _showImageDialog(context),
                child: Container(
                  child: ImageUtil.buildImage(
                    memory.imagePath ?? '',
                    width: double.infinity,
                    fit: BoxFit.cover,
                  ),
                ),
              ),
            ),
            // 下半部分
            Positioned(
              top: 350,
              left: 0,
              right: 0,
              bottom: 0,
              child: Container(
                padding: EdgeInsets.only(bottom: 18),
                decoration: const BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.only(
                    topLeft: Radius.circular(18),
                    topRight: Radius.circular(18),
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: AppColors.text05,
                      blurRadius: 8.76,
                      offset: Offset(0, -3.50),
                      spreadRadius: 1,
                    ),
                  ],
                ),
                child: Stack(
                  children: [
                    DetailContent(
                      title: memory.title,
                      timestamp: memory.updatedAt ?? 0,
                      content: memory.description ?? '暂无详细内容',
                      appName: memory.appName,
                      appIconProvider: memory.appIcon,
                      appSvgPath: memory.appSvgPath,
                      // tags: memory.tags,
                    ),
                    // 底部渐变遮罩
                    Positioned(
                      bottom: 25,
                      left: 0,
                      right: 0,
                      height: 35, // 渐变高度，可根据需要调整
                      child: Container(
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                            begin: Alignment.topCenter,
                            end: Alignment.bottomCenter,
                            colors: [
                              Colors.white.withOpacity(0), // 顶部透明
                              Colors.white, // 底部白色（与背景色一致）
                            ],
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _downloadImage(BuildContext dialogContext) async {
    final imagePath = memory.imagePath;
    if (imagePath == null || imagePath.isEmpty) {
      showToast('暂无可保存的图片', type: ToastType.warning);
      return;
    }

    final granted = await _ensureSavePermission();
    if (!granted) {
      showToast('请先授予存储权限', type: ToastType.warning);
      return;
    }

    try {
      if (ImageUtil.isLocalFilePath(imagePath)) {
        final filePath = imagePath.startsWith('file://')
            ? Uri.parse(imagePath).toFilePath()
            : imagePath;
        final file = File(filePath);
        if (!file.existsSync()) {
          showToast('图片文件不存在', type: ToastType.error);
          return;
        }
        await Gal.putImage(file.path, album: _albumName);
      } else {
        final bytes = await rootBundle.load(imagePath);
        await Gal.putImageBytes(bytes.buffer.asUint8List(), album: _albumName);
      }

      showToast('保存成功', type: ToastType.success);
    } catch (e) {
      showToast('保存失败：$e', type: ToastType.error);
    } finally {
      if (Navigator.of(dialogContext).canPop()) {
        Navigator.of(dialogContext).pop();
      }
    }
  }

  Future<bool> _ensureSavePermission() async {
    final storageStatus = await Permission.storage.request();
    if (storageStatus.isGranted) {
      return true;
    }

    final photosStatus = await Permission.photos.request();
    if (photosStatus.isGranted) {
      return true;
    }

    if (storageStatus.isPermanentlyDenied || photosStatus.isPermanentlyDenied) {
      await openAppSettings();
    }
    return false;
  }

  String get _albumName => '小万';
}
