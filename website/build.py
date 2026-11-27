#!/usr/bin/env python3
"""Build the static documentation; --check rejects stale generated files."""
from pathlib import Path
from html import escape
from html.parser import HTMLParser
import argparse
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from urllib.parse import urlsplit, unquote, quote

from website import layout
from website.content import EVIDENCE_GUIDES
from website.markdown import MarkdownRenderer, reference_pages, source_dispositions, github_anchor_map
from website.content import NOTE_IDS, OVERVIEW_IDS, COMMON_IDS, LABELS, TABLE_LABELS, metadata, support_records, guide_evidence, expand_guide, ai_index, marketing_claims, expand_claims, marketing_cards, marketing_faq, comparison_table

REPO = Path(__file__).resolve().parents[1]
ROOT = REPO / 'website' / 'static'
SOURCE = REPO / 'docs' / 'site'
ORDER = ['', 'quickstart', 'support-matrix', 'sap-commerce', 'spring', 'configuration', 'persistence', 'java', 'troubleshooting', 'configuration-reference', 'evidence']
GROUPS = ['Get started', 'Guides', "When it doesn't work", 'Reference']
REFERENCE_ORDER = ['reference/installation','reference/usage','reference/gradle-plugin','reference/maven','reference/spring-boot-starter','reference/for-ai-agents','reference/mcp-for-coding-agents','reference/mcp-server','reference/sap-commerce','reference/test-guide','reference/reload-support','reference/product-reference','reference/protocol','reference']


class ContentParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.text = []
        self.headings = []
        self.current = None
        self.sections = []
        self.section = None
        self.section_heading = False
    def handle_starttag(self, tag, attrs):
        if tag in ('h2','h3') and dict(attrs).get('id'):
            if self.section: self.sections.append(self.section)
            self.section = {'id':dict(attrs)['id'],'title':[],'text':[]}
            self.section_heading = True
        if tag == 'h2':
            self.current = [dict(attrs).get('id'), []]
    def handle_endtag(self, tag):
        if tag in ('h2','h3'): self.section_heading = False
        if tag == 'h2' and self.current:
            self.headings.append((self.current[0], ''.join(self.current[1])))
            self.current = None
    def handle_data(self, data):
        self.text.append(data)
        if self.section is not None:
            self.section['text'].append(data)
            if self.section_heading: self.section['title'].append(data)
        if self.current is not None:
            self.current[1].append(data)

def url(slug):
    return '/docs/' + (slug + '/' if slug else '')

def route_file(route):
    if not route.startswith('/') or '..' in route or '?' in route or '#' in route:
        raise ValueError('Invalid route: ' + route)
    if route.endswith('/'): return route.lstrip('/') + 'index.html'
    if route.endswith('.html'): return route.lstrip('/')
    raise ValueError('Route must be clean or a legacy HTML alias: ' + route)


def redirect_records(repo, real_routes, revision):
    repo = Path(repo)
    records = json.loads((repo/'website/routes.json').read_text())
    for old, record in list(records.items()):
        route_file(old)
        if old in real_routes: raise ValueError('Redirect shadows real page: ' + old)
        record['destination'] = record['destination'].replace('{revision}',revision)
        source = record.get('source')
        if source:
            expected = github_anchor_map(repo/source)
            if record.get('fragments') != expected:
                raise ValueError('Moved fragment missing or incorrect in map: ' + old)
    for old, record in records.items():
        parsed = urlsplit(record['destination'])
        if parsed.scheme or parsed.netloc:
            allowed = 'https://github.com/onurkat/reclazz/blob/' + revision + '/'
            if not record['destination'].startswith(allowed): raise ValueError('Redirect target not allowed: ' + old)
            source = record.get('source')
            if not source or parsed.path != '/onurkat/reclazz/blob/' + revision + '/' + source or not (repo/source).is_file():
                raise ValueError('Missing external redirect source: ' + old)
        elif parsed.path in records or parsed.path not in real_routes:
            raise ValueError('Redirect loop, chain or missing destination: ' + old)
    # Aliases point directly at final destinations, never at another redirect.
    for route in sorted(set(real_routes) | set(records)):
        if route == '/': continue  # /index.html is already the real root document.
        alias = '/index.html' if route == '/' else route.rstrip('/') + '.html'
        if alias in records: raise ValueError('Duplicate HTML alias: ' + alias)
        records[alias] = dict(records[route]) if route in records else {'destination':route,'fragments':{}}
    legacy = (repo/'website/legacy-routes.txt').read_text().splitlines()
    for route in (line.strip() for line in legacy):
        if route and not route.startswith('#') and route not in real_routes and route not in records:
            raise ValueError('Legacy route without page or stub: ' + route)
    return records


