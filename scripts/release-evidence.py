#!/usr/bin/env python3
# Copyright 2026 Onur Kat
# SPDX-License-Identifier: Apache-2.0
"""Local prepublication evidence, not a public provenance attestation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parent.parent
RECEIPT = Path('build/release-gate/receipt.json')
ASSETS = Path('build/release-gate/assets')
MAVEN_BUNDLE = 'maven-plugin/target/central-publishing/central-bundle.zip'


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, text=True).strip()


def source(root, commit, version):
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('invalid release version')
    if git(root, 'rev-parse', 'HEAD') != commit or git(root, 'status', '--porcelain'):
        raise ValueError('source changed since the release gate started')
    if f'pluginVersion={version}' not in (root / 'gradle.properties').read_text().splitlines():
        raise ValueError('release version changed')


def inventory(root, paths):
    result = {}
    for name in paths:
        relative = Path(name)
        if relative.is_absolute() or '..' in relative.parts:
            raise ValueError('artifact path must stay inside the repository')
        path = root / relative
        if path.is_symlink():
            raise ValueError(f'artifact must not be a symlink: {name}')
        entries = sorted(path.rglob('*')) if path.is_dir() else [path]
        files = []
        for entry in entries:
            if entry.is_symlink() or not entry.resolve().is_relative_to(root.resolve()):
                raise ValueError(f'artifact must not be a symlink: {name}')
            if entry.is_file():
                files.append(entry)
        if not files:
            raise ValueError(f'missing release artifact: {name}')
        for entry in files:
            if entry.stat().st_size == 0:
                raise ValueError(f'empty release artifact: {entry.relative_to(root)}')
            result[entry.relative_to(root).as_posix()] = hashlib.sha256(entry.read_bytes()).hexdigest()
    return dict(sorted(result.items()))


def signed_bundle(path, artifact, version):
    base = f'com/onurkat/reclazz/{artifact}/{version}/{artifact}-{version}'
    with zipfile.ZipFile(path) as archive:
        for suffix in ('.jar', '-sources.jar', '-javadoc.jar', '.pom'):
            for ending in (suffix, suffix + '.asc'):
                if not archive.read(base + ending):
                    raise ValueError(f'empty signed bundle member: {base + ending}')


def create(root, version, commit, profile):
    source(root, commit, version)
    # Always replace the asset directory so old versions cannot be carried over.
    assets = root / ASSETS
    if assets.exists():
        shutil.rmtree(assets)
    assets.mkdir(parents=True)
    for module, original, renamed in (
        ('agent', f'build/libs/agent-{version}.jar', f'reclazz-agent-{version}.jar'),
        ('mcp-server', f'build/distributions/mcp/reclazz-mcp-{version}.jar', f'reclazz-mcp-{version}.jar'),
    ):
        shutil.copyfile(root / module / original, assets / renamed)
        digest = hashlib.sha256((assets / renamed).read_bytes()).hexdigest()
        (assets / (renamed + '.sha256')).write_text(f'{digest}  {renamed}\n')
    paths = [ASSETS.as_posix()]
    if profile != 'ci':
        signed = f'build/distributions/reclazz-{version}-signed.zip'
        shutil.copyfile(root / signed, assets / f'reclazz-{version}.zip')
        paths.append(signed)  # publishPlugin uses this original path.
    if profile == 'distribution':
        for module, artifact in (('agent', 'reclazz-agent'), ('spring-boot-starter', 'reclazz-spring-boot-starter')):
            bundle = f'{module}/build/{artifact}-{version}-central-bundle.zip'
            signed_bundle(root / bundle, artifact, version)
            paths.append(bundle)
        signed_bundle(root / MAVEN_BUNDLE, 'reclazz-maven-plugin', version)
        paths += [MAVEN_BUNDLE, 'gradle-plugin/build/libs', 'gradle-plugin/build/publications']
        # Portal's main publication must be present, not just an unrelated file.
        paths += [f'gradle-plugin/build/libs/gradle-plugin-{version}.jar',
                  'gradle-plugin/build/publications/pluginMaven/pom-default.xml',
                  'gradle-plugin/build/publications/pluginMaven/module.json']
    evidence = dict(schemaVersion=1, sourceCommit=commit, version=version,
                    profile=profile, artifactRoots=paths, artifacts=inventory(root, paths))
    source(root, commit, version)
    (root / RECEIPT).write_text(json.dumps(evidence, indent=2) + '\n')
    return evidence


def verify(root):
    evidence = json.loads((root / RECEIPT).read_text())
    if evidence['schemaVersion'] != 1 or not evidence['artifacts']:
        raise ValueError('invalid release gate receipt')
    source(root, evidence['sourceCommit'], evidence['version'])
    if inventory(root, evidence['artifactRoots']) != evidence['artifacts']:
        raise ValueError('release artifact changed after the gate')
    return evidence


def verify_publisher(root, publisher, paths):
    evidence = verify(root)
    expected_profile = ('local', 'distribution') if publisher == 'marketplace' else ('distribution',)
    if evidence['profile'] not in expected_profile or not paths:
        raise ValueError('publisher was not included in this release gate')
    for name in paths:
        path = Path(name)
        relative = path.resolve().relative_to(root.resolve()).as_posix() if path.is_absolute() else path.as_posix()
        if relative not in evidence['artifacts']:
            raise ValueError(f'publisher artifact was not checked: {relative}')
    return evidence


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward the publication token to a redirect target.


def github_check(root, draft_only=False):
    evidence = verify(root)
    tag = 'v' + evidence['version']
    try:
        result = subprocess.run(['gh', 'release', 'view', tag, '--json', 'tagName,isDraft,assets'],
                                cwd=root, capture_output=True, text=True, timeout=30, check=True)
        release = json.loads(result.stdout)
    except (subprocess.SubprocessError, OSError, ValueError):
        raise ValueError('GitHub draft metadata unavailable; inspect the release before retrying') from None
    if not isinstance(release, dict) or release.get('tagName') != tag or release.get('isDraft') is not True:
        raise ValueError('GitHub release must be the intended draft; do not upload or publish')
    if draft_only:
        return
    version = evidence['version']
    signed_name = f'reclazz-{version}.zip'
    signed_path = (ASSETS / signed_name).as_posix()
    if evidence['profile'] not in ('local', 'distribution') or signed_path not in evidence['artifacts']:
        raise ValueError('signed ZIP was not included in this release gate')
    required = [signed_name]
    for artifact in ('agent', 'mcp'):
        name = f'reclazz-{artifact}-{version}.jar'
        required += [name, name + '.sha256']
    assets = release.get('assets')
    if not isinstance(assets, list) or any(not isinstance(a, dict) for a in assets):
        raise ValueError('invalid GitHub asset metadata')
    for name in required:
        matches = [a for a in assets if a.get('name') == name]
        if (len(matches) != 1 or matches[0].get('state') != 'uploaded'
                or type(matches[0].get('size')) is not int or matches[0]['size'] <= 0):
            raise ValueError(f'GitHub draft asset missing, incomplete or duplicated: {name}')
        if name == signed_name and matches[0]['size'] != (root / signed_path).stat().st_size:
            raise ValueError('GitHub signed ZIP size differs from the checked artifact')
    # This is one metadata observation, not download/digest verification or a lock.
    verify(root)


def stage_maven(root, opener=None):
    evidence = verify(root)
    if evidence['profile'] != 'distribution' or MAVEN_BUNDLE not in evidence['artifacts']:
        raise ValueError('Maven bundle was not included in the release gate')
    token = os.environ.get('RECLAZZ_CENTRAL_TOKEN', '')
    if not token or any(c.isspace() for c in token):
        raise ValueError('RECLAZZ_CENTRAL_TOKEN must contain the Central bearer token')
    data = (root / MAVEN_BUNDLE).read_bytes()
    if hashlib.sha256(data).hexdigest() != evidence['artifacts'][MAVEN_BUNDLE]:
        raise ValueError('Maven bundle changed before upload')
    boundary = 'reclazz-' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; '
            'filename="central-bundle.zip"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()
    body += data + f'\r\n--{boundary}--\r\n'.encode()
    request = urllib.request.Request(
        'https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED',
        data=body, headers={'Authorization': 'Bearer ' + token,
                            'Content-Type': 'multipart/form-data; boundary=' + boundary}, method='POST')
    source(root, evidence['sourceCommit'], evidence['version'])
    opener = opener or urllib.request.build_opener(NoRedirect()).open
    try:
        with opener(request, timeout=120) as response:
            if response.status != 201:
                raise ValueError('Central staging did not return HTTP 201')
            deployment = str(uuid.UUID(response.read(128).decode().strip()))
    except (urllib.error.URLError, ValueError) as error:
        # No echoed HTTP response, request headers or credentials in diagnostics.
        raise ValueError('Central staging failed; check the Portal before retrying') from None
    print(f'Maven bundle staged: {deployment}; manual validation/Publish still required.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    capture = commands.add_parser('create')
    capture.add_argument('version')
    capture.add_argument('commit')
    capture.add_argument('profile', choices=['local', 'distribution', 'ci'])
    commands.add_parser('verify')
    publisher = commands.add_parser('verify-publisher')
    publisher.add_argument('publisher', choices=['marketplace', 'portal'])
    publisher.add_argument('paths', nargs='+')
    commands.add_parser('stage-maven')
    github = commands.add_parser('github-check')
    github.add_argument('--draft-only', action='store_true')
    args = parser.parse_args()
    try:
        if args.command == 'create':
            create(ROOT, args.version, args.commit, args.profile)
            print(f'Release gate recorded: {RECEIPT}')
        elif args.command == 'verify-publisher':
            verify_publisher(ROOT, args.publisher, args.paths)
        elif args.command == 'verify':
            verify(ROOT)
        elif args.command == 'github-check':
            github_check(ROOT, args.draft_only)
            print('GitHub draft checked' if args.draft_only else
                  'GitHub draft assets complete in metadata; download availability remains unverified')
        else:
            stage_maven(ROOT)
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f'Release gate refused: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
