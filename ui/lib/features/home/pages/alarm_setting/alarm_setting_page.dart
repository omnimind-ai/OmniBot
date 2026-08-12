import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/device_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

class AlarmSettingPage extends StatefulWidget {
  const AlarmSettingPage({super.key});

  @override
  State<AlarmSettingPage> createState() => _AlarmSettingPageState();
}

class _AlarmSettingPageState extends State<AlarmSettingPage> {
  static const String _sourceDefault = 'default';
  static const String _sourceLocalMp3 = 'local_mp3';
  static const String _sourceRemoteMp3 = 'remote_mp3_url';

  final TextEditingController _remoteUrlController = TextEditingController();

  String _source = _sourceDefault;
  String _localPath = '';
  bool _loading = true;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _loadAlarmSettings();
  }

  @override
  void dispose() {
    _remoteUrlController.dispose();
    super.dispose();
  }

  Future<void> _loadAlarmSettings() async {
    final payload = await AssistsMessageService.getAlarmSettings();
    if (!mounted) return;

    setState(() {
      _source = (payload['source'] ?? _sourceDefault).toString();
      _localPath = (payload['localPath'] ?? '').toString();
      _remoteUrlController.text = (payload['remoteUrl'] ?? '').toString();
      _loading = false;
    });
  }

  Future<bool> _ensureAudioReadPermission() async {
    final info = await DeviceService.getDeviceInfo();
    final sdkVersion = (info?['sdkVersion'] as num?)?.toInt() ?? 0;
    final permission = sdkVersion >= 33
        ? 'android.permission.READ_MEDIA_AUDIO'
        : 'android.permission.READ_EXTERNAL_STORAGE';
    return requestPermission([permission]);
  }

  Future<void> _pickLocalMp3() async {
    final granted = await _ensureAudioReadPermission();
    if (!mounted) return;
    if (!granted) {
      showToast(
        context.l10n.alarmAudioPermissionDenied,
        type: ToastType.warning,
      );
      return;
    }

    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: const ['mp3'],
      allowMultiple: false,
    );
    if (!mounted) return;

    if (result == null || result.files.isEmpty) {
      return;
    }
    final path = result.files.first.path;
    if (path == null || path.isEmpty) {
      showToast(context.l10n.alarmInvalidFilePath, type: ToastType.warning);
      return;
    }

    setState(() {
      _localPath = path;
      _source = _sourceLocalMp3;
    });
  }

  bool _validateBeforeSave() {
    if (_source == _sourceLocalMp3 && _localPath.trim().isEmpty) {
      showToast(context.l10n.alarmSelectLocalFirst, type: ToastType.warning);
      return false;
    }

    if (_source == _sourceRemoteMp3) {
      final url = _remoteUrlController.text.trim();
      if (!(url.startsWith('http://') || url.startsWith('https://'))) {
        showToast(context.l10n.alarmEnterHttpsUrl, type: ToastType.warning);
        return false;
      }
    }

    return true;
  }

  Future<void> _saveSettings() async {
    if (_saving) return;
    if (!_validateBeforeSave()) return;

    setState(() {
      _saving = true;
    });

    final payload = await AssistsMessageService.saveAlarmSettings(
      source: _source,
      localPath: _source == _sourceLocalMp3 ? _localPath.trim() : null,
      remoteUrl: _source == _sourceRemoteMp3
          ? _remoteUrlController.text.trim()
          : null,
    );

    if (!mounted) return;
    setState(() {
      _saving = false;
    });

    if (payload['success'] == true) {
      showToast(context.l10n.alarmSaved, type: ToastType.success);
      return;
    }

    final error =
        (payload['message'] ?? payload['summary'] ?? context.trLegacy('保存失败'))
            .toString();
    showToast(error, type: ToastType.error);
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: context.l10n.settingsAlarmTitle,
        primary: true,
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(18, 12, 18, 24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SettingsSectionTitle(
                      label: context.l10n.alarmRingtoneSource,
                    ),
                    _buildSourceCard(),
                    if (_source == _sourceLocalMp3) ...[
                      const SizedBox(height: 18),
                      SettingsSectionTitle(label: context.l10n.alarmLocalFile),
                      _buildLocalFileCard(),
                    ],
                    if (_source == _sourceRemoteMp3) ...[
                      const SizedBox(height: 18),
                      SettingsSectionTitle(label: context.trLegacy('远程地址')),
                      _buildRemoteUrlCard(),
                    ],
                    const Spacer(),
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton(
                        onPressed: _saving ? null : _saveSettings,
                        style: FilledButton.styleFrom(
                          backgroundColor: context.isDarkTheme
                              ? palette.accentPrimary
                              : AppColors.primaryBlue,
                          foregroundColor: context.isDarkTheme
                              ? Theme.of(context).colorScheme.onPrimary
                              : Colors.white,
                          minimumSize: const Size.fromHeight(48),
                        ),
                        child: Text(
                          _saving
                              ? context.trLegacy('保存中...')
                              : context.trLegacy('保存'),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
    );
  }

  Widget _buildSourceCard() {
    return Column(
      children: [
        _buildSourceTile(
          value: _sourceDefault,
          title: context.l10n.alarmSystemDefault,
          subtitle: context.l10n.alarmSystemDefaultDesc,
        ),
        const Divider(height: 1),
        _buildSourceTile(
          value: _sourceLocalMp3,
          title: context.l10n.alarmLocalMp3,
          subtitle: context.l10n.alarmLocalMp3Desc,
        ),
        const Divider(height: 1),
        _buildSourceTile(
          value: _sourceRemoteMp3,
          title: context.l10n.alarmMp3Url,
          subtitle: context.l10n.alarmMp3UrlDesc,
        ),
      ],
    );
  }

  Widget _buildSourceTile({
    required String value,
    required String title,
    required String subtitle,
  }) {
    final palette = context.omniPalette;
    final isSelected = _source == value;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () {
          setState(() {
            _source = value;
          });
        },
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Radio<String>(
                value: value,
                groupValue: _source,
                activeColor: context.isDarkTheme
                    ? palette.accentPrimary
                    : AppColors.primaryBlue,
                onChanged: (next) {
                  if (next == null) return;
                  setState(() {
                    _source = next;
                  });
                },
              ),
              const SizedBox(width: 2),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                        color: context.isDarkTheme
                            ? palette.textPrimary
                            : AppColors.text,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      style: TextStyle(
                        fontSize: 12,
                        color: context.isDarkTheme
                            ? palette.textSecondary
                            : AppColors.text70,
                      ),
                    ),
                  ],
                ),
              ),
              if (isSelected)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Icon(
                    LucideIcons.check,
                    size: 16,
                    color: context.isDarkTheme
                        ? palette.accentPrimary
                        : AppColors.primaryBlue,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildLocalFileCard() {
    final palette = context.omniPalette;
    final displayPath = _localPath.isEmpty
        ? context.trLegacy('未选择文件')
        : _localPath;

    return SizedBox(
      width: double.infinity,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            context.l10n.alarmLocalFile,
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w500,
              color: context.isDarkTheme ? palette.textPrimary : AppColors.text,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            displayPath,
            style: TextStyle(
              fontSize: 12,
              color: context.isDarkTheme
                  ? palette.textSecondary
                  : AppColors.text70,
            ),
            maxLines: 3,
            overflow: TextOverflow.ellipsis,
          ),
          const SizedBox(height: 10),
          OutlinedButton(
            onPressed: _pickLocalMp3,
            style: OutlinedButton.styleFrom(
              foregroundColor: context.isDarkTheme
                  ? palette.accentPrimary
                  : AppColors.primaryBlue,
              side: BorderSide(
                color: context.isDarkTheme
                    ? palette.accentPrimary
                    : AppColors.primaryBlue,
              ),
            ),
            child: Text(context.l10n.alarmSelectMp3),
          ),
        ],
      ),
    );
  }

  Widget _buildRemoteUrlCard() {
    final palette = context.omniPalette;
    return SizedBox(
      width: double.infinity,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            context.l10n.alarmMp3Url,
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w500,
              color: context.isDarkTheme ? palette.textPrimary : AppColors.text,
            ),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _remoteUrlController,
            decoration: const InputDecoration(
              hintText: 'https://example.com/alarm.mp3',
              border: OutlineInputBorder(),
              isDense: true,
            ),
          ),
        ],
      ),
    );
  }
}
