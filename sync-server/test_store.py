import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from store import InitialConflict, Record, Store


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "sync.sqlite"
        self.store = Store(self.path)

    def tearDown(self):
        self.tmp.cleanup()

    def record(self, key, time, device="phone", value="content"):
        return Record("homework", key, time, device, 1, value)

    def test_history_and_tombstone_survive_restart_and_retries(self):
        old = self.record("a", 10)
        deletion = self.record("a", 20, value=None)
        self.assertEqual(2, self.store.append([old, deletion]))
        self.assertEqual(2, self.store.append([old, deletion]))
        restarted = Store(self.path)
        self.assertIsNone(restarted.head("homework", "a").value)
        self.assertEqual([old, deletion], [record for _, record in restarted.changes(0)])

    def test_conflicting_duplicate_rolls_back_entire_batch(self):
        old = self.record("a", 10)
        self.store.append([old])
        with self.assertRaises(ValueError):
            self.store.append([self.record("b", 11), self.record("a", 10, value="different")])
        self.assertIsNone(self.store.head("homework", "b"))
        self.assertEqual(1, len(self.store.changes(0)))

    def test_first_join_never_overwrites_existing_server_record(self):
        first = self.record("a", 10)
        self.store.append([first])
        self.assertEqual(1, self.store.append([first], {0}))
        with self.assertRaises(InitialConflict):
            self.store.append([self.record("b", 11), self.record("a", 20, device="tablet")], {1})
        self.assertIsNone(self.store.head("homework", "b"))
        self.assertEqual(first, self.store.head("homework", "a"))

    def test_parallel_clients_keep_independent_records_and_history(self):
        batches = [[self.record("shared", n, device=str(n)), self.record(str(n), n)] for n in range(1, 21)]
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(self.store.append, batches))
        self.assertEqual(20, self.store.head("homework", "shared").modified_at)
        self.assertEqual(40, len(self.store.changes(0)))
        self.assertTrue(all(self.store.head("homework", str(n)) is not None for n in range(1, 21)))

    def test_unreliable_clock_uses_first_arrival_and_retry_cannot_revert(self):
        future_clock = Record("homework", "a", 99999, "phone", 1, "wrong clock", False)
        next_edit = self.record("a", 10, device="tablet", value="next arrival")
        self.store.append([future_clock, next_edit])
        self.assertEqual(next_edit, self.store.head("homework", "a"))
        self.store.append([future_clock])
        self.assertEqual(next_edit, self.store.head("homework", "a"))
        self.assertEqual([future_clock, next_edit], [r for _, r in self.store.changes(0)])
        # 从中间游标开始拉取时，即使新到达的旧版本落败，也能取回服务器胜出版本。
        stale = self.record("a", 1, device="tablet", value="stale")
        self.store.append([stale])
        self.assertEqual([(3, next_edit)], self.store.changes_with_heads(2))


if __name__ == "__main__":
    unittest.main()
