import hashlib
import http.client
import json
import shutil
import ssl
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path

from lan_proxy import LanServer
from service import SyncServer


@unittest.skipUnless(shutil.which("openssl"), "openssl is required for isolated TLS testing")
class LanProxyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        cert, key = root / "test.crt", root / "test.key"
        config = root / "openssl.cnf"
        config.write_text("[req]\ndistinguished_name = dn\n[dn]\n", encoding="ascii")
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-config", str(config), "-subj", "/CN=taskra-lan-test.local", "-addext", "subjectAltName=DNS:taskra-lan-test.local",
                        "-keyout", str(key), "-out", str(cert)], check=True, capture_output=True)
        self.backend = SyncServer(0, root / "sync.sqlite", root / "blobs", "/taskra-sync")
        self.lan = LanServer(("127.0.0.1", 0), str(cert), str(key), self.backend.server_port, "taskra-lan-test.local")
        self.threads = [threading.Thread(target=server.serve_forever, daemon=True)
                        for server in (self.backend, self.lan)]
        for thread in self.threads:
            thread.start()
        self.context = ssl.create_default_context(cafile=str(cert))

    def tearDown(self):
        for server in (self.lan, self.backend):
            server.shutdown()
            server.server_close()
        for thread in self.threads:
            thread.join()
        self.tmp.cleanup()

    def request(self, method, path, body=None, headers=None):
        conn = http.client.HTTPSConnection("127.0.0.1", self.lan.server_port, context=self.context, timeout=4)
        conn.set_tunnel("taskra-lan-test.local", self.lan.server_port)
        try:
            conn.request(method, path, body=body, headers=headers or {})
            result = conn.getresponse()
            return result.status, result.read()
        finally:
            conn.close()

    def test_scoped_tls_proxy_keeps_auth_and_partial_attachments(self):
        raw = http.client.HTTPConnection("127.0.0.1", self.lan.server_port, timeout=4)
        try:
            raw.request("CONNECT", "example.com:443")
            self.assertEqual(403, raw.getresponse().status)
        finally:
            raw.close()
        self.assertEqual(404, self.request("GET", "/unrelated")[0])
        self.assertEqual(401, self.request("GET", "/taskra-sync/v1/time")[0])
        body = json.dumps({"code": self.backend.pairing.bootstrap_code()}).encode()
        status, result = self.request("POST", "/taskra-sync/v1/pair/redeem", body, {"Content-Type": "application/json"})
        self.assertEqual(200, status)
        token = json.loads(result)["token"]
        auth = {"Authorization": "Bearer " + token}
        data = b"synthetic local attachment" * 4000
        path = "/taskra-sync/v1/blobs/" + hashlib.sha256(data).hexdigest()
        self.assertEqual(200, self.request("PUT", path, data, auth)[0])
        self.assertEqual((206, data[1234:]), self.request("GET", path, headers={**auth, "Range": "bytes=1234-"}))
        self.assertEqual(401, self.request("GET", path, headers={"Range": "bytes=1-"})[0])

    def test_trusted_certificate_with_wrong_hostname_is_rejected(self):
        self.lan.tls_host = "wrong-name.local"
        conn = http.client.HTTPSConnection("127.0.0.1", self.lan.server_port, context=self.context, timeout=4)
        conn.set_tunnel("wrong-name.local", self.lan.server_port)
        try:
            with self.assertRaises(ssl.SSLCertVerificationError):
                conn.request("GET", "/taskra-sync/v1/time")
        finally:
            conn.close()


if __name__ == "__main__":
    unittest.main()
