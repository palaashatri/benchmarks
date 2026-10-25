#!/usr/bin/env python3
"""Fetch OpenJDK HotSpot builds for a JDK matrix, verified before anything is unpacked.

Packages are resolved through the Foojay Disco API. A download is accepted only when its
SHA-256 matches the checksum the API publishes, and only archives from an allow-listed
OpenJDK HotSpot distribution are considered. Archives are extracted with tarfile's data
filter, which rejects absolute paths, `..` components and links that leave the target.
"""
from __future__ import annotations

import hashlib
import json
import platform
import shutil
import tarfile
import tempfile
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

DEFAULT_API = "https://api.foojay.io/disco/v3.0"
# Only OpenJDK HotSpot builds are in scope; other VMs and vendor-private builds are refused.
ALLOWED_DISTRIBUTIONS = ("temurin", "zulu", "corretto", "microsoft", "liberica")
TIMEOUT_SECONDS = 60
USER_AGENT = "openjdk-benchmark-suite/1.0 (+jdk-fetch)"


class FetchError(RuntimeError):
    pass


def _get_json(url: str) -> dict[str, Any]:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            payload = json.load(response)
    except (OSError, ValueError) as exception:
        raise FetchError(f"could not read {url}: {exception}") from exception
    if not isinstance(payload, dict):
        raise FetchError(f"unexpected response shape from {url}")
    return payload


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _architecture() -> str:
    machine = platform.machine().lower()
    return {"x86_64": "x64", "amd64": "x64", "aarch64": "aarch64", "arm64": "aarch64"}.get(machine, machine)


def _operating_system() -> str:
    return {"linux": "linux", "darwin": "macos", "windows": "windows"}.get(platform.system().lower(), platform.system().lower())


def _allowed_download(url: str) -> bool:
    # HTTPS only. Plain HTTP is accepted solely for loopback, so tests can serve fixtures.
    parsed = urllib.parse.urlparse(url)
    return parsed.scheme == "https" or (parsed.scheme == "http" and parsed.hostname in ("127.0.0.1", "localhost"))


def resolve_package(feature: int, distribution: str, api_base: str) -> dict[str, Any]:
    """The newest GA package for a feature release from an allowed distribution."""
    if distribution not in ALLOWED_DISTRIBUTIONS:
        raise FetchError(f"distribution {distribution!r} is not an allowed OpenJDK HotSpot source")
    query = urllib.parse.urlencode({
        "version": str(feature),
        "distribution": distribution,
        "architecture": _architecture(),
        "operating_system": _operating_system(),
        "archive_type": "tar.gz",
        "package_type": "jdk",
        "latest": "available",
        "release_status": "ga",
    })
    listing = _get_json(f"{api_base}/packages/jdks?{query}")
    candidates = [item for item in listing.get("result", []) if isinstance(item, dict)
                  and str(item.get("java_version", "")).split(".")[0] == str(feature)
                  and item.get("distribution") == distribution]
    if not candidates:
        raise FetchError(f"no {distribution} JDK {feature} package is published for this platform")
    chosen = max(candidates, key=lambda item: str(item.get("java_version", "")))
    detail = _get_json(f"{api_base}/ids/{urllib.parse.quote(str(chosen.get('id', '')))}")
    results = detail.get("result")
    if not isinstance(results, list) or not results or not isinstance(results[0], dict):
        raise FetchError(f"package {chosen.get('id')} has no download details")
    info = results[0]
    checksum = info.get("checksum")
    checksum_type = str(info.get("checksum_type", "")).lower()
    download = info.get("direct_download_uri")
    if checksum_type != "sha256" or not isinstance(checksum, str) or len(checksum) != 64:
        raise FetchError(f"package {chosen.get('id')} does not publish a SHA-256 checksum; refusing to install it")
    if not isinstance(download, str) or not _allowed_download(download):
        raise FetchError(f"package {chosen.get('id')} has no HTTPS download location")
    return {
        "id": chosen.get("id"),
        "distribution": distribution,
        "java_version": chosen.get("java_version"),
        "download_url": download,
        "sha256": checksum.lower(),
        "filename": info.get("filename") or Path(urllib.parse.urlparse(download).path).name,
    }


def download_verified(url: str, destination: Path, expected_sha256: str) -> Path:
    """Download to a partial file and move it into place only if the SHA-256 matches."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(destination.name + ".partial")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response, partial.open("wb") as handle:
            shutil.copyfileobj(response, handle, 1 << 20)
    except OSError as exception:
        partial.unlink(missing_ok=True)
        raise FetchError(f"download failed: {url}: {exception}") from exception
    actual = sha256_file(partial)
    if actual != expected_sha256:
        partial.unlink(missing_ok=True)
        raise FetchError(f"SHA-256 mismatch for {url}: expected {expected_sha256}, got {actual}")
    partial.replace(destination)
    return destination


def extract_archive(archive: Path, target: Path) -> Path:
    """Unpack a JDK archive whose single top-level directory becomes `target`."""
    staging = Path(tempfile.mkdtemp(prefix="jdk-extract-", dir=str(target.parent)))
    try:
        with tarfile.open(archive, "r:gz") as tar:
            tar.extractall(staging, filter="data")
        tops = [item for item in staging.iterdir()]
        if len(tops) != 1 or not tops[0].is_dir():
            raise FetchError("JDK archive must contain exactly one top-level directory")
        if target.exists():
            shutil.rmtree(target)
        tops[0].rename(target)
    except tarfile.TarError as exception:
        raise FetchError(f"archive rejected: {exception}") from exception
    finally:
        shutil.rmtree(staging, ignore_errors=True)
    if not (target / "bin" / "java").exists():
        raise FetchError(f"extracted JDK has no bin/java: {target}")
    return target


def install_jdk(feature: int, distribution: str, destination_root: Path, api_base: str = DEFAULT_API) -> Path:
    package = resolve_package(feature, distribution, api_base)
    install_name = f"{distribution}-{str(package['java_version']).replace('+', '_')}"
    target = destination_root / install_name
    if (target / "bin" / "java").exists():
        return target
    destination_root.mkdir(parents=True, exist_ok=True)
    archive = destination_root / "downloads" / package["filename"]
    download_verified(package["download_url"], archive, package["sha256"])
    extract_archive(archive, target)
    manifest_path = destination_root / "manifest.json"
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
    manifest[install_name] = {
        "package_id": package["id"],
        "java_version": package["java_version"],
        "distribution": distribution,
        "sha256": package["sha256"],
        "download_url": package["download_url"],
    }
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    return target
