#!/usr/bin/env python3
"""
Generates index.json consumed by the Grimoire app's extension browser.
Reads @SourceInfo annotations and build.gradle.kts for metadata.
Run from repo root or via CI with REPO_URL env var set.
"""
import json
import os
import re
from pathlib import Path

REPO_URL = os.environ.get("REPO_URL", "")
ROOT = Path(__file__).parent.parent


def parse_source_info(ext_dir: Path) -> dict | None:
    for kt in ext_dir.rglob("*.kt"):
        content = kt.read_text(encoding="utf-8")
        match = re.search(r"@SourceInfo\s*\((.*?)\)", content, re.DOTALL)
        if not match:
            continue
        ann = match.group(1)

        def get_str(key):
            m = re.search(rf'{key}\s*=\s*"([^"]*)"', ann)
            return m.group(1) if m else None

        def get_int(key):
            m = re.search(rf"{key}\s*=\s*(\d+)", ann)
            return int(m.group(1)) if m else None

        return {
            "name": get_str("name"),
            "lang": get_str("lang"),
            "baseUrl": get_str("baseUrl"),
            "versionCode": get_int("versionCode"),
        }
    return None


def parse_version_name(build_gradle: Path) -> str:
    m = re.search(r'versionName\s*=\s*"([^"]+)"', build_gradle.read_text())
    return m.group(1) if m else "1.0.0"


index = []

for lang_dir in sorted((ROOT / "src").iterdir()):
    if not lang_dir.is_dir():
        continue
    for ext_dir in sorted(lang_dir.iterdir()):
        build_gradle = ext_dir / "build.gradle.kts"
        if not ext_dir.is_dir() or not build_gradle.exists():
            continue

        info = parse_source_info(ext_dir)
        if not info:
            print(f"WARNING: no @SourceInfo found in {ext_dir}")
            continue

        lang = lang_dir.name
        name = ext_dir.name
        apk_name = f"{lang}-{name}.apk"

        index.append({
            "name": info["name"],
            "pkg": f"io.grimoire.extension.{lang}.{name}",
            "lang": info["lang"] or lang,
            "baseUrl": info["baseUrl"],
            "versionCode": info["versionCode"] or 1,
            "versionName": parse_version_name(build_gradle),
            "apk": apk_name,
            "url": f"{REPO_URL}/{apk_name}",
        })

(ROOT / "index.json").write_text(json.dumps(index, indent=2))
print(f"Generated index.json with {len(index)} extension(s)")
