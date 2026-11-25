"""Validate source coverage and the complete generated website without a network call."""
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit, unquote, urljoin
import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET

from markdown_it import MarkdownIt
from website import build
from website.markdown import github_anchor_map, source_dispositions
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
        if tag in ('h1','h2','h3','h4','h5','h6'): self.headings.append(int(tag[1]))
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


def publication_errors(output, manifest):
    """Check delivered routing data independently from stale-output comparison."""
    output = Path(output); errors = []
    redirects = manifest['redirects']; real_files = set(manifest['site_pages'])
    real_routes = {'/' + name[:-len('index.html')] for name in real_files if name.endswith('index.html')}
    index = json.loads((output/'docs/assets/search-index.json').read_text())
    locations = {node.text for node in ET.parse(output/'sitemap.xml').findall('.//{*}loc')}
    for name in real_files:
        text = (output/name).read_text()
        for href in Document(text).links:
            parsed = urlsplit(href)
            if (not parsed.netloc or parsed.netloc == 'reclazz.com') and parsed.path.endswith('.html') and not (name == '404.html' and href == 'https://reclazz.com/404.html'):
                errors.append('Internal HTML link: ' + name + ': ' + href)
    for route, record in redirects.items():
        path = output/build.route_file(route)
        if not path.is_file():
            errors.append('Missing redirect stub: ' + route); continue
        text = path.read_text(); doc = Document(text)
        data = re.search(r'<script id="redirect-data" type="application/json">(.*?)</script>',text,re.S)
        if data is None or json.loads(data[1]) != {k:record.get(k,{}) for k in ('destination','fragments')}:
            errors.append('Redirect data drift: ' + route)
        if doc.canonical != [record['destination']] or 'name="robots" content="noindex"' not in text or 'location.replace(target.href)' not in text or 'http-equiv="refresh"' not in text:
            errors.append('Invalid redirect stub: ' + route)
        target = urlsplit(record['destination'])
        if not target.netloc and (target.path not in real_routes or target.path in redirects):
            errors.append('Redirect loop, chain or missing destination: ' + route)
        if 'https://reclazz.com' + route in locations:
            errors.append('Redirect in sitemap: ' + route)
    for item in index:
        parsed = urlsplit(item['url'])
        if parsed.path in redirects or parsed.path not in real_routes:
            errors.append('Search points to unpublished page: ' + item['url'])
    if set(manifest['documents']) != set(source_dispositions(build.REPO)):
        errors.append('Canonical Markdown coverage mismatch')
    for source, record in manifest['documents'].items():
        if hashlib.sha256((build.REPO/source).read_bytes()).hexdigest() != record['sha256']:
            errors.append('Wrong canonical source digest: ' + source)
        if 'external' in record:
            route = '/docs/reference/' + Path(source).stem + '/'
            if route in real_routes or 'https://reclazz.com'+route in locations:
                errors.append('External source published locally: ' + source)
    for location in locations:
        parsed = urlsplit(location)
        if parsed.netloc != 'reclazz.com' or parsed.path not in real_routes:
            errors.append('Sitemap points to unpublished page: ' + str(location))
    return errors


def changelog_mention_errors(repo):
    """Release notes are cut from CHANGELOG.md; GitHub turns a bare @Name into a user mention."""
    errors = []
    text = (Path(repo)/'CHANGELOG.md').read_text(encoding='utf-8')
    for number, line in enumerate(text.split('\n'), 1):
        outside = ''.join(part for index, part in enumerate(line.split('`')) if index % 2 == 0)
        for name in re.findall(r'(?<![\w`/.])@([A-Za-z][A-Za-z0-9-]*)', outside):
            errors.append(f'CHANGELOG.md:{number}: @{name} outside code becomes a GitHub mention; write `@{name}`')
    return errors

