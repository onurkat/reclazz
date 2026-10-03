#!/usr/bin/env python3
# Copyright 2026 Onur Kat
# SPDX-License-Identifier: Apache-2.0
"""Prepare release metadata with rollback on caught update failures; never publish."""
import argparse
from datetime import date
import html
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import xml.etree.ElementTree as ET

FILES = ('gradle.properties', 'maven-plugin/pom.xml', 'CHANGELOG.md',
         'src/main/resources/META-INF/plugin.xml')
TRANSACTION = '.reclazz-version-bump'
VERSION = r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)'


def only(matches, label):
    if len(matches) != 1:
        raise ValueError(f'expected exactly one {label}, found {len(matches)}')
    return matches[0]


def replace_group(text, match, value, group=1):
    return text[:match.start(group)] + value + text[match.end(group):]


def render(originals, version, release_date):
    if not re.fullmatch(VERSION, version):
        raise ValueError('version must be X.Y.Z (digits, no leading zeros)')
    if not re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}', release_date):
        raise ValueError('release date must be YYYY-MM-DD')
    date.fromisoformat(release_date)
    texts = {name: data.decode('utf-8') for name, data in originals.items()}
    props, pom, changelog, plugin = (texts[name] for name in FILES)
    only(list(re.finditer(r'^[ \t]*pluginVersion(?:[ \t]*[=:]|[ \t]+)', props, re.M)),
         'pluginVersion definition')
    prop = only(list(re.finditer(r'^pluginVersion=([^\r\n]+)\r?$', props, re.M)), 'pluginVersion')
    previous = prop.group(1)
    if not re.fullmatch(VERSION, previous):
        raise ValueError('current pluginVersion must be X.Y.Z')
    props = replace_group(props, prop, version)

    namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
    tree = ET.fromstring(pom)
    if tree.findtext('m:artifactId', namespaces=namespace) != 'reclazz-maven-plugin':
        raise ValueError('unexpected Maven project artifactId')
    own = only(tree.findall('m:version', namespace), 'Maven project version')
    agent = only([dep for dep in tree.findall('m:dependencies/m:dependency', namespace)
                  if dep.findtext('m:artifactId', namespaces=namespace) == 'reclazz-agent'
                  and dep.findtext('m:groupId', namespaces=namespace) == 'com.onurkat.reclazz'],
                 'Reclazz agent dependency')
    agent_version = only(agent.findall('m:version', namespace), 'agent dependency version')
    if own.text != previous or agent_version.text != previous:
        raise ValueError('Maven project/agent versions must match pluginVersion')
    # Replace only the existing supported literal layout, without reserializing XML.
    for artifact in ('reclazz-maven-plugin', 'reclazz-agent'):
        pattern = r'<artifactId>' + artifact + r'</artifactId>\s*<version>([^<]+)</version>'
        match = only(list(re.finditer(pattern, pom)), artifact + ' literal version')
        if match.group(1) != previous:
            raise ValueError(f'unsupported version layout for {artifact}')
        pom = replace_group(pom, match, version)
    ET.fromstring(pom)

    sections = list(re.finditer(r'^## \[([^\]\r\n]+)\][^\r\n]*', changelog, re.M))
    unreleased = only([m for m in sections if m.group(1) == 'Unreleased'], '[Unreleased] section')
    if unreleased.group(0) != '## [Unreleased]':
        raise ValueError('expected heading ## [Unreleased]')
    if any(m.group(1) == version for m in sections):
        raise ValueError(f'changelog already has a section for {version}')
    if version == previous:
        raise ValueError('target version is already pluginVersion')
    end = next((m.start() for m in sections if m.start() > unreleased.start()), len(changelog))
    body = changelog[unreleased.end():end]
    bullets = []
    for line in body.splitlines():
        if line.startswith('- '):
            bullets.append(line[2:].strip())
        elif line.startswith((' ', '\t')) and bullets and line.strip():
            bullets[-1] += ' ' + line.strip()
    if not bullets or any(not bullet for bullet in bullets):
        raise ValueError('[Unreleased] needs at least one nonempty bullet')
    bold = [match.group(1) for bullet in bullets
            for match in [re.match(r'\*\*([^*]+)\*\*', bullet)] if match]
    headlines = [html.escape(text, quote=False) for text in (bold or bullets)]
    newline = '\r\n' if '\r\n' in changelog else '\n'
    heading = f'## [Unreleased]{newline}{newline}## [{version}] - {release_date}'
    changelog = changelog[:unreleased.start()] + heading + changelog[unreleased.end():]

    plugin_tree = ET.fromstring(plugin)
    only(plugin_tree.findall('change-notes'), 'plugin change-notes element')
    notes = only(list(re.finditer(r'<change-notes>\s*<!\[CDATA\[(.*?)\]\]>\s*</change-notes>',
                                 plugin, re.S)), 'change-notes CDATA block')
    if re.search(r'<h3>\s*' + re.escape(version) + r'\s*</h3>', notes.group(1)):
        raise ValueError(f'plugin already has change-notes for {version}')
    newline = '\r\n' if '\r\n' in plugin else '\n'
    lines = ['', f'        <h3>{version}</h3>', '        <ul>']
    lines += [f'            <li>{headline}</li>' for headline in headlines]
    lines += ['        </ul>']
    plugin = replace_group(plugin, notes, newline.join(lines) + notes.group(1))
    ET.fromstring(plugin)
    return {name: text.encode('utf-8') for name, text in zip(FILES, (props, pom, changelog, plugin))}


