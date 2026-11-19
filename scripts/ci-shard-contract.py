#!/usr/bin/env python3
"""Compare Windows shard dry-run graphs with the unsplit graph; never run tests."""
import argparse
import copy
import json
from pathlib import Path
import re
import runpy
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
RUNNER = runpy.run_path(str(ROOT / "scripts/ci-shard.py"))
SHARDS = {"agent-unit", "agent-e2e", "agent-isolated", "rest"}
COVERAGE = {":agent:jacocoTestReport", ":agent:jacocoTestCoverageVerification"}
E2E = "com/onurkat/reclazz/e2e/**"
PROFILE = "-Preclazz.testShard="
RUNTIME = ("classDirectories", "classpath", "jvmArgs", "systemProperties", "javaVersion",
           "includePatterns", "excludePatterns", "maxParallelForks", "forkEvery")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def excluded(arguments):
    return {arguments[i + 1] for i, value in enumerate(arguments[:-1]) if value == "-x"}


def validate_table(table):
    require(table.get("schema") == 1, "Unknown shard schema")
    require(set(table["windows"]) == SHARDS, "Missing or unknown Windows shard")
    require(set(table["allowed_missing_tasks"]) == COVERAGE, "Coverage exclusion policy changed")
    require(table["oracle"] == ["build", "buildPlugin", "-Preclazz.skipRabbitBroker=true"],
            "Unsplit oracle command changed")
    for name, arguments in table["windows"].items():
        require(isinstance(arguments, list) and all(isinstance(x, str) for x in arguments),
                f"Invalid command array: {name}")
        require(arguments.count("-Preclazz.skipRabbitBroker=true") == 1, f"Rabbit policy: {name}")
        profiles = [a[len(PROFILE):] for a in arguments if a.startswith(PROFILE)]
        expected = [] if name == "rest" else [name.removeprefix("agent-")]
        require(profiles == expected, f"Unknown or incorrect test profile: {name}")
        require("--tests" not in arguments and "--dry-run" not in arguments,
                f"Test selector or disabled execution in actual shard: {name}")
    require(excluded(table["windows"]["rest"]) == COVERAGE | {":agent:test"},
            "Rest must exclude the agent body and both coverage tasks")


def workflow_rows(text):
    build = text.split("\n  build:\n", 1)[1]
    block = re.search(r"(?ms)^      matrix:\n(.*?)^    runs-on:", build).group(1)
    rows, current = [], None
    for line in block.splitlines():
        if not line.strip() or line.lstrip().startswith("#") or line.strip() == "include:":
            continue
        start = re.fullmatch(r"\s+- os: ([\w-]+)", line)
        if start:
            current = {"os": start.group(1)}
            rows.append(current)
            continue
        value = re.fullmatch(r"\s+(jdk|build|shard): ['\"]?([\w-]+)['\"]?", line)
        require(current is not None and value is not None,
                f"Matrix must use explicit scalar include rows: {line.strip()}")
        require(value.group(1) not in current, "Repeated matrix field")
        current[value.group(1)] = value.group(2)
    return rows


def step(text, name):
    build = text.split("\n  build:\n", 1)[1]
    match = re.search(r"(?ms)^      - name: " + re.escape(name) + r"\n(.*?)(?=^      - |\Z)", build)
    require(match is not None, f"Missing workflow step: {name}")
    return match.group(1)


