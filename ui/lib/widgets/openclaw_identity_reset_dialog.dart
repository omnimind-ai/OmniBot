import 'package:flutter/material.dart';
import 'package:ui/services/openclaw_credential_service.dart';

const String kOpenClawResetPhraseZh = '重置 OPENCLAW 设备身份';
const String kOpenClawResetPhraseEn = 'RESET OPENCLAW DEVICE IDENTITY';

/// Two explicit local confirmations. Failure always leaves OpenClaw disabled.
Future<OpenClawIdentityResetResult?> showOpenClawIdentityResetFlow({
  required BuildContext context,
  VoidCallback? onLocalDisabled,
}) async {
  final english = Localizations.localeOf(context).languageCode == 'en';
  final hasExistingIdentity =
      await OpenClawCredentialService.hasExistingIdentity();
  if (!context.mounted) return null;
  final proceed = await showDialog<bool>(
    context: context,
    barrierDismissible: false,
    builder: (dialogContext) => AlertDialog(
      key: const Key('openclaw-reset-consequence-dialog'),
      title: Text(
        english ? 'Reset OpenClaw device identity?' : '重置 OpenClaw 设备身份？',
      ),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (!hasExistingIdentity)
              Text(
                english
                    ? 'No stored identity was detected. Continuing only cleans residual pairing data and does not create an identity.'
                    : '未检测到已保存的设备身份。继续只会清理残留配对数据，不会生成新身份。',
              ),
            if (!hasExistingIdentity) const SizedBox(height: 10),
            for (final item
                in english
                    ? const [
                        'The current device pairing becomes invalid.',
                        'The next connection appears as a new device.',
                        'Delete the old device record separately in Gateway.',
                        'Your Gateway token and cloud data are not deleted.',
                        'The current OpenClaw session must close; restart or reconnect afterward.',
                      ]
                    : const [
                        '当前设备配对会立即失效。',
                        '下次连接会被视为一台新设备。',
                        '旧设备记录仍需在 Gateway 中另行删除。',
                        'Gateway Token 和云端数据不会被删除。',
                        '当前 OpenClaw 会话必须关闭；完成后需重启或重新连接。',
                      ])
              Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Text('• $item'),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
          key: const Key('openclaw-reset-cancel-first'),
          onPressed: () => Navigator.of(dialogContext).pop(false),
          child: Text(english ? 'Cancel' : '取消'),
        ),
        FilledButton(
          key: const Key('openclaw-reset-continue'),
          onPressed: () => Navigator.of(dialogContext).pop(true),
          child: Text(english ? 'Continue' : '继续'),
        ),
      ],
    ),
  );
  if (proceed != true || !context.mounted) return null;

  final phrase = english ? kOpenClawResetPhraseEn : kOpenClawResetPhraseZh;
  final confirmed = await showDialog<bool>(
    context: context,
    barrierDismissible: false,
    builder: (_) =>
        _OpenClawResetPhraseDialog(english: english, phrase: phrase),
  );
  if (confirmed != true) return null;

  // Native persists disabled+generation before it touches sessions or identity material.
  final result = await OpenClawCredentialService.resetDeviceIdentity();
  onLocalDisabled?.call();
  return result;
}

class _OpenClawResetPhraseDialog extends StatefulWidget {
  const _OpenClawResetPhraseDialog({
    required this.english,
    required this.phrase,
  });

  final bool english;
  final String phrase;

  @override
  State<_OpenClawResetPhraseDialog> createState() =>
      _OpenClawResetPhraseDialogState();
}

class _OpenClawResetPhraseDialogState
    extends State<_OpenClawResetPhraseDialog> {
  final TextEditingController _controller = TextEditingController();
  bool _matches = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      key: const Key('openclaw-reset-phrase-dialog'),
      title: Text(widget.english ? 'Type the confirmation phrase' : '输入确认短语'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(widget.english ? 'Type exactly:' : '请完整输入：'),
            const SizedBox(height: 6),
            SelectableText(
              widget.phrase,
              key: const Key('openclaw-reset-required-phrase'),
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 12),
            TextField(
              key: const Key('openclaw-reset-phrase-field'),
              controller: _controller,
              autofocus: true,
              autocorrect: false,
              enableSuggestions: false,
              onChanged: (value) =>
                  setState(() => _matches = value.trim() == widget.phrase),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          key: const Key('openclaw-reset-cancel-second'),
          onPressed: () => Navigator.of(context).pop(false),
          child: Text(widget.english ? 'Cancel' : '取消'),
        ),
        FilledButton(
          key: const Key('openclaw-reset-confirm'),
          onPressed: _matches ? () => Navigator.of(context).pop(true) : null,
          child: Text(widget.english ? 'Reset identity' : '重置身份'),
        ),
      ],
    );
  }
}
