"""设备配对内核。仅管理本地凭据，不启动或暴露网络服务。"""

import hashlib
import hmac
import base64
import re
import secrets
import sqlite3
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Device:
    id: str
    token: str


class Pairing:
    def __init__(self, path: str | Path, clock=time.time):
        self.path = str(path)
        self.clock = clock
        with self._connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS devices (
                    token_hash TEXT PRIMARY KEY, device_id TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS pair_codes (
                    code_hash TEXT PRIMARY KEY, expires_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS pairing_meta (
                    id INTEGER PRIMARY KEY CHECK(id=1), secret BLOB NOT NULL
                );
                INSERT OR IGNORE INTO pairing_meta VALUES (1, randomblob(32));
                CREATE TABLE IF NOT EXISTS pair_redemptions (
                    request_id TEXT PRIMARY KEY, code_hash TEXT NOT NULL, device_id TEXT NOT NULL,
                    expires_at INTEGER NOT NULL
                );
            """)

    @contextmanager
    def _connect(self):
        db = sqlite3.connect(self.path, timeout=30)
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def _hash(secret: str) -> str:
        if not isinstance(secret, str) or len(secret) > 256:
            return ""
        return hashlib.sha256(secret.encode("utf-8")).hexdigest()

    def _new_code(self, db, now):
        db.execute("DELETE FROM pair_codes WHERE expires_at <= ?", (now,))
        for _ in range(8):
            code = f"{secrets.randbelow(100_000_000):08d}"
            result = db.execute("INSERT OR IGNORE INTO pair_codes VALUES (?, ?)", (self._hash(code), now + 300))
            if result.rowcount == 1:
                return code
        raise RuntimeError("unable to generate a unique pairing code")

    def bootstrap_code(self) -> str:
        """仅供服务器本机初始化；一旦有设备便禁止再次初始化。"""
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            if db.execute("SELECT 1 FROM devices LIMIT 1").fetchone():
                raise PermissionError("already initialized")
            db.execute("DELETE FROM pair_codes")
            return self._new_code(db, int(self.clock()))

    def authenticated(self, token: str) -> bool:
        return self.device_id(token) is not None

    def device_id(self, token: str) -> str | None:
        with self._connect() as db:
            row = db.execute("SELECT device_id FROM devices WHERE token_hash=?", (self._hash(token),)).fetchone()
            return row[0] if row else None

    def issue_code(self, token: str) -> str:
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            if not db.execute("SELECT 1 FROM devices WHERE token_hash=?", (self._hash(token),)).fetchone():
                raise PermissionError("invalid device")
            return self._new_code(db, int(self.clock()))

    def redeem_code(self, code: str, request_id: str | None = None) -> Device:
        now = int(self.clock())
        if request_id is not None and (not isinstance(request_id, str) or
                                       not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", request_id)):
            raise ValueError("invalid pairing request")
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("DELETE FROM pair_redemptions WHERE expires_at<=?", (now,))
            if request_id is not None:
                previous = db.execute("SELECT code_hash,device_id FROM pair_redemptions WHERE request_id=?", (request_id,)).fetchone()
                if previous:
                    if not hmac.compare_digest(previous[0], self._hash(code)):
                        raise PermissionError("invalid pairing request")
                    return Device(previous[1], self._retry_token(db, code, request_id))
            expires = db.execute("SELECT expires_at FROM pair_codes WHERE code_hash=? AND expires_at>?", (self._hash(code), now)).fetchone()
            result = db.execute("DELETE FROM pair_codes WHERE code_hash=? AND expires_at>?", (self._hash(code), now))
            if result.rowcount != 1:
                raise PermissionError("invalid or expired pairing code")
            token = self._retry_token(db, code, request_id) if request_id is not None else secrets.token_urlsafe(32)
            device_id = uuid.uuid4().hex
            db.execute("INSERT INTO devices VALUES (?, ?, ?)", (self._hash(token), device_id, now))
            if request_id is not None:
                db.execute("INSERT INTO pair_redemptions VALUES (?, ?, ?, ?)", (request_id, self._hash(code), device_id, expires[0]))
            return Device(device_id, token)

    @staticmethod
    def _retry_token(db, code, request_id):
        secret = db.execute("SELECT secret FROM pairing_meta WHERE id=1").fetchone()[0]
        digest = hmac.new(secret, (request_id + ":" + code).encode("utf-8"), hashlib.sha256).digest()
        return base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
