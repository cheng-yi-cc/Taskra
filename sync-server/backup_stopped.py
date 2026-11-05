"""仅在同步服务停止后，备份并检查服务端数据库和附件目录。"""

import argparse
import hashlib
import sqlite3
import subprocess
import tarfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("data", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    if subprocess.run(["systemctl", "--user", "is-active", "--quiet", "taskra-sync.service"]).returncode == 0:
        raise RuntimeError("同步服务仍在运行，不能制作静态备份")
    args.destination.mkdir(mode=0o700, parents=False, exist_ok=False)
    source = sqlite3.connect(f"file:{(args.data / 'sync.sqlite').resolve()}?mode=ro", uri=True)
    target = sqlite3.connect(args.destination / "sync.sqlite")
    try:
        source.backup(target)
        if target.execute("PRAGMA integrity_check").fetchone()[0] != "ok" or target.execute("PRAGMA foreign_key_check").fetchall():
            raise RuntimeError("服务器备份数据库校验失败")
        events = target.execute("SELECT COUNT(*) FROM events").fetchone()[0]
        devices = target.execute("SELECT COUNT(*) FROM devices").fetchone()[0]
    finally:
        source.close()
        target.close()
    with tarfile.open(args.destination / "blobs.tar", "x") as archive:
        archive.add(args.data / "blobs", arcname="blobs", recursive=True)
    with tarfile.open(args.destination / "blobs.tar", "r:") as archive:
        for member in archive:
            if member.isfile():
                relative = Path(member.name).relative_to("blobs")
                source_file = args.data / "blobs" / relative
                original = hashlib.sha256()
                copied = hashlib.sha256()
                with source_file.open("rb") as stream:
                    while chunk := stream.read(1024 * 1024):
                        original.update(chunk)
                with archive.extractfile(member) as stream:
                    while chunk := stream.read(1024 * 1024):
                        copied.update(chunk)
                if original.digest() != copied.digest():
                    raise RuntimeError("服务器附件备份校验失败")
            elif not member.isdir():
                raise RuntimeError("服务器附件目录含特殊条目")
    print("服务器备份通过：设备", devices, "变更事件", events)


if __name__ == "__main__":
    main()
