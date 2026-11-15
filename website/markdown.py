"""Render repository Markdown with native tables/fences and local site links."""
from collections import Counter
from html import escape
from pathlib import Path
import json
import hashlib
import unicodedata
import posixpath
import re
from urllib.parse import urlsplit, urlunsplit, quote, unquote

from markdown_it import MarkdownIt


def slug(text):
    return re.sub(r'[^\w\- ]', '', text.lower()).replace(' ', '-')


def usage_heading_blocks(text):
    """Fenced-code-aware byte-preserving heading/body slices."""
    tokens=MarkdownIt('commonmark', {'html':True}).parse(text)
    headings=[(token,tokens[i+1]) for i,token in enumerate(tokens[:-1]) if token.type=='heading_open']
    lines=text.splitlines(keepends=True);result=[];ancestors=[];seen=Counter();identities=Counter()
    for i,(token,inline) in enumerate(headings):
        title=''.join(c.content for c in inline.children or [] if c.type in ('text','code_inline'))
        level=int(token.tag[1]);start=token.map[0];end=headings[i+1][0].map[0] if i+1<len(headings) else len(lines)
        while ancestors and ancestors[-1][0]>=level:ancestors.pop()
        identity=[x[1] for x in ancestors if x[0]>1]+[title]
        occurrence=identities[tuple(identity)];identities[tuple(identity)]+=1
        anchor=slug(title);count=seen[anchor];seen[anchor]+=1
        anchor+=('-'+str(count)) if count else ''
        body=''.join(lines[start+1:end])
        result.append(dict(title=title,level=level,line=start,end=end,heading=lines[start],body=body,
            body_sha256=hashlib.sha256(body.encode('utf-8')).hexdigest(),identity=identity,occurrence=occurrence,id=anchor,key=anchor))
        ancestors.append((level,title))
    return result


def pinned_usage_headings(repo,source,text):
    """Pin published usage fragments to their original section, across moves."""
    path=Path(repo)/'website/usage-anchors.json'
    if source!='docs/usage.md' or not path.is_file():return {},set()
    entries=json.loads(path.read_text())['headings'];signatures={};reserved={e['id'] for e in entries}
    for entry in entries:
        signature=(entry['title'],entry['body_sha256'])
        if signature in signatures:raise ValueError('Ambiguous frozen usage heading')
        signatures[signature]=entry
    result={};used=set()
    for block in usage_heading_blocks(text):
        entry=signatures.get((block['title'],block['body_sha256']))
        if entry:
            if entry['key'] in used:raise ValueError('Duplicate frozen usage section: '+entry['key'])
            used.add(entry['key']);result[block['line']]=entry
    return result,reserved


def source_dispositions(repo):
    repo = Path(repo)
    sources = json.loads((repo/'docs/site/manifest.json').read_text()).get('sources', {})
    actual = {p.relative_to(repo).as_posix() for p in (repo/'docs').glob('*.md')}
    missing = actual - sources.keys()
    if missing: raise ValueError('Unclassified source: ' + ', '.join(sorted(missing)))
    if sources.keys() - actual: raise ValueError('Missing disposition source: ' + ', '.join(sorted(sources.keys() - actual)))
    routes = set()
    for name, spec in sources.items():
        if set(spec) == {'external'}:
            if spec['external'] != name or not (repo/name).is_file():
                raise ValueError('Missing or mismatched external source: ' + name)
        elif set(spec) == {'publish'}:
            route = spec['publish']
            if not re.fullmatch(r'/docs/(?:[a-z0-9-]+/)*',route) or route in routes:
                raise ValueError('Invalid or duplicate published route: ' + route)
            routes.add(route)
        else: raise ValueError('Source needs exactly one disposition: ' + name)
    return sources


def github_slug(text):
    # GitHub section-link rules: lowercase, spaces -> hyphens, remove punctuation.
    # Keep underscores/hyphens, which are valid in GitHub heading fragments.
    return ''.join('-' if c == ' ' else c for c in text.lower()
                   if c in ' -_' or (not c.isspace() and unicodedata.category(c)[0] not in 'PSZC'))


def github_anchor_map(path):
    tokens = MarkdownIt('commonmark', {'html':True}).parse(Path(path).read_text())
    result = {}; site_seen = Counter(); github_seen = set()
    for i, token in enumerate(tokens):
        if token.type == 'heading_open':
            text = ''.join(c.content for c in tokens[i+1].children or [] if c.type in ('text','code_inline'))
            base = github_slug(text); anchor = base; n = 0
            while anchor in github_seen:
                n += 1; anchor = base + '-' + str(n)
            github_seen.add(anchor)
            key = slug(text); count = site_seen[key]; site_seen[key] += 1
            result.setdefault(key + ('-'+str(count) if count else ''), anchor)
            result.setdefault(anchor, anchor)
        if token.type in ('html_block','inline'):
            for anchor in re.findall(r'<a (?:id|name)="([A-Za-z0-9_-]+)"',token.content):
                result[anchor] = anchor
    return result


