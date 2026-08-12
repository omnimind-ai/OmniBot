import 'package:flutter/material.dart';
// 分组容器
class SettingSection extends StatelessWidget {
  final List<Widget> children;
  const SettingSection({super.key, required this.children});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(10),
        boxShadow: [
          BoxShadow(
            color: Color(0x0D000000),
            blurRadius: 3,
            offset: Offset(0, 0),
            spreadRadius: 0,
          )
        ],
      ),
      child: Column(
        children: List.generate(children.length * 2 - 1, (i) {
          if (i.isOdd) {
            return Divider(height: 0.5, indent: 16, endIndent: 16, color: Color(0x0D000000));
          }
          return children[i ~/ 2];
        }),
      ),
    );
  }
}
