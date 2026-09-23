import 'package:ui/services/storage_service.dart';

/// The platform operation and existing preference are committed by one native owner.
class HideFromRecentsService {
  static Future<bool> setExcludeFromRecents(bool exclude) =>
      StorageService.setHideFromRecentsEnabled(exclude);
}
