import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/device_service.dart';
import 'package:ui/services/feedback_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/common_app_bar.dart';

class FeedbackPage extends StatefulWidget {
  const FeedbackPage({super.key, this.service});
  final FeedbackService? service;

  @override
  State<FeedbackPage> createState() => _FeedbackPageState();
}

class _FeedbackPageState extends State<FeedbackPage> {
  final _form = GlobalKey<FormState>();
  final _title = TextEditingController();
  final _description = TextEditingController();
  final _contact = TextEditingController();
  final _cancel = CancelToken();
  late final FeedbackService _service = widget.service ?? FeedbackService();
  List<FeedbackAttachment> _attachments = [];
  Map<String, dynamic>? _version;
  String _accountEmail = '';
  String? _reference;
  FeedbackFailure? _error;
  bool _loading = true;
  bool _picking = false;
  bool _sending = false;

  String _text(String zh, String en) =>
      Localizations.localeOf(context).languageCode == 'en' ? en : zh;

  @override
  void initState() {
    super.initState();
    _loadContext();
  }

  Future<void> _loadContext() async {
    final email = FeedbackService.loadAccountEmail();
    final version = await DeviceService.getAppVersion().timeout(
      const Duration(seconds: 5),
      onTimeout: () => null,
    );
    final address = await email;
    if (!mounted) return;
    setState(() {
      _version = version;
      _accountEmail = address;
      _loading = false;
      if (_contact.text.isEmpty) _contact.text = address;
    });
  }

  @override
  void dispose() {
    _cancel.cancel();
    _service.close();
    _title.dispose();
    _description.dispose();
    _contact.dispose();
    super.dispose();
  }

  Future<void> _pickAttachments() async {
    if (_picking || _sending) return;
    setState(() {
      _picking = true;
      _error = null;
    });
    try {
      final picked = await _service.pickAttachments();
      if (!mounted) return;
      final combined = [..._attachments];
      for (final file in picked) {
        if (!combined.any((item) => item.identifier == file.identifier)) {
          combined.add(file);
        }
      }
      FeedbackService.validateAttachments(combined);
      setState(() => _attachments = combined);
    } on FeedbackException catch (error) {
      if (mounted) setState(() => _error = error.reason);
    } finally {
      if (mounted) setState(() => _picking = false);
    }
  }

