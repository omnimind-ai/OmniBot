import 'package:flutter/material.dart';

class ModelSwitcher extends StatefulWidget {
  const ModelSwitcher({Key? key}) : super(key: key);
  @override
  State<ModelSwitcher> createState() => _ModelSwitcherState();
}

class _ModelSwitcherState extends State<ModelSwitcher> {
  String _selectedModel = 'default';
  final List<String> _multimodalModels = [
    'default',
    'gpt-4-vision-preview',
    'claude-3-opus',
    'gemini-pro-vision',
    'custom-vision-model'
  ];
  
  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        DropdownButton<String>(
          value: _selectedModel,
          underline: const SizedBox(),
          iconSize: 0,
          items: _multimodalModels.map((model) {
            return DropdownMenuItem(
              value: model,
              child: Row(
                children: [
                  const Icon(Icons.image_search, size: 16),
                  const SizedBox(width: 4),
                  Text(model, style: const TextStyle(fontSize: 12)),
                ],
              ),
            );
          }).toList(),
          onChanged: (newValue) {
            setState(() {
              _selectedModel = newValue!;
            });
          },
        ),
        IconButton(
          icon: const Icon(Icons.image, size: 20),
          tooltip: 'Enable image input mode',
          padding: EdgeInsets.zero,
          constraints: const BoxConstraints(),
          onPressed: () {},
        ),
      ],
    );
  }
}
