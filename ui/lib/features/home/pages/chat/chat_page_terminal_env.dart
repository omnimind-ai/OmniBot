part of 'chat_page.dart';

mixin _ChatPageTerminalEnvMixin on _ChatPageStateBase {
  @override
  Future<void> _loadTerminalEnvironmentVariables() async {
    final variables = ChatTerminalEnvironmentService.loadVariables();
    if (!mounted) {
      _terminalEnvironmentVariables = variables;
      return;
    }
    setState(() {
      _terminalEnvironmentVariables = variables;
    });
  }

  @override
  Future<void> _updateTerminalEnvironmentVariables(
    List<ChatTerminalEnvironmentVariable> variables,
  ) async {
    final normalized = ChatTerminalEnvironmentService.normalizeVariables(
      variables,
    );
    try {
      await ChatTerminalEnvironmentService.saveVariables(normalized);
      if (!mounted) {
        _terminalEnvironmentVariables = normalized;
        return;
      }
      setState(() {
        _terminalEnvironmentVariables = normalized;
      });
    } catch (error) {
      if (mounted) {
        showToast('保存环境变量失败: $error', type: ToastType.error);
      }
    }
  }

  @override
  Future<void> _openTerminalEnvironmentEditor(
    BuildContext anchorContext,
  ) async {
    if (_activeMode == ChatPageMode.openclaw) {
      return;
    }
    if (_showSlashCommandPanel ||
        _showModelMentionPanel ||
        _openClawPanelExpanded) {
      setState(() {
        _showSlashCommandPanel = false;
        _showModelMentionPanel = false;
        _openClawPanelExpanded = false;
      });
    }
    _inputFocusNode.unfocus();
    final overlayState = Overlay.maybeOf(context);
    final overlay = findActiveRenderObject(overlayState?.context) as RenderBox?;
    final anchorRect = glassPopupAnchorFromContext(anchorContext);
    if (overlay == null || anchorRect == null) {
      return;
    }
    final popupWidth = (overlay.size.width - 32).clamp(220.0, 340.0).toDouble();
    const popupMaxHeight = 360.0;
    await showGlassPopup<String>(
      context: context,
      anchor: anchorRect,
      horizontalPlacement: GlassPopupHorizontalPlacement.centerOnScreen,
      child: _TerminalEnvironmentEditorContent(
        width: popupWidth,
        maxHeight: popupMaxHeight,
        initialVariables: _terminalEnvironmentVariables,
        onChanged: _updateTerminalEnvironmentVariables,
      ),
    );
  }

  @override
  Map<String, String>? _buildAgentTerminalEnvironmentPayload() {
    final environment = ChatTerminalEnvironmentService.buildEnvironmentMap(
      _terminalEnvironmentVariables,
    );
    return environment.isEmpty ? null : environment;
  }
}

class _TerminalEnvironmentEditorContent extends StatefulWidget {
  const _TerminalEnvironmentEditorContent({
    required this.width,
    required this.maxHeight,
    required this.initialVariables,
    required this.onChanged,
  });

  final double width;
  final double maxHeight;
  final List<ChatTerminalEnvironmentVariable> initialVariables;
  final Future<void> Function(List<ChatTerminalEnvironmentVariable>) onChanged;

  @override
  State<_TerminalEnvironmentEditorContent> createState() =>
      _TerminalEnvironmentEditorContentState();
}

