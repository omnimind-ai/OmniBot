"""Run the production Room deletion SQL against paged and mixed-mode history."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DAO = (ROOT / "baselib/src/main/java/cn/com/omnimind/baselib/database/AgentConversationEntryDao.kt").read_text()


def query(method, ids):
    matched = re.search(r'@Query\("""(.*?)"""\)\s*suspend fun ' + method, DAO, re.S)
    sql = matched.group(1).split('@Query("""')[-1]
    sql = sql.replace("IN (:modes)", "IN ('agent', 'normal', 'codex')")
    sql = sql.replace("IN (:entryIds)", "IN (" + ",".join(f":item{i}" for i in range(len(ids))) + ")")
    return sql, {f"item{i}": value for i, value in enumerate(ids)}


class HistoryItemRemovalTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("""CREATE TABLE agent_conversation_entries (
            id INTEGER PRIMARY KEY, conversationId INTEGER, conversationMode TEXT,
            entryId TEXT, entryType TEXT, payloadJson TEXT)""")
        for conversation in (1, 2):
            for number in range(1000):
                for mode in ("agent", "normal", "codex"):
                    self.db.execute("INSERT INTO agent_conversation_entries VALUES(NULL,?,?,?,?,?)",
                                    (conversation, mode, f"item-{number}", "assistant_message", '{"text":"answer"}'))
        self.full_payload = '{"text":"' + "完整🙂" * 100000 + '"}'
        self.db.execute("INSERT INTO agent_conversation_entries VALUES(NULL,1,'agent','offloaded','ui_card',?)", (self.full_payload,))
        self.db.execute("INSERT INTO agent_conversation_entries VALUES(NULL,1,'agent','concurrent','user_message','{}')")
        self.db.execute("INSERT INTO agent_conversation_entries VALUES(NULL,1,'agent','raw-update','stream_event','{}')")

    def tearDown(self):
        self.db.close()

    def test_removal_keeps_unloaded_history_other_conversations_and_full_payloads(self):
        sql, args = query("deleteVisibleEntriesById", ["item-998", "item-999", "raw-update"])
        self.db.execute(sql, dict(args, conversationId=1))
        ids = {row[0] for row in self.db.execute("SELECT entryId FROM agent_conversation_entries WHERE conversationId=1")}
        self.assertIn("item-0", ids)
        self.assertIn("concurrent", ids)
        self.assertIn("raw-update", ids)
        self.assertNotIn("item-998", ids)
        self.assertNotIn("item-999", ids)
        self.assertEqual(self.full_payload, self.db.execute("SELECT payloadJson FROM agent_conversation_entries WHERE entryId='offloaded'").fetchone()[0])
        self.assertEqual(3, self.db.execute("SELECT count(*) FROM agent_conversation_entries WHERE conversationId=2 AND entryId='item-999'").fetchone()[0])

    def test_checkpoint_only_invalidates_when_removed_identity_touches_prefix(self):
        cutoff = self.db.execute("SELECT id FROM agent_conversation_entries WHERE conversationId=1 AND conversationMode='agent' AND entryId='item-500'").fetchone()[0]
        for ids, expected in ((["item-499"], 1), (["item-500"], 1), (["item-999"], 0), (["raw-update"], 0), (["missing"], 0)):
            sql, args = query("hasEntriesInContextPrefix", ids)
            self.assertEqual(expected, self.db.execute(sql, dict(args, conversationId=1, cutoffEntryDbId=cutoff)).fetchone()[0])


if __name__ == "__main__":
    unittest.main()
