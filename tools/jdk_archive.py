#!/usr/bin/env python3
"""Install OpenJDK HotSpot builds from the Ubuntu archive, verified end to end.

Trust chain, every link checked before the next is trusted:

1. `Release` is signed by the Ubuntu archive signing key. `gpgv` checks it against the
   archive keyring installed with the distribution.
2. `Release` lists the SHA-256 of each package index.
3. Each package index lists the SHA-256 of every `.deb`.
4. Each `.deb` is checked against that digest before it is unpacked.

The result is validated before it is installed: `java` and `javac` must run, and the
native library must resolve every dependency. The manifest records the signing key
fingerprint and the digest of every package used.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Iterable, Sequence

ARCHIVE = "https://archive.ubuntu.com/ubuntu"
KEYRING = Path("/usr/share/keyrings/ubuntu-archive-keyring.gpg")
OS_RELEASE = Path("/etc/os-release")
COMPONENTS = ("main", "universe")
USER_AGENT = "openjdk-benchmark-suite/1.0 (+jdk-archive)"
TIMEOUT_SECONDS = 120
ATTEMPTS = 6
# Backoff between download attempts. The archive can briefly refuse a request that succeeds
# moments later; the pause is short enough not to hide a genuinely missing file.
BACKOFF_SECONDS = 2.0
REQUIRED_TOOLS = ("gpgv", "dpkg-deb", "dpkg", "ldd")


class ArchiveError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _allowed_url(url: str) -> bool:
    # HTTPS only; plain HTTP is accepted for loopback so tests can serve fixtures.
    parsed = urllib.parse.urlparse(url)
    return parsed.scheme == "https" or (parsed.scheme == "http" and parsed.hostname in ("127.0.0.1", "localhost"))


def download(url: str, destination: Path, expected_sha256: str | None = None) -> Path:
    """Fetch `url` into `destination`, retrying transient failures.

    With `expected_sha256` the file is moved into place only if its digest matches.
    """
    if not _allowed_url(url):
        raise ArchiveError(f"refusing non-HTTPS download: {url}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(destination.name + ".partial")
    last_error: Exception | None = None
    for attempt in range(1, ATTEMPTS + 1):
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response, partial.open("wb") as handle:
                shutil.copyfileobj(response, handle, 1 << 20)
            last_error = None
            break
        except (OSError, urllib.error.URLError) as exception:
            last_error = exception
            partial.unlink(missing_ok=True)
            if attempt < ATTEMPTS:
                time.sleep(BACKOFF_SECONDS * attempt)
    if last_error is not None:
        raise ArchiveError(f"download failed after {ATTEMPTS} attempts: {url}: {last_error}")
    if expected_sha256 is not None:
        actual = sha256_file(partial)
        if actual != expected_sha256:
            partial.unlink(missing_ok=True)
            raise ArchiveError(f"SHA-256 mismatch for {url}: expected {expected_sha256}, got {actual}")
    partial.replace(destination)
    return destination


def host_codename(os_release: Path = OS_RELEASE) -> str:
    """The Ubuntu release codename of this host. Packages must match the host's release."""
    try:
        text = os_release.read_text()
    except OSError as exception:
        raise ArchiveError(f"cannot read {os_release}: {exception}") from exception
    fields = {}
    for line in text.splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            fields[key.strip()] = value.strip().strip('"')
    if fields.get("ID") != "ubuntu":
        raise ArchiveError(
            f"the Ubuntu archive installs only on Ubuntu hosts; this host reports ID={fields.get('ID')!r}"
        )
    codename = fields.get("UBUNTU_CODENAME") or fields.get("VERSION_CODENAME")
    if not codename:
        raise ArchiveError(f"{os_release} does not name an Ubuntu release codename")
    return codename


def debian_architecture() -> str:
    machine = platform.machine().lower()
    if machine in ("x86_64", "amd64"):
        return "amd64"
    # The archive mirror at archive.ubuntu.com carries amd64 only; other architectures live on
    # ports.ubuntu.com and are not supported by this installer.
    raise ArchiveError(f"no Ubuntu archive mirror is configured for architecture {machine!r}")


def require_tools() -> None:
    missing = [tool for tool in REQUIRED_TOOLS if shutil.which(tool) is None]
    if missing:
        raise ArchiveError("required tools are missing: " + ", ".join(missing))


def verify_release(release: Path, signature: Path, keyring: Path) -> str:
    """Check the Release signature. Returns the fingerprint of the signing primary key."""
    if not keyring.exists():
        raise ArchiveError(f"archive keyring is missing: {keyring}")
    completed = subprocess.run(
        ["gpgv", "--keyring", str(keyring), "--status-fd", "1", str(signature), str(release)],
        capture_output=True, text=True, check=False,
    )
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout).strip()[-300:]
        raise ArchiveError(f"Release signature did not verify against {keyring}: {detail}")
    status = re.search(r"^\[GNUPG:\] VALIDSIG (.+)$", completed.stdout, re.MULTILINE)
    if status is None:
        raise ArchiveError("gpgv reported success without a VALIDSIG status line")
    # The primary key fingerprint is the last field of VALIDSIG.
    return status.group(1).split()[-1]