class _TerminalEnvironmentEditorContentState
    extends State<_TerminalEnvironmentEditorContent> {
  final TextEditingController _keyController = TextEditingController();
  final TextEditingController _valueController = TextEditingController();
  late List<ChatTerminalEnvironmentVariable> _variables;
  String? _editingOriginalKey;
  String? _errorText;

  bool get _isEditing => _editingOriginalKey != null;

  @override
  void initState() {
    super.initState();
    _variables = List<ChatTerminalEnvironmentVariable>.from(
      widget.initialVariables,
    );
  }

  @override
  void dispose() {
    _keyController.dispose();
    _valueController.dispose();
    super.dispose();
  }

  void _resetInputState() {
    _editingOriginalKey = null;
    _errorText = null;
    _keyController.clear();
    _valueController.clear();
  }

  String? _validateKey(String key, {String? editingOriginalKey}) {
    if (key.isEmpty) {
      return '变量名不能为空';
    }
    if (!ChatTerminalEnvironmentService.isValidKey(key)) {
      return '变量名仅支持字母、数字和下划线，且不能以数字开头';
    }
    if (editingOriginalKey != null &&
        ChatTerminalEnvironmentService.containsKey(
          _variables,
          key,
          exceptKey: editingOriginalKey,
        )) {
      return '变量名已存在';
    }
    return null;
  }

  void _handleAdd() {
    if (_isEditing) {
      _handleSaveEdit();
      return;
    }
    final key = _keyController.text.trim();
    final value = _valueController.text;
    final errorText = _validateKey(key);
    if (errorText != null) {
      setState(() {
        _errorText = errorText;
      });
      return;
    }
    final next = ChatTerminalEnvironmentService.normalizeVariables([
      ..._variables.where((item) => item.normalizedKey != key),
      ChatTerminalEnvironmentVariable(key: key, value: value),
    ]);
    setState(() {
      _variables = next;
      _resetInputState();
    });
    unawaited(widget.onChanged(next));
  }

  void _handleEdit(ChatTerminalEnvironmentVariable item) {
    setState(() {
      _editingOriginalKey = item.normalizedKey;
      _errorText = null;
      _keyController.text = item.normalizedKey;
      _valueController.text = item.value;
    });
  }

  void _handleCancelEdit() {
    setState(_resetInputState);
  }

  void _handleSaveEdit() {
    final originalKey = _editingOriginalKey;
    if (originalKey == null) {
      _handleAdd();
      return;
    }

    final key = _keyController.text.trim();
    final value = _valueController.text;
    final errorText = _validateKey(key, editingOriginalKey: originalKey);
    if (errorText != null) {
      setState(() {
        _errorText = errorText;
      });
      return;
    }

    final next = ChatTerminalEnvironmentService.replaceVariable(
      _variables,
      originalKey: originalKey,
      replacement: ChatTerminalEnvironmentVariable(key: key, value: value),
    );
    setState(() {
      _variables = next;
      _resetInputState();
    });
    unawaited(widget.onChanged(next));
  }

  void _handleDelete(ChatTerminalEnvironmentVariable item) {
    final next = _variables
        .where((candidate) => candidate.normalizedKey != item.normalizedKey)
        .toList(growable: false);
    setState(() {
      _variables = next;
      if (_editingOriginalKey == item.normalizedKey) {
        _resetInputState();
      } else {
        _errorText = null;
      }
    });
    unawaited(widget.onChanged(next));
  }

  InputDecoration _buildTextFieldDecoration({
    required String hintText,
    required bool isDark,
  }) {
    final palette = context.omniPalette;
    return InputDecoration(
      isDense: true,
      hintText: hintText,
      hintStyle: TextStyle(
        fontSize: 13,
        color: isDark ? palette.textTertiary : const Color(0xFF9AA4B6),
        fontWeight: FontWeight.w500,
      ),
      filled: true,
      fillColor: isDark
          ? palette.surfaceSecondary.withValues(alpha: 0.62)
          : Colors.white.withValues(alpha: 0.48),
      border: const OutlineInputBorder(
        borderRadius: BorderRadius.all(Radius.circular(12)),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: const BorderRadius.all(Radius.circular(12)),
        borderSide: BorderSide(
          color: isDark
              ? palette.borderSubtle.withValues(alpha: 0.72)
              : Colors.white.withValues(alpha: 0.70),
        ),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: const BorderRadius.all(Radius.circular(12)),
        borderSide: BorderSide(
          color: isDark ? palette.accentPrimary : const Color(0xFF2C7FEB),
          width: 1.2,
        ),
      ),
      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
    );
  }

  Widget _buildErrorText(bool isDark) {
    return Text(
      _errorText!,
      style: TextStyle(
        fontSize: 12,
        color: isDark ? const Color(0xFFE69C8F) : const Color(0xFFD93025),
        fontWeight: FontWeight.w500,
      ),
    );
  }

  Widget _buildIconAction({
    required String tooltip,
    required IconData icon,
    required VoidCallback onTap,
  }) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return Tooltip(
      message: tooltip,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(999),
        child: Padding(
          padding: const EdgeInsets.all(4),
          child: Icon(
            icon,
            size: 18,
            color: isDark ? palette.textTertiary : const Color(0xFF8FA1BC),
          ),
        ),
      ),
    );
  }

  Widget _buildVariableRow(ChatTerminalEnvironmentVariable item) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    if (_editingOriginalKey == item.normalizedKey) {
      return _buildEditingVariableRow();
    }
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: isDark
              ? palette.surfaceSecondary.withValues(alpha: 0.58)
              : Colors.white.withValues(alpha: 0.38),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: isDark
                ? palette.borderSubtle.withValues(alpha: 0.62)
                : Colors.white.withValues(alpha: 0.58),
          ),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    item.normalizedKey,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      color: isDark
                          ? palette.textPrimary
                          : const Color(0xFF1F2937),
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    item.value.isEmpty ? '(空字符串)' : item.value,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 12,
                      color: isDark
                          ? palette.textSecondary
                          : const Color(0xFF64748B),
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            _buildIconAction(
              tooltip: '编辑',
              icon: Icons.edit_outlined,
              onTap: () {
                _handleEdit(item);
              },
            ),
            const SizedBox(width: 2),
            _buildIconAction(
              tooltip: '删除',
              icon: Icons.delete_outline_rounded,
              onTap: () {
                _handleDelete(item);
              },
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildEditingVariableRow() {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: isDark
              ? palette.surfaceSecondary.withValues(alpha: 0.58)
              : Colors.white.withValues(alpha: 0.38),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: isDark
                ? palette.borderSubtle.withValues(alpha: 0.62)
                : Colors.white.withValues(alpha: 0.58),
          ),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextField(
                    controller: _keyController,
                    autofocus: true,
                    scrollPadding: EdgeInsets.zero,
                    textInputAction: TextInputAction.next,
                    cursorColor: isDark ? palette.accentPrimary : null,
                    style: TextStyle(
                      fontSize: 13,
                      color: isDark
                          ? palette.textPrimary
                          : const Color(0xFF1F2937),
                      fontWeight: FontWeight.w500,
                    ),
                    decoration: _buildTextFieldDecoration(
                      hintText: '变量名，例如 OPENAI_API_KEY',
                      isDark: isDark,
                    ),
                    onSubmitted: (_) {
                      _handleSaveEdit();
                    },
                  ),
                  const SizedBox(height: 8),
                  TextField(
                    controller: _valueController,
                    autofocus: false,
                    scrollPadding: EdgeInsets.zero,
                    textInputAction: TextInputAction.done,
                    cursorColor: isDark ? palette.accentPrimary : null,
                    style: TextStyle(
                      fontSize: 13,
                      color: isDark
                          ? palette.textPrimary
                          : const Color(0xFF1F2937),
                      fontWeight: FontWeight.w500,
                    ),
                    decoration: _buildTextFieldDecoration(
                      hintText: '变量值，允许为空',
                      isDark: isDark,
                    ),
                    onSubmitted: (_) {
                      _handleSaveEdit();
                    },
                  ),
                  if (_errorText != null) ...[
                    const SizedBox(height: 8),
                    Align(
                      alignment: Alignment.centerLeft,
                      child: _buildErrorText(isDark),
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(width: 8),
            Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                _buildIconAction(
                  tooltip: '保存',
                  icon: Icons.check_rounded,
                  onTap: _handleSaveEdit,
                ),
                const SizedBox(height: 6),
                _buildIconAction(
                  tooltip: '取消',
                  icon: Icons.close_rounded,
                  onTap: _handleCancelEdit,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAddForm() {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          const SizedBox(height: 2),
          TextField(
            controller: _keyController,
            autofocus: false,
            scrollPadding: EdgeInsets.zero,
            textInputAction: TextInputAction.next,
            cursorColor: isDark ? palette.accentPrimary : null,
            style: TextStyle(
              fontSize: 13,
              color: isDark ? palette.textPrimary : const Color(0xFF1F2937),
              fontWeight: FontWeight.w500,
            ),
            decoration: _buildTextFieldDecoration(
              hintText: '变量名，例如 OPENAI_API_KEY',
              isDark: isDark,
            ),
            onSubmitted: (_) {
              _handleAdd();
            },
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _valueController,
            autofocus: false,
            scrollPadding: EdgeInsets.zero,
            textInputAction: TextInputAction.done,
            cursorColor: isDark ? palette.accentPrimary : null,
            style: TextStyle(
              fontSize: 13,
              color: isDark ? palette.textPrimary : const Color(0xFF1F2937),
              fontWeight: FontWeight.w500,
            ),
            decoration: _buildTextFieldDecoration(
              hintText: '变量值，允许为空',
              isDark: isDark,
            ),
            onSubmitted: (_) {
              _handleAdd();
            },
          ),
          if (_errorText != null) ...[
            const SizedBox(height: 8),
            _buildErrorText(isDark),
          ],
          const SizedBox(height: 10),
          Align(
            alignment: Alignment.centerRight,
            child: FilledButton.tonalIcon(
              onPressed: _handleAdd,
              icon: const Icon(Icons.add_rounded, size: 16),
              label: const Text('添加'),
              style: FilledButton.styleFrom(
                visualDensity: VisualDensity.compact,
                foregroundColor: isDark
                    ? palette.textPrimary
                    : const Color(0xFF1930D9),
                backgroundColor: isDark
                    ? Color.alphaBlend(
                        palette.accentPrimary.withValues(alpha: 0.18),
                        palette.surfaceSecondary,
                      )
                    : null,
              ),
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final mediaQuery = MediaQuery.of(context);
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final dynamicMaxHeight =
        (mediaQuery.size.height - mediaQuery.viewInsets.bottom - 96)
            .clamp(220.0, widget.maxHeight)
            .toDouble();
    return SizedBox(
      width: widget.width,
      child: OmniGlassPanel(
        width: widget.width,
        borderRadius: BorderRadius.circular(18),
        child: Material(
          color: Colors.transparent,
          child: ConstrainedBox(
            constraints: BoxConstraints(maxHeight: dynamicMaxHeight),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(14, 12, 14, 8),
                  child: Row(
                    children: [
                      Icon(
                        Icons.tune_rounded,
                        size: 16,
                        color: isDark
                            ? palette.textSecondary
                            : const Color(0xFF617390),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          '终端环境变量',
                          style: TextStyle(
                            fontSize: 13,
                            color: isDark
                                ? palette.textPrimary
                                : const Color(0xFF1F2937),
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ),
                      Text(
                        '${_variables.length}',
                        style: TextStyle(
                          fontSize: 11,
                          color: isDark
                              ? palette.textTertiary
                              : const Color(0xFF8FA1BC),
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),
                ),
                if (_variables.isEmpty)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                    child: Text(
                      '还没有环境变量',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 12,
                        color: isDark
                            ? palette.textTertiary
                            : const Color(0xFF94A3B8),
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  )
                else
                  Flexible(
                    child: Scrollbar(
                      child: ListView.builder(
                        padding: const EdgeInsets.only(bottom: 8),
                        itemCount: _variables.length,
                        itemBuilder: (context, index) {
                          return _buildVariableRow(_variables[index]);
                        },
                      ),
                    ),
                  ),
                if (!_isEditing) ...[
                  Divider(
                    height: 1,
                    color: isDark
                        ? palette.borderSubtle.withValues(alpha: 0.62)
                        : Colors.white.withValues(alpha: 0.62),
                  ),
                  _buildAddForm(),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
