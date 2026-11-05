"""Taskra 同步存储内核。此模块不提供网络服务或鉴权。"""

from __future__ import annotations

import json
import re
import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any


class InitialConflict(Exception):
    """首次加入的本地记录与服务器已有记录同名，禁止静默覆盖。"""


@dataclass(frozen=True)
class Record:
    kind: str
    key: str
    modified_at: int
    device_id: str
    sequence: int
    value: Any  # None 是删除墓碑
    clock_reliable: bool = True

    @property
    def version(self) -> tuple[int, str, int]:
        return self.modified_at, self.device_id, self.sequence


class Store:
    """持久化全部版本，重复提交幂等；所有调用由各自的连接和事务隔离。"""

    def __init__(self, path: str | Path):
        self.path = str(path)
        with self._connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS events (
                    cursor INTEGER PRIMARY KEY AUTOINCREMENT,
                    kind TEXT NOT NULL, record_key TEXT NOT NULL,
                    modified_at INTEGER NOT NULL, device_id TEXT NOT NULL,
                    sequence INTEGER NOT NULL, value_json TEXT NOT NULL,
                    clock_reliable INTEGER NOT NULL CHECK(clock_reliable IN (0, 1)),
                    UNIQUE(kind, record_key, modified_at, device_id, sequence)
                );
                CREATE TABLE IF NOT EXISTS heads (
                    kind TEXT NOT NULL, record_key TEXT NOT NULL,
                    event_cursor INTEGER NOT NULL REFERENCES events(cursor),
                    PRIMARY KEY(kind, record_key)
                );
            """)

    @contextmanager
    def _connect(self):
        db = sqlite3.connect(self.path, timeout=30)
        try:
            db.execute("PRAGMA foreign_keys=ON")
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def _encoded(record: Record) -> str:
        if not all(isinstance(s, str) and 0 < len(s) <= 256 for s in (record.kind, record.key, record.device_id)):
            raise ValueError("invalid record identifier")
        # 与 Android 的字符串排序保持一致，避免不同运行时对设备 ID 的排序产生分歧。
        if not re.fullmatch(r"[A-Za-z0-9_-]+", record.device_id):
            raise ValueError("invalid device identifier")
        if type(record.modified_at) is not int or not 0 <= record.modified_at < 2**63:
            raise ValueError("invalid modification time")
        if type(record.sequence) is not int or not 0 <= record.sequence < 2**63:
            raise ValueError("invalid sequence")
        if type(record.clock_reliable) is not bool:
            raise ValueError("invalid clock status")
        encoded = json.dumps(record.value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
        if len(encoded.encode("utf-8")) > 2 * 1024 * 1024:
            raise ValueError("record too large")
        return encoded

    def append(self, records: list[Record], if_absent: set[int] | None = None) -> int:
        if len(records) > 500:
            raise ValueError("too many records")
        encoded = [(r, self._encoded(r)) for r in records]
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            for index, (record, value_json) in enumerate(encoded):
                args = (record.kind, record.key, record.modified_at, record.device_id, record.sequence)
                existing = db.execute(
                    "SELECT cursor, value_json, clock_reliable FROM events WHERE kind=? AND record_key=? AND modified_at=? AND device_id=? AND sequence=?",
                    args,
                ).fetchone()
                if existing:
                    if existing[1] != value_json or bool(existing[2]) != record.clock_reliable:
                        raise ValueError("same version has different contents")
                    continue
                if index in (if_absent or set()) and db.execute(
                    "SELECT 1 FROM heads WHERE kind=? AND record_key=?", (record.kind, record.key),
                ).fetchone():
                    raise InitialConflict("existing server record")
                cursor = db.execute(
                    "INSERT INTO events(kind, record_key, modified_at, device_id, sequence, value_json, clock_reliable) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (*args, value_json, int(record.clock_reliable)),
                ).lastrowid
                head = db.execute("""
                    SELECT e.modified_at, e.device_id, e.sequence, e.clock_reliable FROM heads h
                    JOIN events e ON e.cursor=h.event_cursor WHERE h.kind=? AND h.record_key=?
                """, (record.kind, record.key)).fetchone()
                # 任一版本的时钟不可信时，按服务器第一次收到该版本的顺序判定。
                if head is None or not record.clock_reliable or not head[3] or record.version > tuple(head[:3]):
                    db.execute("""
                        INSERT INTO heads(kind, record_key, event_cursor) VALUES (?, ?, ?)
                        ON CONFLICT(kind, record_key) DO UPDATE SET event_cursor=excluded.event_cursor
                    """, (record.kind, record.key, cursor))
            return db.execute("SELECT COALESCE(MAX(cursor), 0) FROM events").fetchone()[0]

    def latest_cursor(self) -> int:
        with self._connect() as db:
            return db.execute("SELECT COALESCE(MAX(cursor), 0) FROM events").fetchone()[0]

    def changes(self, after: int, limit: int = 500) -> list[tuple[int, Record]]:
        if type(after) is not int or after < 0 or not 1 <= limit <= 500:
            raise ValueError("invalid cursor or limit")
        with self._connect() as db:
            rows = db.execute("""
                SELECT cursor, kind, record_key, modified_at, device_id, sequence, value_json, clock_reliable
                FROM events WHERE cursor>? ORDER BY cursor LIMIT ?
            """, (after, limit)).fetchall()
        return [(row[0], Record(*row[1:6], json.loads(row[6]), bool(row[7]))) for row in rows]

    def changes_with_heads(self, after: int, limit: int = 500) -> list[tuple[int, Record]]:
        """返回每次事件对应的当前胜出记录，全部在同一个 SQLite 读取快照中。"""
        if type(after) is not int or after < 0 or not 1 <= limit <= 500:
            raise ValueError("invalid cursor or limit")
        result = []
        total = 0
        with self._connect() as db:
            rows = db.execute("""
                SELECT e.cursor, winner.kind, winner.record_key, winner.modified_at,
                       winner.device_id, winner.sequence, winner.value_json, winner.clock_reliable
                FROM events e
                JOIN heads h ON h.kind=e.kind AND h.record_key=e.record_key
                JOIN events winner ON winner.cursor=h.event_cursor
                WHERE e.cursor>? ORDER BY e.cursor LIMIT ?
            """, (after, limit))
            for row in rows:
                # 限制单页内存和响应体大小，下一页从最后返回的游标继续。
                size = len(row[6].encode("utf-8"))
                if result and total + size > 3 * 1024 * 1024:
                    break
                total += size
                result.append((row[0], Record(*row[1:6], json.loads(row[6]), bool(row[7]))))
        return result

    def head(self, kind: str, key: str) -> Record | None:
        with self._connect() as db:
            row = db.execute("""
                SELECT e.kind, e.record_key, e.modified_at, e.device_id, e.sequence, e.value_json, e.clock_reliable
                FROM heads h JOIN events e ON e.cursor=h.event_cursor
                WHERE h.kind=? AND h.record_key=?
            """, (kind, key)).fetchone()
        return Record(*row[:5], json.loads(row[5]), bool(row[6])) if row else None
