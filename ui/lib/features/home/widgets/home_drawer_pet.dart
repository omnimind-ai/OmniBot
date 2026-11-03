part of 'home_drawer.dart';

extension _HomeDrawerPet on HomeDrawerState {
  Future<void> _handlePetOverlayTap() async {
    if (_isPetOverlayOpening) {
      return;
    }
    _setPetOverlayOpening(true);
    var isHidingPet = false;

    try {
      final isShowing = await OverlayService.isPetOverlayShowing();
      if (!mounted) {
        return;
      }
      if (isShowing) {
        isHidingPet = true;
        final hidden = await OverlayService.hidePetOverlay();
        if (!hidden) {
          throw StateError('hidePetOverlay returned false');
        }
        _setPetOverlayShowing(false);
        return;
      }

      final overlaySpecs = PermissionRegistry.getPermissionsByLevel(
        brand: 'other',
        level: PermissionLevel.overlayDisplay,
      );
      final permissionDataList = PermissionService.specsToPermissionData(
        overlaySpecs,
        context: context,
      );
      await PermissionService.checkPermissions(permissionDataList);
      if (!mounted) {
        return;
      }

      final overlayPermission = permissionDataList
          .where((permission) => permission.id == kOverlayPermissionId)
          .firstOrNull;
      if (overlayPermission == null) {
        throw StateError('Overlay permission is not registered');
      }

      if (!overlayPermission.notifier.value) {
        final shouldShowPet = await PetOverlayPermissionSheet.show(
          context,
          permission: overlayPermission,
        );
        if (!mounted || !shouldShowPet) {
          return;
        }
      }

      final shown = await OverlayService.showPetOverlay();
      if (!shown) {
        throw StateError('showPetOverlay returned false');
      }
      _setPetOverlayShowing(true);
    } catch (error) {
      debugPrint('${isHidingPet ? '收起' : '唤起'}宠物失败: $error');
      if (mounted) {
        showToast(
          LegacyTextLocalizer.localize(
            isHidingPet ? '收起宠物失败' : '唤起宠物失败，请确认悬浮窗权限已开启',
          ),
          type: ToastType.error,
        );
      }
    } finally {
      await _syncPetOverlayState();
      _setPetOverlayOpening(false);
    }
  }

  Future<void> _syncPetOverlayState() async {
    try {
      final isShowing = await OverlayService.isPetOverlayShowing();
      _setPetOverlayShowing(isShowing);
    } catch (error) {
      debugPrint('同步宠物悬浮窗状态失败: $error');
    }
  }

  void _setPetOverlayOpening(bool value) {
    if (!mounted || _isPetOverlayOpening == value) {
      return;
    }
    _isPetOverlayOpening = value;
    _homeMenuRevision.value++;
  }

  void _setPetOverlayShowing(bool value) {
    if (!mounted || _isPetOverlayShowing == value) {
      return;
    }
    _isPetOverlayShowing = value;
    _homeMenuRevision.value++;
  }
}
