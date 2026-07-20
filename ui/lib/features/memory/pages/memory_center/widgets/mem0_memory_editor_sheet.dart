import 'package:flutter/material.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/app_text_styles.dart';

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
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;
    final memoryLength = _memoryController.text.trim().length;
    final overLimit = memoryLength > _maxMemoryLength;

    return Padding(
      padding: EdgeInsets.only(bottom: bottomInset),
      child: Container(
        decoration: const BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
        child: SafeArea(
          top: false,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const SizedBox(height: 12),
              Container(
                width: 42,
                height: 4,
                decoration: BoxDecoration(
                  color: AppColors.text10,
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
                            style: const TextStyle(
                              color: AppColors.text,
                              fontSize: AppTextStyles.fontSizeH2,
                              fontWeight: AppTextStyles.fontWeightSemiBold,
                              height: AppTextStyles.lineHeightH2,
                            ),
                          ),
                        ),
                        IconButton(
                          tooltip: \'Action\', onPressed: () => Navigator.of(context).pop(),
                          icon: const Icon(
                            Icons.close_rounded,
                            color: AppColors.text70,
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
                        hintText:
                            'Enter the long-term memory to save, for example: I prefer unsweetened Americano coffee',
                        hintStyle: const TextStyle(
                          color: AppColors.text50,
                          fontSize: AppTextStyles.fontSizeMain,
                        ),
                        filled: true,
                        fillColor: AppColors.text03,
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
                          borderSide: const BorderSide(
                            color: AppColors.buttonPrimary,
                            width: 1,
                          ),
                        ),
                      ),
                      style: const TextStyle(
                        color: AppColors.text,
                        fontSize: AppTextStyles.fontSizeMain,
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
                              : AppColors.text50,
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
                        labelStyle: const TextStyle(
                          color: AppColors.text70,
                          fontSize: AppTextStyles.fontSizeSmall,
                        ),
                        hintText:
                            'Separate with commas, for example: preference, music',
                        hintStyle: const TextStyle(
                          color: AppColors.text50,
                          fontSize: AppTextStyles.fontSizeSmall,
                        ),
                        filled: true,
                        fillColor: AppColors.text03,
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
                          borderSide: const BorderSide(
                            color: AppColors.buttonPrimary,
                            width: 1,
                          ),
                        ),
                      ),
                      style: const TextStyle(
                        color: AppColors.text,
                        fontSize: AppTextStyles.fontSizeMain,
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
                          backgroundColor: AppColors.buttonPrimary,
                          disabledBackgroundColor: AppColors.text20,
                          elevation: 0,
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(14),
                          ),
                        ),
                        child: Text(
                          widget.submitLabel,
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: AppTextStyles.fontSizeH3,
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
    );
  }
}
