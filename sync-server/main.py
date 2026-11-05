"""实验服务入口：仅监听本机，由外部反向代理负责 HTTPS。"""

import argparse
import os
from pathlib import Path

from pairing import Pairing
from service import SyncServer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", required=True, type=Path)
    sub = parser.add_subparsers(dest="action", required=True)
    serve = sub.add_parser("serve")
    serve.add_argument("--port", type=int, default=38127)
    serve.add_argument("--mount", default="/taskra-sync")
    sub.add_parser("bootstrap")
    args = parser.parse_args()
    os.umask(0o077)
    args.data_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    db = args.data_dir / "sync.sqlite"
    if args.action == "bootstrap":
        # 仅在服务器本机终端运行；配对码不得写入日志或版本库。
        print(Pairing(db).bootstrap_code())
    else:
        server = SyncServer(args.port, db, args.data_dir / "blobs", args.mount)
        server.serve_forever()


if __name__ == "__main__":
    main()