REFERENCE_TITLES = {'index': 'Reference library', 'installation': 'Installation', 'usage': 'Usage manual', 'gradle-plugin': 'Gradle plugin', 'maven': 'Maven plugin', 'spring-boot-starter': 'Spring Boot starter', 'for-ai-agents': 'IntelliJ + coding agents', 'mcp-for-coding-agents': 'MCP for coding agents', 'mcp-server': 'MCP server reference', 'sap-commerce': 'SAP Commerce reference', 'test-guide': 'SAP test guide', 'reload-support': 'What reloads source table', 'product-reference': 'Product reference', 'protocol': 'Status socket protocol'}


def reference_pages(repo):
    pages = []; dispositions = source_dispositions(repo)
    for path in sorted((repo / 'docs').glob('*.md')):
        spec = dispositions[path.relative_to(repo).as_posix()]
        if 'publish' not in spec: continue
        parsed = MarkdownIt('commonmark').parse(path.read_text(encoding='utf-8'))
        first = next((parsed[i + 1].content for i, token in enumerate(parsed[:-1])
                      if token.type == 'heading_open' and token.tag == 'h1'), path.stem)
        title = REFERENCE_TITLES.get(path.stem, first)
        pages.append({'slug': spec['publish'].removeprefix('/docs/').strip('/'),
                      'title': title,
                      'description': 'Complete configuration, behavior and scope reference: ' + first + '.',
                      'group': 'Reference', 'eyebrow': 'Maintaining the project' if path.stem in ('documentation-workflow','publishing') else 'Complete technical reference',
                      'sources': [path.relative_to(repo).as_posix()], 'markdown': path.relative_to(repo).as_posix(),
                      'nav_title': title,
                      'heading_id': slug(first)})
    return sorted(pages, key=lambda p: (p['slug'] != 'reference', p['slug']))