def redirect_html(record):
    destination = record['destination']; fragments = record.get('fragments',{})
    # The same mapping is data for the checker and input for browser navigation.
    data = json.dumps({'destination':destination,'fragments':fragments},ensure_ascii=False).replace('<','\\u003c')
    return ('<!doctype html><html lang="en"><head><meta charset="utf-8">'
            '<title>Page moved | Reclazz</title><meta name="robots" content="noindex">'
            '<link rel="canonical" href="'+escape(destination,quote=True)+'">'
            '<meta http-equiv="refresh" content="0; url='+escape(destination,quote=True)+'">'
            '<script id="redirect-data" type="application/json">'+data+'</script>'
            '<script>(() => { const data = JSON.parse(document.getElementById("redirect-data").textContent);'
            'const target = new URL(data.destination, location.origin);'
            'target.search = location.search || target.search;'
            'let fragment; try { fragment = decodeURIComponent(location.hash.slice(1)); } catch (_) { fragment = location.hash.slice(1); }'
            'if (fragment) target.hash = Object.prototype.hasOwnProperty.call(data.fragments, fragment) ? data.fragments[fragment] : fragment;'
            'location.replace(target.href); })();</script></head><body><p>This page has moved. '
            '<a href="'+escape(destination,quote=True)+'">Continue to the page</a>.</p></body></html>')


def link_chrome(document):
    def arrow(match):
        start, body = match[1], match[2]
        href = re.search(r'href="([^"]+)"',start)
        if href:
            parsed = urlsplit(href[1])
            if not parsed.netloc or parsed.netloc == 'reclazz.com':
                body = re.sub(r'<span[^>]*>\s*↗\s*</span>','',body).replace('↗','')
        return start + body + '</a>'
    return re.sub(r'(<a\b[^>]*>)(.*?)</a>',arrow,document,flags=re.S)


def support_badge(row):
    return '<span class="badge '+row['status']+'">'+TABLE_LABELS[row['status']]+'</span>'


def support_detail(row):
    action = escape(row['action'])
    if row['id'] == 'occ':
        action = 'Repeat the OCC request and check that the response contains your edit. ' + action
    return '<p class="next-action">' + action + '</p><p>' + escape(row['conditions']) + '</p>'


def support_summary(records, renderer):
    by_id={r['id']:r for r in records}
    body='<div class="table-scroll"><table><caption>Six edits to start with</caption><thead><tr><th scope="col">Your change</th><th scope="col">What to expect</th><th scope="col">Before you try it</th></tr></thead><tbody>'
    for key in OVERVIEW_IDS:
        if key not in by_id:continue # small source fixtures need not contain every production row
        row=by_id[key]
        body+='<tr data-summary-id="'+key+'"><th scope="row"><a href="/docs/support-matrix/#'+key+'">'+escape(row['title'])+'</a></th><td>'+support_badge(row)+'</td><td>'+support_detail(row)+'</td></tr>'
    return body+'</tbody></table></div>'


def support_matrix(records,renderer):
    cats=sorted({r['category'] for r in records})
    form='<form class="matrix-controls" hidden role="search" aria-label="Filter reload changes"><label class="matrix-query">Search changes<input id="matrix-query" type="search" placeholder="Try method, properties, endpoint" autocomplete="off"></label><label>Area<select id="matrix-category"><option value="">All areas</option>'+''.join('<option>'+escape(c)+'</option>' for c in cats)+'</select></label><label>Outcome<select id="matrix-status"><option value="">All outcomes</option>'+''.join('<option value="'+k+'">'+v+'</option>' for k,v in TABLE_LABELS.items())+'</select></label><button class="reset-filter" type="reset">Reset</button></form><p class="matrix-count" id="matrix-count" role="status" aria-live="polite">Showing '+str(len(records))+' of '+str(len(records))+' changes and scope notes</p>'
    def attributes(row):
        return ' id="'+row['id']+'" data-support-id="'+row['id']+'" data-category="'+escape(row['category'],quote=True)+'" data-status="'+row['status']+'" data-presentation="'+row['presentation']+'"'
    def scope(row):
        return support_detail(row)+'<a class="matrix-source" href="'+escape(renderer.resolve_link('README.md',row['source']),quote=True)+'">Read conditions</a>'
    notes='<details class="matrix-notes"><summary id="scope-notes">Scope notes ('+str(sum(r["presentation"]=="note" for r in records))+')</summary><p>These expectations describe limits, rather than an edit you can make. The filters include them.</p>'
    for row in records:
        if row['presentation']=='note':notes+='<div class="callout"'+attributes(row)+'><p class="callout-label"><strong>'+escape(row['title'])+'</strong></p>'+support_badge(row)+scope(row)+'</div>'
    notes+='</details>'
    table='<div class="table-scroll matrix-table"><table><caption>Common edits first, followed by other changes</caption><thead><tr><th scope="col">Your change</th><th scope="col">Outcome</th><th scope="col">Conditions &amp; next step</th></tr></thead><tbody>'
    rows=[r for r in records if r['presentation']=='row']
    ordered=sorted(enumerate(rows),key=lambda x:(COMMON_IDS.index(x[1]['id']) if x[1]['id'] in COMMON_IDS else len(COMMON_IDS)+x[0]))
    for _,row in ordered:
        table+='<tr'+attributes(row)+'><th scope="row"><span class="row-category">'+escape(row['category'])+'</span><a class="row-anchor" href="#'+row['id']+'">'+escape(row['title'])+'</a></th><td>'+support_badge(row)+'</td><td>'+scope(row)+'</td></tr>'
    return form+notes+table+'</tbody></table></div><p id="matrix-empty" class="empty-state" hidden>No matching change or scope note. Reset the filters to see everything.</p>'


