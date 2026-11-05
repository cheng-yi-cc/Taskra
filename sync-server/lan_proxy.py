"""仅代理 Taskra 同步接口的局域网 TLS 入口；业务服务仍只监听回环地址。"""

import argparse
import http.client
import ssl
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


class LanServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 32

    def __init__(self, address, cert, key, upstream_port, tls_host="chengyi-thinkpad-t420.taild0ff49.ts.net"):
        self.upstream_port = upstream_port
        self.tls_host = tls_host
        self._slots = threading.BoundedSemaphore(16)
        self.context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.context.minimum_version = ssl.TLSVersion.TLSv1_2
        self.context.load_cert_chain(cert, key)
        super().__init__(address, ConnectHandler)

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


class ConnectHandler(BaseHTTPRequestHandler):
    """仅允许连接固定的本机同步服务，随后由系统 TLS 进行正常域名验证。"""
    def setup(self):
        super().setup()
        self.connection.settimeout(20)

    def log_message(self, format, *args):
        pass

    def do_CONNECT(self):
        if self.path.lower() != f"{self.server.tls_host}:{self.server.server_port}".lower():
            self.send_error(403)
            return
        self.send_response(200, "Connection established")
        self.end_headers()
        try:
            with self.server.context.wrap_socket(self.connection, server_side=True) as connection:
                Handler(connection, self.client_address, self.server)
        except (OSError, ssl.SSLError):
            pass
        self.close_connection = True


class Handler(BaseHTTPRequestHandler):
    server_version = "TaskraLan"
    sys_version = ""

    def setup(self):
        super().setup()
        self.connection.settimeout(20)

    def log_message(self, format, *args):
        pass  # 不记录凭据、路径或个人数据。

    def proxy(self):
        parsed = urlsplit(self.path)
        if not parsed.path.startswith("/taskra-sync/") or parsed.scheme or parsed.netloc:
            self.send_error(404)
            return
        lengths = self.headers.get_all("Content-Length", [])
        if self.headers.get("Transfer-Encoding") is not None or len(lengths) > 1:
            self.send_error(400)
            return
        if self.command in ("PUT", "POST"):
            if len(lengths) != 1 or not lengths[0].isascii() or not lengths[0].isdigit():
                self.send_error(400)
                return
            remaining = int(lengths[0])
            if remaining > 500 * 1024 * 1024:
                self.send_error(413)
                return
        else:
            remaining = 0
            if lengths and lengths[0] != "0":
                self.send_error(400)
                return
        upstream = http.client.HTTPConnection("127.0.0.1", self.server.upstream_port, timeout=25)
        try:
            upstream.putrequest(self.command, self.path, skip_accept_encoding=True)
            for name in ("Authorization", "Accept", "Content-Type", "Range"):
                values = self.headers.get_all(name, [])
                if len(values) > 1:
                    self.send_error(400)
                    return
                if values:
                    upstream.putheader(name, values[0])
            if lengths:
                upstream.putheader("Content-Length", lengths[0])
            upstream.putheader("Connection", "close")
            upstream.endheaders()
            while remaining:
                data = self.rfile.read(min(65536, remaining))
                if not data:
                    raise OSError("incomplete request")
                upstream.send(data)
                remaining -= len(data)
            result = upstream.getresponse()
            self.send_response(result.status)
            for name in ("Content-Type", "Content-Length", "Cache-Control", "Content-Range", "Accept-Ranges"):
                value = result.getheader(name)
                if value is not None:
                    self.send_header(name, value)
            self.send_header("Connection", "close")
            self.end_headers()
            if self.command != "HEAD":
                while chunk := result.read(65536):
                    self.wfile.write(chunk)
        except (OSError, http.client.HTTPException):
            self.close_connection = True
        finally:
            upstream.close()

    do_GET = proxy
    do_HEAD = proxy
    do_POST = proxy
    do_PUT = proxy


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=38129)
    parser.add_argument("--upstream-port", type=int, default=38127)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    args = parser.parse_args()
    LanServer((args.bind, args.port), args.cert, args.key, args.upstream_port).serve_forever()


if __name__ == "__main__":
    main()
