"""Validate source coverage and the complete generated website without a network call."""
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit, unquote
import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET

from markdown_it import MarkdownIt
from website import build
from website.content import source_link_errors, readable_surface_errors

class Document(HTMLParser):
    def __init__(self, text):
        super().__init__(convert_charrefs=True)
        self.ids = []; self.links = []; self.headings = []
        self.headers = 0; self.footers = 0
        self.mains = 0; self.title_count = 0; self.canonical = []; self.active = 0
        self.lang = None; self.images = []
        self.feed(text)
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == 'html': self.lang = a.get('lang')
        if a.get('id'): self.ids.append(a['id'])
        if tag in ('a','link') and a.get('href'): self.links.append(a['href'])
        if tag in ('img','script') and a.get('src'): self.links.append(a['src'])
        if tag == 'img': self.images.append(a)
        if tag == 'a' and a.get('aria-current') == 'page': self.active += 1
        if tag in ('h1','h2','h3','h4'): self.headings.append(int(tag[1]))
        if tag == 'main': self.mains += 1
        if tag == 'header': self.headers += 1
        if tag == 'footer': self.footers += 1
        if tag == 'title': self.title_count += 1
        if tag == 'link' and a.get('rel') == 'canonical': self.canonical.append(a['href'])


def source_index_errors(repo):
    repo = Path(repo)
    names = {p.name for p in (repo/'docs').glob('*.md')} - {'index.md'}
    tokens = MarkdownIt('commonmark').parse((repo/'docs/index.md').read_text())
    linked = set()
    for token in tokens:
        for child in token.children or []:
            if child.type == 'link_open':
                parsed = urlsplit(str(child.attrGet('href')))
                if not parsed.scheme and not parsed.netloc: linked.add(parsed.path)
    return ['Unindexed source document: ' + name for name in sorted(names - linked)]


def output_differences(output, expected):
    output = Path(output)
    differences = []
    for name, value in expected.items():
        data = value if isinstance(value, bytes) else value.encode('utf-8')
        path = output/name
        if not path.is_file() or path.read_bytes() != data: differences.append('Stale or missing output: ' + name)
    extras = {p.relative_to(output).as_posix() for p in output.rglob('*') if p.is_file()} - set(expected)
    differences.extend('Obsolete output: ' + p for p in sorted(extras))
    return differences


