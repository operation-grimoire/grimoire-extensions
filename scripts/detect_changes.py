#!/usr/bin/env python3
"""
Detect extensions whose versionCode is ahead of the published index.json,
and emit a chunked matrix for parallel builds in CI.

Outputs (GITHUB_OUTPUT, when set):
  has_changes   "true" | "false"
  changed_count integer
  matrix        JSON object {"include": [{"chunk": "0", "modules": ":a :b"}, ...]}
"""
import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).parent.parent
INDEX_URL = os.environ.get("INDEX_URL", "").strip()
CHUNK_SIZE = max(1, int(os.environ.get("CHUNK_SIZE", "4")))
BEFORE_SHA = os.environ.get("BEFORE_SHA", "").strip()
AFTER_SHA = os.environ.get("AFTER_SHA", "").strip() or "HEAD"
FORCE_ALL = os.environ.get("FORCE_ALL", "").strip().lower() == "true"
ZERO_SHA = "0" * 40


def fetch_published() -> dict:
    """Map applicationId -> entry from the live index.json. Empty on first run."""
    if not INDEX_URL:
        return {}
    try:
        with urllib.request.urlopen(INDEX_URL, timeout=15) as r:
            data = json.load(r)
    except Exception as e:
        print(f"[info] no published index ({e}); treating all extensions as new", file=sys.stderr)
        return {}
    return {e["pkg"]: e for e in data if "pkg" in e}


def lib_changed() -> bool:
    """True if any file under lib/ changed between BEFORE_SHA and AFTER_SHA."""
    if not BEFORE_SHA or BEFORE_SHA == ZERO_SHA:
        return False
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only", f"{BEFORE_SHA}..{AFTER_SHA}", "--", "lib/"],
            cwd=ROOT, capture_output=True, text=True, check=True,
        )
    except (subprocess.CalledProcessError, FileNotFoundError) as e:
        print(f"[warn] could not diff lib/ ({e}); skipping lib-change detection", file=sys.stderr)
        return False
    files = [l for l in result.stdout.splitlines() if l.strip()]
    if files:
        print(f"[info] lib/ changed in {BEFORE_SHA[:7]}..{AFTER_SHA[:7]}:", file=sys.stderr)
        for f in files:
            print(f"  {f}", file=sys.stderr)
        return True
    return False


def parse_gradle(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    pkg = re.search(r'applicationId\s*=\s*"([^"]+)"', text)
    vc = re.search(r"versionCode\s*=\s*(\d+)", text)
    vn = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not (pkg and vc and vn):
        raise ValueError(f"missing applicationId/versionCode/versionName in {path}")
    return {"pkg": pkg.group(1), "versionCode": int(vc.group(1)), "versionName": vn.group(1)}


def main() -> int:
    published = fetch_published()
    force = FORCE_ALL or lib_changed()
    if FORCE_ALL:
        print("[info] FORCE_ALL=true; rebuilding every extension", file=sys.stderr)
    changed = []

    for lang_dir in sorted((ROOT / "src").iterdir()):
        if not lang_dir.is_dir():
            continue
        for ext_dir in sorted(lang_dir.iterdir()):
            gradle = ext_dir / "build.gradle.kts"
            if not gradle.exists():
                continue
            info = parse_gradle(gradle)
            old = published.get(info["pkg"])
            old_vc = old["versionCode"] if old else None
            bumped = old_vc is None or info["versionCode"] > old_vc
            if bumped or force:
                changed.append({
                    "module": f"{lang_dir.name}-{ext_dir.name}",
                    "previousVersionCode": old_vc,
                    "versionCode": info["versionCode"],
                    "reason": "version-bump" if bumped else "lib-change",
                })

    chunks = [changed[i:i + CHUNK_SIZE] for i in range(0, len(changed), CHUNK_SIZE)]
    matrix = {
        "include": [
            {
                "chunk": str(i),
                "modules": " ".join(f":{m['module']}" for m in chunk),
            }
            for i, chunk in enumerate(chunks)
        ]
    }

    print(f"changed extensions: {len(changed)}", file=sys.stderr)
    for c in changed:
        prev = "new" if c["previousVersionCode"] is None else c["previousVersionCode"]
        print(f"  {c['module']}: {prev} -> {c['versionCode']} ({c['reason']})", file=sys.stderr)
    print(f"chunks of size {CHUNK_SIZE}: {len(chunks)}", file=sys.stderr)

    out_path = os.environ.get("GITHUB_OUTPUT")
    payload = {
        "has_changes": "true" if changed else "false",
        "changed_count": str(len(changed)),
        "matrix": json.dumps(matrix),
    }
    if out_path:
        with open(out_path, "a") as f:
            for k, v in payload.items():
                f.write(f"{k}={v}\n")
    else:
        print(json.dumps(payload, indent=2))

    return 0


if __name__ == "__main__":
    sys.exit(main())
