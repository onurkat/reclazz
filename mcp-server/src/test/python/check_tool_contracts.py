# Copyright 2026 Onur Kat
# SPDX-License-Identifier: Apache-2.0
"""Validate McpToolContractsTest's actual output fixtures with Python jsonschema.

Run from the repository root after :mcp-server:test (requires jsonschema 4.x).
This optional independent checker is not a production or Gradle dependency.
"""
from copy import deepcopy
from pathlib import Path
import json
from jsonschema import Draft202012Validator

fixtures = Path('mcp-server/build/tool-contract-fixtures')
files = sorted(fixtures.glob('*.json'))
assert len(files) == 25, f'Expected 25 current contract fixtures, found {len(files)}'
negative = 0
for file in files:
    fixture = json.loads(file.read_text())
    schema, data = fixture['schema'], fixture['data']
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)
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
    for field in ('reloadConfirmed', 'complete'):
        if field in data:
            bad = deepcopy(data)
            bad[field] = True
            assert not validator.is_valid(bad), (file, 'invented completion')
            negative += 1
print(f'{len(files)} actual results validated; {negative} invalid mutations rejected.')
