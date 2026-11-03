import 'package:flutter/material.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/app_text_styles.dart';
import 'package:ui/theme/theme_context.dart';

class Mem0MemoryEditorResult {
  final String memory;
  final List<String> categories;

  const Mem0MemoryEditorResult({
    required this.memory,
    required this.categories,
  });
}

class Mem0MemoryEditorSheet extends StatefulWidget {
  final String title;
  final String submitLabel;
  final String? initialMemory;
  final List<String> initialCategories;

  const Mem0MemoryEditorSheet({
    super.key,
    required this.title,
    required this.submitLabel,
    this.initialMemory,
    this.initialCategories = const [],
  });

  @override
  State<Mem0MemoryEditorSheet> createState() => _Mem0MemoryEditorSheetState();
}

class _Mem0MemoryEditorSheetState extends State<Mem0MemoryEditorSheet> {
  static const int _maxMemoryLength = 300;

  late final TextEditingController _memoryController;
  late final TextEditingController _categoryController;

  bool get _canSubmit {
    final text = _memoryController.text.trim();
    return text.isNotEmpty && text.length <= _maxMemoryLength;
  }

  @override
  void initState() {
    super.initState();
    _memoryController = TextEditingController(text: widget.initialMemory ?? '');
    _categoryController = TextEditingController(
      text: widget.initialCategories.join(', '),
    );
    _memoryController.addListener(_refresh);
    _categoryController.addListener(_refresh);
  }

  @override
  void dispose() {
    _memoryController
      ..removeListener(_refresh)
      ..dispose();
    _categoryController
      ..removeListener(_refresh)
      ..dispose();
    super.dispose();
  }

  void _refresh() {
    if (!mounted) {
      return;
    }
    setState(() {});
  }

  List<String> _parseCategories() {
    return _categoryController.text
        .split(RegExp(r'[,;\n]'))
        .map((item) => item.trim())
        .where((item) => item.isNotEmpty)
        .toSet()
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;
    final memoryLength = _memoryController.text.trim().length;
    final overLimit = memoryLength > _maxMemoryLength;

    return Padding(
      padding: EdgeInsets.only(bottom: bottomInset),
      child: OmniGlassSheet(
        child: SafeArea(
          top: false,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const SizedBox(height: 12),
                Container(
                  width: 42,
                  height: 4,
                  decoration: BoxDecoration(
                    color: palette.borderSubtle,
                    borderRadius: BorderRadius.circular(999),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 18, 20, 24),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              widget.title,
                              style: TextStyle(
                                color: palette.textPrimary,
                                fontSize: 17,
                                fontWeight: AppTextStyles.fontWeightSemiBold,
                                height: AppTextStyles.lineHeightH2,
                              ),
                            ),
                          ),
                          IconButton(
                            onPressed: () => Navigator.of(context).pop(),
                            icon: Icon(
                              Icons.close_rounded,
                              color: palette.textSecondary,
                              size: 20,
                            ),
                            splashRadius: 18,
                          ),
                        ],
                      ),
                      const SizedBox(height: 14),
                      TextField(
                        controller: _memoryController,
                        maxLines: 5,
                        minLines: 3,
                        textInputAction: TextInputAction.newline,
                        decoration: InputDecoration(
                          hintText: 'Enter the long-term memory to save, for example: I prefer unsweetened Americano coffee',
                          hintStyle: TextStyle(
                            color: palette.textTertiary,
                            fontSize: 15,
                          ),
                          filled: true,
                          fillColor: palette.surfaceSecondary,
                          contentPadding: const EdgeInsets.all(14),
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(14),
                            borderSide: BorderSide.none,
                          ),
                          enabledBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(14),
                            borderSide: BorderSide.none,
                          ),
                          focusedBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(14),
                            borderSide: BorderSide(
                              color: palette.textPrimary,
                              width: 1,
                            ),
                          ),
                        ),
                        style: TextStyle(
                          color: palette.textPrimary,
                          fontSize: 15,
                          height: AppTextStyles.lineHeightH2,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Align(
                        alignment: Alignment.centerRight,
                        child: Text(
                          '$memoryLength/$_maxMemoryLength',
                          style: TextStyle(
                            color: overLimit
                                ? AppColors.alertRed
                                : palette.textTertiary,
                            fontSize: AppTextStyles.fontSizeSmall,
                          ),
                        ),
                      ),
                      const SizedBox(height: 12),
                      TextField(
                        controller: _categoryController,
                        maxLines: 2,
                        decoration: InputDecoration(
                          labelText: 'Tags (Optional)',
                          labelStyle: TextStyle(
                            color: palette.textSecondary,
                            fontSize: AppTextStyles.fontSizeSmall,
                          ),
                          hintText: 'Separate with commas, for example: preference, music',
                          hintStyle: TextStyle(
                            color: palette.textTertiary,
                            fontSize: AppTextStyles.fontSizeSmall,
                          ),
                          filled: true,
                          fillColor: palette.surfaceSecondary,
                          contentPadding: const EdgeInsets.all(14),
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(14),
                            borderSide: BorderSide.none,
                          ),
                          enabledBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(14),
                            borderSide: BorderSide.none,
                          ),
                          focusedBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(14),
                            borderSide: BorderSide(
                              color: palette.textPrimary,
                              width: 1,
                            ),
                          ),
                        ),
                        style: TextStyle(
                          color: palette.textPrimary,
                          fontSize: 15,
                          height: AppTextStyles.lineHeightH2,
                        ),
                      ),
                      const SizedBox(height: 22),
                      SizedBox(
                        width: double.infinity,
                        height: 44,
                        child: ElevatedButton(
                          onPressed: _canSubmit
                              ? () {
                                  Navigator.of(context).pop(
                                    Mem0MemoryEditorResult(
                                      memory: _memoryController.text.trim(),
                                      categories: _parseCategories(),
                                    ),
                                  );
                                }
                              : null,
                          style: ElevatedButton.styleFrom(
                            backgroundColor: palette.textPrimary,
                            disabledBackgroundColor: palette.surfaceElevated,
                            elevation: 0,
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(14),
                            ),
                          ),
                          child: Text(
                            widget.submitLabel,
                            style: TextStyle(
                              color: palette.pageBackground,
                              fontSize: 15,
                              fontWeight: AppTextStyles.fontWeightMedium,
                              height: AppTextStyles.lineHeightH2,
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
