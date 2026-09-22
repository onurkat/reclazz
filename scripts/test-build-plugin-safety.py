#!/usr/bin/env python3
# Copyright 2026 Onur Kat
# SPDX-License-Identifier: Apache-2.0
"""Offline, real-build/real-agent acceptance. No publishing or global repository writes.

Build :gradle-plugin:jar :agent:shadowJar :mcp-server:shadowJar and package
maven-plugin first. Requires Java/Javac 17+, Gradle 8.10.2 and Maven 3.9.x.
All logs/fixtures/evidence remain in the required fresh --work-dir.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import uuid
from xml.sax.saxutils import escape


def require(ok, message):
    if not ok:
        raise AssertionError(message)


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def run(command, cwd, log, success=True):
    with log.open("w") as out:
        process = subprocess.Popen(list(map(str, command)), cwd=cwd, stdout=out,
                                   stderr=subprocess.STDOUT)
        try:
            code = process.wait(timeout=240)
        except BaseException:
            process.kill()
            process.wait(timeout=10)
            raise
    require((code == 0) == success, f"exit={code}, expected success={success}; see {log}")
    return code


def await_text(log, text):
    until = time.monotonic() + 30
    while time.monotonic() < until:
        if log.exists() and text in log.read_text(errors="replace"):
            return
        time.sleep(.05)
    raise AssertionError(f"Missing {text}; see {log}")


def query(port, operation, tail=""):
    token = str(uuid.uuid4())
    with socket.create_connection(("127.0.0.1", port), timeout=5) as sock:
        with sock.makefile("r", encoding="utf-8") as stream:
            hello = json.loads(stream.readline(16385))
            require(hello.get("level") == "CONNECTED", f"Invalid handshake {hello}")
            sock.sendall(f"{operation} {token}{tail}\n".encode())
            prefix = f"{operation}_RESULT {token} "
            until = time.monotonic() + 5
            while time.monotonic() < until:
                sock.settimeout(max(.001, until - time.monotonic()))
                line = stream.readline(16385)
                require(line and len(line) <= 16384, "Missing/bounded agent response")
                event = json.loads(line)
                message = event.get("message", "")
                if event.get("level") == "INFO" and message.startswith(prefix):
                    data = json.loads(message[len(prefix):])
                    require(data["requestId"] == token, "Uncorrelated receipt")
                    return data
    raise AssertionError("Agent response deadline exceeded")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def mirror_maven_cache(source, target):
    # Symlink individual cached files, not directories: our plugin/artifacts are
    # overlaid locally and Maven never writes through a directory into ~/.m2.
    for base, dirs, files in os.walk(source):
        relative = Path(base).relative_to(source)
        directory = target / relative
        directory.mkdir(parents=True, exist_ok=True)
        for name in files:
            (directory / name).symlink_to(Path(base) / name)


def overlay(path, source=None, text=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink() or path.exists():
        path.unlink()
    if source:
        shutil.copyfile(source, path)
    else:
        path.write_text(text, encoding="utf-8")


def prepare_maven(args, repository):
    mirror_maven_cache(args.maven_cache, repository)
    group = repository / "com/onurkat/reclazz"
    plugin = group / "reclazz-maven-plugin" / args.version
    overlay(plugin / f"reclazz-maven-plugin-{args.version}.jar", args.maven_plugin)
    overlay(plugin / f"reclazz-maven-plugin-{args.version}.pom", args.maven_pom)
    agent = group / "reclazz-agent" / args.version
    overlay(agent / f"reclazz-agent-{args.version}.jar", args.agent)
    overlay(agent / f"reclazz-agent-{args.version}.pom", text=f"""<project>
      <modelVersion>4.0.0</modelVersion><groupId>com.onurkat.reclazz</groupId>
      <artifactId>reclazz-agent</artifactId><version>{args.version}</version></project>""")


def sources(root, value, broken=False):
    write(root / "a/src/main/java/A.java", f"public class A {{ public int value() {{ return {value}; }} }}")
    write(root / "b/src/main/java/B.java", "public class B { public int value() { return "
          + ("missingSymbol" if broken else str(value)) + "; } }")


def exercise(args, tool):
    root = args.work_dir / f"{tool} project with spaces"
    root.mkdir(parents=True)
    sources(root, 1)
    if tool == "gradle":
        write(root / "settings.gradle", "rootProject.name='safety'; include 'a', 'b'\n")
        # json quoting is also valid for these Groovy string values; preserve paths.
        quote = json.dumps
        write(root / "build.gradle", f"""
        buildscript {{ dependencies {{ classpath files({quote(str(args.gradle_plugin))}) }} }}
        apply plugin: 'com.onurkat.reclazz'
        subprojects {{ apply plugin: 'java' }}
        project(':b') {{ tasks.named('compileJava') {{ dependsOn ':a:compileJava'; doFirst {{ Thread.sleep(1500) }} }} }}
        tasks.named('reclazzSafeBuild') {{
          mcpJar = file({quote(str(args.mcp))})
          port = providers.gradleProperty('agentPort').map {{ it.toInteger() }}
          owner = providers.gradleProperty('buildOwner')
          buildArguments = ['--offline', '--console=plain', ':b:compileJava']
        }}
        """)
        ordinary = [args.gradle, "--offline", "--no-daemon", "--console=plain", ":b:compileJava"]
        outputs = [root / module / "build/classes/java/main" for module in ("a", "b")]
        def safe(port, owner):
            return [args.gradle, "--offline", "--no-daemon", "--console=plain", "reclazzSafeBuild",
                    f"-PagentPort={port}", f"-PbuildOwner={owner}"]
    else:
        repository = args.work_dir / "maven-repository"
        prepare_maven(args, repository)
        child_args = ["--offline", "--batch-mode", f"-Dmaven.repo.local={repository}", "compile"]
        xml_args = "".join(f"<argument>{escape(str(a))}</argument>" for a in child_args)
        write(root / "pom.xml", f"""<project><modelVersion>4.0.0</modelVersion>
          <groupId>safety</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging>
          <modules><module>a</module><module>b</module></modules>
          <properties><maven.compiler.release>17</maven.compiler.release></properties>
          <build><plugins>
          <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
          <version>3.13.0</version></plugin>
          <plugin><groupId>com.onurkat.reclazz</groupId><artifactId>reclazz-maven-plugin</artifactId>
          <version>{args.version}</version><inherited>false</inherited><configuration>
          <mcpJar>{escape(str(args.mcp))}</mcpJar><mavenExecutable>{escape(str(args.maven))}</mavenExecutable>
          <buildArguments>{xml_args}</buildArguments></configuration></plugin>
          </plugins></build></project>""")
        for module in ("a", "b"):
            write(root / module / "pom.xml", f"""<project><modelVersion>4.0.0</modelVersion>
              <parent><groupId>safety</groupId><artifactId>root</artifactId><version>1</version></parent>
              <artifactId>{module}</artifactId></project>""")
        ordinary = [args.maven, *child_args]
        outputs = [root / module / "target/classes" for module in ("a", "b")]
        def safe(port, owner):
            return [args.maven, "--offline", "--batch-mode", f"-Dmaven.repo.local={repository}",
                    f"-Dreclazz.port={port}", f"-Dreclazz.owner={owner}",
                    f"com.onurkat.reclazz:reclazz-maven-plugin:{args.version}:safe-build"]
    # A normal compile has no agent and must remain unchanged / opted out.
    run(ordinary, root, root / "01-baseline.log")
    class_a, class_b = outputs[0] / "A.class", outputs[1] / "B.class"
    before = sha(class_a)
    app_dir = root / "app"
    write(app_dir / "App.java", """public class App {
        public static void main(String[] args) throws Exception {
            A a = new A(); B b = new B();
            while (true) { System.out.println("VALUES=" + a.value() + ":" + b.value()); Thread.sleep(25); }
        }
    }""")
    cp = os.pathsep.join(map(str, [*outputs, app_dir]))
    run([args.javac, "-cp", cp, "-d", app_dir, app_dir / "App.java"], root, root / "02-app-compile.log")
    port_file, app_log = root / "agent.port", root / "app.log"
    with app_log.open("w") as log:
        app = subprocess.Popen([str(args.java), f"-javaagent:{args.agent}=watchDirs={';'.join(map(str,outputs))},"
                                f"startupDelaySec=1,debounceMs=100,portFile={port_file}", "-cp", cp, "App"],
                               cwd=root, stdout=log, stderr=subprocess.STDOUT)
        try:
            await_text(app_log, "VALUES=1:1")
            await_text(app_log, "] Watching ")
            port = int(port_file.read_text().strip())
            doctor = query(port, "DOCTOR")
            require(doctor["buildHold"] == "none", "Initial build hold")
            session = doctor["sessionId"]
            sources(root, 2, broken=True)
            run(safe(port, "fixture-owner"), root, root / "03-partial-failure.log", success=False)
            require(sha(class_a) != before, "First module must really compile before second fails")
            require("missingSymbol" in (root / "03-partial-failure.log").read_text(), "Expected actual compiler error")
            time.sleep(1.5)
            require("VALUES=2:" not in app_log.read_text(), "Partial output reached the live JVM")
            failed_hash = sha(class_a)
            require(query(port, "DOCTOR")["buildHold"] == "named", "Failed build must keep its named hold")
            require(query(port, "VERIFY", f" A {failed_hash}")["status"] != "applied", "False completion receipt")
            sources(root, 3)
            run(safe(port, "other-owner"), root, root / "04-other-owner.log", success=False)
            require(sha(class_a) == failed_hash, "Competing owner compiled before acquiring a hold")
            require(query(port, "DOCTOR")["buildHold"] == "named", "Other builder released the hold")
            run(safe(port, "fixture-owner"), root, root / "05-recovery.log")
            await_text(app_log, "VALUES=3:3")
            require(query(port, "DOCTOR")["buildHold"] == "none", "Successful owner did not release hold")
            receipts = []
            for name, file in (("A", class_a), ("B", class_b)):
                expected = sha(file)
                until = time.monotonic() + 15
                while True:
                    receipt = query(port, "VERIFY", f" {name} {expected}")
                    if receipt["status"] == "applied" or time.monotonic() > until:
                        break
                    time.sleep(.05)
                require(receipt["status"] == "applied" and receipt["observedSha256"] == expected
                        and receipt["sessionId"] == session, f"Exact receipt missing: {receipt}")
                receipts.append(receipt)
            require(app.poll() is None, "Application restarted/exited")
            # Same successful invocation must still acquire a new hold when tasks are up-to-date.
            run(safe(port, "fixture-owner"), root, root / "06-up-to-date.log")
            require("Reclazz build owner: fixture-owner" in (root / "06-up-to-date.log").read_text(),
                    "Safety wrapper incorrectly skipped")
            # Offline target must fail BEFORE any compiled output changes.
            sources(root, 4)
            with socket.socket() as unused:
                unused.bind(("127.0.0.1", 0))
                dead_port = unused.getsockname()[1]  # bound but not listening
                run(safe(dead_port, "fixture-owner"), root, root / "07-offline.log", success=False)
            require(sha(class_a) == receipts[0]["observedSha256"], "Offline build still wrote output")
            evidence = {"tool": tool, "passed": True, "pid": app.pid, "sessionId": session,
                        "scenarios": ["opt-out", "partial-failure", "competing-owner", "same-owner-recovery",
                                      "exact-byte-receipts", "up-to-date-barrier", "offline-before-output"],
                        "receipts": receipts}
            write(root / "evidence.json", json.dumps(evidence, indent=2) + "\n")
            print(f"PASS {tool}: 7 scenarios, real two-module compiler and live agent", flush=True)
        finally:
            app.kill()
            app.wait(timeout=10)


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--tool", choices=["gradle", "maven", "all"], default="all")
    parser.add_argument("--version", default="1.3.0")
    parser.add_argument("--gradle", type=Path, required=True)
    parser.add_argument("--maven", type=Path, default=Path(shutil.which("mvn") or "mvn"))
    parser.add_argument("--java", type=Path, default=Path(shutil.which("java") or "java"))
    parser.add_argument("--javac", type=Path, default=Path(shutil.which("javac") or "javac"))
    parser.add_argument("--maven-cache", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--agent", type=Path, default=root / "agent/build/libs/agent-1.3.0.jar")
    parser.add_argument("--mcp", type=Path, default=root / "mcp-server/build/libs/reclazz-mcp-1.3.0.jar")
    parser.add_argument("--gradle-plugin", type=Path, default=root / "gradle-plugin/build/libs/gradle-plugin-1.3.0.jar")
    parser.add_argument("--maven-plugin", type=Path, default=root / "maven-plugin/target/reclazz-maven-plugin-1.3.0.jar")
    parser.add_argument("--maven-pom", type=Path, default=root / "maven-plugin/pom.xml")
    args = parser.parse_args()
    args.work_dir = args.work_dir.resolve()
    require(not args.work_dir.exists(), "Use a fresh --work-dir to preserve earlier evidence")
    for tool in (["gradle", "maven"] if args.tool == "all" else [args.tool]):
        exercise(args, tool)


if __name__ == "__main__":
    main()
