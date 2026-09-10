import importlib.util
import json
import sqlite3
import unittest
from pathlib import Path
spec = importlib.util.spec_from_file_location('checkpoint', Path(__file__).with_name('assert-agent-context-checkpoint.py'))
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class AutomaticCheckpointTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:'); self.addCleanup(self.db.close)
        self.db.execute('CREATE TABLE conversations(id INTEGER,contextSummary TEXT,contextSummaryCutoffEntryDbId INTEGER,contextSummaryUpdatedAt INTEGER)')
        self.db.execute("INSERT INTO conversations VALUES(1,'summary',12,100)")
        self.db.execute('CREATE TABLE agent_conversation_entries(id INTEGER,conversationId INTEGER,entryType TEXT,payloadJson TEXT)')
        self.marker='OOB_LIVE_AUTO_COMPACT_123'
        self.db.execute('INSERT INTO agent_conversation_entries VALUES(10,1,?,?)',('user_message', json.dumps({'content':{'text':self.marker}})))
        self.db.execute('INSERT INTO agent_conversation_entries VALUES(12,1,?,?)',('tool_event',json.dumps({'toolCallId':'call','sessionId':'session','success':True})))
    def test_same_task_checkpoint_is_stable_across_database_reopen(self):
        first=module.verify_live_checkpoint(self.db,self.marker)
        reopened=sqlite3.connect(':memory:'); self.addCleanup(reopened.close)
        reopened.executescript('\n'.join(self.db.iterdump()))
        self.assertEqual(first,module.verify_live_checkpoint(reopened,self.marker))
    def test_old_summary_does_not_prove_new_compaction(self):
        self.db.execute('UPDATE conversations SET contextSummaryCutoffEntryDbId=5')
        with self.assertRaisesRegex(AssertionError,'Old checkpoint'): module.verify_live_checkpoint(self.db,self.marker)
    def test_unfinished_tool_cannot_be_checkpoint(self):
        self.db.execute("UPDATE agent_conversation_entries SET payloadJson=? WHERE id=12",(json.dumps({'toolCallId':'call','sessionId':'session','success':False}),))
        with self.assertRaisesRegex(AssertionError,'completion missing'): module.verify_live_checkpoint(self.db,self.marker)
    def test_later_task_checkpoint_is_not_accepted(self):
        self.db.execute('INSERT INTO agent_conversation_entries VALUES(11,1,?,?)',('user_message','{}'))
        with self.assertRaisesRegex(AssertionError,'Later task'): module.verify_live_checkpoint(self.db,self.marker)
    def test_duplicate_admission_is_not_accepted(self):
        self.db.execute('INSERT INTO agent_conversation_entries SELECT 11,conversationId,entryType,payloadJson FROM agent_conversation_entries WHERE id=10')
        with self.assertRaisesRegex(AssertionError,'duplicate'): module.verify_live_checkpoint(self.db,self.marker)

if __name__=='__main__': unittest.main()
