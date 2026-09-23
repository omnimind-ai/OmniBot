"""Exercise the production Room paging SQL and index on a long mixed history."""
import re
import sqlite3
import statistics
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATABASE = ROOT / "baselib/src/main/java/cn/com/omnimind/baselib/database"
DAO = (DATABASE / "AgentConversationEntryDao.kt").read_text()
QUERY = re.search(
    r'@Query\("""(.*?)"""\)\s*suspend fun getLogicalThreadPageSlices',
    DAO, re.S,
).group(1).split('@Query("""')[-1]
PROJECTION = re.search(r'CHUNKED_ENTRY_PROJECTION = """(.*?)"""', DAO, re.S).group(1)
QUERY = QUERY.replace("$CHUNKED_ENTRY_PROJECTION", PROJECTION).replace(
    "IN (:modes)", "IN ('agent', 'codex', 'normal')"
)
INDEX_NAME = "index_agent_conversation_entries_conversationId_createdAt_id"
MIGRATION = (DATABASE / "DatabaseHelper.kt").read_text()
INDEX = re.search(r'"(CREATE INDEX IF NOT EXISTS ' + INDEX_NAME + r' )"\s*\+\s*"([^"]+)"', MIGRATION)
INDEX_SQL = "".join(INDEX.groups())


def database():
    db = sqlite3.connect(":memory:")
    db.execute("""CREATE TABLE agent_conversation_entries (
        id INTEGER PRIMARY KEY, conversationId INTEGER, conversationMode TEXT,
        entryId TEXT, entryType TEXT, status TEXT, summary TEXT, payloadJson TEXT,
        createdAt INTEGER, updatedAt INTEGER)""")
    db.execute("CREATE UNIQUE INDEX identity ON agent_conversation_entries (conversationId, conversationMode, entryId)")
    db.execute("CREATE INDEX updated ON agent_conversation_entries (conversationId, conversationMode, updatedAt)")
    rows = []
    for conversation in (1, 2):
        for i in range(10000):
            for mode in ("agent", "normal", "codex"):
                rows.append((conversation, mode, f"item-{i}", "assistant_message", "success", "text", '{"text":"answer"}', i // 2, i))
        rows.append((conversation, "agent", "hidden", "stream_event", "success", "", "{}", 99999, 99999))
    db.executemany("INSERT INTO agent_conversation_entries VALUES (NULL,?,?,?,?,?,?,?,?,?)", rows)
    return db


class HistoryQueryTest(unittest.TestCase):
    def test_index_preserves_all_pages_and_avoids_full_sort(self):
        db = database()
        args = dict(conversationId=1, limit=50, offset=0)
        baseline = db.execute(QUERY, args).fetchall()
        self.assertTrue(any("TEMP B-TREE" in row[3] for row in db.execute("EXPLAIN QUERY PLAN " + QUERY, args)))
        db.execute(INDEX_SQL)
        plan = [row[3] for row in db.execute("EXPLAIN QUERY PLAN " + QUERY, args)]
        self.assertFalse(any("TEMP B-TREE" in row for row in plan), plan)
        self.assertTrue(any(INDEX_NAME in row for row in plan), plan)
        self.assertEqual(baseline, db.execute(QUERY, args).fetchall())
        seen = []
        for offset in range(0, 10000, 50):
            page = db.execute(QUERY, dict(args, offset=offset)).fetchall()
            self.assertTrue(all(row[1] == 1 and row[2] == "agent" for row in page))
            seen.extend(row[3] for row in page)
        self.assertEqual(len(seen), 10000)
        self.assertEqual(len(set(seen)), 10000)
        self.assertEqual(set(seen), {f"item-{i}" for i in range(10000)})
        db.close()

    def test_conversation_paging_filters_before_offset_and_orders_like_sidebar(self):
        source = (DATABASE / "ConversationDao.kt").read_text()
        query = re.search(r'@Query\("""(.*?)"""\)\s*suspend fun getDisplayPage', source, re.S).group(1)
        db = sqlite3.connect(":memory:")
        db.execute("CREATE TABLE conversations (id INTEGER PRIMARY KEY, mode TEXT, isArchived INTEGER, updatedAt INTEGER, createdAt INTEGER)")
        db.executemany("INSERT INTO conversations VALUES (?,?,?,?,?)", [
            (1, "subagent", 0, 20, 20), (2, "agent", 0, 20, 18),
            (3, "normal", 0, 20, 19), (4, "agent", 1, 30, 30),
            (5, "chat_only", 0, 10, 10),
        ])
        args = dict(includeArchived=0, archivedOnly=0, mode=None, offset=0, limit=2)
        self.assertEqual([3, 2], [row[0] for row in db.execute(query, args)])
        self.assertEqual([1, 5], [row[0] for row in db.execute(query, dict(args, offset=2))])
        self.assertEqual([4], [row[0] for row in db.execute(query, dict(args, archivedOnly=1))])
        self.assertEqual([2], [row[0] for row in db.execute(query, dict(args, mode="agent", offset=1))])
        db.close()

    def test_index_benchmark(self):
        db = database()
        args = dict(conversationId=1, limit=51, offset=0)

        def elapsed():
            samples = []
            for _ in range(7):
                start = time.perf_counter()
                db.execute(QUERY, args).fetchall()
                samples.append((time.perf_counter() - start) * 1000)
            return statistics.median(samples)

        before = elapsed()
        db.execute(INDEX_SQL)
        after = elapsed()
        # Timing is evidence, not a flaky pass/fail threshold.
        print(f"SQLite 10,000 logical messages / 30,000 stored rows: first 51 rows {before:.2f} ms -> {after:.2f} ms")
        db.close()


if __name__ == "__main__":
    unittest.main()
