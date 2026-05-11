#!/usr/bin/env python3
"""
Generates index.json consumed by the Grimoire app's extension browser.
- pkg / versionCode / versionName: from build.gradle.kts (APK metadata)
- name / lang / baseUrl: from @SourceInfo annotation in Kotlin source
- iconUrl: highest-density mipmap launcher icon copied alongside the APK

Merges over any existing index.json published at INDEX_URL so extensions that
weren't rebuilt this run retain their previous entry.
"""
import json
import os
import re
import shutil
import sys
import urllib.request
from pathlib import Path

REPO_URL = os.environ.get("REPO_URL", "").rstrip("/")
INDEX_URL = os.environ.get("INDEX_URL", "").strip()
ROOT = Path(__file__).parent.parent

# Highest to lowest density — we publish the best available so the app can
# scale down as needed.
ICON_DENSITIES = ("xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi")


def fetch_published() -> list:
    if not INDEX_URL:
        return []
    try:
        with urllib.request.urlopen(INDEX_URL, timeout=15) as r:
            return json.load(r)
    except Exception as e:
        print(f"[info] no published index ({e}); starting fresh")
        return []


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


def find_icon(ext_dir: Path) -> Path | None:
    res = ext_dir / "src" / "main" / "res"
    for density in ICON_DENSITIES:
        icon = res / f"mipmap-{density}" / "ic_launcher.png"
        if icon.exists():
            return icon
    return None


by_pkg = {e["pkg"]: e for e in fetch_published() if "pkg" in e}
errors = 0
updated = 0

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
            # Not rebuilt this run — keep the prior entry if one exists.
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

        icon_src = find_icon(ext_dir)
        if icon_src is not None:
            icon_name = f"{lang}-{ext}.png"
            shutil.copyfile(icon_src, ROOT / "repo" / icon_name)
            entry["icon"] = icon_name
            entry["iconUrl"] = f"{REPO_URL}/{icon_name}"
        else:
            print(f"  [warn] {lang}/{ext}: no launcher icon found")

        by_pkg[gradle["pkg"]] = entry
        updated += 1
        print(f"  [{lang}] {source['name']} ({gradle['pkg']}) v{gradle['versionName']} code={gradle['versionCode']}")

index = sorted(by_pkg.values(), key=lambda e: (e.get("lang", ""), e.get("pkg", "")))
(ROOT / "index.json").write_text(json.dumps(index, indent=2))
print(f"\nindex.json: {len(index)} extension(s) total, {updated} updated this run")

if errors:
    print(f"{errors} error(s) — see above")
    sys.exit(1)