  Future<void> _submit() async {
    if (_loading || _sending || _picking || !_form.currentState!.validate()) {
      return;
    }
    FocusScope.of(context).unfocus();
    setState(() {
      _sending = true;
      _error = null;
    });
    try {
      final reference = await _service.submit(
        title: _title.text,
        description: _description.text,
        contact: _contact.text,
        accountEmail: _accountEmail,
        languageCode: Localizations.localeOf(context).languageCode,
        versionInfo: _version,
        attachments: _attachments,
        cancelToken: _cancel,
      );
      if (mounted) setState(() => _reference = reference);
    } on FeedbackException catch (error) {
      if (mounted) setState(() => _error = error.reason);
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  String _errorMessage(FeedbackFailure reason) => switch (reason) {
    FeedbackFailure.invalidFields => _text(
      '请填写问题标题和具体描述，并检查内容长度。',
      'Enter a title and description, and check the text lengths.',
    ),
    FeedbackFailure.fileCount => _text(
      '最多添加 5 个附件。',
      'Add up to 5 attachments.',
    ),
    FeedbackFailure.fileSize => _text(
      '每个附件不能超过 10 MB。',
      'Each attachment must be 10 MB or smaller.',
    ),
    FeedbackFailure.totalSize => _text(
      '附件总大小不能超过 25 MB。',
      'Attachments must total 25 MB or smaller.',
    ),
    FeedbackFailure.unreadableFile => _text(
      '无法读取附件，请重新选择。',
      'Unable to read the attachment. Choose it again.',
    ),
    FeedbackFailure.rateLimited => _text(
      '提交次数较多，请稍后再试。',
      'Too many submissions. Try again later.',
    ),
    FeedbackFailure.network => _text(
      '提交失败，请检查网络后重试。填写的内容已保留。',
      'Submission failed. Check your connection and try again. Your entries are preserved.',
    ),
  };

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.surfacePrimary,
      appBar: CommonAppBar(
        title: _text('我要反馈', 'Send feedback'),
        primary: true,
        backgroundColor: palette.surfacePrimary,
      ),
      body: SafeArea(
        top: false,
        child: _reference != null
            ? _success()
            : Form(
                key: _form,
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(20, 16, 20, 28),
                  children: [
                    Text(
                      _text(
                        '遇到问题或有改进建议？告诉我们。',
                        'Tell us about a problem or an improvement you would like.',
                      ),
                      style: TextStyle(
                        color: palette.textSecondary,
                        height: 1.5,
                      ),
                    ),
                    const SizedBox(height: 24),
                    TextFormField(
                      key: const ValueKey('feedback-title'),
                      controller: _title,
                      enabled: !_sending,
                      maxLength: 200,
                      textInputAction: TextInputAction.next,
                      decoration: InputDecoration(
                        labelText: _text('问题标题', 'Title'),
                        hintText: _text(
                          '简要描述你遇到的问题',
                          'Briefly describe the issue',
                        ),
                      ),
                      validator: (value) => value?.trim().isEmpty ?? true
                          ? _text('请填写问题标题', 'Enter a title')
                          : null,
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      key: const ValueKey('feedback-description'),
                      controller: _description,
                      enabled: !_sending,
                      maxLength: 10000,
                      minLines: 5,
                      maxLines: 9,
                      decoration: InputDecoration(
                        labelText: _text('具体描述', 'Description'),
                        hintText: _text(
                          '你做了什么、出现了什么、希望得到什么结果？',
                          'What did you do, what happened, and what did you expect?',
                        ),
                        alignLabelWithHint: true,
                      ),
                      validator: (value) => value?.trim().isEmpty ?? true
                          ? _text('请填写具体描述', 'Enter a description')
                          : null,
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      key: const ValueKey('feedback-contact'),
                      controller: _contact,
                      enabled: !_sending,
                      maxLength: 300,
                      keyboardType: TextInputType.emailAddress,
                      decoration: InputDecoration(
                        labelText: _text(
                          '联系方式（选填）',
                          'Contact details (optional)',
                        ),
                        hintText: _text(
                          '邮箱、微信或其他联系方式',
                          'Email or another way to contact you',
                        ),
                      ),
                    ),
                    if (_loading)
                      Text(
                        _text('正在读取账号信息…', 'Loading account details…'),
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 12,
                        ),
                      )
                    else if (_accountEmail.isNotEmpty)
                      Text(
                        _text(
                          '反馈会附上账号邮箱 $_accountEmail，联系方式可修改。',
                          'Your account email $_accountEmail accompanies the feedback. Contact details are editable.',
                        ),
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 12,
                          height: 1.5,
                        ),
                      ),
                    const SizedBox(height: 22),
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            _text('附件（选填）', 'Attachments (optional)'),
                            style: TextStyle(
                              color: palette.textPrimary,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                        TextButton.icon(
                          key: const ValueKey('feedback-add-attachment'),
                          onPressed: _sending || _picking
                              ? null
                              : _pickAttachments,
                          icon: const Icon(LucideIcons.paperclip, size: 17),
                          label: Text(_text('添加附件', 'Add files')),
                        ),
                      ],
                    ),
                    Text(
                      _text(
                        '可添加截图、录屏或日志，最多 5 个；每个不超过 10 MB，总计不超过 25 MB。',
                        'Screenshots, recordings or logs: up to 5 files, 10 MB each, 25 MB total.',
                      ),
                      style: TextStyle(
                        color: palette.textSecondary,
                        fontSize: 12,
                        height: 1.5,
                      ),
                    ),
                    for (final file in _attachments)
                      ListTile(
                        contentPadding: EdgeInsets.zero,
                        leading: const Icon(LucideIcons.file, size: 20),
                        title: Text(
                          file.name,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                        subtitle: Text('${(file.size / 1024).ceil()} KB'),
                        trailing: IconButton(
                          tooltip: _text('移除附件', 'Remove attachment'),
                          onPressed: _sending
                              ? null
                              : () => setState(() => _attachments.remove(file)),
                          icon: const Icon(LucideIcons.x, size: 18),
                        ),
                      ),
                    if (_error != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 18),
                        child: Text(
                          _errorMessage(_error!),
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.error,
                          ),
                          semanticsLabel: _errorMessage(_error!),
                        ),
                      ),
                    const SizedBox(height: 28),
                    FilledButton(
                      key: const ValueKey('feedback-submit'),
                      onPressed: _loading || _sending || _picking
                          ? null
                          : _submit,
                      style: FilledButton.styleFrom(
                        backgroundColor: palette.accentPrimary,
                        padding: const EdgeInsets.symmetric(vertical: 16),
                      ),
                      child: _sending
                          ? const SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : Text(_text('提交反馈', 'Submit feedback')),
                    ),
                  ],
                ),
              ),
      ),
    );
  }

  Widget _success() => Center(
    child: SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            LucideIcons.circleCheck,
            color: context.omniPalette.accentPrimary,
            size: 44,
          ),
          const SizedBox(height: 20),
          Text(
            _text('反馈已收到', 'Feedback received'),
            style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 12),
          Text(
            _text(
              '感谢你的反馈，请保存反馈编号，方便后续联系。',
              'Thank you. Save the reference number for future contact.',
            ),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 16),
          SelectableText(
            _reference!,
            key: const ValueKey('feedback-reference'),
          ),
          const SizedBox(height: 24),
          TextButton(
            onPressed: () => setState(() {
              _reference = null;
              _title.clear();
              _description.clear();
              _contact.text = _accountEmail;
              _attachments = [];
              _error = null;
            }),
            child: Text(_text('再提交一条', 'Send more feedback')),
          ),
        ],
      ),
    ),
  );
}