def validate_workflow(text, table):
    rows = workflow_rows(text)
    windows = [r for r in rows if r["os"] == "windows-latest"]
    linux = [r for r in rows if r["os"] == "ubuntu-latest"]
    require(len(rows) == 7 and len(windows) == 4 and len(linux) == 3, "Matrix job count changed")
    require({r["shard"] for r in windows} == set(table["windows"]), "Windows matrix/table mismatch")
    require(all(r == {"os": "windows-latest", "jdk": "17", "build": "17", "shard": r["shard"]}
                for r in windows), "Windows build/runtime row changed")
    require(sorted((r["jdk"], r["build"], r["shard"]) for r in linux)
            == [("17", "17", "all"), ("21", "21", "all"), ("25", "21", "all")],
            "Linux matrix changed")
    windows_step = step(text, "Build and test on Windows (Linux RabbitMQ broker suite explicitly omitted)")
    require("shell: bash" in windows_step and "python scripts/ci-shard.py ${{ matrix.shard }}" in windows_step,
            "Windows step must use the shared shard runner under Git Bash")
    require("run: ./gradlew build buildPlugin" in step(text, "Build and test, then package the plugin"),
            "Linux default build command changed")
    runtime25 = step(text, "Test agent on JDK 25 with build JDK 21")
    require("run: ./gradlew :agent:unitTest :agent:e2eTest :agent:springSixTest -Preclazz.testJava=25 "
            "-Porg.gradle.java.installations.fromEnv=JAVA_HOME_25_X64" in runtime25,
            "JDK25 scope changed")
    for name in ["Set up independent MCP client", "Install locked test-only MCP SDK",
                 "Independent MCP SDK and live JVM acceptance", "Upload independent client evidence"]:
        block = step(text, name)
        require("matrix.jdk == '17'" in block and "matrix.shard == 'all'" in block
                and "matrix.shard == 'rest'" in block, f"MCP singleton guard: {name}")
    for name in ["Upload independent client evidence", "Upload Test Reports"]:
        require("${{ matrix.shard }}" in step(text, name), f"Artifact name collision: {name}")
    require("matrix.os == 'ubuntu-latest' && matrix.jdk == '17'"
            in step(text, "Verify Plugin (JetBrains Plugin Verifier)"), "Plugin verifier owner changed")
    require("matrix.os == 'ubuntu-latest' && matrix.jdk == '17'"
            in step(text, "Maven plugin and documented consumer acceptance"), "Maven owner changed")


def tests(model):
    return {row["path"]: row["test"] for row in model["tasks"] if row.get("kind") == "Test"}


def validate(table, workflow, models):
    validate_table(table)
    validate_workflow(workflow, table)
    oracle = models["oracle"]
    expected = {row["path"] for row in oracle["tasks"]} - COVERAGE
    oracle_tests = tests(oracle)
    fixture_builds = {path for path in expected if path.startswith(":agent:")
                      and (path.split(":")[-1].startswith("compile")
                           or (path.split(":")[-1].startswith("process") and path.endswith("Resources")))}
    require(set(table["minimum_test_tasks"]) <= set(oracle_tests), "An existing default Test task disappeared")
    require(all(m["skipRabbit"] == "true" for m in models.values()), "Rabbit skip property changed")
    scheduled = {name: {row["path"] for row in models[name]["tasks"]} for name in SHARDS}
    require(expected <= set().union(*scheduled.values()), "A task or non-Test gate was lost")
    for name in ["agent-unit", "agent-e2e"]:
        require(fixture_builds <= scheduled[name], f"Main-test fixture compilation lost: {name}")
    require(not (COVERAGE & set().union(*scheduled.values())), "Windows unexpectedly runs coverage")
    owners = {}
    for name in SHARDS:
        for path in tests(models[name]):
            owners.setdefault(path, []).append(name)
    require(set(owners) == set(oracle_tests), "Test task inventory differs from unsplit command")
    for path, names in owners.items():
        if path == ":agent:test":
            require(set(names) == SHARDS - {"rest"}, "Agent body ownership changed")
        else:
            require(len(names) == 1, f"Duplicate Test ownership: {path}: {names}")
            require(tests(models[names[0]])[path] == oracle_tests[path], f"Isolated Test configuration changed: {path}")
    gates = {row["path"] for row in oracle["tasks"] if row.get("kind") == "JavaExec"}
    gates |= {":checkReleaseNotes", ":checkInternalApiUsage", ":verifyPluginProjectConfiguration"}
    for path in gates:
        require(sum(path in scheduled[name] for name in SHARDS) == 1,
                f"Repeated or missing executable verification gate: {path}")
    baseline = oracle["agentTest"]
    require(baseline["includes"] == [] and baseline["excludes"] == [], "Default main test filter changed")
    for name, model in models.items():
        for key in RUNTIME:
            require(model["agentTest"][key] == baseline[key], f"Agent runtime/input changed: {name}: {key}")
    for name in ["agent-unit", "agent-e2e", "agent-isolated"]:
        value = models[name]["agentTest"]
        require(value["bodyEnabled"] == (name != "agent-isolated"), f"Wrong agent body policy: {name}")
    unit, e2e = models["agent-unit"]["agentTest"], models["agent-e2e"]["agentTest"]
    require((unit["includes"], unit["excludes"]) == ([], [E2E]), "Unit filter is not the complement")
    require((e2e["includes"], e2e["excludes"]) == ([E2E], []), "E2E filter is not the complement")
    require(models["agent-isolated"]["agentTest"]["includes"] == []
            and models["agent-isolated"]["agentTest"]["excludes"] == [], "Isolated body filter changed")


