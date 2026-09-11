import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/assists_core_service.dart';

void main() {
  test('nested result does not overwrite the current tool lifecycle', () {
    final event = AgentToolEventData.fromMap({
      'toolType': 'terminal',
      'status': 'running',
      'summary': 'Waiting for output',
      'rawResultJson': jsonEncode({
        'status': 'failed',
        'rawOutput': {'exit_code': 182},
      }),
    });
    expect(event.status, 'running');
    expect(event.summary, 'Waiting for output');
  });

  test('invalid stored result preserves the recorded error explanation', () {
    final event = AgentToolEventData.fromMap({
      'toolType': 'terminal',
      'status': 'error',
      'summary': 'Recorded failure',
      'rawResultJson': '{unfinished',
    });
    expect(event.status, 'error');
    expect(event.summary, 'Recorded failure');
  });

  for (final output in ['', 'sandbox diagnostic\n']) {
    test('restores terminal detail from stored ACP result ${output.isNotEmpty}', () {
      final event = AgentToolEventData.fromMap({
        'toolName': 'agent.terminal',
        'toolType': 'terminal',
        'status': 'error',
        'success': false,
        'summary': '',
        'rawResultJson': jsonEncode({
          'type': 'commandExecution',
          'status': 'failed',
          'rawOutput': {'formatted_output': output, 'exit_code': 182},
        }),
      });
      expect(event.status, 'error');
      expect(event.summary, 'Command exited with code 182');
      expect(event.terminalOutput, output);
    });
  }
}
