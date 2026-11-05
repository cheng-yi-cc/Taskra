"""把已验证的附件副本写入已停止应用的私有同步缓存，不改业务附件或数据库。"""

import argparse
import base64
import hashlib
import json
import re
import sqlite3
import subprocess
import tarfile
import uuid
from pathlib import Path


def adb(serial, *args, data=None, check=True):
    return subprocess.run(["adb", "-s", serial, *args], input=data, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, check=check)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--backup", required=True, type=Path)
    parser.add_argument("--archive", required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9._-]+", args.serial):
        raise ValueError("请使用已确认的 mDNS 设备标识")
    package = "com.taskra.debug"
    running = adb(args.serial, "shell", "pidof", package, check=False)
    if running.stdout.strip():
        raise RuntimeError("应用仍在运行，不能写入同步缓存")
    summary = json.loads((args.backup / "summary.json").read_text(encoding="utf8"))
    if summary["package"] != package or summary["db_version"] != 6:
        raise RuntimeError("平板备份未通过检查")
    db = sqlite3.connect(args.backup / "verified/databases/taskra.db")
    try:
        needed = set()
        for (raw,) in db.execute("SELECT head_json FROM sync_inbox"):
            needed.update((json.loads(raw).get("value") or {}).get("attachments", []))
    finally:
        db.close()
    if not needed or any(not re.fullmatch(r"[a-f0-9]{64}", x) for x in needed):
        raise RuntimeError("待接收附件清单无效")
    with tarfile.open(args.archive, "r:") as archive:
        members = {m.name: m for m in archive if m.isfile()}
        if not needed <= members.keys():
            raise RuntimeError("已验证备份缺少所需附件")
        contents = {}
        for name in needed:
            value = archive.extractfile(members[name]).read()
            if hashlib.sha256(value).hexdigest() != name:
                raise RuntimeError("源附件校验失败")
            contents[name] = value
    folder = "files/sync-incoming/blobs"
    adb(args.serial, "shell", "run-as", package, "mkdir", "-p", folder)
    for digest, value in contents.items():
        target = f"{folder}/{digest}"
        existing = adb(args.serial, "shell", "run-as", package, "sha256sum", target, check=False)
        if existing.returncode == 0:
            if existing.stdout.decode().split()[0] != digest:
                raise RuntimeError("已有缓存附件校验失败，未覆盖")
            continue
        temp = f"files/sync-incoming/seed-{uuid.uuid4().hex}"
        # Windows 的 adb 标准输入可能把二进制 0x1a 当作 EOF；用 ASCII 编码传输并在设备端解码。
        command = f"run-as {package} sh -c 'base64 -d > {temp}'"
        result = subprocess.run(["adb", "-s", args.serial, "shell", "-T", command],
                                input=base64.b64encode(value), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        if result.returncode:
            raise RuntimeError("无法写入平板暂存附件")
        checksum = adb(args.serial, "shell", "run-as", package, "sha256sum", temp).stdout.decode().split()[0]
        if checksum != digest:
            raise RuntimeError("平板暂存附件校验失败")
        # 应用已停止；同文件系统内移动，不覆盖已有目标。
        adb(args.serial, "shell", "run-as", package, "mv", "-n", temp, target)
        check = adb(args.serial, "shell", "run-as", package, "sha256sum", target).stdout.decode().split()[0]
        if check != digest:
            raise RuntimeError("平板缓存附件校验失败")
    print(f"已校验 {len(contents)} 个附件并写入私有同步缓存；未修改业务记录。")


if __name__ == "__main__":
    main()
