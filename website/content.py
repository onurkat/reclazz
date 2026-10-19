"""Canonical documentation inputs shared by rendering and validation."""
from collections import Counter
from datetime import date
from html import escape
from pathlib import Path
import json
import hashlib
import re
from urllib.parse import urlsplit, unquote

from markdown_it import MarkdownIt
from website.markdown import slug

LABELS = {'works': 'Reloads', 'conditional': 'Conditional', 'restart': 'Restart',
          'unverified': 'Unverified', 'not-reloaded': 'No reload'}
SUPPORT_COLUMNS = ['ID', 'Area', 'Change', 'Outcome', 'Conditions', 'Next action', 'Reference']


def metadata(repo):
    repo = Path(repo)
    result = json.loads((repo/'docs/site/manifest.json').read_text())
    if 'source_version' in result:
        raise ValueError('Documentation version must come only from gradle.properties')
    result['source_version'] = re.search(r'^pluginVersion=(.+)$',
        (repo/'gradle.properties').read_text(), re.M).group(1).strip()
    reviewed = date.fromisoformat(result['reviewed'])
    months = ('January','February','March','April','May','June','July','August','September','October','November','December')
    result['reviewed_label'] = str(reviewed.day)+' '+months[reviewed.month-1]+' '+str(reviewed.year)
    if not re.fullmatch(r'[a-f0-9]{40}', result['source_commit']):
        raise ValueError('Evidence baseline must be an exact source commit')
    return result


def support_records(repo):
    tokens = MarkdownIt('commonmark').enable('table').parse(
        (Path(repo)/'docs/reload-support.md').read_text())
    rows = []; row = None
    for i, token in enumerate(tokens):
        if token.type == 'tr_open': row = []
        elif token.type == 'inline' and row is not None: row.append(token)
        elif token.type == 'tr_close': rows.append(row); row = None
    def plain(token):
        return ''.join(t.content for t in token.children or [] if t.type in ('text','code_inline','html_inline'))
    if not rows or [plain(t) for t in rows[0]] != SUPPORT_COLUMNS:
        raise ValueError('Support table has missing or reordered columns')
    records = []; ids = set()
    for row in rows[1:]:
        if len(row) != len(SUPPORT_COLUMNS): raise ValueError('Incomplete support row')
        values = [plain(t) for t in row]
        links = [t.attrGet('href') for t in row[-1].children or [] if t.type == 'link_open']
        if len(links) != 1: raise ValueError('Support row needs one canonical reference')
        key, area, title, status, conditions, action, _ = values
        if not re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*',key) or key in ids:
            raise ValueError('Invalid or duplicate support ID: ' + key)
        if status not in LABELS: raise ValueError('Unknown support outcome: ' + status)
        if not all((area,title,conditions,action)): raise ValueError('Empty support condition/action')
        source = links[0]
        if urlsplit(source).scheme or source.startswith('/'):
            raise ValueError('Support scope must link to a canonical docs reference')
        target = (Path(repo)/'docs'/urlsplit(source).path).resolve()
        if not target.is_relative_to(Path(repo).resolve()):
            raise ValueError('Escaping support reference: '+source)
        source = target.relative_to(Path(repo).resolve()).as_posix() + (('#'+urlsplit(source).fragment) if urlsplit(source).fragment else '')
        ids.add(key)
        records.append(dict(id=key, category=area, title=title, status=status,
                            conditions=conditions, action=action, source=source))
    if not records: raise ValueError('Empty support table')
    return records


def expand_guide(text, renderer, info):
    if re.search(r"https://github\.com/onurkat/reclazz/blob/[^/]+/(?:docs/[^\s<>\"]+\.md|README\.md)",text):
        raise ValueError("Use a canonical REF for guide reading links")
    values = {'VERSION': info['source_version'], 'BASELINE': info['source_commit'],
              'BASELINE_SHORT': info['source_commit'][:7], 'REVIEWED': info['reviewed'],
              'REVIEWED_DATE': info['reviewed_label']}
    for key, value in values.items(): text = text.replace('{{'+key+'}}', escape(value))
    text = re.sub(r'\{\{REF ([^}]+)\}\}',
        lambda m: escape(renderer.resolve_link('README.md',m[1]),quote=True),text)
    def include(match):
        source, anchor = match[1].split('#',1)
        body = renderer.render(source, section=anchor)
        link = renderer.resolve_link('README.md',match[1])
        return body + '<p class="source-link"><a href="' + escape(link,quote=True) + '">Read the complete reference →</a></p>'
    return re.sub(r'\{\{INCLUDE ([^}]+)\}\}',include,text)


