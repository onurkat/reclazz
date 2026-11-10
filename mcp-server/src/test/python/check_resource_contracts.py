# Copyright 2026 Onur Kat
# SPDX-License-Identifier: Apache-2.0
"""Independent validation of actual resource JSON, using unchanged tool schemas."""
from copy import deepcopy
from datetime import datetime
from pathlib import Path
import json
from jsonschema import Draft202012Validator, FormatChecker

formats = FormatChecker()
@formats.checks('date-time', raises=(ValueError, TypeError))
def wire_datetime(value):
    return not isinstance(value, str) or datetime.fromisoformat(value.replace('Z', '+00:00')).utcoffset() is not None

files = sorted(Path('mcp-server/build/resource-contract-fixtures').glob('*.json'))
expected = {'status-attached', 'status-detached', 'pending-observed', 'pending-unavailable',
            'events-empty', 'events-observed', 'events-unavailable'}
assert {p.stem for p in files} == expected, 'Missing or stale resource fixtures'
negative = 0
for file in files:
    fixture = json.loads(file.read_text())
    schema, data = fixture['schema'], fixture['data']
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=formats)
    validator.validate(data)
    for field in schema['required']:
        for remove in (True, False):
            bad = deepcopy(data)
            if remove:
                del bad[field]
            else:
                bad[field] = None
            assert not validator.is_valid(bad), (file.name, field)
            negative += 1
    for field in ('complete', 'reloadConfirmed', 'sourcePathConfirmed', 'editRevisionConfirmed'):
        if field in data:
            bad = deepcopy(data)
            bad[field] = True
            assert not validator.is_valid(bad), (file.name, 'invented completion')
            negative += 1
    if file.stem.startswith('events-'):
        for field, value in [('status', 'applied'), ('nextCursor', 1), ('sinceCursor', '-1'), ('limit', 101)]:
            bad = deepcopy(data)
            bad[field] = value
            assert not validator.is_valid(bad), (file.name, field)
            negative += 1
        if data['events']:
            for field, value in [('timestamp', '2026-99-99T00:00:00Z'), ('type', 'applied'), ('sequence', 1), ('message', 'x' * 513)]:
                bad = deepcopy(data)
                bad['events'][0][field] = value
                assert not validator.is_valid(bad), (file.name, field)
                negative += 1
            bad = deepcopy(data)
            bad['events'] *= 101
            assert not validator.is_valid(bad), (file.name, 'oversized page')
            negative += 1
print(f'{len(files)} actual resource results validated; {negative} invalid mutations rejected.')
