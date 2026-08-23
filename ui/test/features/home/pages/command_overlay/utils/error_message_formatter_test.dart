import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/utils/error_message_formatter.dart';

void main() {
  test('extracts an OpenAI-compatible nested error message', () {
    const raw = '''
{"error":{"message":"Invalid JSON data: tools[8].type: unknown variant namespace","type":"upstream_error","code":"400"}}
''';

    final result = formatErrorMessageForUser(raw, fallback: 'fallback');

    expect(result, contains('Invalid JSON data'));
    expect(result, isNot(contains('{"error"')));
  });
}
