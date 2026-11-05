"""以两份一致的应用私有目录快照保护数据；仅接受显式指定的 adb 设备。"""

import argparse
import base64
import hashlib
import json
import re
import sqlite3
import subprocess
import tarfile
from pathlib import Path


def digest_stream(source):
    digest = hashlib.sha256()
    while chunk := source.read(1024 * 1024):
        digest.update(chunk)
    return digest.hexdigest()


def capture(serial, package, path):
    with path.open("xb") as out:
        result = subprocess.run(
            ["adb", "-s", serial, "exec-out", "run-as", package, "tar", "-cf", "-", "databases", "files", "shared_prefs"],
            stdout=out, stderr=subprocess.PIPE,
        )
    if result.returncode:
        raise RuntimeError("无法完整读取应用数据；未允许更新")
    files = {}
    with tarfile.open(path, "r:") as archive:
        for member in archive:
            name = member.name.removeprefix("./")
            if member.isdir():
                continue
            if not member.isfile() or name.startswith("/") or ".." in Path(name).parts or name in files:
                raise RuntimeError("快照包含无法安全校验的条目；未允许更新")
            if not name.startswith(("databases/", "files/", "shared_prefs/")):
                raise RuntimeError("快照包含未知路径；未允许更新")
            with archive.extractfile(member) as source:
                files[name] = digest_stream(source)
    if "databases/taskra.db" not in files:
        raise RuntimeError("快照缺少数据库；未允许更新")
    return files


def summarize_database(path):
    db = sqlite3.connect(path)
    try:
        if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok" or db.execute("PRAGMA foreign_key_check").fetchall():
            raise RuntimeError("数据库完整性检查失败；未允许更新")
        version = db.execute("PRAGMA user_version").fetchone()[0]
        tables = {}
        names = db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").fetchall()
        for (name,) in names:
            quoted = '"' + name.replace('"', '""') + '"'
            rows = db.execute("SELECT * FROM " + quoted).fetchall()
            def normalize(value):
                return {"bytes": base64.b64encode(value).decode("ascii")} if isinstance(value, bytes) else value
            hashes = sorted(hashlib.sha256(json.dumps([normalize(v) for v in row], ensure_ascii=False, separators=(",", ":")).encode()).hexdigest() for row in rows)
            tables[name] = {"count": len(rows), "digest": hashlib.sha256("".join(hashes).encode()).hexdigest()}
        return version, tables
    finally:
        db.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="com.taskra.debug")
    parser.add_argument("--dest", required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9._:-]+", args.serial) or not re.fullmatch(r"[A-Za-z0-9._]+", args.package):
        raise ValueError("设备标识或应用包名无效")
    args.dest.mkdir(parents=True, exist_ok=False)
    first = capture(args.serial, args.package, args.dest / "snapshot-a.tar")
    second = capture(args.serial, args.package, args.dest / "snapshot-b.tar")
    # SQLite 共享内存是可重建的临时索引；数据库、WAL 和其他文件必须逐个一致。
    stable_first = {k: v for k, v in first.items() if k != "databases/taskra.db-shm"}
    stable_second = {k: v for k, v in second.items() if k != "databases/taskra.db-shm"}
    if stable_first != stable_second:
        raise RuntimeError("两次快照不同，可能仍有写入；保留快照并停止更新")
    extracted = args.dest / "verified"
    extracted.mkdir()
    with tarfile.open(args.dest / "snapshot-b.tar", "r:") as archive:
        archive.extractall(extracted, filter="data")
    version, tables = summarize_database(extracted / "databases/taskra.db")
    files = {name: digest for name, digest in stable_second.items()
             if name.startswith(("files/images/", "files/audio/", "files/datastore/", "files/sync-outbox/", "files/sync-incoming/", "shared_prefs/"))}
    summary = {"package": args.package, "db_version": version, "tables": tables, "protected_files": files}
    (args.dest / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"快照校验通过：数据库版本 {version}，业务表 {len(tables)}，受保护文件 {len(files)}。")
    print("仍须核对安装包身份、签名、数据库迁移，并在安装及首次启动后再次核验。")


if __name__ == "__main__":
    main()