def check_site(output, revision='main'):
    output = Path(output).resolve()
    errors = source_index_errors(build.REPO) + source_link_errors(build.REPO) + readable_surface_errors(build.REPO) + changelog_mention_errors(build.REPO)
    if build.metadata(build.REPO).get('content_contract') in ('3a','3b','3c','4'): errors.extend(stage3_contract_errors(build.REPO,output))
    if build.metadata(build.REPO).get('content_contract') in ('3c','4'):
        usage_page=output/'docs/reference/usage/index.html'
        errors.extend(usage_contract_errors(build.REPO,rendered=usage_page.read_text() if usage_page.is_file() else ''))
    if build.metadata(build.REPO).get('content_contract')=='4': errors.extend(marketing_contract_errors(build.REPO,output))
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
    redirects = manifest['redirects']
    redirect_files = {build.route_file(route) for route in redirects}
    documents = {p: Document(p.read_text()) for p in output.rglob('*.html') if (p.name == 'index.html' or p.relative_to(output).as_posix() == '404.html') and p.relative_to(output).as_posix() not in redirect_files}
    for error in publication_errors(output,manifest): check(False,error)
    docs = {p:d for p,d in documents.items() if p.is_relative_to(output/'docs')}
    check(len(documents) == len(manifest['site_pages']), 'Whole-site page count drift')
    check(len(docs) == manifest['pages'], 'Page count disagrees with provenance manifest')
    check(len(manifest['documents']) == len(list((build.REPO/'docs').glob('*.md'))), 'Canonical Markdown coverage mismatch')
    for source, record in manifest['documents'].items():
        check(hashlib.sha256((build.REPO/source).read_bytes()).hexdigest() == record['sha256'], 'Wrong canonical source digest: ' + source)
        if 'publish' in record:
            check((output/build.route_file(record['publish'])).is_file(), 'Source has no public page: ' + source)
        else:
            check(record.get('external') == source, 'External source disposition drift: ' + source)
    # Redirect stubs retain their separate minimal navigation contract.
    for error in contrast_errors((output/'assets/tokens.css').read_text()): check(False,error)
    for path, doc in documents.items():
        for error in page_quality_errors(output,path): check(False,str(path.relative_to(output))+': '+error)
        check(doc.lang == ('tr' if path.is_relative_to(output/'tr') else 'en'), str(path) + ': missing language')
        check(doc.headings.count(1) == 1 and doc.mains == 1 and doc.title_count == 1, str(path) + ': require one H1/main/title')
        check(len(doc.ids) == len(set(doc.ids)), str(path) + ': duplicate IDs')
        if path in docs:
            expected_active = 0 if path == output/'docs/evidence/index.html' else 2
            # The setup page also has its two current links in the shared desktop/mobile header.
            if path == output/'docs/sap-commerce/index.html': expected_active += 2
            check(doc.active == expected_active, str(path) + ': desktop/mobile active navigation mismatch')
        check(doc.headers == 1 and doc.footers == 1, str(path) + ': require one shared header/footer')
        for control in ('theme-toggle','search-open','search-dialog'):
            check(control in doc.ids, str(path)+': missing shared '+control)
        check('{{SITE_' not in path.read_text(), str(path)+': unexpanded layout marker')
        check(len(doc.canonical) == 1, str(path) + ': require canonical URL')
        check(all('alt' in img for img in doc.images), str(path) + ': image lacks alt')
        check('Skip to content' in path.read_text(), str(path) + ': missing skip link')
        for href in doc.links:
            parsed = urlsplit(href)
            if parsed.netloc == 'github.com' and '/docs/' in parsed.path and parsed.fragment:
                name = 'docs/' + parsed.path.split('/docs/',1)[1]
                spec = manifest['documents'].get(name,{})
                if 'external' in spec:
                    check(unquote(parsed.fragment) in github_anchor_map(build.REPO/name).values(), 'Missing external source anchor: ' + href)
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
    for path,doc in documents.items():
        if path.name == '404.html':
            check(doc.canonical[0] not in locations, '404 must not appear in sitemap')
            check(not any(record['url'].startswith('/404') for record in index), '404 must not appear in search')
        else: check(doc.canonical[0] in locations, 'Missing sitemap page: ' + doc.canonical[0])

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

class AssetDocument(HTMLParser):
    """Request positions and executable scripts, excluding JSON data/schema."""
    def __init__(self, text):
        super().__init__(convert_charrefs=True)
        self.requests=[];self.css=[];self.scripts=[];self.inline=[];self.in_script=False
        self.feed(text)
    def handle_starttag(self, tag, attrs):
        a=dict(attrs)
        for key in ('src','poster'):
            if a.get(key): self.requests.append(a[key])
        if a.get('srcset'):
            self.requests.extend(item.strip().split()[0] for item in a['srcset'].split(',') if item.strip())
        if tag=='link' and set(a.get('rel','').split()) & {'stylesheet','preload','preconnect','prefetch','icon','modulepreload'}:
            if a.get('href'):self.requests.append(a['href'])
            if a.get('rel')=='stylesheet' and a.get('href'):self.css.append(a['href'])
        if tag=='script':
            executable=a.get('type','') in ('','module','text/javascript','application/javascript')
            if executable and a.get('src'):self.scripts.append(a['src'])
            self.in_script=executable and not a.get('src')
    def handle_endtag(self,tag):
        if tag=='script':self.in_script=False
    def handle_data(self,data):
        if self.in_script:self.inline.append(data)


def resource_path(output, page, href):
    parsed=urlsplit(href)
    if parsed.netloc and parsed.netloc!='reclazz.com':return None
    return (output/unquote(parsed.path).lstrip('/')) if parsed.path.startswith('/') else (page.parent/unquote(parsed.path))