def parse_release_checksums(text: str) -> dict[str, tuple[str, int]]:
    """Map each listed path to its (SHA-256, size) from the Release file's SHA256 block."""
    entries: dict[str, tuple[str, int]] = {}
    in_block = False
    for line in text.splitlines():
        if line.startswith("SHA256:"):
            in_block = True
            continue
        if not in_block:
            continue
        if not line.startswith(" "):
            break
        parts = line.split()
        if len(parts) == 3 and re.fullmatch(r"[0-9a-f]{64}", parts[0]) and parts[1].isdigit():
            entries[parts[2]] = (parts[0], int(parts[1]))
    return entries


def parse_packages(text: str) -> list[dict[str, str]]:
    records = []
    for stanza in text.split("\n\n"):
        fields: dict[str, str] = {}
        for line in stanza.splitlines():
            if ": " in line and not line.startswith(" "):
                key, value = line.split(": ", 1)
                fields[key] = value
        if fields.get("Package"):
            records.append(fields)
    return records


def newest_version(versions: Sequence[str]) -> str:
    best = versions[0]
    for candidate in versions[1:]:
        if subprocess.run(["dpkg", "--compare-versions", candidate, "gt", best], check=False).returncode == 0:
            best = candidate
    return best


def java_home_name(feature: int) -> str:
    return f"java-{feature}-openjdk-amd64"


def select_packages(records: Iterable[dict[str, Any]], feature: int) -> tuple[dict[str, Any], dict[str, Any]]:
    """The newest JDK and matching JRE for a feature release. Both must share one version."""
    jdk_name = f"openjdk-{feature}-jdk-headless"
    jre_name = f"openjdk-{feature}-jre-headless"
    jdks = {record["Version"]: record for record in records if record["Package"] == jdk_name}
    jres = {record["Version"]: record for record in records if record["Package"] == jre_name}
    # Feature releases appear as "21.0.12.1+1-...", "17.0.20.1+1-..." or, for 8, "8u504-ga-...".
    pattern = re.compile(rf"{feature}(\.|u|\+|-|~|$)")
    versions = [version for version in jdks if version in jres and pattern.match(version)]
    if not versions:
        raise ArchiveError(f"no {jdk_name} with a matching {jre_name} is published for this release")
    chosen = newest_version(versions)
    return jdks[chosen], jres[chosen]


def load_release(base: str, suite: str, work: Path, keyring: Path) -> tuple[dict[str, tuple[str, int]], str]:
    release = download(f"{base}/dists/{suite}/Release", work / suite / "Release")
    signature = download(f"{base}/dists/{suite}/Release.gpg", work / suite / "Release.gpg")
    fingerprint = verify_release(release, signature, keyring)
    return parse_release_checksums(release.read_text(errors="replace")), fingerprint


