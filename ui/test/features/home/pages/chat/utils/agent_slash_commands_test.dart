import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/utils/agent_slash_commands.dart';

void main() {
  test('Plan shortcut requires an exact advertised value', () {
    expect(advertisedPlanMode(const []), isNull);
    expect(advertisedPlanMode(['default', 'explain', 'planning_disabled']), isNull);
    expect(advertisedPlanMode(['default', 'Plan']), 'Plan');
  });

  test('unadvertised pause and resume never resolve to execution controls', () {
    for (var attempt = 0; attempt < 20; attempt++) {
      for (final command in ['/pause', '/resume', '/PAUSE', '/resume task']) {
        expect(resolveAgentSlashSubmitIntent(command).kind,
            AgentSlashSubmitKind.unsupported);
      }
    }
  });

  test('routes codex model command intents', () {
    expect(
      resolveAgentSlashSubmitIntent('/model').kind,
      AgentSlashSubmitKind.openModelPicker,
    );

    final intent = resolveAgentSlashSubmitIntent('/model gpt-5-codex');
    expect(intent.kind, AgentSlashSubmitKind.selectModel);
    expect(intent.value, 'gpt-5-codex');
  });

  test('routes codex review init and plan command intents', () {
    expect(
      resolveAgentSlashSubmitIntent('/review').kind,
      AgentSlashSubmitKind.startReview,
    );
    expect(
      resolveAgentSlashSubmitIntent('/init').kind,
      AgentSlashSubmitKind.startInit,
    );
    expect(
      resolveAgentSlashSubmitIntent('/plan').kind,
      AgentSlashSubmitKind.togglePlan,
    );

    final planIntent = resolveAgentSlashSubmitIntent('/plan inspect the diff');
    expect(planIntent.kind, AgentSlashSubmitKind.startPlan);
    expect(planIntent.value, 'inspect the diff');

    expect(
      resolveAgentSlashSubmitIntent('/chat').kind,
      AgentSlashSubmitKind.unsupported,
    );
    expect(
      resolveAgentSlashSubmitIntent('/normal').kind,
      AgentSlashSubmitKind.unsupported,
    );
  });

  test('rejects agent-only slash commands in codex mode', () {
    expect(
      resolveAgentSlashSubmitIntent('/compact').kind,
      AgentSlashSubmitKind.unsupported,
    );
    expect(
      resolveAgentSlashSubmitIntent('/effort high').kind,
      AgentSlashSubmitKind.unsupported,
    );
    expect(
      resolveAgentSlashSubmitIntent('/openclaw http://example.com').kind,
      AgentSlashSubmitKind.unsupported,
    );
  });
}
