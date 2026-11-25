#!/usr/bin/env python3
"""Regenerate feature evidence, or print computed source fingerprints for one registry."""
import argparse
import json
import os
import subprocess
from pathlib import Path
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from website.evidence_registry import refresh, snapshot


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path)
    args = parser.parse_args()
    if args.snapshot:
        claims = snapshot(ROOT, json.loads(args.snapshot.read_bytes()))
        print(json.dumps({c["id"]: {"surfaces": c["shipped_surfaces"], "tests": c["tests"]} for c in claims}))
    else:
        docs_python = os.environ.get("RECLAZZ_DOCS_PYTHON")
        if not docs_python:
            venv = ROOT / ".venv-docs" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
            if venv.is_file():
                docs_python = str(venv)
        if docs_python and not os.environ.get("_RECLAZZ_DOCS_REEXEC"):
            env = dict(os.environ, _RECLAZZ_DOCS_REEXEC="1")
            try:
                result = subprocess.run([docs_python, "-B", __file__], env=env)
            except OSError:
                sys.exit("docs Python with markdown-it-py required (set RECLAZZ_DOCS_PYTHON)")
            sys.exit(result.returncode)
        try:
            import markdown_it
        except ImportError:
            sys.exit("docs Python with markdown-it-py required (set RECLAZZ_DOCS_PYTHON)")
        from website.check import refreshed_usage_anchors
        anchors = ROOT / "website/usage-anchors.json"
        count, changed = refresh(ROOT, extra_outputs={anchors: refreshed_usage_anchors(ROOT)})
        print(f"{count} claims checked, {changed} files refreshed")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        sys.exit(str(error))
