import hashlib
import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from service import SyncServer


class ServiceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.server = SyncServer(0, root / "sync.sqlite", root / "blobs")
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.tmp.cleanup()

    def request(self, method, path, body=None, token=None, extra_headers=None):
        headers = dict(extra_headers or {})
        if token:
            headers["Authorization"] = "Bearer " + token
        if isinstance(body, dict):
            body = json.dumps(body).encode()
        req = Request(self.base + path, data=body, headers=headers, method=method)
        try:
            with urlopen(req, timeout=3) as response:
                return response.status, response.read()
        except HTTPError as e:
            return e.code, e.read()

    def pair(self, code):
        status, body = self.request("POST", "/v1/pair/redeem", {"code": code})
        self.assertEqual(200, status)
        return json.loads(body)

    def test_mount_prefix_keeps_authentication(self):
        self.server.mount_path = "/taskra-sync"
        device = self.pair(self.server.pairing.bootstrap_code())
        self.assertEqual(401, self.request("GET", "/taskra-sync/v1/time")[0])
        self.assertEqual(200, self.request("GET", "/taskra-sync/v1/time", token=device["token"])[0])
        self.assertEqual(404, self.request("GET", "/taskra-sync-other/v1/time", token=device["token"])[0])

    def test_pairing_requires_authorization_and_code_is_one_time(self):
        self.assertEqual(401, self.request("POST", "/v1/pair", {})[0])
        code = self.server.pairing.bootstrap_code()
        phone = self.pair(code)
        status, response = self.request("GET", "/v1/time", token=phone["token"])
        self.assertEqual(200, status)
        self.assertGreater(json.loads(response)["unix_ms"], 0)
        self.assertEqual(401, self.request("POST", "/v1/pair/redeem", {"code": code})[0])
        status, response = self.request("POST", "/v1/pair", {}, phone["token"])
        self.assertEqual(200, status)
        tablet = self.pair(json.loads(response)["code"])
        self.assertNotEqual(phone["device_id"], tablet["device_id"])

    def test_lan_hint_is_authenticated_and_opt_in(self):
        token = self.pair(self.server.pairing.bootstrap_code())["token"]
        with patch("service.lan_address", return_value="192.168.31.55"):
            status, body = self.request("GET", "/v1/time", token=token)
            self.assertIsNone(json.loads(body)["lan_address"])
            (self.server.db_dir / "lan-enabled").write_text("enabled")
            self.assertEqual(401, self.request("GET", "/v1/time")[0])
            status, body = self.request("GET", "/v1/time", token=token)
            self.assertEqual("192.168.31.55", json.loads(body)["lan_address"])

    def test_attachment_must_exist_before_record_and_unauthorized_cannot_read(self):
        device = self.pair(self.server.pairing.bootstrap_code())
        token = device["token"]
        data = b"image content"
        digest = hashlib.sha256(data).hexdigest()
        record = {"kind": "homework", "key": "a", "modified_at": 10, "sequence": 1,
                  "value": {"title": "A"}, "clock_reliable": True, "attachments": [digest], "if_absent": False}
        self.assertEqual(400, self.request("POST", "/v1/changes", {"records": [record]}, token)[0])
        self.assertEqual([], self.server.store.changes(0))
        self.assertEqual(200, self.request("PUT", "/v1/blobs/" + digest, data, token)[0])
        self.assertEqual(200, self.request("POST", "/v1/changes", {"records": [record]}, token)[0])
        self.assertEqual(401, self.request("GET", "/v1/blobs/" + digest, token="bad")[0])
        self.assertEqual((200, data), self.request("GET", "/v1/blobs/" + digest, token=token))
        status, response = self.request("GET", "/v1/changes?after=0", token=token)
        self.assertEqual(200, status)
        page = json.loads(response)
        self.assertFalse(page["has_more"])
        head = page["changes"][0]["head"]
        self.assertEqual(device["device_id"], head["device_id"])
        self.assertEqual([digest], head["value"]["attachments"])
        status, state = self.request("GET", "/v1/state", token=token)
        self.assertEqual(200, status)
        self.assertEqual(1, json.loads(state)["cursor"])
        tablet = self.pair(self.server.pairing.issue_code(token))
        collision = dict(record, sequence=2, value={"title": "older tablet data"}, if_absent=True)
        self.assertEqual(409, self.request("POST", "/v1/changes", {"records": [collision]}, tablet["token"])[0])
        self.assertEqual("A", self.server.store.head("homework", "a").value["payload"]["title"])

    def test_chunked_attachment_resumes_and_checks_complete_hash(self):
        device = self.pair(self.server.pairing.bootstrap_code())
        token = device["token"]
        data = (b"taskra resumable test" * 9000)[:150_000]
        digest = hashlib.sha256(data).hexdigest()
        path = "/v1/blobs/" + digest
        self.assertEqual(401, self.request("HEAD", path)[0])
        self.assertEqual(404, self.request("HEAD", path, token=token)[0])
        self.assertEqual(200, self.request("PUT", path + "/parts/0", data[:65536], token)[0])
        self.assertEqual(400, self.request("POST", path + "/complete", {"size": len(data)}, token)[0])
        status, body = self.request("GET", path + "/parts", token=token)
        self.assertEqual(200, status)
        part = json.loads(body)["parts"][0]
        self.assertEqual({"index": 0, "size": 65536, "sha256": hashlib.sha256(data[:65536]).hexdigest()}, part)
        self.assertEqual(200, self.request("PUT", path + "/parts/1", data[65536:131072], token)[0])
        self.assertEqual(200, self.request("PUT", path + "/parts/2", b"x" * len(data[131072:]), token)[0])
        self.assertEqual(400, self.request("POST", path + "/complete", {"size": len(data)}, token)[0])
        self.assertEqual(404, self.request("HEAD", path, token=token)[0])
        self.assertEqual(200, self.request("PUT", path + "/parts/2", data[131072:], token)[0])
        self.assertEqual(200, self.request("POST", path + "/complete", {"size": len(data)}, token)[0])
        self.assertEqual(200, self.request("HEAD", path, token=token)[0])
        self.assertEqual((200, data), self.request("GET", path, token=token))
        self.assertEqual([], json.loads(self.request("GET", path + "/parts", token=token)[1])["parts"])

    def test_authenticated_partial_download(self):
        token = self.pair(self.server.pairing.bootstrap_code())["token"]
        data = bytes(range(256)) * 700
        path = "/v1/blobs/" + hashlib.sha256(data).hexdigest()
        self.assertEqual(200, self.request("PUT", path, data, token)[0])
        self.assertEqual(401, self.request("GET", path, extra_headers={"Range": "bytes=100-"})[0])
        self.assertEqual((206, data[100:]), self.request("GET", path, token=token, extra_headers={"Range": "bytes=100-"}))
        self.assertEqual(416, self.request("GET", path, token=token, extra_headers={"Range": f"bytes={len(data)}-"})[0])
        self.assertEqual(400, self.request("GET", path, token=token, extra_headers={"Range": "bytes=1-2"})[0])


if __name__ == "__main__":
    unittest.main()