def automatic_request_errors(text, kind='html'):
    """Reject external request positions; outbound anchors and schema URLs are inert."""
    requests=[]
    if kind=='html':
        doc=AssetDocument(text);requests.extend(doc.requests)
        # Inline CSS and executable JS are checked too.
        requests.extend(re.findall(r'''url\(\s*["']?([^\s)'";]+)''',text,re.I))
        text='\n'.join(doc.inline)
    if kind=='css':
        requests.extend(re.findall(r'''url\(\s*["']?([^\s)'";]+)''',text,re.I))
        requests.extend(re.findall(r'''@import\s+["']([^"']+)''',text,re.I))
    requests.extend(re.findall(r'''(?:fetch|open|sendBeacon|EventSource|WebSocket|importScripts)\s*\(\s*["']([^"']+)''',text))
    requests.extend(re.findall(r'''\.open\s*\(\s*["'][A-Z]+["']\s*,\s*["']([^"']+)''',text))
    return ['Automatic third-party request: '+url for url in requests if urlsplit(url).netloc and urlsplit(url).netloc!='reclazz.com']


def scroll_region_errors(text):
    """Validate keyboard access in delivered HTML, without relying on JS or axe."""
    from website.layout import is_scroll_region
    class Regions(HTMLParser):
        def __init__(self):
            super().__init__(convert_charrefs=True);self.stack=[];self.errors=[]
        def handle_starttag(self,tag,attrs):
            a=dict(attrs);parents={c for _,cs in self.stack for c in cs}
            if is_scroll_region(tag,a,parents):
                if a.get('tabindex')!='0' or a.get('role')!='region' or not a.get('aria-label','').strip():
                    self.errors.append('Scrollable region needs tabindex=0, role=region and aria-label: '+tag+' '+a.get('class',''))
            if tag not in {'area','base','br','col','embed','hr','img','input','link','meta','param','source','track','wbr'}:
                self.stack.append((tag,set(a.get('class','').split())))
        def handle_endtag(self,tag):
            for i in range(len(self.stack)-1,-1,-1):
                if self.stack[i][0]==tag:del self.stack[i:];break
    parser=Regions();parser.feed(text);return parser.errors


def page_quality_errors(output, page):
    output=Path(output);page=Path(page);text=page.read_text();doc=AssetDocument(text);errors=automatic_request_errors(text)+scroll_region_errors(text)
    js=sum(len(part.encode('utf-8')) for part in doc.inline);css=0;fonts=set()
    for kind,links in [('js',doc.scripts),('css',doc.css)]:
        for href in set(links):
            p=resource_path(output,page,href)
            if p is None or not p.is_file():continue # generic missing-link gate reports this
            data=p.read_bytes();source=data.decode('utf-8');errors.extend(automatic_request_errors(source,kind))
            if kind=='js':js+=len(data)
            else:
                css+=len(data)
                for font in re.findall(r'''url\(\s*["']?([^\s)'";]+\.woff2)["']?\s*\)''',source):
                    target=resource_path(output,p,font)
                    if target and target.is_file():fonts.add(target)
    if js>45000:errors.append('Page JS budget exceeded: '+str(js))
    if css>70000:errors.append('Page CSS budget exceeded: '+str(css))
    font_bytes=sum(p.stat().st_size for p in fonts)
    if font_bytes>250000:errors.append('Page font budget exceeded: '+str(font_bytes))
    d=Document(text)
    for a,b in zip(d.headings,d.headings[1:]):
        if b>a+1:errors.append('Heading level skipped: '+str(a)+' -> '+str(b))
    for img in d.images:
        if any(not img.get(key) for key in ('width','height')) or 'alt' not in img:errors.append('Image dimensions/alt missing')
    if page.name=='404.html':
        if d.headers!=1 or d.footers!=1 or d.mains!=1 or d.headings.count(1)!=1 or 'search-dialog' not in d.ids:errors.append('404 shared shell missing')
        for href in doc.requests+[h for h in d.links if not urlsplit(h).netloc]:
            if href and not href.startswith(('/','#','data:')):errors.append('404 relative asset/link: '+href)
        if not re.search(r'<meta[^>]+name="robots"[^>]+content="noindex"',text):errors.append('404 must be noindex')
    return errors


def contrast_ratio(first,second):
    def luminance(color):
        parts=[int(color.lstrip('#')[i:i+2],16)/255 for i in (0,2,4)]
        linear=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in parts]
        return sum(v*c for v,c in zip(linear,(.2126,.7152,.0722)))
    a,b=sorted((luminance(first),luminance(second)))
    return (b+.05)/(a+.05)