def guide_rows(keys, records, renderer):
    by_id={r['id']:r for r in records}; selected=[]
    for key in keys:
        if key not in by_id:raise ValueError('Unknown guide support ID: '+key)
        selected.append(by_id[key])
    rows='<div class="table-scroll matrix-table"><table><caption>Your changes and their conditions</caption><thead><tr><th scope="col">Your change</th><th scope="col">Result</th><th scope="col">Before you try it</th></tr></thead><tbody>'
    notes=''
    for row in selected:
        key=row['id'];attrs=' data-guide-id="'+key+'" data-status="'+row['status']+'" data-presentation="'+row['presentation']+'"'
        title='<a href="/docs/support-matrix/#'+key+'">'+escape(row['title'])+'</a>'
        detail=support_detail(row)
        if row['presentation']=='note':
            notes+='<aside class="callout"'+attrs+'><p><strong>'+title+'</strong></p>'+support_badge(row)+detail+'</aside>'
        else:rows+='<tr'+attrs+'><th scope="row">'+title+'</th><td>'+support_badge(row)+'</td><td>'+detail+'</td></tr>'
    return rows+'</tbody></table></div>'+notes


def grouped_options(options,renderer):
    from website.content import OPTION_GROUPS
    keys=[key for values in OPTION_GROUPS.values() for key in values]
    names=[o['name'] for o in options]
    if len(keys)!=len(set(keys)) or set(names)-set(keys) or len(names)!=len(set(names)):
        raise ValueError('Options must be grouped exactly once')
    body='<section aria-labelledby="all-options"><h2 id="all-options">Startup options</h2>'
    intros={
        'Common':'In IntelliJ, open Settings → Tools → Reclazz for structural reload, exclusions and timing. Other keys use the startup argument below.',
        'SAP Commerce':'In IntelliJ, open Settings → Tools → Reclazz. The SAP Commerce controls appear for a detected SAP Commerce project. Use its Install Java Agent action for server startup configuration.',
        'Opt-in behaviour':'In IntelliJ, open Settings → Tools → Reclazz for JPA refresh, static initialization and reload timing. Custom cache-owner rules use the startup argument.',
        'Diagnostics':'In IntelliJ, open Settings → Tools → Reclazz for verbose logging and connection settings. Output, dump and session-log keys use the startup argument.'}
    examples={'Common':'startupDelaySec=30','SAP Commerce':'autoImpex=false','Opt-in behaviour':'jpaRefresh=false','Diagnostics':'verbose=true'}
    for group,keys in OPTION_GROUPS.items():
        group_id='options-'+group.lower().replace(' ','-')
        body+='<section aria-labelledby="'+group_id+'"><h3 id="'+group_id+'">'+group+'</h3><p>'+intros[group]+'</p><div class="code-block"><div class="code-label">Application JVM argument</div><pre><code>-javaagent:/absolute/tools/reclazz-agent-X.Y.Z.jar='+examples[group]+'</code></pre></div><div class="table-scroll matrix-table"><table class="options-table"><caption>'+group+' options</caption><thead><tr><th scope="col">Option</th><th scope="col">Default</th><th scope="col">Behavior and scope</th></tr></thead><tbody>'
        for option in options:
            if option['name'] not in keys:continue
            body+='<tr id="option-'+escape(option['name'])+'" data-option="'+escape(option['name'])+'" data-option-group="'+group+'"><th scope="row"><code>'+escape(option['name'])+'</code></th><td>'+renderer.engine.renderInline(option['default'])+'</td><td>'+renderer.render_inline('docs/usage.md',option['description'])+'</td></tr>'
        body+='</tbody></table></div></section>'
    return body+'</section>'


