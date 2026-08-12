import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/widgets/common_app_bar.dart';

class EditProfilePage extends StatefulWidget {
  final int? initialAvatarIndex;
  final String? initialNickname;
  
  const EditProfilePage({
    super.key, 
    this.initialAvatarIndex, 
    this.initialNickname,
  });

  @override
  State<EditProfilePage> createState() => _EditProfilePageState();
}

class _EditProfilePageState extends State<EditProfilePage> {
  int selectedAvatarIndex = 0;
  String nickname = '';
  bool isLoading = true; // 添加加载状态

  @override
  void initState() {
    super.initState();
    _loadUserData();
  }

  Future<void> _loadUserData() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    final storedAvatarIndex =
        widget.initialAvatarIndex ?? prefs.getInt('avatarIndex');
    setState(() {
      selectedAvatarIndex = _validAvatarIndex(storedAvatarIndex);
      nickname = widget.initialNickname ?? prefs.getString('nickname') ?? '';
      isLoading = false; // 加载完成
    });
  }

  Future<void> _saveUserData() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('avatarIndex', selectedAvatarIndex);
    await prefs.setString('nickname', nickname);
  }
  
  // 预设头像列表
  final List<String> presetAvatars = [
    'assets/avatar/default_avatar1.png',
    'assets/avatar/default_avatar2.png',
    'assets/avatar/default_avatar3.png',
    'assets/avatar/default_avatar4.png',
    'assets/avatar/default_avatar5.png',
    'assets/avatar/default_avatar6.png',
  ];

  int _validAvatarIndex(int? candidate) {
    if (candidate == null || candidate < 0) {
      return 0;
    }
    if (candidate >= presetAvatars.length) {
      return presetAvatars.length - 1;
    }
    return candidate;
  }

  @override
  Widget build(BuildContext context) {
    const primaryBlack = Color(0xFF333333);
    const lightGrey2 = Color(0xFFD8D8D8);
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: const CommonAppBar(title: '编辑个人信息', primary: true),
      body: isLoading
          ? const Center(child: CircularProgressIndicator(color: primaryBlack))
          : Padding(
              padding: const EdgeInsets.all(24.0),
              child: Column(
                children: [
                  const SizedBox(height: 40),
                  // 头像区域
                  Center(
                    child: CircleAvatar(
                      radius: 60,
                      backgroundColor: Colors.grey[300],
                      backgroundImage: AssetImage(
                        presetAvatars[selectedAvatarIndex],
                      ),
                      onBackgroundImageError: (_, __) {},
                    ),
                  ),
                  const SizedBox(height: 60),
                  // 昵称区域
                  // Align(
                  //   alignment: Alignment.centerLeft,
                  //   child: Column(
                  //     crossAxisAlignment: CrossAxisAlignment.start,
                  //     children: [
                  //       Padding(
                  //         padding: const EdgeInsets.only(left: 4.0),
                  //         child: const Text(
                  //           '昵称',
                  //           style: TextStyle(
                  //             fontSize: 12,
                  //             color: Colors.grey,
                  //             fontWeight: FontWeight.w400,
                  //           ),
                  //         ),
                  //       ),
                  //       const SizedBox(height: 8),
                  //       Container(
                  //         width: double.infinity,
                  //         padding: const EdgeInsets.symmetric(vertical: 16),
                  //         decoration: const BoxDecoration(
                  //           color: Color(0xFFF5F5F5),
                  //           borderRadius: BorderRadius.all(Radius.circular(8)),
                  //         ),
                  //         child: Padding(
                  //           padding: const EdgeInsets.symmetric(horizontal: 16),
                  //           child: Text(
                  //             nickname ?? "",
                  //             style: const TextStyle(
                  //               fontSize: 16,
                  //               color: Colors.grey,
                  //             ),
                  //           ),
                  //         ),
                  //       ),
                  //     ],
                  //   ),
                  // ),
                  Container(
                    padding: const EdgeInsets.all(20),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        GridView.builder(
                          shrinkWrap: true,
                          gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                            crossAxisCount: 3,
                            crossAxisSpacing: 15,
                            mainAxisSpacing: 15,
                          ),
                          itemCount: presetAvatars.length,
                          itemBuilder: (context, index) {
                            return GestureDetector(
                              onTap: () {
                                setState(() {
                                  selectedAvatarIndex = index;
                                });
                              },
                              child: Container(
                                decoration: BoxDecoration(
                                  shape: BoxShape.circle,
                                  border: Border.all(
                                    color: selectedAvatarIndex == index 
                                        ? Colors.blue 
                                        : Colors.transparent,
                                    width: 3,
                                  ),
                                ),
                                child: CircleAvatar(
                                  radius: 40,
                                  backgroundColor: Colors.grey[300],
                                  backgroundImage: AssetImage(presetAvatars[index]),
                                  onBackgroundImageError: (_, __) {},
                                  child: presetAvatars[index].contains('assets') 
                                      ? null 
                                      : Icon(
                                          Icons.person,
                                          size: 40,
                                          color: Colors.grey[600],
                                        ),
                                ),
                              ),
                            );
                          },
                        ),
                        const SizedBox(height: 20),
                      ],
                    ),
                  ),

                  Container(
                    margin: const EdgeInsets.only(right: 16),
                    child: ElevatedButton(
                      onPressed: isLoading
                          ? null
                          : () async {
                              await _saveUserData();
                              if (!context.mounted) return;
                              Navigator.pop(context, {
                                'avatarIndex': selectedAvatarIndex,
                                'nickname': nickname,
                              });
                            },
                      style: ElevatedButton.styleFrom(
                        backgroundColor: lightGrey2,
                        foregroundColor: Colors.black,
                        elevation: 0,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(8),
                        ),
                        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                      ),
                      child: const Text(
                        '保存',
                        style: TextStyle(fontSize: 14),
                      ),
                    ),
                  ),
                ],
              ),
            ),
    );
  }
}