def contrast_errors(css):
    dark=re.search(r':root\{([^}]+)\}',css)[1]
    light=re.search(r'html\[data-theme=light\]\{([^}]+)\}',css)[1]
    base=dict(re.findall(r'--([\w-]+):([^;{}]+)',dark));errors=[]
    for theme,override in [('dark',''),('light',light)]:
        colors={**base,**dict(re.findall(r'--([\w-]+):([^;{}]+)',override))}
        def color(key):
            value=colors[key]
            if value.startswith('var('):return color(value[6:-1])
            return value
        for fg in ('text','muted','dim','accent','green','gold','rose','works','conditional','restart','unverified','not-reloaded'):
            for bg in ('bg','surface','surface-hover','surface-alt'):
                if contrast_ratio(color(fg),color(bg))<4.5:errors.append('Contrast below 4.5: '+theme+' '+fg+'/'+bg)
        if contrast_ratio(color('on-accent'),color('accent-fill'))<4.5:errors.append('Button contrast below 4.5: '+theme)
        for bg in ('bg','surface','surface-hover','surface-alt'):
            if contrast_ratio(color('border'),color(bg))<3:errors.append('UI contrast below 3: '+theme+' border/'+bg)
    return errors


class ContentTree(HTMLParser):
    """Small HTML tree for semantic checks independent of the rendering helpers."""
    def __init__(self,text):
        super().__init__(convert_charrefs=True)
        self.root={'tag':'root','attrs':{},'children':[]};self.stack=[self.root];self.nodes=[];self.feed(text)
    def handle_starttag(self,tag,attrs):
        n={'tag':tag,'attrs':dict(attrs),'children':[]};self.stack[-1]['children'].append(n);self.nodes.append(n)
        if tag not in {'area','base','br','col','embed','hr','img','input','link','meta','param','source','track','wbr'}:self.stack.append(n)
    def handle_endtag(self,tag):
        for i in range(len(self.stack)-1,0,-1):
            if self.stack[i]['tag']==tag:del self.stack[i:];break
    def handle_data(self,data):self.stack[-1]['children'].append(data)


def node_text(node,ignore=()):
    if isinstance(node,str):return node
    if node['tag'] in ignore:return ''
    return ''.join(node_text(c,ignore) for c in node['children'])


def descendants(node):
    for child in node['children']:
        if isinstance(child,dict):yield child;yield from descendants(child)


def rewritten_prose_errors(repo):
    repo=Path(repo);errors=[]
    for name in ['overview','quickstart','support-matrix','troubleshooting','sap-commerce','spring','configuration','persistence','java','configuration-reference']:
        p=repo/'docs/site'/(name+'.html')
        if p.is_file() and '—' in node_text(ContentTree(p.read_text()).root,('pre','code','script','style')):errors.append('Em dash in rewritten prose: '+name)
    p=repo/'docs/reload-support.md'
    if p.is_file():
        tokens=MarkdownIt('commonmark').enable('table').parse(p.read_text());col=-1
        for t in tokens:
            if t.type=='tr_open':col=-1
            elif t.type=='inline':
                col+=1
                if col in (4,5) and any(c.type=='text' and '—' in c.content for c in t.children or []):errors.append('Em dash in support condition/action')
    return errors


def rendered_records_errors(text,records,summary=False):
    from website.content import LABELS,OVERVIEW_IDS
    tree=ContentTree(text);key='data-summary-id' if summary else 'data-support-id'
    nodes=[n for n in tree.nodes if key in n['attrs']];ids=[n['attrs'][key] for n in nodes]
    expected={r['id']:r for r in records if not summary or r['id'] in OVERVIEW_IDS};errors=[]
    if Counter(ids)!=Counter(expected.keys()):errors.append('Support record coverage mismatch'+(' in overview' if summary else ''))
    for node in nodes:
        row=expected.get(node['attrs'][key])
        if row is None:continue
        if not summary and (node['attrs'].get('data-status')!=row['status'] or node['attrs'].get('data-presentation')!=row['presentation']):errors.append('Support presentation/status mismatch: '+row['id'])
        badges=[n for n in descendants(node) if 'badge' in n['attrs'].get('class','').split()]
        if len(badges)!=1 or row['status'] not in badges[0]['attrs'].get('class','').split() or ' '.join(node_text(badges[0]).split())!=LABELS[row['status']]:errors.append('Support badge mismatch: '+row['id'])
        if summary and row['conditions'] not in node_text(node):errors.append('Overview conditions drift: '+row['id'])
    return errors


def stage3_contract_errors(repo,output):
    from website.content import support_records
    repo=Path(repo);output=Path(output);errors=rewritten_prose_errors(repo);records=support_records(repo)
    p=repo/'website/support-status.lock.json'
    if not p.is_file():errors.append('Missing support status lock')
    elif json.loads(p.read_text())!={r['id']:r['status'] for r in records}:errors.append('Support status lock drift')
    errors.extend(frozen_fragment_errors(repo,output))
    if build.metadata(repo).get('content_contract') in ('3b','3c','4'):errors.extend(stage3b_contract_errors(repo,output,records))
    for name,summary in [('docs/support-matrix/index.html',False),('docs/index.html',True)]:
        p=output/name
        if p.is_file():errors.extend(rendered_records_errors(p.read_text(),records,summary))
        else:errors.append('Missing support presentation: '+name)
    p=output/'docs/assets/support.json'
    if not p.is_file() or json.loads(p.read_text())!=records:errors.append('Support download coverage/content drift')
    p=output/'docs/assets/search-index.json'
    index=json.loads(p.read_text()) if p.is_file() else []
    for row in records:
        if sum(x['url']=='/docs/support-matrix/#'+row['id'] for x in index)!=1:errors.append('Support search coverage mismatch: '+row['id'])
    p=output/'docs/quickstart/index.html'
    if p.is_file():
        errors.extend(quickstart_panel_errors(p.read_text()))
    return errors


