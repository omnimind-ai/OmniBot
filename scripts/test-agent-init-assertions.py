import importlib.util
import json
import sqlite3
import unittest
from pathlib import Path
spec = importlib.util.spec_from_file_location('init_assertion', Path(__file__).with_name('assert-agent-init.py'))
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class InitAssertions(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.execute('CREATE TABLE agent_conversation_entries(id INTEGER, conversationId INTEGER, entryType TEXT, payloadJson TEXT)')
        self.add(1, 'user_message', {'content': {'text': '/init'}})
        self.add(2, 'assistant_message', {'content': {'text': 'Created AGENTS.md'}, 'streamMeta': {'sessionId': 's', 'turnId': 't', 'stopReason': 'end_turn'}})
    def add(self, i, kind, payload):
        self.db.execute('INSERT INTO agent_conversation_entries VALUES(?,1,?,?)', (i,kind,json.dumps(payload)))
    def test_attachment_is_required_and_cancel_is_not_success(self):
        with self.assertRaises(AssertionError): module.verify(self.db,0,attachment='fixture.txt')
        self.db.execute('DELETE FROM agent_conversation_entries')
        self.add(1,'user_message',{'content':{'text':'/init','attachments':[{'name':'fixture.txt','path':'/workspace/fixture.txt'}]}})
        self.add(2,'assistant_message',{'streamMeta':{'sessionId':'s','turnId':'t','stopReason':'cancelled'}})
        self.assertTrue(module.verify(self.db,0,'cancelled','fixture.txt')['passed'])
        with self.assertRaises(AssertionError): module.verify(self.db,0,'end_turn','fixture.txt')
        with self.assertRaises(AssertionError): module.verify(self.db,0,'active','fixture.txt')
    def test_active_requires_output_identity_and_no_terminal(self):
        self.db.execute('DELETE FROM agent_conversation_entries WHERE id=2')
        with self.assertRaises(module.InitPending): module.verify(self.db,0,'active')
        self.add(2,'assistant_message',{'isLoading':True,'streamMeta':{'sessionId':'s','turnId':'t'}})
        self.assertTrue(module.verify(self.db,0,'active')['passed'])
        with self.assertRaises(module.InitPending): module.verify(self.db,0)
    def test_completion(self):
        self.assertTrue(module.verify(self.db,0)['passed'])
    def test_old_success_cannot_pass(self):
        with self.assertRaises(AssertionError): module.verify(self.db,2)
    def test_duplicate_submission(self):
        self.add(3,'user_message',{'content':{'text':'/init'}})
        with self.assertRaises(AssertionError): module.verify(self.db,0)
    def test_error_cancel_and_pending_never_pass(self):
        for stop in ['error','cancelled',None]:
            with self.subTest(stop=stop):
                self.db.execute('DELETE FROM agent_conversation_entries WHERE id=2')
                self.add(2,'assistant_message',{'streamMeta':{'sessionId':'s','turnId':'t','stopReason':stop}})
                with self.assertRaises(AssertionError): module.verify(self.db,0)
    def test_mixed_identity(self):
        self.add(3,'assistant_message',{'streamMeta':{'sessionId':'other','turnId':'t','stopReason':'end_turn'}})
        with self.assertRaises(AssertionError): module.verify(self.db,0)
    def test_duplicate_tool_projection(self):
        for i in [3,4]:
            self.add(i,'tool_event',{'toolName':'file_write','toolCallId':'call1','streamMeta':{'sessionId':'s','turnId':'t'}})
        with self.assertRaises(AssertionError): module.verify(self.db,0)

if __name__ == '__main__': unittest.main()
