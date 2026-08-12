import 'package:flutter/material.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/assists_core_service.dart';

/// 文本上下文菜单
/// 
/// 支持可编辑和只读两种模式：
/// - 可编辑模式：显示全选/剪切/复制/粘贴
/// - 只读模式：仅显示全选/复制
class TextInputContextMenu extends StatelessWidget {
  final EditableTextState editableTextState;
  
  /// 是否为只读模式
  /// 
  /// - true: 仅显示全选和复制（用于 SelectableText）
  /// - false: 显示全选/剪切/复制/粘贴（用于 TextField）
  final bool readOnly;
  
  const TextInputContextMenu({
    super.key, 
    required this.editableTextState,
    this.readOnly = false,
  });

  @override
  Widget build(BuildContext context) {
    return AdaptiveTextSelectionToolbar.buttonItems(
      anchors: editableTextState.contextMenuAnchors,
      buttonItems: [
        if (editableTextState.textEditingValue.text.isNotEmpty)
          ContextMenuButtonItem(
            label: LegacyTextLocalizer.isEnglish ? 'Select all' : '全选',
            onPressed: () {
              editableTextState.selectAll(SelectionChangedCause.toolbar);
            },
          ),
        if (!readOnly && !editableTextState.textEditingValue.selection.isCollapsed)
          ContextMenuButtonItem(
            label: LegacyTextLocalizer.isEnglish ? 'Cut' : '剪切',
            onPressed: () {
              final selection = editableTextState.textEditingValue.selection;
              final selectedText = selection.textInside(editableTextState.textEditingValue.text);
              try {
                AssistsMessageService.copyToClipboard(selectedText);
                final newText = selection.textBefore(editableTextState.textEditingValue.text) +
                    selection.textAfter(editableTextState.textEditingValue.text);
                final newSelection = TextSelection.collapsed(offset: selection.start);
                editableTextState.userUpdateTextEditingValue(
                  editableTextState.textEditingValue.copyWith(text: newText, selection: newSelection),
                  SelectionChangedCause.toolbar,
                );
                editableTextState.hideToolbar();
              } catch (e) {
              }
            },
          ),
        if (!editableTextState.textEditingValue.selection.isCollapsed)
          ContextMenuButtonItem(
            label: LegacyTextLocalizer.isEnglish ? 'Copy' : '复制',
            onPressed: () {
              final selection = editableTextState.textEditingValue.selection;
              final selectedText = selection.textInside(editableTextState.textEditingValue.text);
              try {
                AssistsMessageService.copyToClipboard(selectedText);
                editableTextState.hideToolbar();
              } catch (e) {
              }
            },
          ),
        if (!readOnly)
          ContextMenuButtonItem(
            label: LegacyTextLocalizer.isEnglish ? 'Paste' : '粘贴',
            onPressed: () async {
            final value = editableTextState.textEditingValue;
            final selection = value.selection.isValid
                ? value.selection
                : TextSelection.collapsed(offset: value.text.length);

            String? pasteText;
            try {
              pasteText = await AssistsMessageService.getClipboardText();
            } catch (e) {
            }

            if (pasteText != null && pasteText.isNotEmpty) {
              final newText = selection.textBefore(value.text) + pasteText + selection.textAfter(value.text);
              final newSelection = TextSelection.collapsed(
                offset: selection.start + pasteText.length,
              );
              editableTextState.userUpdateTextEditingValue(
                value.copyWith(text: newText, selection: newSelection),
                SelectionChangedCause.toolbar,
              );
            } else {
            }

            editableTextState.hideToolbar();
          },
        ),
      ],
    );
  }
}
