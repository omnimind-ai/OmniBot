import 'package:flutter/material.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

// 条款页面
class TermsPage extends StatelessWidget {
  final String title;
  final String content;

  TermsPage({required this.title, required this.content});

  @override
  Widget build(BuildContext context) {
    const darkGrey = Color(0xFF666666);

    return Scaffold(
      appBar: CommonAppBar(
        title: title,
        primary: true,
        onBackPressed: () {
          Navigator.pop(context);
        },
      ),
      body: SingleChildScrollView(
        padding: edgeToEdgeScrollPadding(context, const EdgeInsets.all(16)),
        child: Text(content, style: TextStyle(fontSize: 12.0, color: darkGrey)),
      ),
    );
  }
}
