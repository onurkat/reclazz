# Copyright 2026 Onur Kat
# SPDX-License-Identifier: Apache-2.0
"""Validate McpToolContractsTest's actual output fixtures with Python jsonschema.

Run from the repository root after :mcp-server:test (requires jsonschema 4.x).
This optional independent checker is not a production or Gradle dependency.
"""
from copy import deepcopy
from pathlib import Path
from datetime import datetime
import json
from jsonschema import Draft202012Validator, FormatChecker

# The minimal local jsonschema install does not include optional RFC3339 extras.
# Check calendar/timezone validity explicitly instead of silently ignoring format.
formats = FormatChecker()
@formats.checks('date-time', raises=(ValueError, TypeError))
def wire_datetime(value):
    if not isinstance(value, str):
        return True  # The schema's type branch rejects non-string timestamps.
    return datetime.fromisoformat(value.replace('Z', '+00:00')).utcoffset() is not None

fixtures = Path('mcp-server/build/tool-contract-fixtures')
files = sorted(fixtures.glob('*.json'))
assert len(files) == 43, f'Expected 43 current contract fixtures, found {len(files)}'
negative = 0
for file in files:
    fixture = json.loads(file.read_text())
    schema, data = fixture['schema'], fixture['data']
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=formats)
    validator.validate(data)
    # Independent negative witnesses: advertised requirements/types/enums matter.
    for field in schema['required']:
        bad = deepcopy(data)
        del bad[field]
        assert not validator.is_valid(bad), (file, 'missing', field)
        bad = deepcopy(data)
        bad[field] = None
        assert not validator.is_valid(bad), (file, 'wrong type', field)
        negative += 2
    if 'status' in data:
        bad = deepcopy(data)
        bad['status'] = 'made_up_status'
        assert not validator.is_valid(bad), (file, 'invalid status')
        negative += 1
    for field in ('reloadConfirmed', 'complete', 'atomic'):
        if field in data:
            bad = deepcopy(data)
            bad[field] = not data[field]
            assert not validator.is_valid(bad), (file, 'invented completion')
            negative += 1
    if 'correlationScope' in data:
        for field in ('sourcePathConfirmed', 'editRevisionConfirmed'):
            bad = deepcopy(data)
            bad[field] = True
            assert not validator.is_valid(bad), (file, 'invented source proof', field)
            negative += 1
        if data['status'] == 'applied':
            for field in ('receipt', 'observedSha256', 'hashSource'):
                bad = deepcopy(data)
                del bad[field]
                assert not validator.is_valid(bad), (file, 'missing applied proof', field)
                negative += 1
            bad = deepcopy(data)
            bad['receipt']['status'] = 'unverified'
            assert not validator.is_valid(bad), (file, 'unverified receipt called applied')
            negative += 1
    if file.name.startswith('reclazz_events-'):
        for field, value in [('status', 'applied'), ('nextCursor', 1), ('sinceCursor', '-1'), ('limit', 101), ('extra', True)]:
            bad = deepcopy(data)
            bad[field] = value
            assert not validator.is_valid(bad), (file, 'events invalid field', field)
            negative += 1
        if data['status'] == 'observed' and data['events']:
            for field, value in [('timestamp', '2026-99-99T00:00:00Z'), ('sequence', 1), ('type', 'applied'), ('message', 'x' * 513), ('unexpected', True)]:
                bad = deepcopy(data)
                bad['events'][0][field] = value
                assert not validator.is_valid(bad), (file, 'events invalid row', field)
                negative += 1
            bad = deepcopy(data)
            bad['events'] = [deepcopy(data['events'][0]) for _ in range(101)]
            assert not validator.is_valid(bad), (file, 'oversized event page')
            negative += 1
    if 'allApplied' in data:
        bad = deepcopy(data)
        bad['allApplied'] = not data['allApplied']
        assert not validator.is_valid(bad), (file, 'inconsistent allApplied')
        negative += 1
        for index, receipt in enumerate(data['results']):
            for field in ('status', 'className', 'expectedSha256', 'detail'):
                bad = deepcopy(data)
                del bad['results'][index][field]
                assert not validator.is_valid(bad), (file, 'missing receipt field', field)
                negative += 1
            bad = deepcopy(data)
            bad['results'][index]['status'] = 'invented_success'
            assert not validator.is_valid(bad), (file, 'invalid receipt status')
            negative += 1
print(f'{len(files)} actual results validated; {negative} invalid mutations rejected.')