def history_date(repo, revision, sources):
    """Latest commit touching this page's content, in the requested revision's history."""
    if not (repo / '.git').exists():
        return None
    try:
        result = subprocess.run(
            ['git', 'log', '-1', '--format=%cs', '--end-of-options', revision, '--', *sorted(sources)],
            cwd=repo, capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        return None
    value = result.stdout.strip()
    return value if re.fullmatch(r'\d{4}-\d{2}-\d{2}', value) else None


def guide_content_sources(page, template):
    if page.get('markdown'):
        return {page['markdown']}
    slug = page['slug']
    sources = {'docs/site/' + (slug or 'overview') + '.html'}
    sources.update(re.findall(r'\{\{INCLUDE ([^#}]+)#', template))
    if any(token in template for token in ('{{MATRIX}}', '{{ROWS:', '{{SUPPORT_SUMMARY}}')):
        sources.add('docs/reload-support.md')
    if '{{OPTIONS}}' in template:
        sources.add('docs/usage.md')
    if '{{GUIDE_EVIDENCE}}' in template:
        sources.update('docs/feature-evidence/' + name + '.json'
                       for name, (guide, _) in EVIDENCE_GUIDES.items() if guide == slug)
    return sources


def public_content_sources(name, template, claims):
    sources = {'website/static/' + name}
    if '{{HOME_CARDS}}' in template:
        sources.add('docs/reload-support.md')
    if '{{COMPARE_' in template:
        sources.add('website/compare.json')
    used = set(re.findall(r'\{\{CLAIM:([a-z_]+)\}\}', template))
    if '{{COMPARE_TABLE}}' in template or '{{COMPARE_SUMMARY}}' in template:
        model = json.loads((REPO / 'website/compare.json').read_text())
        for row in model['rows']:
            if '{{COMPARE_TABLE}}' in template or row['id'] in model['home']:
                for cell in row['cells'].values():
                    used.update(re.findall(r'\{\{CLAIM:([a-z_]+)\}\}', cell['text']))
    if used and claims:
        sources.add('website/claims.json')
        sources.update(claims[key]['source'] for key in used if key in claims)
    return sources


def render_sitemap(routes, revision):
    root = ET.Element('urlset', xmlns='http://www.sitemaps.org/schemas/sitemap/0.9')
    today = datetime.now(timezone.utc).date().isoformat()
    date_cache = {}
    for route, sources in sorted(routes.items()):
        key = tuple(sorted(sources))
        if key not in date_cache:
            date_cache[key] = history_date(REPO, revision, key) or today
        entry = ET.SubElement(root, 'url')
        ET.SubElement(entry, 'loc').text = 'https://reclazz.com' + route
        ET.SubElement(entry, 'lastmod').text = date_cache[key]
    ET.indent(root, space='  ')
    return ET.tostring(root, encoding='utf-8', xml_declaration=True).decode() + '\n'


def breadcrumbs(page):
    base = 'https://reclazz.com'
    links = [('Reclazz', '/'), ('Docs', '/docs/')]
    slug = page['slug']
    if slug == 'reference' or slug.startswith('reference/'):
        links.append(('Reference', url('reference')))
    if slug and slug != 'reference':
        links.append((page['title'], url(slug)))
    return {'@context': 'https://schema.org', '@type': 'BreadcrumbList',
            'itemListElement': [{'@type': 'ListItem', 'position': n, 'name': name, 'item': base + route}
                                for n, (name, route) in enumerate(links, 1)]}


def generate(revision='main'):
    manifest = metadata(REPO)
    base = 'https://github.com/onurkat/reclazz/blob/' + revision + '/'
    pages = sorted(manifest['pages'], key=lambda p: ORDER.index(p['slug']))
    support = support_records(REPO)
    # The usage table is the only authored option table; derive every build.
    usage = (REPO / 'docs/usage.md').read_text(encoding='utf-8')
    table = usage.split('| Argument | Default | Description |', 1)[1].split('\n\n', 1)[0]
    options = []
    for line in table.splitlines():
        if line.startswith('| `'):
            cells = [cell.strip() for cell in line.strip('|').split('|')]
            options.append({'name': cells[0].strip('`'), 'default': cells[1], 'description': '|'.join(cells[2:])})
    pages.extend(sorted(reference_pages(REPO),key=lambda p: REFERENCE_ORDER.index(p['slug']) if p['slug'] in REFERENCE_ORDER else len(REFERENCE_ORDER)))
    renderer = MarkdownRenderer(REPO, pages, revision)

    output = {p.relative_to(ROOT).as_posix(): p.read_bytes() for p in ROOT.rglob('*') if p.is_file()}
    page_sources = {}
    claims_path = REPO / 'website/claims.json'
    claim_sources = json.loads(claims_path.read_text()) if claims_path.is_file() else {}
    public_urls = {item.text for item in ET.parse(ROOT / 'sitemap.xml').findall('.//{*}loc')}
    for name, kind in layout.PUBLIC_PAGES.items():
        if name in output:
            template = output[name].decode('utf-8')
            if name.endswith('index.html'):
                route = '/' + name[:-len('index.html')]
                if 'https://reclazz.com' + route in public_urls and route not in ('/license/', '/privacy/'):
                    page_sources[route] = public_content_sources(name, template, claim_sources)
            output[name] = link_chrome(layout.render_public(template,kind,manifest['reviewed'][:4],manifest['source_version']))
    if (REPO/'website/claims.json').is_file():
        values=marketing_claims(REPO)
        faq, schema=marketing_faq(values)
        for name in ('index.html','compare/index.html','assets/i18n/tr.json'):
            if name not in output: continue
            text=output[name].decode('utf-8') if isinstance(output[name],bytes) else output[name]
            text=text.replace('{{HOME_CARDS}}',marketing_cards(support,renderer)).replace('{{HOME_FAQ}}',faq).replace('{{HOME_FAQ_SCHEMA}}',schema)
            text=text.replace('{{COMPARE_SUMMARY}}',comparison_table(REPO,renderer,values,True)).replace('{{COMPARE_TABLE}}',comparison_table(REPO,renderer,values))
            output[name]=expand_claims(text,values,'tr' if name=='assets/i18n/tr.json' else 'en')
    index = []
    for page in pages:
        slug = page['slug']
        nav = ''
        for group in GROUPS:
            links = ''.join('<a' + (' aria-current="page"' if p['slug'] == slug else '') + ' href="' + url(p['slug']) + '">' + escape(p['nav_title']) + '</a>' for p in pages if p['group'] == group)
            if links: nav += '<div class="nav-group"><p>' + escape(group) + '</p>' + links + '</div>'
        template = '' if page.get('markdown') else (SOURCE / ((slug or 'overview') + '.html')).read_text(encoding='utf-8')
        page_sources[url(slug)] = guide_content_sources(page, template)
        body = renderer.render(page['markdown']) if page.get('markdown') else expand_guide(template, renderer, manifest)
        title = page['title']
        if slug and 'reclazz' not in title.lower() and len(title + ' | Reclazz docs') <= 60:
            title += ' | Reclazz docs'
        body=body.replace('{{SUPPORT_SUMMARY}}',support_summary(support,renderer)) if '{{SUPPORT_SUMMARY}}' in body else body
        body=re.sub(r'\{\{ROWS:([^}]+)\}\}',lambda m:guide_rows([x.strip() for x in m[1].split(',')],support,renderer),body)
        if '{{GUIDE_EVIDENCE}}' in body:
            body = body.replace('{{GUIDE_EVIDENCE}}', guide_evidence(renderer, slug))
        if '{{MATRIX}}' in body:
            body=body.replace('{{MATRIX}}',support_matrix(support,renderer))
            for row in support:
                index.append({'title':row['title'],'section':row['category']+' · '+LABELS[row['status']],'url':url(slug)+'#'+row['id'],'text':row['conditions']+' '+row['action']})
        if '{{OPTIONS}}' in body:
            body=body.replace('{{OPTIONS}}',grouped_options(options,renderer))
        if page.get('markdown'):
            if body.startswith('<p>') and page['slug'] not in ('reference/installation', 'reference/sap-commerce', 'reference/test-guide'):
                body = body.replace('<p>', '<p class="lead">', 1)
            else:
                body = '<p class="lead">' + escape(page['description']) + '</p>' + body
        parser = ContentParser()
        parser.feed(body)
        toc = '<p class="toc-label">On this page</p>' + ''.join('<a href="#' + str(id) + '">' + escape(title) + '</a>' for id, title in parser.headings)
        sources = ''
        if page.get('markdown'):
            sources = '<p class="source-link"><a href="https://github.com/onurkat/reclazz/blob/' + revision + '/' + page['markdown'] + '">Edit on GitHub ↗</a></p>'
        pager_pages = [p for p in pages if p['group'] != 'Footer']
        i = pager_pages.index(page) if page in pager_pages else None
        previous = pager_pages[i - 1] if i is not None and i else None
        following = pager_pages[i + 1] if i is not None and i + 1 < len(pager_pages) else None
        pager = '<nav class="page-pager" aria-label="Documentation pages">' + ('<a href="' + url(previous['slug']) + '"><span>← Previous</span><strong>' + escape(previous['title'] if previous['slug'] else 'Overview') + '</strong></a>' if previous else '<span></span>') + ('<a href="' + url(following['slug']) + '"><span>Next →</span><strong>' + escape(following['title']) + '</strong></a>' if following else '') + '</nav>'
        schema = {'@context': 'https://schema.org', '@type': 'TechArticle', 'headline': page['title'], 'description': page['description'], 'url': 'https://reclazz.com' + url(slug), 'dateModified': manifest['reviewed'], 'version': manifest['source_version']}
        heading = '' if not slug else '<h1' + (' id="' + page['heading_id'] + '"' if page.get('heading_id') else '') + '>' + escape(page['title']) + '</h1>' + ('' if page.get('markdown') else '<p class="lead">' + escape(page['description']) + '</p>')
        document = '''<!doctype html>
<html lang="en" data-theme="dark">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>''' + escape(title) + '''</title>
<meta name="description" content="''' + escape(page['description'], quote=True) + '''">
<meta name="theme-color" content="#0b0d12"><meta name="color-scheme" content="dark light">
<link rel="canonical" href="https://reclazz.com''' + url(slug) + '''">
<meta property="og:type" content="article"><meta property="og:title" content="''' + escape(page['title'], quote=True) + ''' | Reclazz docs">
<meta property="og:description" content="''' + escape(page['description'], quote=True) + '''"><meta property="og:url" content="https://reclazz.com''' + url(slug) + '''">
<meta property="og:image" content="https://reclazz.com/og-image.png">
<link rel="icon" href="/favicon.svg" type="image/svg+xml"><link rel="apple-touch-icon" href="/apple-touch-icon.png">
''' + layout.head() + '''
<script type="application/ld+json">''' + json.dumps(schema).replace('<', '\\u003c') + '''</script>
<script type="application/ld+json">''' + json.dumps(breadcrumbs(page)).replace('<', '\\u003c') + '''</script>
</head>
<body class="docs-page''' + (' matrix-page' if slug == 'support-matrix' else '') + '''">
''' + layout.header('setup' if slug == 'sap-commerce' else 'docs') + '''
''' + layout.mobile_navigation(nav) + '''
<div class="docs-shell"><aside class="sidebar"><nav aria-label="Documentation">''' + nav + '''</nav><a class="sidebar-source" href="https://github.com/onurkat/reclazz"><strong>Source on GitHub ↗</strong></a></aside>
<main id="main" tabindex="-1"><div class="breadcrumb"><a href="/docs/">Documentation</a><span aria-hidden="true">/</span><span>''' + escape(page['group'] if page['group'] != 'Footer' else 'Project') + '''</span><span aria-hidden="true">/</span><span>''' + escape(page['title'] if slug else 'Overview') + '''</span></div><article>''' + heading + body + sources + pager + '''</article></main><aside class="page-toc"><nav aria-label="On this page">''' + toc + '''</nav><div class="toc-help"><span>Choosing an edit?</span><a href="/docs/support-matrix/">Check the reload matrix ↗</a></div></aside></div>
''' + layout.footer(manifest['reviewed'][:4],version=manifest['source_version']) + layout.utilities() + '''
</body></html>
'''
        target = 'docs/' + (slug + '/' if slug else '') + 'index.html'
        output[target] = link_chrome(document)
        index.append({'title': page['title'], 'section': page['eyebrow'], 'url': url(slug), 'text': ' '.join(' '.join(parser.text).split())[:1200]})
        # The complete evidence registry remains readable/downloadable; avoid copying
        # its thousands of provenance lines into every visitor's search download.
        if page.get('markdown') != 'docs/feature-evidence.md':
            for section in parser.sections + ([parser.section] if parser.section else []):
                index.append({'title': ''.join(section['title']), 'section':page['nav_title'],
                              'url':url(slug)+'#'+section['id'], 'text':' '.join(' '.join(section['text']).split())})
    if 'jrebel-alternative/index.html' in output:
        from website.localized import add_pages
        add_pages(output, page_sources, REPO, renderer)
    real_routes = {'/' + name[:-len('index.html')] for name in output if name.endswith('index.html')}
    redirects = redirect_records(REPO,real_routes,revision)
    for route, record in redirects.items(): output[route_file(route)] = redirect_html(record)
    for name, value in list(output.items()):
        if not (name.endswith('index.html') or name == '404.html') or (name != '404.html' and '/' + name[:-len('index.html')] in redirects): continue
        document = value.decode('utf-8') if isinstance(value,bytes) else value
        for asset_name, asset_value in output.items():
            if not asset_name.endswith(('.css','.js')): continue
            data = asset_value if isinstance(asset_value,bytes) else asset_value.encode('utf-8')
            digest = hashlib.sha256(data).hexdigest()[:12]
            document = document.replace('/'+asset_name+'"','/'+asset_name+'?v='+digest+'"')
        output[name] = layout.enhance_document(document)
    output['llms.txt'] = ai_index(REPO,pages,manifest)
    output['docs/assets/search-index.json'] = json.dumps(index, ensure_ascii=False, separators=(',', ':')) + '\n'
    output['docs/assets/support.json'] = json.dumps(support, indent=2) + '\n'
    output.update(renderer.assets)
    output['sitemap.xml'] = render_sitemap(page_sources, revision)
    inputs = [p for p in SOURCE.rglob('*') if p.is_file()] + list((REPO / 'docs').glob('*.md')) + list((REPO / 'docs/feature-evidence').glob('*')) + [p for p in ROOT.rglob('*') if p.is_file()] + list((REPO/'website').glob('*.py')) + [REPO/'website/routes.json', REPO/'website/legacy-routes.txt', *[p for p in (REPO/'website').glob('*.json')], REPO/'requirements-docs.txt', REPO/'gradle.properties', REPO/'README.md', REPO/'SECURITY.md']
    output['docs/assets/build-manifest.json'] = json.dumps({
        'source_commit': manifest['source_commit'], 'source_version': manifest['source_version'],
        'reviewed': manifest['reviewed'], 'build_revision': revision, 'pages': len(pages),
        'site_pages':sorted([route_file(route) for route in real_routes] + (['404.html'] if '404.html' in output else [])),
        'on_demand_data': {name:len(value if isinstance(value,bytes) else value.encode('utf-8')) for name,value in output.items() if name in ('docs/assets/search-index.json','assets/i18n/tr.json')},
        'redirects':redirects,
        'curated_pages': len(manifest['pages']), 'reference_pages': len(pages) - len(manifest['pages']),
        'support_entries': len(support), 'options': len(options), 'search_entries':len(index),
        'documents': {name: {**spec, 'sha256':hashlib.sha256((REPO/name).read_bytes()).hexdigest()} for name,spec in source_dispositions(REPO).items()},
        'inputs': {p.relative_to(REPO).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(inputs))}
    }, indent=2) + '\n'
    return output

