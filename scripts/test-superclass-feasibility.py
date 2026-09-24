#!/usr/bin/env python3
# Copyright 2026 Onur Kat
# SPDX-License-Identifier: Apache-2.0
"""Reproduce the bounded superclass probe; no Reclazz production agent or downloads.

Requires Python 3.9+ and explicit full JDK homes (17+). Output directory must not exist.
Each JDK compiles its own original/replacement classes and runs one isolated JVM.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


SOURCE = Path(__file__).resolve().parent / "probes" / "superclass"


def digest(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def run(command, folder, label, record):
    command = list(map(str, command))
    entry = {"command": command, "log": str(folder / (label + ".log"))}
    record["commands"].append(entry)
    with Path(entry["log"]).open("w", encoding="utf-8") as log:
        result = subprocess.run(command, cwd=folder, stdout=log, stderr=subprocess.STDOUT, timeout=60)
    entry["exitCode"] = result.returncode
    if result.returncode != 0:
        raise RuntimeError(f"{label} failed, exit={result.returncode}; see {entry['log']}")
    return Path(entry["log"]).read_text(encoding="utf-8")


def probe(java_home, folder, record):
    suffix = ".exe" if os.name == "nt" else ""
    tools = {name: java_home / "bin" / (name + suffix) for name in ["java", "javac", "jar"]}
    for name, file in tools.items():
        if not file.is_file():
            raise RuntimeError(f"Missing {name}: {file}; supply a full JDK home")
    source = folder / "src"
    shutil.copytree(SOURCE, source)
    record["sourceSha256"] = {str(file.relative_to(source)): digest(file)
                              for file in sorted(source.rglob("*")) if file.is_file()}
    original, replacement = folder / "original", folder / "replacement"
    original.mkdir()
    replacement.mkdir()
    record["javaVersion"] = run([tools["java"], "-version"], folder, "java-version", record).strip()
    run([tools["javac"], "-d", original, *sorted(source.glob("*.java"))], folder, "compile-original", record)
    run([tools["javac"], "-cp", original, "-d", replacement, source / "v2/C.java"],
        folder, "compile-replacement", record)
    agent = folder / "probe-agent.jar"
    run([tools["jar"], "cfm", agent, source / "MANIFEST.MF", "-C", original, "ProbeAgent.class"],
        folder, "package-agent", record)
    record["classSha256"] = {"originalC": digest(original / "C.class"),
                             "replacementC": digest(replacement / "C.class"),
                             "newBehavior": digest(original / "NewBehavior.class"), "probeAgent": digest(agent)}
    if record["classSha256"]["originalC"] == record["classSha256"]["replacementC"]:
        raise RuntimeError("Replacement class bytes must differ")
    output = run([tools["java"], f"-javaagent:{agent}", "-cp", original, "Probe",
                  original / "C.class", replacement / "C.class", original / "NewBehavior.class"],
                 folder, "probe", record)
    values = dict(line.removeprefix("RESULT ").split("=", 1)
                  for line in output.splitlines() if line.startswith("RESULT "))
    if values.get("passed") != "true":
        raise RuntimeError("Probe did not emit a successful terminal result")
    record["observations"] = values
    record["passed"] = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, action="append", required=True,
                        help="Explicit full JDK home; repeat for each runtime to measure")
    parser.add_argument("--work-dir", type=Path, required=True, help="Fresh output path (must not exist)")
    args = parser.parse_args()
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=False)  # Never mix a new run with old evidence.
    report = {"kind": "bounded-superclass-research", "passed": False,
              "planned": len(args.java_home), "runs": []}
    try:
        for index, home in enumerate(args.java_home, 1):
            folder = work / f"jdk-{index}"
            folder.mkdir()
            record = {"javaHome": str(home.resolve()), "passed": False, "commands": []}
            report["runs"].append(record)
            probe(home.resolve(), folder, record)
        report["passed"] = True
    except Exception as error:
        report["error"] = str(error)
        raise
    finally:
        (work / "evidence.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"Evidence: {work / 'evidence.json'}")


if __name__ == "__main__":
    main()