def collect(table, output):
    models = {}
    for name, arguments in [("oracle", table["oracle"]), *table["windows"].items()]:
        report = output / f"{name}.json"
        command = RUNNER["gradle_command"]([*arguments, ":agent:testShardReport", ":testTaskTypes",
            "--dry-run", "--console=plain", "-I", str(ROOT / "scripts/ci-shard-report.gradle"),
            "-Dreclazz.ciReport=" + str(report)])
        with (output / f"{name}.log").open("w", encoding="utf-8") as log:
            result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        require(result.returncode == 0, f"Dry-run failed: {name}; see {output / (name + '.log')}")
        models[name] = json.loads(report.read_text(encoding="utf-8"))
        print(f"DRY_RUN {name}: {len(models[name]['tasks'])} tasks", flush=True)
    return models


def self_test(table, workflow, models):
    def missing_shard(t, m):
        del t["windows"]["agent-unit"]
    def duplicated_jms(t, m):
        m["rest"]["tasks"].append(copy.deepcopy(next(r for r in m["agent-isolated"]["tasks"]
                                                   if r["path"] == ":agent:jmsTest")))
    def duplicated_harness(t, m):
        m["agent-unit"]["tasks"].append(copy.deepcopy(next(r for r in m["rest"]["tasks"]
                                                        if r["path"] == ":integration-test:harnessContractTest")))
    def lost_fixture_compile(t, m):
        m["agent-unit"]["tasks"] = [r for r in m["agent-unit"]["tasks"]
                                     if r["path"] != ":agent:compileSpringSixTestJava"]
    def wrong_filter(t, m):
        m["agent-unit"]["agentTest"]["excludes"] = []
    def lost_coverage_guard(t, m):
        arguments = t["windows"]["rest"]
        index = arguments.index(":agent:jacocoTestReport")
        del arguments[index - 1:index + 1]
    def unknown_profile(t, m):
        t["windows"]["agent-unit"] = [a.replace("testShard=unit", "testShard=unknown")
                                            for a in t["windows"]["agent-unit"]]
    def changed_runtime(t, m):
        m["agent-e2e"]["agentTest"]["systemProperties"]["jdk.attach.allowAttachSelf"] = "false"
    def lost_gate(t, m):
        for name in SHARDS:
            m[name]["tasks"] = [r for r in m[name]["tasks"] if r["path"] != ":integration-test:sapProofTest"]
    for name, mutation in [("missing-shard", missing_shard), ("duplicate-jms", duplicated_jms),
                           ("duplicate-harness", duplicated_harness), ("fixture-compile", lost_fixture_compile),
                           ("wrong-filter", wrong_filter), ("coverage-guard", lost_coverage_guard),
                           ("unknown-profile", unknown_profile), ("runtime-value", changed_runtime),
                           ("lost-non-test-gate", lost_gate)]:
        t, m = copy.deepcopy(table), copy.deepcopy(models)
        mutation(t, m)
        try:
            validate(t, workflow, m)
        except ValueError:
            print(f"MUTATION_REJECTED {name}")
        else:
            raise ValueError(f"Contract accepted mutation: {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "build/ci-shard-contract")
    args = parser.parse_args()
    table = RUNNER["load_table"]()
    workflow = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
    validate_table(table)
    validate_workflow(workflow, table)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    models = collect(table, args.output_dir.resolve())
    validate(table, workflow, models)
    print(f"CI_SHARD_CONTRACT PASS: four Windows partitions; host={sys.platform}; no tests executed")
    if args.self_test:
        self_test(table, workflow, models)
        print("CI_SHARD_SELF_TEST PASS: 9 mutations rejected")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, KeyError, IndexError, AttributeError) as failure:
        print(f"CI_SHARD_CONTRACT FAILED: {failure}", file=sys.stderr)
        raise SystemExit(1)
