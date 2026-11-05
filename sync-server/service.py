"""仅监听本机的同步接口；上线前须完成真实设备和跨网络验证。"""

import dataclasses
import json
import ipaddress
import re
import socket
import shutil
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from blobs import BlobStore, StorageLimitError
from pairing import Pairing
from store import InitialConflict, Record, Store


def lan_address():
    """只公布当前默认路由的 RFC1918 地址；查询路由不发送数据。"""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("1.1.1.1", 53))
            address = ipaddress.IPv4Address(probe.getsockname()[0])
        networks = (ipaddress.IPv4Network("10.0.0.0/8"), ipaddress.IPv4Network("172.16.0.0/12"),
                    ipaddress.IPv4Network("192.168.0.0/16"))
        return str(address) if any(address in network for network in networks) else None
    except OSError:
        return None


class Attempts:
    def __init__(self):
        self._lock = threading.Lock()
        self._times = deque()

    def accept(self):
        with self._lock:
            now = time.monotonic()
            while self._times and self._times[0] <= now - 300:
                self._times.popleft()
            if len(self._times) >= 12:
                return False
            self._times.append(now)
            return True


class ExactBody:
    def __init__(self, stream, length):
        self.stream = stream
        self.remaining = length

    def read(self, size=-1):
        if self.remaining == 0:
            return b""
        count = self.remaining if size < 0 else min(size, self.remaining)
        data = self.stream.read(count)
        if not data:
            raise ValueError("incomplete request")
        self.remaining -= len(data)
        return data


class SyncServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 32

    def __init__(self, port, db_path, blob_dir, mount_path=""):
        if mount_path and (not mount_path.startswith("/") or mount_path.endswith("/") or ".." in mount_path):
            raise ValueError("invalid mount path")
        self.mount_path = mount_path
        self.db_dir = Path(db_path).resolve().parent
        self.store = Store(db_path)
        self.pairing = Pairing(db_path)
        self.blobs = BlobStore(blob_dir)
        self.attempts = Attempts()
        self._slots = threading.BoundedSemaphore(16)
        super().__init__(("127.0.0.1", port), Handler)

    def process_request(self, request, client_address):
        if not self._slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._slots.release()


