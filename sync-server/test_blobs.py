import hashlib
import io
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from blobs import BlobStore, StorageLimitError


class BlobStoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = BlobStore(Path(self.tmp.name), max_bytes=64)
        self.content = b"an attachment"
        self.digest = hashlib.sha256(self.content).hexdigest()

    def tearDown(self):
        self.tmp.cleanup()

    def test_verified_round_trip_and_corruption_detection(self):
        path = self.store.put(self.digest, io.BytesIO(self.content))
        self.assertEqual(self.content, self.store.get(self.digest).read_bytes())
        path.write_bytes(b"corrupt")
        with self.assertRaises(ValueError):
            self.store.get(self.digest)
        self.store.put(self.digest, io.BytesIO(self.content))
        self.assertEqual(self.content, self.store.get(self.digest).read_bytes())

    def test_wrong_hash_oversize_and_traversal_do_not_publish(self):
        with self.assertRaises(ValueError):
            self.store.put(self.digest, io.BytesIO(b"wrong"))
        with self.assertRaises(ValueError):
            self.store.put(self.digest, io.BytesIO(b"x" * 65))
        with self.assertRaises(ValueError):
            self.store.get("../other")
        self.assertEqual([], list(Path(self.tmp.name).iterdir()))

    def test_concurrent_uploads_are_complete(self):
        with ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(lambda _: self.store.put(self.digest, io.BytesIO(self.content)), range(8)))
        self.assertEqual(self.content, self.store.get(self.digest).read_bytes())

    def test_storage_reserve_does_not_publish_partial_upload(self):
        full = BlobStore(Path(self.tmp.name), reserve_bytes=2**63)
        with self.assertRaises(StorageLimitError):
            full.put(self.digest, io.BytesIO(self.content))
        self.assertEqual([], list(Path(self.tmp.name).iterdir()))

    def test_slow_network_part_does_not_block_another_upload(self):
        store = BlobStore(Path(self.tmp.name), max_bytes=200_000)
        started = threading.Event()
        release = threading.Event()

        class SlowStream(io.BytesIO):
            def read(self, size=-1):
                started.set()
                if not release.wait(5):
                    raise TimeoutError("slow stream was not released")
                return super().read(size)

        with ThreadPoolExecutor(max_workers=2) as pool:
            slow = pool.submit(store.put_part, self.digest, 0, SlowStream(b"one"))
            self.assertTrue(started.wait(2))
            fast = pool.submit(store.put_part, self.digest, 1, io.BytesIO(b"two"))
            try:
                fast.result(timeout=2)
            finally:
                release.set()
            slow.result(timeout=2)
        self.assertEqual([0, 1], [part["index"] for part in store.list_parts(self.digest)])


if __name__ == "__main__":
    unittest.main()
