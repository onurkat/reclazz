"""Run only the virtual-display IDE fixture. Docker publishes no ports."""
import hashlib
from datetime import datetime, timezone
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build"
ARCHIVES = BUILD / "archives"


def download(name, url, expected):
    destination = ARCHIVES / name
    if destination.is_file() and checksum(destination) == expected:
        return destination
    with tempfile.NamedTemporaryFile(dir=ARCHIVES, delete=False) as tmp:
        path = Path(tmp.name)
        try:
            with urlopen(url, timeout=120) as response:
                shutil.copyfileobj(response, tmp)
            tmp.flush()
            if checksum(path) != expected:
                raise ValueError("Unexpected SHA-256 for " + name)
            path.replace(destination)
        finally:
            path.unlink(missing_ok=True)
    return destination


def checksum(path):
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    scenario = sys.argv[2]
    if scenario not in ("all", "settings", "reload", "wizard", "wizard-sap", "wizard-java-off"):
        raise ValueError("Expected all, settings, reload or wizard")
    plugin = Path(sys.argv[1]).resolve(strict=True)
    arch = subprocess.check_output(["docker", "info", "--format", "{{.Architecture}}"], text=True).strip()
    if arch in ("aarch64", "arm64"):
        archive = "ideaIC-2023.3-aarch64.tar.gz"
        digest = "6c0990d8b81d4c7c1e2783c0af5c01ff022ebac1a187036f413ef7572f77a9d2"
    elif arch in ("x86_64", "amd64"):
        archive = "ideaIC-2023.3.tar.gz"
        digest = "822dae9284a9432e110ee36a217d1da508061bf1fc17e38fb59c6912a9c8aef7"
    else:
        raise ValueError("Unsupported Docker architecture " + arch)
    ARCHIVES.mkdir(exist_ok=True, parents=True)
    idea = download(archive, "https://download.jetbrains.com/idea/" + archive, digest)
    robot = download("robot-0.11.23.zip",
                     "https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/"
                     "com/intellij/remoterobot/robot-server-plugin/0.11.23/robot-server-plugin-0.11.23.zip",
                     "65658b502c6bee6b3649298b7bfe887fd3ae5ca4d1482914f41da4f0acaf8f23")
    tag = "reclazz-ide-ui-test:temurin17"
    with (ROOT / "Dockerfile").open("rb") as dockerfile:
        subprocess.run(["docker", "build", "-t", tag, "-"], stdin=dockerfile, check=True)
    report = BUILD / "reports" / "uiTest" / datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-%fZ")
    report.mkdir(parents=True)
    print("UI evidence:", report, flush=True)
    cmd = ["docker", "run", "--rm", "--network", "none", "--shm-size=512m"]
    mounts = [(idea, "/input/idea.tar.gz"), (robot, "/input/robot.zip"), (plugin, "/input/plugin.zip"),
              (BUILD / "robot-libs", "/input/client-libs"),
              (ROOT / "fixture/build/ui-libs", "/input/fixture-libs")]
    for entry in ("inside.sh", "driver", "robot", "fixture/src", "fixture/build.gradle.kts"):
        mounts.append((ROOT / entry, "/suite/" + entry))
    for source, target in mounts:
        cmd.extend(["-v", f"{source.resolve(strict=True)}:{target}:ro"])
    cmd.extend(["-v", f"{report}:/out", tag, "/bin/bash", "/suite/inside.sh", scenario])
    subprocess.run(cmd, check=True)


if __name__ == "__main__":
    main()
