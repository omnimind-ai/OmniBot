import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/scheduled_task_scheduler_service.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('restart sync retains disabled tasks without enabling them', () async {
    SharedPreferences.setMockInitialValues({
      'scheduled_tasks': [
        for (final enabled in [false, true])
          jsonEncode({
            'id': 'OOB_$enabled', 'title': 'Synthetic task',
            'targetKind': 'subagent', 'subagentPrompt': 'Return cerulean.',
            'type': 'countdown', 'countdownMinutes': 60,
            'repeatDaily': false, 'isEnabled': enabled,
          }),
      ],
    });
    await StorageService.init();
    final syncs = <List<dynamic>>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(AssistsMessageService.assistCore, (call) async {
      if (call.method == 'syncWorkspaceScheduledTasks') {
        syncs.add(List<dynamic>.from((call.arguments as Map)['tasks']));
        return {'count': syncs.last.length};
      }
      return null;
    });
    addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(AssistsMessageService.assistCore, null));
    await ScheduledTaskSchedulerService.initialize();
    await ScheduledTaskSchedulerService.initialize();
    expect(syncs, hasLength(2));
    for (final tasks in syncs) {
      expect(tasks.map((t) => t['id']), ['OOB_false', 'OOB_true']);
      expect(tasks.map((t) => t['isEnabled']), [false, true]);
    }
  });
}
