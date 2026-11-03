import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';

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
  int? selectedAvatarIndex;
  String? nickname;
  bool isLoading = true;
  bool _isSaving = false;

  static const presetAvatars = [
    'assets/avatar/default_avatar1.png',
    'assets/avatar/default_avatar2.png',
    'assets/avatar/default_avatar3.png',
    'assets/avatar/default_avatar4.png',
    'assets/avatar/default_avatar5.png',
    'assets/avatar/default_avatar6.png',
  ];

  @override
  void initState() {
    super.initState();
    _loadUserData();
  }

  Future<void> _loadUserData() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    final savedIndex = widget.initialAvatarIndex ?? prefs.getInt('avatarIndex');
    setState(() {
      selectedAvatarIndex =
          savedIndex != null &&
              savedIndex >= 0 &&
              savedIndex < presetAvatars.length
          ? savedIndex
          : 0;
      nickname = widget.initialNickname ?? prefs.getString('nickname') ?? '';
      isLoading = false;
    });
  }

  Future<void> _saveUserData() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('avatarIndex', selectedAvatarIndex!);
    await prefs.setString('nickname', nickname!);
  }

  Future<void> _saveAndClose() async {
    if (isLoading || _isSaving) return;
    setState(() => _isSaving = true);
    try {
      await _saveUserData();
      if (!mounted) return;
      Navigator.pop(context, {
        'avatarIndex': selectedAvatarIndex,
        'nickname': nickname,
      });
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final english = Localizations.localeOf(context).languageCode == 'en';
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: const CommonAppBar(title: '编辑个人信息', primary: true),
      body: SafeArea(
        top: false,
        bottom: false,
        child: OmniContentWidth(
          child: isLoading
              ? Center(
                  child: CircularProgressIndicator(
                    color: palette.accentPrimary,
                  ),
                )
              : SingleChildScrollView(
                  key: const ValueKey('profile-avatar-scroll'),
                  padding: const EdgeInsets.fromLTRB(24, 24, 24, 32),
                  child: Column(
                    children: [
                      CircleAvatar(
                        key: const ValueKey('profile-selected-avatar'),
                        radius: 56,
                        backgroundColor: palette.surfaceElevated,
                        backgroundImage: AssetImage(
                          presetAvatars[selectedAvatarIndex!],
                        ),
                        onBackgroundImageError: (_, _) {},
                      ),
                      const SizedBox(height: 24),
                      Text(
                        english ? 'Choose an avatar' : '选择头像',
                        style: TextStyle(
                          color: palette.textPrimary,
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 20),
                      OmniContentWidth(
                        maxWidth: 360,
                        child: GridView.builder(
                          shrinkWrap: true,
                          physics: const NeverScrollableScrollPhysics(),
                          gridDelegate:
                              const SliverGridDelegateWithMaxCrossAxisExtent(
                                maxCrossAxisExtent: 112,
                                crossAxisSpacing: 16,
                                mainAxisSpacing: 16,
                              ),
                          itemCount: presetAvatars.length,
                          itemBuilder: (context, index) => Semantics(
                            label: english
                                ? 'Avatar ${index + 1}'
                                : '头像 ${index + 1}',
                            selected: selectedAvatarIndex == index,
                            child: Material(
                              color: Colors.transparent,
                              child: InkWell(
                                key: ValueKey('profile-avatar-$index'),
                                customBorder: const CircleBorder(),
                                onTap: _isSaving
                                    ? null
                                    : () => setState(
                                        () => selectedAvatarIndex = index,
                                      ),
                                child: Container(
                                  padding: const EdgeInsets.all(4),
                                  decoration: BoxDecoration(
                                    shape: BoxShape.circle,
                                    border: Border.all(
                                      color: selectedAvatarIndex == index
                                          ? palette.accentPrimary
                                          : Colors.transparent,
                                      width: 3,
                                    ),
                                  ),
                                  child: CircleAvatar(
                                    backgroundColor: palette.surfaceElevated,
                                    backgroundImage: AssetImage(
                                      presetAvatars[index],
                                    ),
                                    onBackgroundImageError: (_, _) {},
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
        ),
      ),
      bottomNavigationBar: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          OmniContentWidth(
            child: SafeArea(
              top: false,
              minimum: const EdgeInsets.fromLTRB(22, 12, 22, 16),
              child: FilledButton(
                key: const ValueKey('profile-save'),
                onPressed: isLoading || _isSaving ? null : _saveAndClose,
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(48),
                  backgroundColor: palette.accentPrimary,
                  foregroundColor: Theme.of(context).colorScheme.onPrimary,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
                child: Text(
                  LegacyTextLocalizer.localize(_isSaving ? '保存中...' : '保存'),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
