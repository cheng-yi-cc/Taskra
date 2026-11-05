"""按内容哈希保存不可变附件的内核；尚未接入网络服务。"""

import hashlib
import os
import re
import shutil
import tempfile
import threading
from pathlib import Path


class StorageLimitError(Exception):
    pass


class BlobStore:
    def __init__(self, directory: str | Path, max_bytes: int = 500 * 1024 * 1024, reserve_bytes: int = 2 * 1024 * 1024 * 1024):
        self.directory = Path(directory)
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.max_bytes = max_bytes
        self.reserve_bytes = reserve_bytes
        self._publish_lock = threading.RLock()
        self.chunk_bytes = 64 * 1024

    def _parts_directory(self, digest: str) -> Path:
        self._path(digest)  # 校验哈希后才能用于路径。
        return self.directory / "parts" / digest

    def list_parts(self, digest: str):
        directory = self._parts_directory(digest)
        with self._publish_lock:
            if not directory.exists():
                return []
            parts = []
            for file in directory.iterdir():
                if not file.is_file() or not re.fullmatch(r"[0-9]{6}", file.name):
                    continue
                index = int(file.name)
                if index >= (self.max_bytes + self.chunk_bytes - 1) // self.chunk_bytes:
                    continue
                parts.append({"index": index, "size": file.stat().st_size, "sha256": self._sha256(file)})
            return sorted(parts, key=lambda part: part["index"])

    def put_part(self, digest: str, index: int, stream):
        if type(index) is not int or index < 0 or index >= (self.max_bytes + self.chunk_bytes - 1) // self.chunk_bytes:
            raise ValueError("invalid part index")
        directory = self._parts_directory(digest)
        # 先并行接收有限大小的数据；不要在等待慢网络时持有全局发布锁。
        chunks = []
        size = 0
        while chunk := stream.read(self.chunk_bytes):
            size += len(chunk)
            if size > self.chunk_bytes:
                raise ValueError("part too large")
            chunks.append(chunk)
        if not size:
            raise ValueError("empty part")
        with self._publish_lock:
            directory.mkdir(mode=0o700, parents=True, exist_ok=True)
            fd, filename = tempfile.mkstemp(prefix="part-", dir=directory)
            temporary = Path(filename)
            try:
                with os.fdopen(fd, "wb") as output:
                    if shutil.disk_usage(directory).free - size < self.reserve_bytes:
                        raise StorageLimitError("insufficient storage")
                    for chunk in chunks:
                        output.write(chunk)
                    output.flush()
                    os.fsync(output.fileno())
                os.replace(temporary, directory / f"{index:06d}")
            finally:
                temporary.unlink(missing_ok=True)

    def complete_parts(self, digest: str, size: int):
        if type(size) is not int or not 1 <= size <= self.max_bytes:
            raise ValueError("invalid attachment size")
        directory = self._parts_directory(digest)
        count = (size + self.chunk_bytes - 1) // self.chunk_bytes
        with self._publish_lock:
            files = [directory / f"{index:06d}" for index in range(count)]
            for index, file in enumerate(files):
                expected = min(self.chunk_bytes, size - index * self.chunk_bytes)
                if not file.is_file() or file.stat().st_size != expected:
                    raise ValueError("missing or invalid part")

            class PartsStream:
                def __init__(self, paths):
                    self.paths = iter(paths)
                    self.current = None

                def read(self, amount):
                    while True:
                        if self.current is None:
                            path = next(self.paths, None)
                            if path is None:
                                return b""
                            self.current = path.open("rb")
                        chunk = self.current.read(amount)
                        if chunk:
                            return chunk
                        self.current.close()
                        self.current = None

                def close(self):
                    if self.current is not None:
                        self.current.close()

            source = PartsStream(files)
            try:
                self.put(digest, source)  # 完整哈希校验与原子落盘。
            finally:
                source.close()
            for file in files:
                file.unlink(missing_ok=True)

    def _path(self, digest: str) -> Path:
        if not isinstance(digest, str) or not re.fullmatch(r"[a-f0-9]{64}", digest):
            raise ValueError("invalid attachment hash")
        return self.directory / digest

    @staticmethod
    def _sha256(path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            while chunk := stream.read(1024 * 1024):
                digest.update(chunk)
        return digest.hexdigest()

    def get(self, digest: str) -> Path:
        path = self._path(digest)
        if not path.is_file() or self._sha256(path) != digest:
            raise ValueError("attachment missing or corrupt")
        return path

    def put(self, expected: str, stream) -> Path:
        destination = self._path(expected)
        fd, filename = tempfile.mkstemp(prefix="upload-", dir=self.directory)
        temporary = Path(filename)
        digest = hashlib.sha256()
        size = 0
        try:
            with os.fdopen(fd, "wb") as output:
                while chunk := stream.read(1024 * 1024):
                    size += len(chunk)
                    if size > self.max_bytes:
                        raise ValueError("attachment too large")
                    if shutil.disk_usage(self.directory).free - len(chunk) < self.reserve_bytes:
                        raise StorageLimitError("insufficient storage")
                    digest.update(chunk)
                    output.write(chunk)
                if size == 0 or digest.hexdigest() != expected:
                    raise ValueError("attachment hash mismatch or empty")
                output.flush()
                os.fsync(output.fileno())
            # 所有同哈希的已验证文件内容相同，原子替换也可修复损坏副本。
            with self._publish_lock:
                os.replace(temporary, destination)
                if hasattr(os, "O_DIRECTORY"):
                    directory_fd = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY)
                    try:
                        os.fsync(directory_fd)
                    finally:
                        os.close(directory_fd)
            return destination
        finally:
            temporary.unlink(missing_ok=True)