def quickstart_panel_errors(text):
    tree=ContentTree(text);panels=[n for n in tree.nodes if n['attrs'].get('role')=='tabpanel'];errors=[]
    if len(panels)!=5:errors.append('Quickstart needs five route panels')
    for panel in panels:
        a=panel['attrs']
        if not a.get('id') or 'hidden' in a or not any(n['tag'] in ('h2','h3','h4') for n in descendants(panel)):errors.append('Quickstart panel must be visible and headed in static HTML')
    return errors


def frozen_fragment_errors(repo,output):
    repo=Path(repo);output=Path(output);p=repo/'website/fragments.json';errors=[]
    if not p.is_file():return ['Missing frozen fragments']
    redirects=json.loads((repo/'website/routes.json').read_text()) if (repo/'website/routes.json').is_file() else {}
    for name,ids in json.loads(p.read_text()).items():
        route='/'+name.removesuffix('index.html')
        moved=redirects.get(route)
        if moved:
            target=urlsplit(moved['destination']).path
            path=output/target.lstrip('/')/'index.html';mapping=moved.get('fragments',{})
            targetids=set(Document(path.read_text()).ids) if path.is_file() else set()
            for key in ids:
                if key not in mapping or mapping[key] not in targetids:errors.append('Missing frozen fragment: '+name+'#'+key)
        else:
            path=output/name
            if not path.is_file():errors.append('Missing frozen page: '+name);continue
            for key in set(ids)-set(Document(path.read_text()).ids):errors.append('Missing frozen fragment: '+name+'#'+key)
    return errors


def guide_records_errors(records,pages,assignments):
    from website.content import LABELS
    by_id={r['id']:r for r in records};seen=set();errors=[]
    for page,expected in assignments.items():
        tree=ContentTree(pages.get(page,''));nodes=[n for n in tree.nodes if 'data-guide-id' in n['attrs']]
        keys=[n['attrs']['data-guide-id'] for n in nodes];seen.update(keys)
        if Counter(keys)!=Counter(expected):errors.append('Guide coverage mismatch: '+page)
        for node in nodes:
            key=node['attrs']['data-guide-id'];row=by_id.get(key)
            if row is None:errors.append('Unknown guide support ID: '+key);continue
            badges=[n for n in descendants(node) if 'badge' in n['attrs'].get('class','').split()]
            if len(badges)!=1 or row['status'] not in badges[0]['attrs'].get('class','').split() or ' '.join(node_text(badges[0]).split())!=LABELS[row['status']]:errors.append('Guide badge mismatch: '+key)
            if node['attrs'].get('data-status')!=row['status'] or node['attrs'].get('data-presentation')!=row['presentation']:errors.append('Guide presentation mismatch: '+key)
            if row['conditions'] not in node_text(node) or row['action'] not in node_text(node):errors.append('Guide conditions drift: '+key)
            if not any(n['attrs'].get('href')=='/docs/support-matrix/#'+key for n in descendants(node)):errors.append('Guide row link missing: '+key)
    if seen!=set(by_id):errors.append('Guide inventory must cover all support records')
    return errors


def option_group_errors(text,options,groups):
    nodes=[n for n in ContentTree(text).nodes if 'data-option' in n['attrs']];errors=[]
    keys=[n['attrs']['data-option'] for n in nodes]
    expected=[key for values in groups.values() for key in values]
    if len(expected)!=len(set(expected)) or set(expected)!=set(options) or Counter(keys)!=Counter(options):errors.append('Option grouping coverage mismatch')
    for node in nodes:
        key=node['attrs']['data-option'];group=node['attrs'].get('data-option-group')
        if key not in groups.get(group,[]):errors.append('Option group mismatch: '+key)
    return errors