def check_site(output, revision='main'):
    output = Path(output).resolve()
    errors = source_index_errors(build.REPO) + source_link_errors(build.REPO) + readable_surface_errors(build.REPO)
    expected = build.generate(revision)
    errors.extend(output_differences(output, expected))
    # Invalid/missing output must fail before attempting to parse a partial tree.
    if errors: return errors, Counter()
    stats = Counter()
    def check(ok, message):
        stats['checks'] += 1
        if not ok: errors.append(message)
    manifest = json.loads((output/'docs/assets/build-manifest.json').read_text())
    index = json.loads((output/'docs/assets/search-index.json').read_text())
    documents = {p: Document(p.read_text()) for p in output.rglob('index.html')}
    docs = {p:d for p,d in documents.items() if p.is_relative_to(output/'docs')}
    check(len(documents) == len(manifest['site_pages']), 'Whole-site page count drift')
    check(len(docs) == manifest['pages'], 'Page count disagrees with provenance manifest')
    check(len(manifest['documents']) == len(list((build.REPO/'docs').glob('*.md'))), 'Canonical Markdown coverage mismatch')
    for source, record in manifest['documents'].items():
        check(hashlib.sha256((build.REPO/source).read_bytes()).hexdigest() == record['sha256'], 'Wrong canonical source digest: ' + source)
        check((output/record['url'].lstrip('/')/'index.html').is_file(), 'Source has no public page: ' + source)
    for path, doc in documents.items():
        check(doc.lang == 'en', str(path) + ': missing language')
        check(doc.headings.count(1) == 1 and doc.mains == 1 and doc.title_count == 1, str(path) + ': require one H1/main/title')
        check(len(doc.ids) == len(set(doc.ids)), str(path) + ': duplicate IDs')
        if path in docs: check(doc.active == 2, str(path) + ': desktop/mobile active navigation mismatch')
        check(doc.headers == 1 and doc.footers == 1, str(path) + ': require one shared header/footer')
        for control in ('theme-toggle','search-open','search-dialog'):
            check(control in doc.ids, str(path)+': missing shared '+control)
        check('{{SITE_' not in path.read_text(), str(path)+': unexpanded layout marker')
        check(len(doc.canonical) == 1, str(path) + ': require canonical URL')
        check(all('alt' in img for img in doc.images), str(path) + ': image lacks alt')
        check('Skip to content' in path.read_text(), str(path) + ': missing skip link')
        for href in doc.links:
            parsed = urlsplit(href)
            if parsed.scheme or parsed.netloc: continue
            dest = output / unquote(parsed.path).lstrip('/') if parsed.path.startswith('/') else path.parent / unquote(parsed.path)
            if not parsed.path: dest = path
            if dest.is_dir(): dest /= 'index.html'
            check(dest.is_file(), str(path.relative_to(output)) + ': missing target ' + href)
            if parsed.fragment and dest.is_file():
                target = documents.get(dest)
                if target is None:
                    try: target = Document(dest.read_text())
                    except UnicodeDecodeError: continue
                check(unquote(parsed.fragment) in target.ids, str(path.relative_to(output)) + ': missing anchor ' + href)
    for record in index:
        parsed = urlsplit(record['url'])
        dest = output/parsed.path.lstrip('/')/'index.html'
        check(dest in docs, 'Search points outside the site: ' + record['url'])
        if parsed.fragment and dest in docs: check(parsed.fragment in docs[dest].ids, 'Search has broken anchor: ' + record['url'])
    check(len(index) == manifest['search_entries'], 'Search omits a page, section or support entry')
    check(all(any(record['url'] == '/docs/' + (page['slug']+'/' if page['slug'] else '') for record in index) for page in build.metadata(build.REPO)['pages']), 'Search omits a guide')
    check(not (build.ROOT/'llms.txt').exists(), 'AI index must be generated from canonical inputs')
    check(not (build.SOURCE/'support.json').exists(), 'Support data must be derived from canonical Markdown')
    ns = {'s':'http://www.sitemaps.org/schemas/sitemap/0.9'}
    sitemap = ET.parse(output/'sitemap.xml')
    locations = [node.text for node in sitemap.findall('.//s:loc',ns)]
    check(len(locations) == len(set(locations)), 'Duplicate sitemap entry')
    for doc in documents.values(): check(doc.canonical[0] in locations, 'Missing sitemap page: ' + doc.canonical[0])
    # Existing feature evidence remains a checked source contract after README relocation.
    for path in (build.REPO/'docs/feature-evidence').glob('*.json'):
        registry = json.loads(path.read_text())
        for claim in registry.get('claims',[]):
            for surface in claim.get('shipped_surfaces',[]):
                source = build.REPO/surface['path']
                check(source.is_file(), 'Missing evidence surface: ' + surface['path'])
                if source.is_file():
                    check(hashlib.sha256(source.read_bytes()).hexdigest() == surface['sha256'], 'Evidence surface digest drift: ' + surface['path'])
                    lines = source.read_text().splitlines(); n = surface['line']
                    check(0 < n <= len(lines) and surface['quote'] in lines[n-1], 'Evidence surface location drift: ' + claim['id'])
    # Runtime contracts must retain local canonical text; do not replace them with stubs.
    check('## Commands' in (build.REPO/'docs/protocol.md').read_text(), 'Protocol was replaced with a link stub')
    check('### Agent Arguments' in (build.REPO/'docs/usage.md').read_text(), 'Argument reference was replaced with a link stub')
    accepted_block = (build.REPO/'agent/src/main/java/com/onurkat/reclazz/config/AgentConfig.java').read_text().split('KNOWN_KEYS = Set.of(',1)[1].split(');',1)[0]
    accepted = set(re.findall(r'"([A-Za-z]+)"',accepted_block))
    options_page = (output/'docs/configuration-reference/index.html').read_text()
    check(accepted == set(re.findall(r'id="option-([A-Za-z]+)"',options_page)), 'Derived options disagree with accepted agent keys')
    stats.update(site_pages=len(documents),pages=len(docs),references=manifest['reference_pages'],support=manifest['support_entries'],search=len(index))
    return errors, stats


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=build.REPO/'build/site')
    parser.add_argument('--revision',default='main')
    args = parser.parse_args()
    errors, stats = check_site(args.output,args.revision)
    for error in errors: print('FAIL: ' + error)
    if errors: raise SystemExit(str(len(errors)) + ' documentation checks failed')
    print('PASS: ' + str(stats['checks']) + ' checks; ' + str(stats['site_pages']) + ' total site pages; ' + str(stats['pages']) + ' pages; ' + str(stats['references']) + ' complete Markdown references; ' + str(stats['support']) + ' support entries; ' + str(stats['search']) + ' search destinations')

if __name__ == '__main__': main()
