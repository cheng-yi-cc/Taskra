"""仅供本机模拟器验收：合成数据库、测试证书和局域网代理。"""

import argparse
import os
import sys
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "sync-server"))
from lan_proxy import LanServer
from service import SyncServer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--cert", required=True, type=Path)
    parser.add_argument("--key", required=True, type=Path)
    args = parser.parse_args()
    args.directory.mkdir(mode=0o700, parents=False, exist_ok=False)
    server = SyncServer(0, args.directory / "sync.sqlite", args.directory / "blobs", "/taskra-sync")
    code = server.pairing.bootstrap_code()
    fd = os.open(args.directory / "bootstrap.txt", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="ascii") as output:
        output.write(code)
    proxy = LanServer(("127.0.0.1", 38129), str(args.cert), str(args.key), server.server_port)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    (args.directory / "ready").write_text("ready", encoding="ascii")
    proxy.serve_forever()


if __name__ == "__main__":
    main()