def stage3b_contract_errors(repo,output,records):
    from website.content import GUIDE_IDS,OPTION_GROUPS
    repo=Path(repo);output=Path(output);errors=[];pages={}
    for name in GUIDE_IDS:
        p=output/'docs'/name/'index.html';pages[name]=p.read_text() if p.is_file() else ''
    errors.extend(guide_records_errors(records,pages,GUIDE_IDS))
    usage=(repo/'docs/usage.md').read_text();table=usage.split('| Argument | Default | Description |',1)[1].split('\n\n',1)[0]
    options=re.findall(r'^\| `([^`]+)`',table,re.M)
    p=output/'docs/configuration-reference/index.html'
    errors.extend(option_group_errors(p.read_text() if p.is_file() else '',options,OPTION_GROUPS))
    for p in output.rglob('*.html'):
        text=p.read_text()
        if 'id="redirect-data"' in text:continue
        for link in Document(text).links:
            parsed=urlsplit(urljoin('https://reclazz.com/'+p.relative_to(output).as_posix(),link))
            if parsed.netloc in ('','reclazz.com') and parsed.path in ('/docs/messaging/','/docs/boundaries/','/docs/messaging.html','/docs/boundaries.html'):errors.append('Link to folded guide: '+str(p.relative_to(output))+': '+link)
    return errors


def refreshed_usage_anchors(repo):
    """Refresh bodies only at an already approved heading location."""
    from website.markdown import pinned_usage_headings, usage_heading_blocks
    path = repo / 'website/usage-anchors.json'
    original = path.read_text()
    record = json.loads(original)
    entries = record['headings']
    source = record['source']
    text = (repo / source).read_text()
    structure = json.loads((repo / 'website/usage-structure.json').read_bytes())
    groups = {'Option 1: Manual Setup': structure['manual_keys']}
    groups.update({group['heading']: group['keys'] for group in structure['groups']})
    parents = {key: parent for parent, keys in groups.items() for key in keys}
    blocks = {(tuple(b['identity']), b['occurrence']): b for b in usage_heading_blocks(text)}
    pins, _ = pinned_usage_headings(repo, source, text)
    matched = {}
    changed = False
    for entry in entries:
        key = entry['key']
        identity = tuple(entry['identity'])
        if key in parents:
            identity = (parents[key], entry['title'])
        block = blocks.get((identity, entry['occurrence']))
        level = structure.get('allowed_level_changes', {}).get(key, entry['level'])
        heading = '#' * level + entry['heading'].lstrip('#')
        if not block or block['level'] != level or block['heading'] != heading:
            raise ValueError('Missing or moved usage heading: ' + entry['title'])
        pinned = pins.get(block['line'])
        if pinned and pinned['key'] != key:
            raise ValueError('Usage heading now belongs to a different fragment: ' + key)
        matched[key] = block['line']
        if entry['body_sha256'] != block['body_sha256']:
            entry['body_sha256'] = block['body_sha256']
            changed = True
    original_siblings = {}
    for entry in entries:
        if entry['level'] > 2 and entry['key'] not in parents:
            original_siblings.setdefault(tuple(entry['identity'][:-1]), []).append(entry['key'])
    for keys in [*groups.values(), *original_siblings.values()]:
        ordered = sorted(keys, key=matched.__getitem__)
        if ordered != keys:
            moved = [key for i, key in enumerate(keys) if ordered[i] != key]
            raise ValueError('Usage section order changed: ' + ', '.join(moved))
    errors = usage_contract_errors(repo, text=text, frozen=entries)
    if errors:
        raise ValueError('\n'.join(errors))
    return json.dumps(record, ensure_ascii=False, indent=2) + '\n' if changed else original