def bump(root, version, release_date):
    # mkdir is exclusive: another invocation (or unrecovered originals) blocks us.
    transaction = root / TRANSACTION
    transaction.mkdir()
    keep_backups = False
    attempted = []
    try:
        originals = {}
        modes = {}
        for name in FILES:
            path = root / name
            if path.resolve() != root.resolve() / name or not path.is_file():
                raise ValueError(f'required regular file (no symlinks): {name}')
            originals[name] = path.read_bytes()
            modes[name] = stat.S_IMODE(path.stat().st_mode)
        outputs = render(originals, version, release_date)
        # Every new file AND backup exists before the first replacement.
        for index, name in enumerate(FILES):
            for suffix, data in [('original', originals[name]), ('new', outputs[name])]:
                staged = transaction / f'{index}.{suffix}'
                staged.write_bytes(data)
                staged.chmod(modes[name])
        for name in FILES:
            if (root / name).read_bytes() != originals[name]:
                raise ValueError(f'input changed during preparation: {name}')
        try:
            for index, name in enumerate(FILES):
                # Register before replace so an interrupt immediately after replace
                # still restores that file. No other writer may edit these inputs.
                attempted.append(index)
                os.replace(transaction / f'{index}.new', root / name)
        except BaseException:
            failures = []
            for index in reversed(attempted):
                try:
                    # Keep original backups if even restoration fails partway.
                    recovery = transaction / f'{index}.restore'
                    shutil.copy2(transaction / f'{index}.original', recovery)
                    os.replace(recovery, root / FILES[index])
                except BaseException as error:
                    failures.append(f'{FILES[index]}: {error}')
            if failures:
                keep_backups = True
                raise RuntimeError(f'rollback incomplete: {"; ".join(failures)}. '
                                   f'Originals retained in {transaction}; '
                                   'restore N.original using FILES order in bump-version.py before retrying')
            raise
    finally:
        if not keep_backups:
            shutil.rmtree(transaction)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version', help='X.Y.Z or vX.Y.Z')
    args = parser.parse_args()
    version = args.version[1:] if args.version.startswith('v') else args.version
    release_date = os.environ.get('RECLAZZ_RELEASE_DATE', date.today().isoformat())
    root = Path(__file__).resolve().parent.parent
    try:
        bump(root, version, release_date)
    except (OSError, ValueError, ET.ParseError, RuntimeError, KeyboardInterrupt) as error:
        print(f'Version preparation failed: {error}', file=sys.stderr)
        return 1
    print(f'Prepared {version} ({release_date}):')
    for name in FILES:
        print(f'  {name}')
    print(f'Then: review the change-notes, commit, and scripts/release.sh {version}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
