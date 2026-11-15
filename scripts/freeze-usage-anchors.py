#!/usr/bin/env python3
"""Freeze the current usage section identities once, before a reviewed move."""
from pathlib import Path
import sys,json,hashlib
sys.dont_write_bytecode=True
REPO=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(REPO))
from website.markdown import usage_heading_blocks

def freeze(repo):
    repo=Path(repo);source=(repo/'docs/usage.md').read_text()
    blocks=usage_heading_blocks(source);signatures=[(b['title'],b['body_sha256']) for b in blocks]
    if len(signatures)!=len(set(signatures)):raise ValueError('Duplicate same-text/same-body heading; explicit review needed')
    return {'source':'docs/usage.md','baseline':'3327bc6','source_sha256':hashlib.sha256(source.encode()).hexdigest(),
            'headings':[{k:b[k] for k in ('key','identity','occurrence','level','title','id','body_sha256','heading')} for b in blocks]}

if __name__=='__main__':
    target=REPO/'website/usage-anchors.json'
    if target.exists():raise SystemExit('Refusing to overwrite the frozen usage contract')
    data=freeze(REPO);target.write_text(json.dumps(data,indent=2,ensure_ascii=False)+'\n')
    print('Frozen '+str(len(data['headings']))+' usage heading identities')
