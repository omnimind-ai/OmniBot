import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/acp_extension_registry.dart';

void main() {
  test('keeps Xiaowan shared presentation metadata compatible', () {
    final projection = AcpExtensionRegistry.shared.project({
      '_meta': {
        'cn.com.omnimind.agent': {
          'usage': {
            'turnUsage': {'in': 10, 'out': 4},
          },
          'reasoning': {'taskTitle': 'inspect'},
        },
      },
    });

    expect(projection.presentation['usage'], isA<Map>());
    expect(projection.presentation['reasoning'], isA<Map>());
    expect(
      projection.extensions['cn.com.omnimind.agent']!['reasoning'],
      isA<Map>(),
    );
  });

  test('projects common metadata aliases from another ACP namespace', () {
    final projection = AcpExtensionRegistry.shared.project({
      '_meta': {
        'com.example.agent': {
          'thinking': {'taskTitle': 'plan'},
          'context_compaction': {'status': 'started'},
          'artifacts': [
            {'uri': 'file:///tmp/result.txt'},
          ],
        },
      },
    });

    expect(projection.presentation['reasoning'], isA<Map>());
    expect(projection.presentation['compaction'], isA<Map>());
    expect(projection.presentation['artifacts'], isA<List>());
  });

  test('allows a Harness adapter to register a typed namespace projector', () {
    final registry = AcpExtensionRegistry(
      projectors: {
        'com.example.typed': (payload) => {'usage': payload['tokens']},
      },
    );

    final projection = registry.project({
      '_meta': {
        'com.example.typed': {'tokens': 42},
      },
    });

    expect(projection.presentation['usage'], 42);
    expect(projection.extensions['com.example.typed']!['tokens'], 42);
  });

  test('retains non-object extension payloads for forward compatibility', () {
    final projection = AcpExtensionRegistry().project({
      '_meta': {
        'com.example.scalar': 'provider-progress',
        'com.example.list': [1, 2, 3],
      },
    });

    expect(projection.extensions['com.example.scalar'], 'provider-progress');
    expect(projection.extensions['com.example.list'], [1, 2, 3]);
  });

  test('maps legacy reasoning fields into the shared reasoning projection', () {
    final projection = AcpExtensionRegistry().project({
      '_meta': {
        'com.example.agent': {
          'deep_thinking': 'thinking',
          'task_title': 'Inspect the repository',
          'sub_tasks': ['read', 'test'],
          'clarify': {'question': 'Which file? '},
        },
      },
    });

    expect(projection.presentation['reasoning'], {
      'text': 'thinking',
      'taskTitle': 'Inspect the repository',
      'subTasks': ['read', 'test'],
    });
    expect(projection.presentation['clarification'], {
      'question': 'Which file? ',
    });
  });
}
