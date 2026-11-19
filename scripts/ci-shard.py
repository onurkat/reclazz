#!/usr/bin/env python3
"""Run one declared Windows shard; argument arrays never pass through a shell."""
import argparse
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TABLE = ROOT / "scripts/ci-shards.json"


def load_table(path=TABLE):
    with Path(path).open(encoding="utf-8") as source:
        return json.load(source)


def gradle_command(arguments):
    launcher = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    return [str(launcher), *arguments]


def shard_arguments(name, table):
    if name not in table["windows"]:
        raise ValueError(f"Unknown Windows shard: {name}")
    arguments = table["windows"][name]
    if not isinstance(arguments, list) or not all(isinstance(a, str) for a in arguments):
        raise ValueError(f"Invalid argument array for shard: {name}")
    return arguments


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("shard")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--print-command", action="store_true")
    args = parser.parse_args()
    command = gradle_command(shard_arguments(args.shard, load_table()))
    if args.dry_run:
        command.append("--dry-run")
    if args.print_command:
        print(json.dumps(command))
        return 0
    return subprocess.run(command, cwd=ROOT).returncode


if __name__ == "__main__":
    raise SystemExit(main())