class MarkdownRenderer:
    def __init__(self, repo, pages, revision='main'):
        self.repo = Path(repo).resolve()
        self.routes = {p['markdown']: '/docs/' + p['slug'] + '/' for p in pages if 'markdown' in p}
        self.dispositions = source_dispositions(self.repo)
        self.revision = revision
        self.assets = {}
        self.source_links = []
        self.engine = MarkdownIt('commonmark', {'html': True}).enable(['table', 'strikethrough'])
        self.engine.add_render_rule('fence', self.fence)
        self.engine.add_render_rule('html_inline', self.literal_html)
        self.engine.add_render_rule('html_block', self.literal_html)
        self.engine.add_render_rule('table_open', lambda renderer, tokens, i, options, env: '<div class="table-scroll"><table>\n')
        self.engine.add_render_rule('table_close', lambda renderer, tokens, i, options, env: '</table></div>\n')

    @staticmethod
    def literal_html(renderer, tokens, i, options, env):
        content = tokens[i].content
        # Existing explicit empty anchors preserve incoming links. Prose such as
        # <String> or <header> is documentation text, never page structure.
        if re.fullmatch(r'<a id="[A-Za-z0-9_-]+">(?:</a>)?\s*', content) or content == '</a>':
            return content
        return escape(content)

    @staticmethod
    def fence(renderer, tokens, i, options, env):
        token = tokens[i]
        language = token.info.strip().split()[0] if token.info.strip() else 'text'
        return '<div class="code-block"><div class="code-label">' + escape(language) + '</div><pre><code>' + escape(token.content) + '</code></pre></div>\n'

    def resolve_link(self, source, link, image=False):
        parsed = urlsplit(link)
        if parsed.scheme or parsed.netloc or not parsed.path:
            return link
        if parsed.path.startswith('/'):
            # Explicit website links in canonical sources are already public routes.
            return link
        relative = posixpath.normpath(posixpath.join(posixpath.dirname(source), unquote(parsed.path)))
        target = (self.repo / relative).resolve()
        if not target.is_relative_to(self.repo):
            raise ValueError('Escaping documentation link: ' + source + ' -> ' + link)
        if relative in self.dispositions and 'external' in self.dispositions[relative]:
            fragments = github_anchor_map(target)
            if parsed.fragment and unquote(parsed.fragment) not in fragments:
                raise ValueError('Missing external source anchor: ' + relative + '#' + parsed.fragment)
            return urlunsplit(('https', 'github.com', '/onurkat/reclazz/blob/' + self.revision + '/' + quote(relative), parsed.query, fragments.get(unquote(parsed.fragment), '')))
        if relative in self.routes:
            return urlunsplit(('', '', self.routes[relative], parsed.query, parsed.fragment))
        if relative == 'README.md':
            route = self.routes.get('docs/product-reference.md', '/docs/') if parsed.fragment else '/docs/'
            return urlunsplit(('', '', route, parsed.query, parsed.fragment))
        if image or (relative.startswith('docs/feature-evidence/') and target.suffix in ('.json', '.txt')):
            if not target.is_file():
                raise ValueError('Missing documentation asset: ' + relative)
            route = '/docs/source-assets/' + quote(relative)
            self.assets[route.lstrip('/')] = target.read_bytes()
            return urlunsplit(('', '', route, parsed.query, parsed.fragment))
        # Project code/example/policy links retain a versioned source location.
        if target.is_dir():
            return 'https://github.com/onurkat/reclazz/tree/' + self.revision + '/' + quote(relative) + (('#' + parsed.fragment) if parsed.fragment else '')
        if not target.is_file():
            raise ValueError('Missing repository link: ' + source + ' -> ' + link)
        self.source_links.append(relative)
        return 'https://github.com/onurkat/reclazz/blob/' + self.revision + '/' + quote(relative) + (('#' + parsed.fragment) if parsed.fragment else '')

    def render_inline(self, source, text):
        tokens = self.engine.parseInline(text)
        for token in tokens:
            for child in token.children or []:
                if child.type == 'link_open':
                    link = str(child.attrGet('href'))
                    if link.startswith('#'): link = Path(source).name + link
                    child.attrSet('href',self.resolve_link(source,link))
        return self.engine.renderer.render(tokens,self.engine.options,{})

    def render(self, source, section=None):
        path = (self.repo / source).resolve()
        if not path.is_relative_to(self.repo):
            raise ValueError('Escaping documentation source: ' + source)
        if not path.is_file():
            raise ValueError('Missing documentation source: ' + source)
        source_text=path.read_text(encoding='utf-8')
        tokens = self.engine.parse(source_text)
        pinned,reserved=pinned_usage_headings(self.repo,source,source_text)
        if tokens and tokens[0].type == 'heading_open' and tokens[0].tag == 'h1':
            tokens = tokens[3:]
        seen = Counter(); used_ids=set()
        for i, token in enumerate(tokens):
            if token.type == 'heading_open':
                inline = tokens[i + 1]
                text = ''.join(child.content for child in (inline.children or []) if child.type in ('text', 'code_inline'))
                fixed=pinned.get(token.map[0]) if token.map else None
                if fixed:
                    anchor=fixed['id'];token.attrSet('data-section-key',fixed['key'])
                elif reserved:
                    base=slug(text);anchor=base;occurrence=seen[base]
                    while anchor in reserved or anchor in used_ids:
                        occurrence+=1;anchor=base+'-'+str(occurrence)
                    seen[base]=occurrence
                else:
                    base=slug(text);occurrence=seen[base];seen[base]+=1
                    anchor=base+('-'+str(occurrence) if occurrence else '')
                used_ids.add(anchor);token.attrSet('id',anchor)
            for child in token.children or []:
                if child.type == 'link_open':
                    link = child.attrGet('href')
                    if link is not None:
                        if section and str(link).startswith('#'):
                            link = Path(source).name + str(link)
                        child.attrSet('href', self.resolve_link(source, str(link)))
                elif child.type == 'image':
                    link = child.attrGet('src')
                    if link is not None: child.attrSet('src', self.resolve_link(source, str(link), image=True))
        if section:
            start = next((i for i,t in enumerate(tokens) if t.type == 'heading_open' and t.attrGet('id') == section), None)
            if start is None: raise ValueError('Missing documentation section: ' + source + '#' + section)
            level = int(tokens[start].tag[1])
            end = next((i for i in range(start+3,len(tokens)) if tokens[i].type == 'heading_open' and int(tokens[i].tag[1]) <= level),len(tokens))
            tokens = tokens[start+3:end]
        body = self.engine.renderer.render(tokens, self.engine.options, {})
        # The HTML fragments are maintained in the same trusted source tree. No scripts
        # from Markdown are required by this corpus; fence contents stay HTML-escaped.
        return '<div class="reference-content">' + body + '</div>'
