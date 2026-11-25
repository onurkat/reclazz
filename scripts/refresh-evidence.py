#!/usr/bin/env python3
"""Regenerate feature evidence, or print computed source fingerprints for one registry."""
import argparse
import json
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
        count, changed = refresh(ROOT)
        print(f"{count} claims checked, {changed} files refreshed")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        sys.exit(str(error))
