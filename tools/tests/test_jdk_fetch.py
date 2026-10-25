import hashlib
import io
import json
import sys
import tarfile
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import jdk_fetch

JAVA_VERSION = "21.0.4+7"


def make_archive(members: dict[str, bytes]) -> bytes:
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as tar:
        for name, data in members.items():
            info = tarfile.TarInfo(name.rstrip("/"))
            if name.endswith("/"):
                info.type = tarfile.DIRTYPE
                info.mode = 0o755
                tar.addfile(info)
                continue
            info.size = len(data)
            info.mode = 0o755 if name.endswith("/bin/java") else 0o644
            tar.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


def jdk_members(top: str = "jdk-21.0.4+7") -> dict[str, bytes]:
    return {
        f"{top}/": b"",
        f"{top}/bin/": b"",
        f"{top}/bin/java": b"#!/bin/sh\necho fake\n",
        f"{top}/release": b'JAVA_VERSION="21.0.4"\n',
    }


class FakeFoojay:
    """Serves a Disco-style listing, a package record and an archive over loopback HTTP."""

    def __init__(self, archive: bytes, *, advertised_sha256: str | None = None,
                 checksum_type: str = "sha256", distribution: str = "temurin",
                 listing: list | None = None):
        self.archive = archive
        digest = advertised_sha256 or hashlib.sha256(archive).hexdigest()
        self.routes: dict[str, tuple[int, bytes, str]] = {}
        base = {"result": listing if listing is not None else [
            {"id": "p1", "java_version": JAVA_VERSION, "distribution": distribution}]}
        detail = {"result": [{
            "checksum": digest,
            "checksum_type": checksum_type,
            "direct_download_uri": "http://127.0.0.1:PORT/dl/jdk.tar.gz",
            "filename": "jdk.tar.gz",
        }]}
        self.routes["/disco/v3.0/packages/jdks"] = (200, json.dumps(base).encode(), "application/json")
        self.routes["/disco/v3.0/ids/p1"] = (200, json.dumps(detail).encode(), "application/json")
        self.routes["/dl/jdk.tar.gz"] = (200, archive, "application/gzip")
        handler = self._handler()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        port = self.server.server_address[1]
        for path, (status, body, kind) in list(self.routes.items()):
            body = body.replace(b"PORT", str(port).encode())
            self.routes[path] = (status, body, kind)
        self.base = f"http://127.0.0.1:{port}/disco/v3.0"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def _handler(self):
        routes = self.routes

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                path = self.path.split("?", 1)[0]
                status, body, kind = routes.get(path, (404, b"{}", "application/json"))
                self.send_response(status)
                self.send_header("Content-Type", kind)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        return Handler

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()


class JdkFetchTests(unittest.TestCase):
    def test_verified_package_is_installed_and_recorded(self):
        with FakeFoojay(make_archive(jdk_members())) as fake, tempfile.TemporaryDirectory() as tmp:
            home = jdk_fetch.install_jdk(21, "temurin", Path(tmp), fake.base)
            self.assertTrue((home / "bin" / "java").exists())
            manifest = json.loads((Path(tmp) / "manifest.json").read_text())
            self.assertEqual("p1", manifest["temurin-21.0.4_7"]["package_id"])
            self.assertEqual(64, len(manifest["temurin-21.0.4_7"]["sha256"]))

    def test_installed_package_is_not_downloaded_again(self):
        with FakeFoojay(make_archive(jdk_members())) as fake, tempfile.TemporaryDirectory() as tmp:
            first = jdk_fetch.install_jdk(21, "temurin", Path(tmp), fake.base)
            second = jdk_fetch.install_jdk(21, "temurin", Path(tmp), fake.base)
            self.assertEqual(first, second)

    def test_checksum_mismatch_is_refused_and_nothing_is_installed(self):
        wrong = "0" * 64
        with FakeFoojay(make_archive(jdk_members()), advertised_sha256=wrong) as fake, tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(jdk_fetch.FetchError, "SHA-256 mismatch"):
                jdk_fetch.install_jdk(21, "temurin", Path(tmp), fake.base)
            self.assertFalse((Path(tmp) / "temurin-21.0.4_7").exists())
            self.assertFalse(any(Path(tmp, "downloads").glob("*")))

    def test_package_without_sha256_is_refused(self):
        with FakeFoojay(make_archive(jdk_members()), checksum_type="md5") as fake, tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(jdk_fetch.FetchError, "does not publish a SHA-256"):
                jdk_fetch.install_jdk(21, "temurin", Path(tmp), fake.base)

    def test_path_traversal_in_archive_is_rejected(self):
        members = jdk_members()
        members["../escape.txt"] = b"outside the install directory"
        with FakeFoojay(make_archive(members)) as fake, tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(jdk_fetch.FetchError):
                jdk_fetch.install_jdk(21, "temurin", Path(tmp), fake.base)
            self.assertFalse((Path(tmp) / "escape.txt").exists())
            self.assertFalse((Path(tmp).parent / "escape.txt").exists())

    def test_non_openjdk_distribution_is_refused_before_any_request(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(jdk_fetch.FetchError, "not an allowed OpenJDK HotSpot source"):
                jdk_fetch.install_jdk(21, "graalvm", Path(tmp), "http://127.0.0.1:9/unreachable")

    def test_no_matching_package_is_an_error(self):
        with FakeFoojay(make_archive(jdk_members()), listing=[]) as fake, tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(jdk_fetch.FetchError, "no temurin JDK 21 package"):
                jdk_fetch.install_jdk(21, "temurin", Path(tmp), fake.base)

    def test_plain_http_to_a_remote_host_is_refused(self):
        self.assertFalse(jdk_fetch._allowed_download("http://example.com/jdk.tar.gz"))
        self.assertTrue(jdk_fetch._allowed_download("https://example.com/jdk.tar.gz"))


if __name__ == "__main__":
    unittest.main()