def usage_contract_errors(repo,text=None,rendered=None,frozen=None):
    """C1-C3: frozen section bodies/ids, exact additions and group membership."""
    from website.markdown import usage_heading_blocks
    repo=Path(repo);a=repo/'website/usage-anchors.json';p=repo/'website/usage-structure.json'
    if not a.is_file() or not p.is_file():return ['Missing usage preservation contract']
    if frozen is None:frozen=json.loads(a.read_text())['headings']
    structure=json.loads(p.read_text());errors=[]
    text=(repo/'docs/usage.md').read_text() if text is None else text
    blocks=usage_heading_blocks(text);signatures={(e['title'],e['body_sha256']):e for e in frozen}
    if len(signatures)!=len(frozen):errors.append('C1 ambiguous frozen usage identity')
    found={};actual_groups={};h2_order=[];parent=None
    groups={g['heading']:g for g in structure['groups']};group_counts=Counter()
    for b in blocks:
        if b['level']==2:parent=b['title'];h2_order.append(parent)
        e=signatures.get((b['title'],b['body_sha256']))
        if e:
            key=e['key']
            if key in found:errors.append('C1 duplicate usage section: '+key)
            found[key]=b
            expected_level=structure.get('allowed_level_changes',{}).get(key,e['level'])
            if b['level']!=expected_level:errors.append('C2 unlisted heading level: '+key)
            if b['heading']!=('#'*expected_level+e['heading'].lstrip('#')):errors.append('C2 changed frozen heading line: '+key)
            if e['level']==3:actual_groups.setdefault(parent,[]).append(key)
        elif b['title'] in groups and b['level']==2:
            g=groups[b['title']];group_counts[b['title']]+=1
            if b['heading']!='## '+g['heading']+'\n' or b['body']!='\n'+g['intro']+'\n\n':errors.append('C2 unlisted usage intro/body: '+b['title'])
        else:errors.append('C2 unknown or changed usage block: '+b['title'])
    for e in frozen:
        if e['key'] not in found:errors.append('C1 missing or changed usage body: '+e['key'])
    if text and (not blocks or blocks[0]['line']!=0):errors.append('C2 unlisted usage preamble')
    for title in groups:
        if group_counts[title]!=1:errors.append('C3 missing or duplicated usage group: '+title)
    if h2_order!=structure['h2_order']:errors.append('C3 usage H2 order mismatch')
    expected_assignments={'Option 1: Manual Setup':structure['manual_keys'],**{g['heading']:g['keys'] for g in structure['groups']}}
    assigned=[k for keys in expected_assignments.values() for k in keys]
    old_manual=[e['key'] for e in frozen if e['level']==3 and e['identity'][:-1]==['Option 1: Manual Setup']]
    if Counter(assigned)!=Counter(old_manual):errors.append('C3 original Manual sections not assigned exactly once')
    for parent,keys in expected_assignments.items():
        if actual_groups.get(parent,[])!=keys:errors.append('C3 usage membership/order mismatch: '+parent)
    for e in frozen:
        b=found.get(e['key'])
        if b and e['key'] not in assigned and b['identity']!=e['identity']:errors.append('C3 moved unassigned usage section: '+e['key'])
    if rendered is not None:
        tree=ContentTree(rendered);nodes=[n for n in tree.nodes if 'data-section-key' in n['attrs']]
        for e in frozen:
            if e['level']==1:
                matches=[n for n in tree.nodes if n['tag']=='h1' and n['attrs'].get('id')==e['id']]
            else:matches=[n for n in nodes if n['attrs']['data-section-key']==e['key'] and n['attrs'].get('id')==e['id']]
            if len(matches)!=1:errors.append('C1 rendered usage id/identity mismatch: '+e['key'])
    return errors


COMPARISON_ROWS = ['price','reload-mechanism','state','structural-stock-jdk','jvm-required','superclass','spring-framework','spring-added-members','framework-breadth','sap-java','sap-model-xml','sap-impex','sap-config','sap-interceptor','setup','licence-activation','network','ide']
VENDOR_DOMAINS = {'www.jrebel.com','jrebel.com','manuals.jrebel.com','docs.spring.io','github.com'}


def comparison_model_errors(repo, model=None):
    from website.content import support_records, compare_source_key, heading_ids
    repo=Path(repo);errors=[]
    model=json.loads((repo/'website/compare.json').read_text()) if model is None else model
    contract=json.loads((repo/'website/compare-contract.json').read_text())
    raw=json.dumps(model)+json.dumps(contract)
    if any(x in raw for x in ('/Users/','.collab','publish_measured','"measured": {"payload"')):errors.append('Private or hidden comparison payload')
    if [x['id'] for x in model['rows']] != COMPARISON_ROWS:errors.append('Comparison row inventory')
    if model['home'] != contract['home']:errors.append('Comparison home subset')
    records={x['id']:x for x in support_records(repo)}
    sources={x['key']:x for x in model['sources'].values()}
    for source in sources.values():
        url=urlsplit(source['url'])
        if url.scheme!='https' or url.netloc not in VENDOR_DOMAINS or (url.netloc=='github.com' and not url.path.startswith(('/HotswapProjects/','/spring-projects/','/JetBrains/'))):errors.append('Non-allowlisted comparison source')
        if not source.get('quote') or not source.get('read') or not source.get('version'):errors.append('Missing comparison provenance')
        expected=source['url']+'#'+hashlib.sha256(source['quote'].encode()).hexdigest()[:16]
        if source['key']!=expected:errors.append('Comparison source witness mismatch')
    if any(c['source_key'].startswith('measured:') for c in contract['cells']):errors.append('Measured comparison sources are not allowed')
    actual=[]
    for row in model['rows']:
        if set(row['cells'])!={'reclazz','jrebel','devtools','hotswap'}:errors.append('Comparison product inventory')
        for product,c in row['cells'].items():
            source=c['source'];key=compare_source_key(source)
            actual.append(dict(row=row['id'],product=product,text=c['text'],verdict=c['verdict'],source_key=key))
            if not source and c['verdict']!='not-documented':errors.append('Missing comparison source')
            if 'key' in source and source['key'] not in sources:errors.append('Unknown comparison source')
            if 'record' in source:
                rec=records.get(source['record'])
                if not rec:errors.append('Unknown comparison record')
                elif c['verdict'] not in {'works':{'yes','partial'},'conditional':{'partial'},'restart':{'no'},'unverified':{'not-documented'},'not-reloaded':{'no','not-documented'}}[rec['status']]:errors.append('Comparison record status upgraded')
            if 'doc' in source:
                name,_,fragment=source['doc'].partition('#');path=repo/name
                if not path.is_file() or fragment not in heading_ids(path):errors.append('Missing comparison document anchor: '+source['doc'])
            if 'measured' in source:errors.append('Measured comparison sources are not allowed')
    if actual != contract['cells']:errors.append('Comparison differs from approved cell contract')
    return errors


