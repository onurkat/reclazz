"""Render repository Markdown with native tables/fences and local site links."""
from collections import Counter
from html import escape
from pathlib import Path
import posixpath
import re
from urllib.parse import urlsplit, urlunsplit, quote, unquote

from markdown_it import MarkdownIt


def slug(text):
    return re.sub(r'[^\w\- ]', '', text.lower()).replace(' ', '-')


def reference_pages(repo):
    pages = []
    for path in sorted((repo / 'docs').glob('*.md')):
        parsed = MarkdownIt('commonmark').parse(path.read_text(encoding='utf-8'))
        first = next((parsed[i + 1].content for i, token in enumerate(parsed[:-1])
                      if token.type == 'heading_open' and token.tag == 'h1'), path.stem)
        pages.append({'slug': 'reference' if path.stem == 'index' else 'reference/' + path.stem,
                      'title': 'Technical reference library' if path.stem == 'index' else first,
                      'description': 'Complete configuration, behavior and scope reference: ' + first + '.',
                      'group': 'Maintaining' if path.stem in ('documentation-workflow','publishing') else 'Technical reference', 'eyebrow': 'Maintaining the project' if path.stem in ('documentation-workflow','publishing') else 'Complete technical reference',
                      'sources': [path.relative_to(repo).as_posix()], 'markdown': path.relative_to(repo).as_posix(),
                      'nav_title': 'Reference library' if path.stem == 'index' else first,
                      'heading_id': slug(first)})
    return sorted(pages, key=lambda p: (p['slug'] != 'reference', p['slug']))


class MarkdownRenderer:
    def __init__(self, repo, pages, revision='main'):
        self.repo = Path(repo).resolve()
        self.routes = {p['markdown']: '/docs/' + p['slug'] + '/' for p in pages if 'markdown' in p}
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
        tokens = self.engine.parse(path.read_text(encoding='utf-8'))
        if tokens and tokens[0].type == 'heading_open' and tokens[0].tag == 'h1':
            tokens = tokens[3:]
        seen = Counter()
        for i, token in enumerate(tokens):
            if token.type == 'heading_open':
                inline = tokens[i + 1]
                text = ''.join(child.content for child in (inline.children or []) if child.type in ('text', 'code_inline'))
                anchor = slug(text)
                occurrence = seen[anchor]; seen[anchor] += 1
                token.attrSet('id', anchor + ('-' + str(occurrence) if occurrence else ''))
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
