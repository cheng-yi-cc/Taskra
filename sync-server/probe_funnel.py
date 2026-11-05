"""使用服务器独立测试库验证公网 HTTPS；绝不打印配对码或设备令牌。"""

import hashlib
import json
import subprocess
import uuid
from urllib.error import HTTPError
from urllib.error import URLError
from urllib.request import ProxyHandler, Request, build_opener

BASE = "https://chengyi-thinkpad-t420.taild0ff49.ts.net/taskra-sync-probe"
OPENER = build_opener(ProxyHandler({}))


def request(method, path, payload=None, token=None, retry=False):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if isinstance(payload, dict):
        payload = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    for attempt in range(3 if retry else 1):
        try:
            with OPENER.open(Request(BASE + path, data=payload, method=method, headers=headers), timeout=20) as r:
                return r.status, r.read()
        except HTTPError as e:
            return e.code, e.read()
        except (TimeoutError, URLError):
            if attempt == 2 or not retry:
                raise
            print("测试连接超时，重试同一个幂等请求。", flush=True)


def main():
    code = subprocess.run([
        "ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=8", "server", "/usr/bin/python3",
        "/home/chengyi/taskra-sync/main.py", "--data-dir", "/home/chengyi/taskra-sync-probe-3/data", "bootstrap",
    ], capture_output=True, text=True, check=True).stdout.strip()
    pairing = {"code": code, "request_id": uuid.uuid4().hex}
    status, data = request("POST", "/v1/pair/redeem", pairing, retry=True)
    assert status == 200
    phone = json.loads(data)
    status, data = request("POST", "/v1/pair/redeem", pairing, retry=True)
    assert status == 200 and json.loads(data) == phone
    assert request("POST", "/v1/pair/redeem", {"code": code, "request_id": uuid.uuid4().hex})[0] == 401
    assert request("GET", "/v1/time", token="invalid")[0] == 401
    status, data = request("POST", "/v1/pair", {}, phone["token"])
    assert status == 200
    status, data = request("POST", "/v1/pair/redeem", {"code": json.loads(data)["code"], "request_id": uuid.uuid4().hex}, retry=True)
    assert status == 200
    tablet = json.loads(data)
    blob = b"synthetic taskra funnel test"
    digest = hashlib.sha256(blob).hexdigest()
    assert request("PUT", "/v1/blobs/" + digest, blob, phone["token"], retry=True)[0] == 200
    record = {"kind": "homework", "key": "synthetic", "modified_at": 1, "sequence": 1,
              "value": {"text": "test"}, "clock_reliable": False, "attachments": [digest], "if_absent": False}
    assert request("POST", "/v1/changes", {"records": [record]}, phone["token"], retry=True)[0] == 200
    status, data = request("GET", "/v1/changes?after=0", token=tablet["token"], retry=True)
    assert status == 200 and json.loads(data)["changes"][0]["head"]["value"]["payload"]["text"] == "test"
    assert request("GET", "/v1/blobs/" + digest, token=tablet["token"]) == (200, blob)
    print("公网入口隔离测试通过：配对、鉴权、同步和附件往返。")


if __name__ == "__main__":
    main()