def ai_index(repo, pages, info):
    readme = (Path(repo)/'README.md').read_text()
    intro = readme.split('# Reclazz\n',1)[1].split('## Why Reclazz?',1)[0].strip()
    output = '# Reclazz\n\n' + intro + '\n\n## Documentation scope\n\n'
    output += ('Source version ' + info['source_version'] + '; evidence baseline ' +
               info['source_commit'] + '; reviewed ' + info['reviewed'] + '.\n'
               'Source presence does not establish release availability or acceptance on every runtime.\n'
               'Read each support condition and verify the running application.\n\n## Guides and references\n\n')
    for page in pages:
        route = '/docs/' + (page['slug']+'/' if page['slug'] else '')
        output += '- ['+page['nav_title']+'](https://reclazz.com'+route+'): '+page['description']+'\n'
    return output


def heading_ids(path):
    tokens = MarkdownIt('commonmark', {'html':True}).parse(Path(path).read_text())
    ids = set(); counts = Counter()
    for i, token in enumerate(tokens):
        if token.type == 'heading_open':
            inline = tokens[i+1]
            text = ''.join(t.content for t in inline.children or [] if t.type in ('text','code_inline'))
            anchor = slug(text); n = counts[anchor]; counts[anchor] += 1
            ids.add(anchor + ('-'+str(n) if n else ''))
        if token.type in ('html_block','inline'):
            ids.update(re.findall(r'<a id="([A-Za-z0-9_-]+)"',token.content))
    return ids


def source_link_errors(repo):
    """Check source Markdown too: GitHub reading must work without website aliases."""
    repo = Path(repo).resolve(); errors = []; anchors = {}
    sources = list((repo/'docs').glob('*.md')) + [repo/'README.md',repo/'SECURITY.md']
    for source in sources:
        for token in MarkdownIt('commonmark').parse(source.read_text()):
            for child in token.children or []:
                if child.type != 'link_open': continue
                href = str(child.attrGet('href')); parsed = urlsplit(href)
                if parsed.scheme or parsed.netloc or not parsed.path: continue
                if parsed.path.startswith('/'): continue
                target = (source.parent/unquote(parsed.path)).resolve()
                if not target.is_relative_to(repo):
                    errors.append(str(source.relative_to(repo))+': escaping source link '+href); continue
                if not target.exists(): errors.append(str(source.relative_to(repo))+': missing source '+href)
                elif parsed.fragment and target.suffix == '.md':
                    if target not in anchors: anchors[target] = heading_ids(target)
                    if unquote(parsed.fragment) not in anchors[target]:
                        errors.append(str(source.relative_to(repo))+': missing source anchor '+href)
    return errors


def readable_surface_errors(repo):
    repo = Path(repo).resolve(); errors = []; digests = {}
    source = repo/'docs/feature-evidence.md'
    if not source.is_file(): return errors
    pattern = r'`([^`]+):(\d+)` \(SHA-256 `([a-f0-9]{64})`\)'
    for line in source.read_text().splitlines():
        if not line.startswith('**Shipped surfaces:**'): continue
        for name, number, recorded in re.findall(pattern,line):
            path = (repo/name).resolve()
            if not path.is_relative_to(repo) or not path.is_file():
                errors.append('Missing human-readable evidence surface: '+name); continue
            if path not in digests: digests[path] = hashlib.sha256(path.read_bytes()).hexdigest()
            if digests[path] != recorded:
                errors.append('Human-readable evidence digest drift: '+name+':'+number)
    return errors
