import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/terminal_output_utils.dart';

void main() {
  test('terminal detail output preserves every persisted line', () {
    final output = List<String>.generate(
      1_001,
      (index) => 'line-$index ${'x' * 80}',
    ).join('\n');

    final displayed = TerminalOutputUtils.buildDisplayOutput(
      terminalOutput: output,
      rawResultJson: '',
      resultPreviewJson: '',
    );

    expect(displayed, contains('line-0'));
    expect(displayed, contains('line-1000'));
    expect(displayed.length, output.length);
  });

  test('terminal detail transcript receives complete output', () {
    final output = List<String>.generate(
      1_001,
      (index) => 'line-$index',
    ).join('\n');

    final transcript = buildAgentToolTranscript(<String, dynamic>{
      'toolType': 'terminal',
      'toolName': 'terminal_execute',
      'terminalOutput': output,
    });

    expect(transcript.outputText, contains('line-0'));
    expect(transcript.outputText, contains('line-1000'));
    expect(transcript.outputText.length, output.length);
  });
}
