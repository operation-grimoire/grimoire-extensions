#!/usr/bin/env python3
"""
Detect which extensions a pull request touches, for the dry-run PR build.

Diffs the PR branch against its base (three-dot, i.e. merge-base..head):
  - a change to any shared build input (lib/, gradle config) builds every
    extension, since it can break extensions the PR never touched
  - otherwise only the extensions whose src/<lang>/<name>/ files changed
    are built

Outputs (GITHUB_OUTPUT, when set):
  has_changes  "true" | "false"
  modules      space-separated Gradle paths, e.g. ":en-novelfull :en-novgo"
"""
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).parent.parent
BASE_SHA = os.environ.get("BASE_SHA", "").strip()
HEAD_SHA = os.environ.get("HEAD_SHA", "").strip() or "HEAD"

# A change to any of these can break extensions the PR never touched, so we
# fall back to building every extension.
SHARED_BUILD_PATHS = (
    "lib/",
    "gradle/",
    "build.gradle.kts",
    "settings.gradle.kts",
)


def all_modules() -> list:
    """Every extension Gradle path, e.g. [":en-novelfull", ":all-zlibrary"]."""
    mods = []
    for lang_dir in sorted((ROOT / "src").iterdir()):
        if not lang_dir.is_dir():
            continue
        for ext_dir in sorted(lang_dir.iterdir()):
            if (ext_dir / "build.gradle.kts").exists():
                mods.append(f":{lang_dir.name}-{ext_dir.name}")
    return mods


def changed_files():
    """PR-introduced file paths, or None when the diff can't be computed."""
    if not BASE_SHA:
        print("[warn] no BASE_SHA; building every extension", file=sys.stderr)
        return None
    result = subprocess.run(
        ["git", "diff", "--name-only", f"{BASE_SHA}...{HEAD_SHA}"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    )
    return [line for line in result.stdout.splitlines() if line.strip()]


def main() -> int:
    files = changed_files()

    if files is None or any(
        f.startswith(p) for f in files for p in SHARED_BUILD_PATHS
    ):
        modules = all_modules()
        print("shared build input changed — building every extension", file=sys.stderr)
    else:
        seen = []
        for f in files:
            parts = f.split("/")
            if len(parts) >= 4 and parts[0] == "src":
                lang, name = parts[1], parts[2]
                mod = f":{lang}-{name}"
                gradle = ROOT / "src" / lang / name / "build.gradle.kts"
                # Skip extensions deleted by the PR — they're gone from settings.gradle.kts.
                if mod not in seen and gradle.exists():
                    seen.append(mod)
        modules = sorted(seen)

    if modules:
        for m in modules:
            print(f"  will build {m}", file=sys.stderr)
    else:
        print("no extension changes detected", file=sys.stderr)

    payload = {
        "has_changes": "true" if modules else "false",
        "modules": " ".join(modules),
    }
    out_path = os.environ.get("GITHUB_OUTPUT")
    if out_path:
        with open(out_path, "a") as fh:
            for k, v in payload.items():
                fh.write(f"{k}={v}\n")
    else:
        for k, v in payload.items():
            print(f"{k}={v}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