def marketing_render_errors(repo,home,compare,tr):
    from website.content import marketing_claims, expand_claims, HOME_FAQ, LABELS, OVERVIEW_IDS, support_records
    from html import unescape
    errors=[];values=marketing_claims(repo)
    keys=set(re.findall(r'data-i18n="([^"]+)"',home))
    if keys!=set(tr) or any(not v for v in tr.values()):errors.append('English/Turkish key mismatch')
    for text in (home,compare,json.dumps(tr,ensure_ascii=False)):
        if '{{' in text:errors.append('Unexpanded marketing token')
        if '\u2014' in text:errors.append('Em dash in marketing copy')
    # Generated output must contain source-derived claim-bearing sentences in both languages.
    for k in ('sap_scenarios','intellij_builds','agent_java_min','jdk_tested','version'):
        if values[k] not in unescape(home):errors.append('Missing rendered marketing claim: '+k)
    if not re.search(r'default live suite defines '+re.escape(values['sap_scenarios'])+r' required scenarios',home):errors.append('Rendered scenario claim mismatch')
    faq_schemas=0
    for page in (home,compare):
        for n in re.findall(r'<script type="application/ld\+json">(.*?)</script>',page,re.S):
            schema=json.loads(n)
            if schema.get('@type')!='FAQPage':continue
            faq_schemas+=1
            tree=ContentTree(page);faq=[]
            for d in descendants(tree.root):
                if d['tag']=='details' and 'marketing-faq' in d['attrs'].get('class',''):
                    q=next(x for x in descendants(d) if x['tag']=='summary');a=next(x for x in descendants(d) if x['tag']=='p')
                    faq.append((node_text(q).strip(),node_text(a).strip()))
            structured=[(x['name'],x['acceptedAnswer']['text']) for x in schema['mainEntity']]
            if faq!=structured or len(faq)!=5:errors.append('Visible FAQ differs from JSON-LD')
    if faq_schemas!=1:errors.append('Missing or duplicate FAQ schema')
    if 'class="measured-note"' in home+compare:errors.append('Measured comparison content is not allowed')
    def cells(text):
        result={};tree=ContentTree(text)
        for row in descendants(tree.root):
            if 'data-compare-row' not in row['attrs']:continue
            for td in row['children']:
                if isinstance(td,dict) and td['tag']=='td':result[(row['attrs']['data-compare-row'],td['attrs'].get('data-product'))]=(td['attrs'].get('data-verdict'),node_text(td))
        return result
    summary=cells(home);full=cells(compare)
    if len(summary)!=20 or any(full.get(k)!=v for k,v in summary.items()):errors.append('Home/full comparison mismatch')
    model=json.loads((Path(repo)/'website/compare.json').read_text())
    for row in model['rows']:
        for product,c in row['cells'].items():
            rendered=full.get((row['id'],product))
            expected=unescape(expand_claims(c['text'],values))
            if not rendered or rendered[0]!=c['verdict'] or expected not in rendered[1]:errors.append('Rendered comparison cell mismatch')
    records={x['id']:x for x in support_records(repo)};cards=[]
    for n in descendants(ContentTree(home).root):
        if 'data-support-id' not in n['attrs']:continue
        key=n['attrs']['data-support-id'];cards.append(key)
        if key not in records or n['attrs'].get('data-support-status')!=records[key]['status'] or LABELS[records[key]['status']] not in node_text(n):errors.append('Home card status mismatch')
    if cards!=OVERVIEW_IDS:errors.append('Home card inventory')
    return errors


def marketing_contract_errors(repo,output):
    repo=Path(repo);output=Path(output);errors=comparison_model_errors(repo)
    from website.content import marketing_claims
    try:marketing_claims(repo)
    except ValueError as e:errors.append(str(e));return errors
    for name in ('index.html','compare/index.html'):
        source=(repo/'website/static'/name).read_text()
        if re.search(r'\b\d+ of \d+ (?:integration )?scenarios|\b\d+ required scenarios|IntelliJ IDEA 20\d\d\.\d',source):errors.append('Hard-coded marketing claim')
    if all((output/n).is_file() for n in ('index.html','compare/index.html','assets/i18n/tr.json')):
        errors.extend(marketing_render_errors(repo,(output/'index.html').read_text(),(output/'compare/index.html').read_text(),json.loads((output/'assets/i18n/tr.json').read_text())))
    else:errors.append('Missing marketing output')
    return errors
