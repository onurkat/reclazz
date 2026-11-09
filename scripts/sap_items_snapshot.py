"""Stopped-HSQLDB recovery primitives for the opt-in, clean-install items test."""
from pathlib import Path
import hashlib
import json
import shutil


def inventory(root):
    root = Path(root)
    result = {}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise RuntimeError('Snapshot refuses symlink: ' + str(path.relative_to(root)))
        if path.is_file():
            result[str(path.relative_to(root))] = {
                'size': path.stat().st_size,
                'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            }
        elif not path.is_dir():
            raise RuntimeError('Snapshot refuses non-regular entry')
    return result


def capture(source, destination, stopped):
    """The caller supplies a guard that proves server/writer exit and released lock."""
    source, destination = Path(source), Path(destination)
    if source.resolve() == destination.resolve() or source.resolve() in destination.resolve().parents or destination.resolve() in source.resolve().parents:
        raise RuntimeError("Overlapping snapshot paths")
    stopped()
    if destination.exists():
        raise RuntimeError('Snapshot already exists')
    before = inventory(source)
    if not before:
        raise RuntimeError('Empty snapshot refused')
    shutil.copytree(source, destination / 'files')
    stopped()
    if inventory(source) != before or inventory(destination / 'files') != before:
        raise RuntimeError('Snapshot copy differs from stopped source')
    (destination / 'manifest.json').write_text(json.dumps(before, indent=2) + '\n')
    return before


def verify(snapshot):
    snapshot = Path(snapshot)
    expected = json.loads((snapshot / 'manifest.json').read_text())
    if not expected or inventory(snapshot / 'files') != expected:
        raise RuntimeError('Snapshot checksum/membership verification failed')
    return expected


def restore(snapshot, target, stopped):
    """Validate before deleting anything; replace the whole tree, including extra files."""
    target = Path(target)
    if target.resolve() == Path(snapshot).resolve() or target.resolve() in Path(snapshot).resolve().parents or Path(snapshot).resolve() in target.resolve().parents:
        raise RuntimeError("Overlapping recovery paths")
    expected = verify(snapshot)
    stopped()
    # The immutable snapshot is never moved or overwritten: retain it on any failure.
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(Path(snapshot) / 'files', target)
    stopped()
    if inventory(target) != expected:
        raise RuntimeError('Restored directory differs from verified snapshot')
    return expected
