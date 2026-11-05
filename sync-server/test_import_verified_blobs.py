import hashlib
import io
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path


class ImportVerifiedBlobsTest(unittest.TestCase):
    def test_all_items_are_verified_before_any_are_imported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / "input.tar"
            valid = b"synthetic image"
            digest = hashlib.sha256(valid).hexdigest()
            with tarfile.open(archive, "w") as out:
                for name, data in ((digest, valid), ("0" * 64, b"corrupt")):
                    info = tarfile.TarInfo(name)
                    info.size = len(data)
                    out.addfile(info, io.BytesIO(data))
            script = Path(__file__).with_name("import_verified_blobs.py")
            args = [sys.executable, str(script), str(archive), str(root / "blobs"), "--expected-count", "2"]
            self.assertNotEqual(0, subprocess.run(args, capture_output=True).returncode)
            self.assertFalse((root / "blobs" / digest).exists())
            with tarfile.open(archive, "w") as out:
                info = tarfile.TarInfo(digest)
                info.size = len(valid)
                out.addfile(info, io.BytesIO(valid))
            args[-1] = "1"
            self.assertEqual(0, subprocess.run(args, capture_output=True).returncode)
            self.assertEqual(valid, (root / "blobs" / digest).read_bytes())


if __name__ == "__main__":
    unittest.main()