def styleguide(output):
    """Audit-only artifact: sibling of the published site, never in generate()."""
    destination=Path(output);destination.mkdir(parents=True,exist_ok=True)
    examples='''<nav class="breadcrumb" aria-label="Breadcrumb"><a href="/docs/">Documentation</a><span>/</span><span>Design system</span></nav><h1>Design system</h1><p>Audit reference for both themes. Use the theme button to compare states.</p><h2>Typography and surfaces</h2><p>Body 17px · Small 15px · Caption 13px. Aa Ğğ İı Şş Çç Öö Üü 0123456789.</p><div class="token-swatches">'''
    for name in ('bg','surface','surface-hover','surface-alt','text','muted','dim','accent','green','gold','rose'):
        examples+='<p style="border-left:24px solid var(--'+name+');padding-left:16px">'+name+'</p>'
    examples+='''</div><h2>Actions and cards</h2><p><a class="btn btn-primary" href="/docs/">Primary</a> <a class="btn btn-secondary" href="/docs/">Secondary</a> <a class="btn btn-ghost" href="/docs/">Ghost</a></p><a class="path-card" href="/docs/quickstart/">Read the quickstart</a><h2>Outcomes</h2>'''
    for key,label in LABELS.items():examples+='<span class="badge '+key+'">'+layout.icon('check' if key=='works' else 'circle-help')+label+'</span> '
    examples+='<h2>Scales and elevation</h2>'
    for prefix,count in [('neutral',12),('brand',5)]:
        for i in range(0 if prefix=='neutral' else 1,count if prefix=='neutral' else count+1):examples+='<span style="display:inline-block;width:72px;height:48px;background:var(--'+prefix+'-'+str(i)+');border:1px solid var(--border)" aria-label="'+prefix+' '+str(i)+'"></span>'
    for size in (13,15,17,20,24,30,38,48,60):examples+='<p style="font-size:'+str(size)+'px">Type '+str(size)+'</p>'
    for kind in ('sm','md','lg'):examples+='<p style="padding:24px;border:1px solid var(--border);border-radius:var(--radius-'+kind+');box-shadow:var(--shadow-'+kind+')">Radius and shadow '+kind+'</p>'
    examples+='<h2>Callouts</h2>'
    for key in ('note','tip','warning','restart'):examples+='<aside class="callout" data-kind="'+key+'"><div class="callout-label">'+layout.icon('info')+key.title()+'</div><p>Visible text explains the outcome independently of color.</p></aside>'
    examples+='''<h2>Tabs and code</h2><div role="tablist" aria-label="Build tool" data-preference="build-tool"><button role="tab" id="gradle-tab" aria-controls="gradle-panel" data-choice="gradle">Gradle</button><button role="tab" id="maven-tab" aria-controls="maven-panel" data-choice="maven">Maven</button></div><div role="tabpanel" id="gradle-panel" aria-labelledby="gradle-tab"><div class="code-block"><div class="code-label">shell</div><pre><code>./gradlew classes</code></pre></div></div><div role="tabpanel" id="maven-panel" aria-labelledby="maven-tab"><div class="code-block"><div class="code-label">shell</div><pre><code>mvn compile</code></pre></div></div><h2>Steps, table and disclosure</h2><ol class="steps"><li>Make an edit.</li><li>Compile and check the outcome.</li></ol><div class="table-scroll"><table><thead><tr><th>State</th><th>Meaning</th></tr></thead><tbody><tr><td>Ready</td><td>Continue your session.</td></tr></tbody></table></div><details><summary>Tested with</summary><p>Component demonstration only.</p></details><figure><div class="figure-chrome" aria-hidden="true">Preview</div><img src="/og-image.png" alt="Reclazz project artwork" width="1200" height="630"><figcaption>Figure and caption</figcaption></figure>'''
    html='<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Design system audit</title>'+layout.head()+'</head><body>'+layout.header()+layout.mobile_navigation('<a href="/docs/">Overview</a><a href="/docs/quickstart/">Quickstart</a>')+'<main id="main" tabindex="-1" style="max-width:808px;padding:48px 24px"><article>'+examples+'<nav class="page-pager" aria-label="Documentation pages"><a href="/docs/"><span>Previous</span><strong>Overview</strong></a><a href="/docs/quickstart/"><span>Next</span><strong>Quickstart</strong></a></nav></article></main>'+layout.footer()+layout.utilities()+'</body></html>'
    (destination/'index.html').write_text(layout.enhance_document(html))
    for p in ROOT.rglob('*'):
        if p.is_file() and (p.suffix in ('.css','.js','.woff2','.svg','.png') or p.name=='tr.json'):
            target=destination/p.relative_to(ROOT);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(p.read_bytes())
    (destination/'docs/assets/search-index.json').write_text('[]')


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument('--check', action='store_true')
    args.add_argument('--output', type=Path, default=REPO/'build/site')
    args.add_argument('--revision', default='main', help='Source revision for repository links (CI uses the exact commit)')
    options = args.parse_args()
    destination = options.output.resolve()
    # Build output must not overwrite the authoring tree.
    if destination == REPO or (destination.is_relative_to(REPO) and not destination.is_relative_to(REPO/'build')):
        raise SystemExit('Output must be a separate build directory, not documentation source')
    output = generate(options.revision)
    stale = []
    for name, content in output.items():
        path = destination / name
        data = content if isinstance(content, bytes) else content.encode('utf-8')
        if options.check:
            if not path.exists() or path.read_bytes() != data: stale.append(name)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    expected = set(output)
    extras = [p for p in destination.rglob('*') if p.is_file() and p.relative_to(destination).as_posix() not in expected]
    if options.check:
        stale.extend('obsolete output: ' + p.relative_to(destination).as_posix() for p in extras)
    else:
        for path in extras: path.unlink()
    if stale: raise SystemExit('Stale generated documentation: ' + ', '.join(stale))
    if not options.check: styleguide(REPO/'build/styleguide')
    stats = json.loads(output['docs/assets/build-manifest.json'])
    print(('CHECK OK' if options.check else 'BUILT') + ': ' + str(stats['pages']) + ' pages (' + str(stats['reference_pages']) + ' full source references), ' + str(stats['support_entries']) + ' support entries, ' + str(stats['options']) + ' derived options')

if __name__ == '__main__':
    main()