def load_index_records(base: str, suite: str, checksums: dict[str, tuple[str, int]], work: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for component in COMPONENTS:
        relative = f"{component}/binary-amd64/Packages.gz"
        if relative not in checksums:
            continue
        expected, _size = checksums[relative]
        index = download(f"{base}/dists/{suite}/{relative}", work / suite / f"{component}-Packages.gz", expected)
        for record in parse_packages(gzip.decompress(index.read_bytes()).decode(errors="replace")):
            record["_suite"] = suite
            record["_component"] = component
            records.append(record)
    return records


def fetch_package(base: str, record: dict[str, Any], work: Path) -> Path:
    expected = record.get("SHA256", "")
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ArchiveError(f"index entry for {record['Package']} has no SHA-256")
    return download(f"{base}/{record['Filename']}", work / "debs" / Path(record["Filename"]).name, expected)


def rebase_absolute_links(home: Path, staging: Path) -> list[str]:
    """Point absolute links at copies of their targets inside the install.

    Ubuntu's JDK packages link configuration such as `conf/security/java.security` to
    `/etc/java-<N>-openjdk/...`. Those files come from the same package, so the install
    carries them under `_system/` and the links become relative. Links to paths the packages
    do not provide (for example the host CA store) are left alone, and reported.
    """
    external: list[str] = []
    links = [path for path in home.rglob("*") if path.is_symlink()]
    for link in links:
        target = os.readlink(link)
        if not os.path.isabs(target):
            continue
        source = staging / target.lstrip("/")
        if not source.exists():
            external.append(target)
            continue
        mirror = home / "_system" / target.lstrip("/")
        mirror.parent.mkdir(parents=True, exist_ok=True)
        if source.is_dir():
            shutil.copytree(source, mirror, dirs_exist_ok=True)
        else:
            shutil.copy2(source, mirror)
        link.unlink()
        link.symlink_to(os.path.relpath(mirror, link.parent))
    return sorted(set(external))


def validate_home(home: Path, check_libraries: bool = True) -> dict[str, Any]:
    """Run the installed JDK. Missing tools or unresolved native libraries are refused."""
    java = home / "bin" / "java"
    javac = home / "bin" / "javac"
    for tool in (java, javac):
        if not tool.exists():
            raise ArchiveError(f"installed JDK lacks {tool.relative_to(home)}")
    version = subprocess.run([str(java), "-version"], capture_output=True, text=True, check=False)
    if version.returncode != 0 or "OpenJDK" not in version.stderr:
        raise ArchiveError(f"installed java did not report an OpenJDK runtime: {version.stderr.strip()[-200:]}")
    compiler = subprocess.run([str(javac), "-version"], capture_output=True, text=True, check=False)
    if compiler.returncode != 0:
        raise ArchiveError("installed javac did not run")
    missing: list[str] = []
    if check_libraries:
        native = home / "lib" / "server" / "libjvm.so"
        listing = subprocess.run(["ldd", str(native)], capture_output=True, text=True, check=False).stdout
        missing = sorted({line.split("=>")[0].strip() for line in listing.splitlines() if "not found" in line})
        if missing:
            raise ArchiveError("the JVM cannot load these libraries from the host: " + ", ".join(missing))
    return {"java_version": version.stderr.strip().splitlines()[0], "javac_version": compiler.stdout.strip() or compiler.stderr.strip()}


def install_ubuntu_jdk(
    feature: int,
    install_root: Path,
    *,
    archive: str = ARCHIVE,
    keyring: Path = KEYRING,
    codename: str | None = None,
    suites: Sequence[str] | None = None,
    check_libraries: bool = True,
) -> Path:
    """Install the newest verified OpenJDK HotSpot build for `feature` under `install_root`."""
    require_tools()
    if feature < 8:
        raise ArchiveError(f"no Ubuntu OpenJDK package exists for feature release {feature}")
    codename = codename or host_codename()
    debian_architecture()
    selected_suites = tuple(suites) if suites is not None else (f"{codename}-updates", codename)
    work = install_root / "downloads"
    work.mkdir(parents=True, exist_ok=True)

    records: list[dict[str, Any]] = []
    signers: dict[str, str] = {}
    for suite in selected_suites:
        checksums, fingerprint = load_release(archive, suite, work, keyring)
        signers[suite] = fingerprint
        records.extend(load_index_records(archive, suite, checksums, work))
    jdk, jre = select_packages(records, feature)

    jdk_deb = fetch_package(archive, jdk, work)
    jre_deb = fetch_package(archive, jre, work)
    version = jdk["Version"]
    target = install_root / f"ubuntu-openjdk-{re.sub(r'[^A-Za-z0-9._-]', '_', version)}"
    if (target / "bin" / "java").exists():
        return target

    tmp = Path(tempfile.mkdtemp(prefix=".install-", dir=install_root))
    try:
        staging = tmp / "staging"
        staging.mkdir()
        for deb in (jre_deb, jdk_deb):
            extracted = subprocess.run(["dpkg-deb", "-x", str(deb), str(staging)], capture_output=True, text=True, check=False)
            if extracted.returncode != 0:
                raise ArchiveError(f"could not unpack {deb.name}: {extracted.stderr.strip()[-200:]}")
        home = staging / "usr" / "lib" / "jvm" / java_home_name(feature)
        if not home.is_dir():
            raise ArchiveError(f"packages did not provide {java_home_name(feature)}")
        external = rebase_absolute_links(home, staging)
        result = validate_home(home, check_libraries=check_libraries)
        shutil.rmtree(target, ignore_errors=True)
        shutil.move(str(home), str(target))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    manifest_path = install_root / "manifest.json"
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
    manifest[target.name] = {
        "source": "ubuntu-archive",
        "archive": archive,
        "codename": codename,
        "suites": list(selected_suites),
        "release_signers": signers,
        "keyring": str(keyring),
        "feature_version": feature,
        "java_version": version,
        "packages": {
            jdk["Package"]: {"version": jdk["Version"], "sha256": jdk["SHA256"], "suite": jdk["_suite"], "component": jdk["_component"]},
            jre["Package"]: {"version": jre["Version"], "sha256": jre["SHA256"], "suite": jre["_suite"], "component": jre["_component"]},
        },
        "validation": result,
        "host_links_outside_install": external,
    }
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    return target
