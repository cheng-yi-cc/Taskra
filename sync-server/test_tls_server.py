"""仅供本机隔离集成测试；证书、数据库和一次性配对码由调用方放在测试目录。"""

import argparse
import os
import ssl
import time
from pathlib import Path

from service import Handler, SyncServer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--directory", required=True)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--part-delay-ms", type=int, default=0)
    args = parser.parse_args()
    directory = Path(args.directory)
    directory.mkdir(mode=0o700, parents=False, exist_ok=False)
    server = SyncServer(args.port, directory / "sync.sqlite", directory / "blobs")
    if args.part_delay_ms:
        class DelayedHandler(Handler):
            def handle_request(self):
                if self.command == "PUT" and "/parts/" in self.path:
                    time.sleep(args.part_delay_ms / 1000)
                super().handle_request()
        server.RequestHandlerClass = DelayedHandler
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(args.cert, args.key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    code = server.pairing.bootstrap_code()
    code_path = directory / "bootstrap.txt"
    descriptor = os.open(code_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(code)
    (directory / "ready").write_text("ready", encoding="utf-8")
    server.serve_forever()


if __name__ == "__main__":
    main()
