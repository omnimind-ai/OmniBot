import importlib.util, json, sqlite3, unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('outcome',Path(__file__).with_name('assert-agent-turn-outcome.py'))
outcome=importlib.util.module_from_spec(spec); spec.loader.exec_module(outcome)

class TurnOutcomeTest(unittest.TestCase):
    def setUp(self):
        self.db=sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.execute('CREATE TABLE agent_conversation_entries(id INTEGER PRIMARY KEY,conversationId INTEGER,entryType TEXT,payloadJson TEXT)')
    def add(self,kind,payload,conversation=1):
        self.db.execute('INSERT INTO agent_conversation_entries(conversationId,entryType,payloadJson) VALUES(?,?,?)',(conversation,kind,json.dumps(payload)))
    def user(self,marker): self.add('user_message',{'content':{'text':'Reply '+marker}})
    def error(self): self.add('tool_event',{'toolName':'agent.status','status':'error','streamMeta':{'sessionId':'s','turnId':'t','stopReason':'error'}})
    def test_earlier_failure_cannot_satisfy_new_turn(self):
        self.user('OOB_OLD');self.error();self.user('OOB_NEW')
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_NEW','error')
    def test_later_failure_cannot_satisfy_earlier_turn(self):
        self.user('OOB_OLD');self.user('OOB_NEW');self.error()
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_OLD','error')
    def test_duplicate_user_admission_is_rejected(self):
        self.user('OOB_NEW');self.error();self.user('OOB_NEW');self.error()
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_NEW','error')
    def test_current_failure_is_accepted(self):
        self.user('OOB_NEW');self.error()
        self.assertTrue(outcome.verify(self.db,'OOB_NEW','error')['passed'])
    def test_wrong_error_category_is_rejected(self):
        self.user('OOB_NEW');self.error()
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_NEW','error','quota')
    def test_unrelated_turn_identity_is_rejected(self):
        self.user('OOB_NEW');self.error()
        self.add('assistant_message',{'streamMeta':{'sessionId':'other','turnId':'t'}})
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_NEW','error')
    def test_failure_cannot_be_reported_as_recovery(self):
        self.user('OOB_NEW');self.error()
        with self.assertRaises(AssertionError): outcome.verify(self.db,'OOB_NEW','recovered')

if __name__=='__main__': unittest.main()
