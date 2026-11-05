"""从受保护的本地归档导入已备份附件；先完整校验，再按哈希原子写入。"""

import argparse
import hashlib
import re
import tarfile
from pathlib import Path

from blobs import BlobStore


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("blob_dir", type=Path)
    parser.add_argument("--expected-count", type=int, required=True)
    args = parser.parse_args()
    store = BlobStore(args.blob_dir)
    with tarfile.open(args.archive, "r:") as archive:
        members = archive.getmembers()
        if len(members) != args.expected_count or len({m.name for m in members}) != len(members):
            raise ValueError("附件数量不符")
        for member in members:
            if not member.isfile() or not re.fullmatch(r"[a-f0-9]{64}", member.name) or not 1 <= member.size <= store.max_bytes:
                raise ValueError("附件归档条目无效")
            digest = hashlib.sha256()
            with archive.extractfile(member) as source:
                while chunk := source.read(1024 * 1024):
                    digest.update(chunk)
            if digest.hexdigest() != member.name:
                raise ValueError("附件归档哈希不符")
        for member in members:
            try:
                store.get(member.name)
            except ValueError:
                with archive.extractfile(member) as source:
                    store.put(member.name, source)
        for member in members:
            store.get(member.name)
    print("附件已完成哈希验证并导入:", len(members))


if __name__ == "__main__":
    main()
