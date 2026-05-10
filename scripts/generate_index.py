#!/usr/bin/env python3
"""
Generates index.json consumed by the Grimoire app's extension browser.
- pkg / versionCode / versionName: from build.gradle.kts (APK metadata)
- name / lang / baseUrl: from @SourceInfo annotation in Kotlin source
"""
import json
import os
import re
import sys
from pathlib import Path

REPO_URL = os.environ.get("REPO_URL", "").rstrip("/")
ROOT = Path(__file__).parent.parent


def parse_gradle(build_gradle: Path) -> dict:
    text = build_gradle.read_text(encoding="utf-8")

    pkg_m = re.search(r'applicationId\s*=\s*"([^"]+)"', text)
    vc_m = re.search(r'versionCode\s*=\s*(\d+)', text)
    vn_m = re.search(r'versionName\s*=\s*"([^"]+)"', text)

    if not (pkg_m and vc_m and vn_m):
        raise ValueError(f"Missing applicationId/versionCode/versionName in {build_gradle}")

    return {
        "pkg": pkg_m.group(1),
        "versionCode": int(vc_m.group(1)),
        "versionName": vn_m.group(1),
    }


def parse_source_info(ext_dir: Path) -> dict:
    for kt in ext_dir.rglob("*.kt"):
        content = kt.read_text(encoding="utf-8")
        m = re.search(r"@SourceInfo\s*\((.*?)\)", content, re.DOTALL)
        if not m:
            continue
        ann = m.group(1)

        def get_str(key):
            sm = re.search(rf'{key}\s*=\s*"([^"]*)"', ann)
            return sm.group(1) if sm else None

        return {
            "name": get_str("name"),
            "lang": get_str("lang"),
            "baseUrl": get_str("baseUrl"),
        }
    raise ValueError(f"No @SourceInfo annotation found under {ext_dir}")


index = []
errors = 0

for lang_dir in sorted((ROOT / "src").iterdir()):
    if not lang_dir.is_dir():
        continue
    for ext_dir in sorted(lang_dir.iterdir()):
        build_gradle = ext_dir / "build.gradle.kts"
        if not ext_dir.is_dir() or not build_gradle.exists():
            continue

        lang = lang_dir.name
        ext = ext_dir.name
        apk_name = f"{lang}-{ext}.apk"
        apk_path = ROOT / "repo" / apk_name

        if not apk_path.exists():
            print(f"[WARN] APK missing for {lang}/{ext}, skipping")
            continue

        try:
            gradle = parse_gradle(build_gradle)
            source = parse_source_info(ext_dir)
        except Exception as e:
            print(f"[ERROR] {lang}/{ext}: {e}")
            errors += 1
            continue

        entry = {
            "name": source["name"],
            "pkg": gradle["pkg"],
            "lang": source["lang"] or lang,
            "baseUrl": source["baseUrl"] or "",
            "versionCode": gradle["versionCode"],
            "versionName": gradle["versionName"],
            "apk": apk_name,
            "url": f"{REPO_URL}/{apk_name}",
        }
        index.append(entry)
        print(f"  [{lang}] {source['name']} ({gradle['pkg']}) v{gradle['versionName']} code={gradle['versionCode']}")

(ROOT / "index.json").write_text(json.dumps(index, indent=2))
print(f"\nindex.json: {len(index)} extension(s)")

if errors:
    print(f"{errors} error(s) — see above")
    sys.exit(1)
