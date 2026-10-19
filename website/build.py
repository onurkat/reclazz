#!/usr/bin/env python3
"""Build the static documentation; --check rejects stale generated files."""
from pathlib import Path
from html import escape
from html.parser import HTMLParser
import argparse
import hashlib
import json
import re

from website import layout
from website.markdown import MarkdownRenderer, reference_pages
from website.content import LABELS, metadata, support_records, expand_guide, ai_index

REPO = Path(__file__).resolve().parents[1]
ROOT = REPO / 'website' / 'static'
SOURCE = REPO / 'docs' / 'site'
ORDER = ['', 'quickstart', 'support-matrix', 'java', 'spring', 'messaging', 'configuration', 'sap-commerce', 'persistence', 'boundaries', 'configuration-reference', 'troubleshooting', 'evidence']
GROUPS = ['Start here', 'Reload reference', 'Guides', 'Operations', 'Project', 'Technical reference', 'Maintaining']

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
    pages.extend(reference_pages(REPO))
    renderer = MarkdownRenderer(REPO, pages, revision)

    output = {p.relative_to(ROOT).as_posix(): p.read_bytes() for p in ROOT.rglob('*') if p.is_file()}
    for name, kind in layout.PUBLIC_PAGES.items():
        if name in output:
            output[name] = layout.render_public(output[name].decode('utf-8'),kind,manifest['reviewed'][:4])
    index = []
    for page in pages:
        slug = page['slug']
        nav = ''
        for group in GROUPS:
            links = ''.join('<a' + (' aria-current="page"' if p['slug'] == slug else '') + ' href="' + url(p['slug']) + '">' + ('<span class="nav-icon" aria-hidden="true">↳</span>' if p['slug'] != slug else '<span class="nav-icon" aria-hidden="true">◈</span>') + escape(p['nav_title']) + '</a>' for p in pages if p['group'] == group)
            if group == 'Technical reference':
                nav += '<details class="nav-group reference-nav"' + (' open' if page.get('markdown') else '') + '><summary>Technical reference</summary>' + links + '</details>'
            else:
                nav += '<div class="nav-group"><p>' + group + '</p>' + links + '</div>' 
        body = renderer.render(page['markdown']) if page.get('markdown') else expand_guide((SOURCE / ((slug or 'overview') + '.html')).read_text(encoding='utf-8'), renderer, manifest)
        if '{{MATRIX}}' in body:
            cats = sorted({r['category'] for r in support})
            filters = '''<form class="matrix-controls" hidden role="search" aria-label="Filter reload changes"><label class="matrix-query">Search changes<input id="matrix-query" type="search" placeholder="Try @Bean, YAML, enum, schema…" autocomplete="off"></label><label>Area<select id="matrix-category"><option value="">All areas</option>''' + ''.join('<option>' + escape(c) + '</option>' for c in cats) + '''</select></label><label>Outcome<select id="matrix-status"><option value="">All outcomes</option>''' + ''.join('<option value="' + key + '">' + value + '</option>' for key, value in LABELS.items()) + '''</select></label><button class="reset-filter" type="reset">Reset</button></form><p class="matrix-count" id="matrix-count" role="status" aria-live="polite">Showing ''' + str(len(support)) + ' changes</p>'
            table = '<div class="table-scroll matrix-table"><table><caption>Reload outcomes in the documented source snapshot</caption><thead><tr><th scope="col">Your change</th><th scope="col">Outcome</th><th scope="col">Conditions &amp; next step</th></tr></thead><tbody>'
            for row in support:
                table += '<tr id="' + row['id'] + '" data-category="' + escape(row['category']) + '" data-status="' + row['status'] + '"><th scope="row"><span class="row-category">' + row['category'] + '</span><a class="row-anchor" href="#' + row['id'] + '">' + escape(row['title']) + '</a></th><td><span class="badge ' + row['status'] + '">' + LABELS[row['status']] + '</span></td><td><p>' + escape(row['conditions']) + '</p><p class="next-action"><strong>Next:</strong> ' + escape(row['action']) + '</p><a class="matrix-source" href="' + escape(renderer.resolve_link('README.md',row['source']),quote=True) + '">Read conditions &amp; scope →</a></td></tr>'
                index.append({'title': row['title'], 'section': row['category'] + ' · ' + LABELS[row['status']], 'url': url(slug) + '#' + row['id'], 'text': row['conditions'] + ' ' + row['action']})
            body = body.replace('{{MATRIX}}', filters + table + '</tbody></table></div><p id="matrix-empty" class="empty-state" hidden>No matching change. Try another term or reset the filters. Missing from this table does not establish support.</p>')
        if '{{OPTIONS}}' in body:
            table = '<section aria-labelledby="all-options"><h2 id="all-options">Startup options</h2><div class="table-scroll"><table class="options-table"><caption>Accepted keys and documented defaults at ' + escape(manifest['source_version']) + '</caption><thead><tr><th scope="col">Option</th><th scope="col">Default</th><th scope="col">Behavior and scope</th></tr></thead><tbody>'
            for option in options:
                table += '<tr id="option-' + escape(option['name']) + '"><th scope="row"><code>' + escape(option['name']) + '</code></th><td>' + renderer.engine.renderInline(option['default']) + '</td><td>' + renderer.render_inline('docs/usage.md',option['description']) + '</td></tr>'
            body = body.replace('{{OPTIONS}}', table + '</tbody></table></div></section>')
        parser = ContentParser()
        parser.feed(body)
        toc = '<p class="toc-label">On this page</p>' + ''.join('<a href="#' + str(id) + '">' + escape(title) + '</a>' for id, title in parser.headings)
        sources = '<details class="page-sources"><summary>Sources &amp; version scope</summary><p>Documentation source ' + escape(manifest['source_version']) + ' · evidence baseline <a href="https://github.com/onurkat/reclazz/tree/' + manifest['source_commit'] + '"><code>' + manifest['source_commit'][:7] + '</code></a> · reviewed ' + escape(manifest['reviewed_label']) + '. Check your installed artifact; source features may postdate a release.</p><ul>' + ''.join('<li><a href="' + base + path + '">' + escape(path) + ' ↗</a></li>' for path in page['sources']) + '</ul></details>'
        if page.get('markdown'):
            sources = '<details class="page-sources"><summary>Canonical source</summary><p>This full page is generated from <a href="https://github.com/onurkat/reclazz/blob/' + revision + '/' + page['markdown'] + '">' + page['markdown'] + '</a>. Edit the repository source and rebuild; do not edit generated HTML.</p></details>'
        i = pages.index(page)
        previous = pages[i - 1] if i else None
        following = pages[i + 1] if i + 1 < len(pages) else None
        pager = '<nav class="page-pager" aria-label="Documentation pages">' + ('<a href="' + url(previous['slug']) + '"><span>← Previous</span><strong>' + escape(previous['title'] if previous['slug'] else 'Overview') + '</strong></a>' if previous else '<span></span>') + ('<a href="' + url(following['slug']) + '"><span>Next →</span><strong>' + escape(following['title']) + '</strong></a>' if following else '') + '</nav>'
        schema = {'@context': 'https://schema.org', '@type': 'TechArticle', 'headline': page['title'], 'description': page['description'], 'url': 'https://reclazz.com' + url(slug), 'dateModified': manifest['reviewed'], 'version': manifest['source_version']}
        heading = '' if not slug else '<h1' + (' id="' + page['heading_id'] + '"' if page.get('heading_id') else '') + '>' + escape(page['title']) + '</h1>' + ('' if page.get('markdown') else '<p class="lead">' + escape(page['description']) + '</p>')
        document = '''<!doctype html>
<html lang="en" data-theme="dark">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>''' + escape(page['title']) + ''' | Reclazz docs</title>
<meta name="description" content="''' + escape(page['description'], quote=True) + '''">
<meta name="theme-color" content="#0b0d12"><meta name="color-scheme" content="dark light">
<link rel="canonical" href="https://reclazz.com''' + url(slug) + '''">
<meta property="og:type" content="article"><meta property="og:title" content="''' + escape(page['title'], quote=True) + ''' | Reclazz docs">
<meta property="og:description" content="''' + escape(page['description'], quote=True) + '''"><meta property="og:url" content="https://reclazz.com''' + url(slug) + '''">
<meta property="og:image" content="https://reclazz.com/og-image.png">
<link rel="icon" href="/favicon.svg" type="image/svg+xml"><link rel="apple-touch-icon" href="/apple-touch-icon.png">
''' + layout.head() + '''
<script type="application/ld+json">''' + json.dumps(schema).replace('<', '\\u003c') + '''</script>
</head>
<body class="''' + ('matrix-page' if slug == 'support-matrix' else '') + '''">
''' + layout.header('docs') + '''
<details class="mobile-nav"><summary>Documentation menu <span aria-hidden="true">⌄</span></summary><nav aria-label="Mobile documentation">''' + nav + '''</nav></details>
<div class="docs-shell"><aside class="sidebar"><div class="snapshot-label"><span class="live-dot"></span> Source snapshot <code>''' + escape(manifest['source_version']) + '''</code></div><nav aria-label="Documentation">''' + nav + '''</nav><a class="sidebar-source" href="https://github.com/onurkat/reclazz"><span>Built in the open.</span><strong>Explore the source ↗</strong></a></aside>
<main id="main" tabindex="-1"><div class="breadcrumb"><a href="/docs/">Documentation</a><span aria-hidden="true">/</span><span>''' + escape(page['eyebrow']) + '''</span></div><article><div class="article-eyebrow">''' + escape(page['eyebrow']) + '''</div>''' + heading + body + sources + pager + '''</article></main><aside class="page-toc"><nav aria-label="On this page">''' + toc + '''</nav><div class="toc-help"><span>Choosing an edit?</span><a href="/docs/support-matrix/">Check the reload matrix ↗</a></div></aside></div>
''' + layout.footer(manifest['reviewed'][:4]) + layout.utilities() + '''
</body></html>
'''
        target = 'docs/' + (slug + '/' if slug else '') + 'index.html'
        output[target] = document
        index.append({'title': page['title'], 'section': page['eyebrow'], 'url': url(slug), 'text': ' '.join(' '.join(parser.text).split())[:1200]})
        # The complete evidence registry remains readable/downloadable; avoid copying
        # its thousands of provenance lines into every visitor's search download.
        if page.get('markdown') != 'docs/feature-evidence.md':
            for section in parser.sections + ([parser.section] if parser.section else []):
                index.append({'title': ''.join(section['title']), 'section':page['nav_title'],
                              'url':url(slug)+'#'+section['id'], 'text':' '.join(' '.join(section['text']).split())})
    for name, value in list(output.items()):
        if not name.endswith('index.html'): continue
        document = value.decode('utf-8') if isinstance(value,bytes) else value
        for asset_name, asset_value in output.items():
            if not asset_name.endswith(('.css','.js')): continue
            data = asset_value if isinstance(asset_value,bytes) else asset_value.encode('utf-8')
            digest = hashlib.sha256(data).hexdigest()[:12]
            document = document.replace('/'+asset_name+'"','/'+asset_name+'?v='+digest+'"')
        output[name] = document
    output['llms.txt'] = ai_index(REPO,pages,manifest)
    output['docs/assets/search-index.json'] = json.dumps(index, ensure_ascii=False, separators=(',', ':')) + '\n'
    output['docs/assets/support.json'] = json.dumps(support, indent=2) + '\n'
    output.update(renderer.assets)
    # Sitemap remains owned by the existing site; retain non-doc URLs verbatim.
    sitemap = (ROOT / 'sitemap.xml').read_text()
    sitemap = re.sub(r'\s*<url>\s*<loc>https://reclazz\.com/docs/.*?</url>', '', sitemap, flags=re.S)
    entries = ''.join('    <url><loc>https://reclazz.com' + url(p['slug']) + '</loc><lastmod>' + manifest['reviewed'] + '</lastmod><changefreq>monthly</changefreq><priority>' + ('0.8' if not p['slug'] else '0.7') + '</priority></url>\n' for p in pages)
    output['sitemap.xml'] = sitemap.replace('</urlset>', entries + '</urlset>')
    inputs = [p for p in SOURCE.rglob('*') if p.is_file()] + list((REPO / 'docs').glob('*.md')) + list((REPO / 'docs/feature-evidence').glob('*')) + [p for p in ROOT.rglob('*') if p.is_file()] + list((REPO/'website').glob('*.py')) + [REPO/'requirements-docs.txt', REPO/'gradle.properties', REPO/'README.md', REPO/'SECURITY.md']
    output['docs/assets/build-manifest.json'] = json.dumps({
        'source_commit': manifest['source_commit'], 'source_version': manifest['source_version'],
        'reviewed': manifest['reviewed'], 'build_revision': revision, 'pages': len(pages),
        'site_pages':sorted(name for name in output if name.endswith('index.html')),
        'curated_pages': len(manifest['pages']), 'reference_pages': len(pages) - len(manifest['pages']),
        'support_entries': len(support), 'options': len(options), 'search_entries':len(index),
        'documents': {p['markdown']: {'url': url(p['slug']), 'sha256': hashlib.sha256((REPO/p['markdown']).read_bytes()).hexdigest()} for p in pages if p.get('markdown')},
        'inputs': {p.relative_to(REPO).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(inputs))}
    }, indent=2) + '\n'
    return output

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
    stats = json.loads(output['docs/assets/build-manifest.json'])
    print(('CHECK OK' if options.check else 'BUILT') + ': ' + str(stats['pages']) + ' pages (' + str(stats['reference_pages']) + ' full source references), ' + str(stats['support_entries']) + ' support entries, ' + str(stats['options']) + ' derived options')

if __name__ == '__main__':
    main()
