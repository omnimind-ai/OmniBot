import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/permission_registry.dart';
import 'package:ui/services/permission_service.dart';
import 'package:ui/services/special_permission.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Map<String, dynamic> snapshot;
  late List<String> calls;
  late List<String> assistsCoreCalls;

  setUp(() {
    debugResetAppEditionCapabilitySnapshot();
    calls = <String>[];
    assistsCoreCalls = <String>[];
    snapshot = <String, dynamic>{
      'schemaVersion': 1,
      'edition': 'standard',
      'installedAppsQuery': true,
      'publicStorageAccess': true,
    };
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, (call) async {
          calls.add(call.method);
          if (call.method == 'getAppEditionCapabilitySnapshot') {
            return snapshot;
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(AssistsMessageService.assistCore, (
          call,
        ) async {
          assistsCoreCalls.add(call.method);
          return const <dynamic>[];
        });
  });

  tearDown(() {
    debugResetAppEditionCapabilitySnapshot();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(AssistsMessageService.assistCore, null);
  });

  test('standard snapshot keeps installed-app and public-storage UI', () async {
    final capabilities = await refreshAppEditionCapabilitySnapshot();

    expect(capabilities.installedAppsQuery, isTrue);
    expect(capabilities.publicStorageAccess, isTrue);
    expect(
      PermissionRegistry.getPermissions(
        brand: 'other',
      ).any((item) => item.id == kInstalledAppsPermissionId),
      isTrue,
    );
    expect(
      PermissionService.buildDisplayPermissionsForIds(const <String>[
        kPublicStoragePermissionId,
      ]),
      hasLength(1),
    );
  });

  test(
    'play snapshot is fail-closed and never opens unavailable settings',
    () async {
      snapshot = <String, dynamic>{
        'schemaVersion': 1,
        'edition': 'play',
        // Even a malformed native Play payload cannot opt these capabilities in.
        'installedAppsQuery': true,
        'publicStorageAccess': true,
      };
      final capabilities = await refreshAppEditionCapabilitySnapshot();

      expect(capabilities.installedAppsQuery, isFalse);
      expect(capabilities.publicStorageAccess, isFalse);
      expect(
        PermissionRegistry.getPermissions(
          brand: 'other',
        ).any((item) => item.id == kInstalledAppsPermissionId),
        isFalse,
      );
      expect(
        PermissionService.buildDisplayPermissionsForIds(const <String>[
          kInstalledAppsPermissionId,
          kPublicStoragePermissionId,
        ]),
        isEmpty,
      );

      expect(await isInstalledAppsPermissionGranted(), isFalse);
      expect(await isPublicStorageAccessGranted(), isFalse);
      expect(await AssistsMessageService.getInstalledApplications(), isEmpty);
      expect(
        await AssistsMessageService.getInstalledApplicationsWithIconUpdate(),
        isEmpty,
      );
      expect(await AssistsMessageService.getDeskTopPackageName(), isEmpty);
      await openInstalledAppsSettings();
      await openPublicStorageSettings();
      expect(calls, isNot(contains('isInstalledAppsPermissionGranted')));
      expect(calls, isNot(contains('isPublicStorageAccessGranted')));
      expect(calls, isNot(contains('openInstalledAppsSettings')));
      expect(calls, isNot(contains('openPublicStorageSettings')));
      expect(assistsCoreCalls, isEmpty);
    },
  );

  test('malformed native snapshot fails closed', () async {
    snapshot = <String, dynamic>{
      'schemaVersion': 2,
      'edition': 'standard',
      'installedAppsQuery': true,
      'publicStorageAccess': true,
    };

    final capabilities = await refreshAppEditionCapabilitySnapshot();

    expect(capabilities, same(AppEditionCapabilitySnapshot.unavailable));
    expect(capabilities.installedAppsQuery, isFalse);
    expect(capabilities.publicStorageAccess, isFalse);
  });
}
