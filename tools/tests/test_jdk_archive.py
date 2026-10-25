import gzip
import hashlib
import http.server
import json
import os
import shutil
import socketserver
import subprocess
import sys
import tempfile
import threading
import unittest
from functools import partial
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import jdk_archive as archive

# Exercises the real verification path against a fixture archive: real GPG signatures, real
# .deb packages built with dpkg-deb, served over loopback HTTP.


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kwargs)


class FixtureArchive:
    def __init__(self, root: Path, gnupg_home: Path):
        self.root = root
        self.gnupg = gnupg_home
        self.key = None
        self.keyring = root / "archive-keyring.gpg"
        self.archive_root = root / "ubuntu"
        self.archive_root.mkdir(parents=True)

    def make_key(self):
        env = {**os.environ, "GNUPGHOME": str(self.gnupg)}
        self.gnupg.mkdir(mode=0o700, exist_ok=True)
        run(["gpg", "--batch", "--pinentry-mode", "loopback", "--passphrase", "",
             "--quick-gen-key", "Fixture Archive <archive@example.invalid>", "ed25519", "sign", "never"], env=env)
        listing = run(["gpg", "--batch", "--with-colons", "--list-keys"], env=env).stdout
        self.key = next(line.split(":")[9] for line in listing.splitlines() if line.startswith("fpr:"))
        run(["gpg", "--batch", "--export", "--output", str(self.keyring), self.key], env=env)

    def build_deb(self, name: str, version: str, files: dict[str, bytes], links: dict[str, str]) -> Path:
        tree = self.root / "build" / f"{name}-{version}"
        (tree / "DEBIAN").mkdir(parents=True)
        (tree / "DEBIAN" / "control").write_text(
            f"Package: {name}\nVersion: {version}\nArchitecture: amd64\nMaintainer: fixture <f@example.invalid>\n"
            f"Description: fixture\n")
        for relative, content in files.items():
            path = tree / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
            if relative.endswith(("/bin/java", "/bin/javac")):
                path.chmod(0o755)
        for relative, target in links.items():
            path = tree / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.symlink_to(target)
        out = self.root / "debs" / f"{name}_{version}_amd64.deb"
        out.parent.mkdir(exist_ok=True)
        run(["dpkg-deb", "--root-owner-group", "--build", str(tree), str(out)])
        return out

    def publish(self, suite: str, packages: list[Path], pool_dir: str = "pool/main/o/openjdk-21"):
        """Write a signed suite: index, Release, Release.gpg, and the packages under pool/."""
        records = []
        for deb in packages:
            target = self.archive_root / pool_dir / deb.name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(deb, target)
            control = run(["dpkg-deb", "-f", str(deb), "Package", "Version", "Architecture"]).stdout
            fields = dict(line.split(": ", 1) for line in control.splitlines() if ": " in line)
            records.append(
                f"Package: {fields['Package']}\nVersion: {fields['Version']}\nArchitecture: amd64\n"
                f"Filename: {pool_dir}/{deb.name}\nSize: {deb.stat().st_size}\nSHA256: {sha256(deb)}\n"
                "Description: fixture\n")
        dists = self.archive_root / "dists" / suite / "main" / "binary-amd64"
        dists.mkdir(parents=True, exist_ok=True)
        index = dists / "Packages.gz"
        index.write_bytes(gzip.compress("\n".join(records).encode()))
        universe = self.archive_root / "dists" / suite / "universe" / "binary-amd64"
        universe.mkdir(parents=True, exist_ok=True)
        (universe / "Packages.gz").write_bytes(gzip.compress(b""))
        release = self.archive_root / "dists" / suite / "Release"
        release.write_text(
            f"Origin: Ubuntu\nSuite: {suite}\nArchitectures: amd64\nComponents: main universe\nSHA256:\n"
            f" {sha256(index)} {index.stat().st_size} main/binary-amd64/Packages.gz\n"
            f" {sha256(universe / 'Packages.gz')} {(universe / 'Packages.gz').stat().st_size} universe/binary-amd64/Packages.gz\n")
        env = {**os.environ, "GNUPGHOME": str(self.gnupg)}
        run(["gpg", "--batch", "--yes", "--pinentry-mode", "loopback", "--passphrase", "", "-u", self.key,
             "--detach-sign", "--output", str(release.with_name("Release.gpg")), str(release)], env=env)

    def serve(self):
        class Quiet(http.server.SimpleHTTPRequestHandler):
            def log_message(self, *args):
                pass

        handler = partial(Quiet, directory=str(self.archive_root))
        server = socketserver.ThreadingTCPServer(("127.0.0.1", 0), handler)
        server.daemon_threads = True
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.server = server
        return f"http://127.0.0.1:{server.server_address[1]}/"


def jdk_packages(fixture: FixtureArchive, version: str, message: str) -> list[Path]:
    home = "usr/lib/jvm/java-21-openjdk-amd64"
    java = f"#!/bin/sh\necho 'openjdk version \"{message}\"' >&2\necho 'OpenJDK Runtime Environment' >&2\n".encode()
    jre = fixture.build_deb(
        "openjdk-21-jre-headless", version,
        {f"{home}/bin/java": java,
         f"{home}/lib/server/libjvm.so": b"not a real library",
         f"{home}/release": b'JAVA_VERSION="21"\n',
         "etc/java-21-openjdk/logging.properties": b"handlers=\n"},
        {f"{home}/conf": "/etc/java-21-openjdk"},
    )
    jdk = fixture.build_deb(
        "openjdk-21-jdk-headless", version,
        {f"{home}/bin/javac": b"#!/bin/sh\necho 'javac " + message.encode() + b"'\n"},
        {},
    )
    return [jre, jdk]


class JdkArchiveTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        for tool in ("gpg", "dpkg-deb", "dpkg", "gpgv"):
            if shutil.which(tool) is None:
                raise unittest.SkipTest(f"{tool} is required for the archive fixture")

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="jdk-archive-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.fixture = FixtureArchive(self.tmp / "fixture", self.tmp / "gnupg")
        self.fixture.make_key()
        self.base = self.fixture.serve()
        self.addCleanup(self.fixture.server.shutdown)
        self.addCleanup(self.fixture.server.server_close)
        self.saved_backoff = archive.BACKOFF_SECONDS
        archive.BACKOFF_SECONDS = 0
        self.addCleanup(setattr, archive, "BACKOFF_SECONDS", self.saved_backoff)
        self.install_root = self.tmp / "jdks"

    def install(self, feature=21, **overrides):
        options = dict(archive=self.base, keyring=self.fixture.keyring, codename="noble",
                       suites=("noble-updates",), check_libraries=False)
        options.update(overrides)
        return archive.install_ubuntu_jdk(feature, self.install_root, **options)

    def test_verified_install_runs_and_records_its_provenance(self):
        self.fixture.publish("noble-updates", jdk_packages(self.fixture, "21.0.12+1-1", "21.0.12"))
        home = self.install()
        self.assertTrue((home / "bin" / "java").exists())
        version = run([str(home / "bin" / "java"), "-version"]).stderr
        self.assertIn("OpenJDK", version)
        manifest = json.loads((self.install_root / "manifest.json").read_text())
        entry = manifest[home.name]
        self.assertEqual(self.fixture.key, entry["release_signers"]["noble-updates"])
        self.assertEqual("21.0.12+1-1", entry["java_version"])
        self.assertEqual(64, len(entry["packages"]["openjdk-21-jre-headless"]["sha256"]))

    def test_configuration_links_are_carried_inside_the_install(self):
        self.fixture.publish("noble-updates", jdk_packages(self.fixture, "21.0.12+1-1", "21.0.12"))
        home = self.install()
        link = home / "conf"
        self.assertTrue(link.is_symlink())
        self.assertFalse(os.path.isabs(os.readlink(link)))
        self.assertTrue((link / "logging.properties").exists())

    def test_tampered_release_is_refused_before_anything_is_downloaded_or_installed(self):
        self.fixture.publish("noble-updates", jdk_packages(self.fixture, "21.0.12+1-1", "21.0.12"))
        release = self.fixture.archive_root / "dists" / "noble-updates" / "Release"
        release.write_text(release.read_text() + " tampered\n")
        with self.assertRaisesRegex(archive.ArchiveError, "signature"):
            self.install()
        self.assertFalse(any(self.install_root.glob("ubuntu-openjdk-*")))

    def test_corrupted_package_is_refused_by_its_index_digest(self):
        packages = jdk_packages(self.fixture, "21.0.12+1-1", "21.0.12")
        self.fixture.publish("noble-updates", packages)
        served = self.fixture.archive_root / "pool" / "main" / "o" / "openjdk-21" / packages[0].name
        served.write_bytes(served.read_bytes() + b"corruption")
        with self.assertRaisesRegex(archive.ArchiveError, "SHA-256 mismatch"):
            self.install()
        self.assertFalse(any(self.install_root.glob("ubuntu-openjdk-*")))

    def test_newest_matching_version_is_chosen(self):
        packages = jdk_packages(self.fixture, "21.0.11+1-1", "21.0.11") + jdk_packages(self.fixture, "21.0.12+1-1", "21.0.12")
        self.fixture.publish("noble-updates", packages)
        home = self.install()
        self.assertEqual("21.0.12+1-1", json.loads((self.install_root / "manifest.json").read_text())[home.name]["java_version"])

    def test_jre_without_jdk_is_refused(self):
        jre_only = jdk_packages(self.fixture, "21.0.12+1-1", "21.0.12")[:1]
        self.fixture.publish("noble-updates", jre_only)
        with self.assertRaisesRegex(archive.ArchiveError, "no openjdk-21-jdk-headless"):
            self.install()

    def test_non_ubuntu_host_is_refused(self):
        os_release = self.tmp / "os-release"
        os_release.write_text('ID=debian\nVERSION_CODENAME=bookworm\n')
        with self.assertRaisesRegex(archive.ArchiveError, "only on Ubuntu"):
            archive.host_codename(os_release)

    def test_ubuntu_host_reports_its_codename(self):
        os_release = self.tmp / "os-release"
        os_release.write_text('ID=ubuntu\nUBUNTU_CODENAME=noble\nVERSION_CODENAME=noble\n')
        self.assertEqual("noble", archive.host_codename(os_release))

    def test_missing_keyring_is_refused(self):
        self.fixture.publish("noble-updates", jdk_packages(self.fixture, "21.0.12+1-1", "21.0.12"))
        with self.assertRaisesRegex(archive.ArchiveError, "keyring is missing"):
            self.install(keyring=self.tmp / "absent.gpg")

    def test_plain_http_to_a_remote_host_is_refused(self):
        self.assertFalse(archive._allowed_url("http://archive.ubuntu.com/ubuntu/"))


if __name__ == "__main__":
    unittest.main()
