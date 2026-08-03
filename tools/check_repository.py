#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN_PATH_PARTS = {"build", "target", ".gradle", "results"}
FORBIDDEN_SUFFIXES = {".class", ".jar", ".log", ".pid", ".jfr", ".hprof"}
FORBIDDEN_PUBLIC_TERMS = (
    "Azul Platform" + " Prime",
    "Optimizer" + " Hub",
    "Cloud Native" + " Compiler",
    "Compilation" + " Streaming",
    "Graal" + "VM",
    "Open" + "J9",
    "C" + "RaC",
)
TEXT_SUFFIXES = {".md", ".java", ".kt", ".kts", ".py", ".sh", ".yml", ".yaml", ".json", ".xml", ".proto"}


def tracked_files() -> list[Path]:
    result = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, check=True)
    return [Path(item.decode()) for item in result.stdout.split(b"\0") if item]


def main() -> int:
    failures: list[str] = []
    files = tracked_files()
    for rel in files:
        parts = set(rel.parts)
        if parts & FORBIDDEN_PATH_PARTS or rel.suffix.lower() in FORBIDDEN_SUFFIXES or rel.name == ".DS_Store":
            failures.append(f"generated artifact is tracked: {rel}")
        if rel.name in {"CLAUDE.md", "STATUS.md"}:
            failures.append(f"duplicate truth document is tracked: {rel}")
        path = ROOT / rel
        if path.suffix.lower() in TEXT_SUFFIXES and path.is_file():
            text = path.read_text(errors="replace")
            for term in FORBIDDEN_PUBLIC_TERMS:
                if term.lower() in text.lower():
                    failures.append(f"out-of-scope public term {term!r} found in {rel}")
    for manifest in (ROOT / "experiments").glob("*.yaml"):
        try:
            data = json.loads(manifest.read_text())
            if data.get("schema_version") != "1.0.0":
                failures.append(f"invalid schema version in {manifest.relative_to(ROOT)}")
        except Exception as exc:
            failures.append(f"invalid experiment {manifest.relative_to(ROOT)}: {exc}")
    if failures:
        for failure in failures:
            print(f"ERROR {failure}")
        return 1
    print(f"repository hygiene passed ({len(files)} tracked files checked)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
