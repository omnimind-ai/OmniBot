import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/agent/agent_sessions_page.dart';

void main() {
  test('projects local ACP host ownership without treating history as loaded', () {
    final sessions = extractAgentSessionSummariesForTesting([
      {'sessions': [
        {'sessionId': 'running', 'loaded': true, 'active': true},
        {'sessionId': 'idle', 'loaded': true, 'active': false},
        {'sessionId': 'history', 'loaded': false, 'active': false},
      ]},
    ]);
    expect(sessions, hasLength(3));
    final byId = {for (final session in sessions) session['threadId']: session};
    expect(byId['running']!['active'], isTrue);
    expect(byId['idle']!['active'], isFalse);
    expect(byId['idle']!['loaded'], isTrue);
    expect(byId['history']!['loaded'], isFalse);
  });

  test('extracts remote codex session metadata for the dashboard', () {
    final sessions = extractAgentSessionSummariesForTesting([
      {
        'threads': ['loaded-thread'],
      },
      {
        'threads': [
          {
            'id': 'running-thread',
            'title': 'Fix remote Codex UX',
            'cwd': '/Users/ocean/code/OmnibotApp',
            'status': 'running',
            'summary': 'Building the native mobile control surface',
            'model': 'gpt-5-codex',
            'gitBranch': 'feat/remote-codex',
            'updated_at': '2026-05-30T10:30:00Z',
          },
          {
            'id': 'archived-thread',
            'name': 'Old review',
            'archived': true,
            'status': 'idle',
          },
        ],
      },
    ]);

    final running = sessions.firstWhere(
      (session) => session['threadId'] == 'running-thread',
    );
    expect(running['active'], isTrue);
    expect(running['loaded'], isFalse);
    expect(running['title'], 'Fix remote Codex UX');
    expect(running['preview'], 'Building the native mobile control surface');
    expect(running['model'], 'gpt-5-codex');
    expect(running['branch'], 'feat/remote-codex');

    final loaded = sessions.firstWhere(
      (session) => session['threadId'] == 'loaded-thread',
    );
    expect(loaded['active'], isFalse);
    expect(loaded['loaded'], isTrue);

    final archived = sessions.firstWhere(
      (session) => session['threadId'] == 'archived-thread',
    );
    expect(archived['archived'], isTrue);
  });

  test('merges loaded thread ids with richer thread list entries', () {
    final sessions = extractAgentSessionSummariesForTesting([
      {
        'loaded_threads': ['thread-1'],
      },
      {
        'threads': [
          {
            'id': 'thread-1',
            'title': 'Continue mobile session',
            'cwd': '/workspace/app',
            'status': 'loaded',
          },
        ],
      },
    ]);

    expect(sessions, hasLength(1));
    expect(sessions.single['title'], 'Continue mobile session');
    expect(sessions.single['cwd'], '/workspace/app');
    expect(sessions.single['active'], isFalse);
    expect(sessions.single['loaded'], isTrue);
  });
}
