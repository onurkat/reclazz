# Copyright 2026 Onur Kat
# SPDX-License-Identifier: Apache-2.0
"""Validate P5's separately exported actual parser/tool/resource results.

Run after the complete :mcp-server:test; no live/native producer claim is made.
"""
from copy import deepcopy
from pathlib import Path
from datetime import datetime
import json
from jsonschema import Draft202012Validator, FormatChecker

formats = FormatChecker()
@formats.checks('date-time', raises=(ValueError, TypeError))
def wire_datetime(value):
    return not isinstance(value, str) or datetime.fromisoformat(value.replace('Z', '+00:00')).utcoffset() is not None

root = Path('mcp-server')
fixtures = sorted((root / 'build/sap-outcome-fixtures').glob('*.json'))
assert fixtures, 'Run :mcp-server:test first'
registry = json.loads((root / 'src/main/resources/tool-output-schemas.json').read_text())
row_schema = registry['reclazz_events']['properties']['events']['items']
annotation = registry['reclazz_await_reload']['properties']['sapOutcomes']['items']
assert row_schema['properties']['sap'] == annotation['properties']['sap']
negative = 0
operations, outcomes = set(), set()
expected_labels = {'await-' + x for x in ['checkpoint','held','timeout','applied','compile_error','refused','unavailable','restart_required']} | {'events-all','resource-all'}
expected_labels |= {'row-' + str(index) for index in range(45)}
assert expected_labels == {p.stem for p in fixtures}, 'Missing or stale SAP fixture inventory'
row_files = [p for p in fixtures if p.stem.startswith('row-')]
assert len(row_files) == 45, 'Producer branch fixture coverage missing'
for file in fixtures:
    fixture = json.loads(file.read_text())
    schema, data = fixture['schema'], fixture['data']
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=formats)
    validator.validate(data)
    if file.stem.startswith('row-'):
        assert schema == row_schema
        rows, field = [data], None
    elif 'events' in data:
        assert schema == registry['reclazz_events']
        rows, field = data['events'], 'events'
    else:
        assert schema == registry['reclazz_await_reload']
        rows, field = data['sapOutcomes'], 'sapOutcomes'
    for index, row in enumerate(rows):
        sap = row['sap']; operations.add(sap['operation']); outcomes.add((sap['operation'],sap['reportedOutcome']))
        def reject(changed):
            global negative
            bad = deepcopy(data)
            if field is None: bad = changed
            else: bad[field][index] = changed
            assert not validator.is_valid(bad), (file, changed)
            negative += 1
        for name in sap:
            bad = deepcopy(row); del bad['sap'][name]
            # An optional count may legitimately be absent; required fields must not.
            if name in {'properties','unresolvedLines'}: continue
            reject(bad)
        for name, value in [('applicationBehaviorConfirmed',True),('evidenceScope','native_receipt'),('reportedOutcome','invented'),('operation','unknown'),('reasonCode','invented'),('ownerClassName','com.example.Secret'),('resourceBasename','/private/example.xml')]:
            bad = deepcopy(row); bad['sap'][name] = value; reject(bad)
        for name,value in [('type','unknown'),('ownerClassName','com.example.Service'),('fileBasename','Service.java')]:
            bad=deepcopy(row); bad[name]=value; reject(bad)
        if sap['operation']=='solr_reindex':
            bad=deepcopy(row);bad['sap']['fieldFreshnessConfirmed']=True;reject(bad)
        if sap['reportedOutcome']=='finished_unverified':
            bad=deepcopy(row);bad['sap']['reportedOutcome']='finished';reject(bad)
        for name in ('properties','documents','durationMs','services','headerLine','unresolvedLines','listenerMetadata'):
            if name in sap:
                bad=deepcopy(row);bad['sap'][name]=-1;reject(bad)
                bad=deepcopy(row);bad['sap'][name]=2147483648;reject(bad)
    if field=='sapOutcomes':
        if rows:
            bad=deepcopy(data);bad[field]=[deepcopy(rows[0]) for _ in range(17)]
            assert not validator.is_valid(bad);negative+=1
        if data['action']=='checkpoint':
            bad=deepcopy(data);bad['sapOutcomesTruncated']=True
            assert not validator.is_valid(bad);negative+=1
assert operations=={'impex_import','items_schema','codegen','backoffice_reset','solr_reindex'}
assert json.loads((root/'build/sap-outcome-fixtures/events-all.json').read_text())['data']==json.loads((root/'build/sap-outcome-fixtures/resource-all.json').read_text())['data']
print(f'{len(fixtures)} SAP fixtures validated across {len(operations)} families/{len(outcomes)} outcomes; {negative} invalid mutations rejected.')