class Handler(BaseHTTPRequestHandler):
    server_version = "TaskraSync"
    sys_version = ""

    def setup(self):
        super().setup()
        self.connection.settimeout(20)

    def log_message(self, format, *args):
        # 请求可能含配对码；不把 URL、令牌或正文写进日志。
        pass

    def respond(self, status, body):
        data = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def body(self, limit):
        values = self.headers.get_all("Content-Length", [])
        if len(values) != 1 or not values[0].isascii() or not values[0].isdigit():
            raise ValueError("invalid content length")
        length = int(values[0])
        if length > limit or self.headers.get("Transfer-Encoding") is not None:
            raise ValueError("invalid content length")
        return ExactBody(self.rfile, length)

    def json_body(self):
        raw = self.body(5 * 1024 * 1024).read()
        value = json.loads(raw.decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError("expected object")
        return value

    def device(self):
        values = self.headers.get_all("Authorization", [])
        if len(values) != 1 or not values[0].startswith("Bearer "):
            raise PermissionError("authentication required")
        device_id = self.server.pairing.device_id(values[0][7:])
        if device_id is None:
            raise PermissionError("authentication required")
        return device_id

    def handle_request(self):
        path = urlsplit(self.path)
        # Funnel 的反向代理版本可能保留或移除挂载前缀；只识别明确配置的前缀。
        if self.server.mount_path and path.path.startswith(self.server.mount_path + "/"):
            path = path._replace(path=path.path[len(self.server.mount_path):])
        if self.command == "POST" and path.path == "/v1/pair/redeem":
            if not self.server.attempts.accept():
                return self.respond(429, {"error": "pairing temporarily limited"})
            body = self.json_body()
            device = self.server.pairing.redeem_code(body.get("code"), body.get("request_id"))
            return self.respond(200, {"device_id": device.id, "token": device.token})

        device_id = self.device()
        if self.command == "GET" and path.path == "/v1/time":
            hint = lan_address() if (self.server.db_dir / "lan-enabled").is_file() else None
            return self.respond(200, {"unix_ms": int(time.time() * 1000), "lan_address": hint})
        if self.command == "GET" and path.path == "/v1/state":
            return self.respond(200, {"cursor": self.server.store.latest_cursor()})
        if self.command == "POST" and path.path == "/v1/pair":
            if not self.server.attempts.accept():
                return self.respond(429, {"error": "pairing temporarily limited"})
            self.json_body()
            token = self.headers["Authorization"][7:]
            return self.respond(200, {"code": self.server.pairing.issue_code(token), "expires_in": 300})

        if self.command == "GET" and path.path == "/v1/changes":
            query = parse_qs(path.query, strict_parsing=True)
            if set(query) != {"after"} or len(query["after"]) != 1:
                raise ValueError("invalid cursor")
            after = int(query["after"][0])
            changes = self.server.store.changes_with_heads(after)
            last = changes[-1][0] if changes else after
            return self.respond(200, {"changes": [{"cursor": cursor, "head": dataclasses.asdict(record)} for cursor, record in changes],
                                      "has_more": self.server.store.latest_cursor() > last})

        if self.command == "POST" and path.path == "/v1/changes":
            if min(shutil.disk_usage(self.server.blobs.directory).free, shutil.disk_usage(self.server.db_dir).free) < self.server.blobs.reserve_bytes:
                raise StorageLimitError("insufficient storage")
            body = self.json_body()
            rows = body.get("records")
            if not isinstance(rows, list) or len(rows) > 500:
                raise ValueError("invalid records")
            records = []
            if_absent = set()
            for row in rows:
                if not isinstance(row, dict) or set(row) != {"kind", "key", "modified_at", "sequence", "value", "clock_reliable", "attachments", "if_absent"}:
                    raise ValueError("invalid record")
                if type(row["if_absent"]) is not bool:
                    raise ValueError("invalid initial flag")
                if row["if_absent"]:
                    if_absent.add(len(records))
                attachments = row["attachments"]
                if not isinstance(attachments, list) or len(attachments) > 1000:
                    raise ValueError("invalid attachment list")
                if row["value"] is None and attachments:
                    raise ValueError("deleted record cannot have attachments")
                for digest in attachments:
                    self.server.blobs.get(digest)
                value = None if row["value"] is None else {"payload": row["value"], "attachments": attachments}
                records.append(Record(row["kind"], row["key"], row["modified_at"], device_id, row["sequence"], value, row["clock_reliable"]))
            return self.respond(200, {"cursor": self.server.store.append(records, if_absent)})

        prefix = "/v1/blobs/"
        if path.path.startswith(prefix):
            segments = path.path[len(prefix):].split("/")
            digest = segments[0]
            if len(segments) == 2 and segments[1] == "parts" and self.command == "GET":
                return self.respond(200, {"parts": self.server.blobs.list_parts(digest)})
            if len(segments) == 3 and segments[1] == "parts" and self.command == "PUT":
                if not segments[2].isascii() or not segments[2].isdigit() or len(segments[2]) > 6:
                    raise ValueError("invalid part index")
                self.server.blobs.put_part(digest, int(segments[2]), self.body(self.server.blobs.chunk_bytes))
                return self.respond(200, {"ok": True})
            if len(segments) == 2 and segments[1] == "complete" and self.command == "POST":
                body = self.json_body()
                if set(body) != {"size"}:
                    raise ValueError("invalid completion request")
                self.server.blobs.complete_parts(digest, body["size"])
                return self.respond(200, {"sha256": digest})
            if len(segments) != 1:
                return self.respond(404, {"error": "not found"})
            if self.command == "HEAD":
                try:
                    self.server.blobs.get(digest)
                except ValueError:
                    return self.respond(404, {"error": "attachment not found"})
                self.send_response(200)
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if self.command == "PUT":
                self.server.blobs.put(digest, self.body(self.server.blobs.max_bytes))
                return self.respond(200, {"sha256": digest})
            if self.command == "GET":
                file = self.server.blobs.get(digest)
                size = file.stat().st_size
                ranges = self.headers.get_all("Range", [])
                start = 0
                if ranges:
                    if len(ranges) != 1:
                        raise ValueError("invalid range")
                    match = re.fullmatch(r"bytes=([0-9]{1,12})-", ranges[0])
                    if not match:
                        raise ValueError("invalid range")
                    start = int(match.group(1))
                    if start >= size:
                        self.send_response(416)
                        self.send_header("Content-Range", f"bytes */{size}")
                        self.send_header("Content-Length", "0")
                        self.send_header("Cache-Control", "no-store")
                        self.end_headers()
                        return
                self.send_response(206 if ranges else 200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Accept-Ranges", "bytes")
                if ranges:
                    self.send_header("Content-Range", f"bytes {start}-{size - 1}/{size}")
                self.send_header("Content-Length", str(size - start))
                self.end_headers()
                with file.open("rb") as source:
                    source.seek(start)
                    while chunk := source.read(1024 * 1024):
                        self.wfile.write(chunk)
                return
        self.respond(404, {"error": "not found"})

    def run_handler(self):
        try:
            self.handle_request()
        except PermissionError:
            self.respond(401, {"error": "authentication or pairing failed"})
        except InitialConflict:
            self.respond(409, {"error": "existing record requires review"})
        except StorageLimitError:
            self.respond(507, {"error": "insufficient server storage"})
        except (ValueError, TypeError, KeyError, json.JSONDecodeError, UnicodeError):
            self.respond(400, {"error": "invalid request"})
        except (OSError, TimeoutError):
            self.close_connection = True

    do_GET = run_handler
    do_HEAD = run_handler
    do_POST = run_handler
    do_PUT = run_handler
