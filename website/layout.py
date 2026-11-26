"""One public navigation, footer and search shell for docs and marketing pages."""
from html import escape, unescape
from html.parser import HTMLParser
import re
import json
from functools import lru_cache
from pathlib import Path

PUBLIC_PAGES = {'index.html': 'home', 'get-started/index.html': 'start', 'compare/index.html': 'compare',
                'privacy/index.html': 'privacy', 'license/index.html': 'license', '404.html': '404',
                'tr/sap-commerce-kurulum/index.html': 'setup-tr'}


def icon(name, css=''):
    """Unmodified Lucide paths from the licensed vendored subset."""
    source = (Path(__file__).parent/'static/assets/icons'/(name+'.svg')).read_text()
    source = re.sub(r'<svg\b[^>]*>', '<svg class="icon '+css+'" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">', source, count=1)
    return source


@lru_cache(maxsize=1)
def turkish_copy():
    return json.loads((Path(__file__).parent / 'static/assets/i18n/tr.json').read_text())


def shell_label(key, english, bilingual, turkish):
    attrs = ' data-i18n="' + key + '"' if bilingual else ''
    return attrs + '>' + (escape(turkish_copy()[key]) if turkish else english)


def header(active='docs', bilingual=False):
    start_href = '/get-started/'
    setup_tr = active == 'setup-tr'
    setup_href = '/tr/sap-commerce-kurulum/' if setup_tr else '/docs/sap-commerce/'
    setup_label = 'SAP Commerce kurulumu' if setup_tr else 'SAP Commerce setup'
    def links():
        result = ''
        for key, label, href in [('docs','Docs','/docs/'),('sapSetup',setup_label,setup_href),('compare','Compare','/compare/'),('github','GitHub ↗','https://github.com/onurkat/reclazz')]:
            current = ' aria-current="' + ('true' if key == 'docs' else 'page') + '"' if active == key or key == 'sapSetup' and active in ('setup','setup-tr') else ''
            translation = ' data-i18n="nav.'+key+'"' if bilingual and key != 'github' else ''
            if setup_tr and key != 'github':
                label = turkish_copy()['nav.' + key]
            setup_attrs = (' data-sap-setup lang="' + ('tr' if setup_tr else 'en') + '"') if key == 'sapSetup' else ''
            result += '<a href="'+href+'"'+current+translation+setup_attrs+'>'+label+'</a>'
        return result
    language = '<button class="site-language" id="langToggle" type="button" aria-label="Switch language to Turkish">TR</button>' if bilingual else ''
    if active in ('setup','setup-tr'):
        language = '<a class="site-language" href="' + ('/docs/sap-commerce/' if setup_tr else '/tr/sap-commerce-kurulum/') + '" lang="' + ('en' if setup_tr else 'tr') + '" hreflang="' + ('en' if setup_tr else 'tr') + '" aria-label="' + ('Read the English setup guide' if setup_tr else 'Türkçe kurulum rehberi') + '">' + ('EN' if setup_tr else 'TR') + '</a>'
    return '''<a class="skip-link" href="#main"''' + shell_label('site.skip', 'Skip to content', bilingual, setup_tr) + '''</a>
<header class="docs-header site-header">
<a class="brand" href="/" aria-label="Reclazz home"><img src="/favicon.svg" alt="" width="32" height="32"><span>Reclazz</span></a>
<span class="header-divider" aria-hidden="true"></span><nav class="header-links" aria-label="Main navigation">''' + links() + '''</nav>
<button class="search-trigger" id="search-open" type="button" aria-controls="search-dialog" hidden><svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4 4"/></svg><span''' + shell_label('site.search','Search documentation',bilingual,setup_tr) + '''</span><kbd>⌘ K</kbd></button>''' + language + '''
<button class="theme-button" id="theme-toggle" type="button" aria-label="Switch color theme" hidden><svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M20.6 13.1A8.5 8.5 0 0 1 10.9 3.4 8.5 8.5 0 1 0 20.6 13.1Z"/></svg></button>
<a class="header-install" href="''' + start_href + '''"''' + shell_label('site.start','Get started',bilingual,setup_tr) + ''' <span aria-hidden="true">↗</span></a>
<details class="site-menu"><summary aria-label="Site navigation"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16"/></svg></summary><nav aria-label="Mobile main navigation">''' + links() + '''<a href="''' + start_href + '''"''' + shell_label('site.start','Get started',bilingual,setup_tr) + '''</a></nav></details>
</header>'''


def footer(year='2026', bilingual=False, version=None, setup_tr=False):
    def label(key, text):
        return shell_label('site.'+key, text, bilingual, setup_tr)
    setup_href = '/tr/sap-commerce-kurulum/' if setup_tr else '/docs/sap-commerce/'
    setup_text = 'SAP Commerce kurulumu' if setup_tr else 'SAP Commerce setup'
    setup_link = '<a href="' + setup_href + '" data-sap-setup lang="' + ('tr' if setup_tr else 'en') + '"' + label('sapSetup',setup_text) + '</a>'
    version_line = ('<p class="footer-version"><span' + label('docsVersion','Docs for') + '</span> ' + escape(version) + ' · <a href="/docs/evidence/"' + label('evidence','Test coverage &amp; versions') + '</a></p>') if version else ''
    return '''<footer class="site-footer"><div class="site-footer-inner">
<div class="footer-brand"><a class="brand" href="/" aria-label="Reclazz home"><img src="/favicon.svg" width="28" height="28" alt=""><span>Reclazz</span></a><p''' + label('tagline','Hot reload for Java, Spring and SAP Commerce.') + '''</p><span class="footer-license"''' + label('openSource','Apache 2.0 · Built in the open.') + '''</span></div>
<nav aria-label="Product links"><p''' + label('product','Product') + '''</p><a href="/docs/"''' + label('documentation','Documentation') + '''</a>''' + setup_link + '''<a href="/docs/quickstart/#install-from-marketplace"''' + label('quickstart','Quickstart') + '''</a><a href="/docs/support-matrix/"''' + label('support','Reload support') + '''</a><a href="/compare/"''' + label('compare','Compare') + '''</a></nav>
<nav aria-label="Project links"><p''' + label('project','Project') + '''</p><a href="https://github.com/onurkat/reclazz">GitHub ↗</a><a href="https://github.com/onurkat/reclazz/blob/main/CHANGELOG.md"''' + label('changelog','Changelog ↗') + '''</a><a href="https://github.com/onurkat/reclazz/blob/main/CONTRIBUTING.md"''' + label('contributing','Contributing ↗') + '''</a><a href="https://github.com/onurkat/reclazz/blob/main/SECURITY.md"''' + label('security','Security ↗') + '''</a><a href="https://github.com/onurkat/reclazz/releases"''' + label('releases','Releases ↗') + '''</a><a href="https://github.com/onurkat/reclazz/issues"''' + label('issues','Report an issue ↗') + '''</a></nav>
<nav aria-label="Legal links"><p''' + label('legal','Legal') + '''</p><a href="/license/"''' + label('license','License') + '''</a><a href="/privacy/"''' + label('privacy','Privacy') + '''</a></nav>
''' + version_line + '''<div class="footer-bottom"><span>© ''' + escape(year) + ''' Onur Kat</span><a href="https://github.com/sponsors/onurkat"''' + label('sponsor','Support development ↗') + '''</a></div></div></footer>'''


def utilities():
    return '''<dialog class="search-dialog" id="search-dialog" aria-labelledby="search-title"><div class="search-top"><label id="search-title" for="docs-search">Search documentation</label><button id="search-close" class="dialog-close" type="button" aria-label="Close search">Esc</button></div><input id="docs-search" type="search" placeholder="What would you like to reload?" autocomplete="off"><p id="search-status" role="status" aria-live="polite">Search guides, changes and limits. Your query stays in this browser.</p><div id="search-results"></div><div class="search-hint">↑ ↓ to select · Enter to open · Esc to close</div></dialog><div class="sr-only" id="copy-status" role="status" aria-live="polite"></div>'''


def head(marketing=False):
    return '''<link rel="stylesheet" href="/assets/tokens.css">
<script>if(location.pathname.endsWith('/index.html'))location.replace(location.pathname.slice(0,-10)+location.search+location.hash);</script>
<script src="/docs/assets/theme.js"></script>
<link rel="stylesheet" href="/docs/assets/docs.css"><link rel="stylesheet" href="/assets/site.css">''' + ('<link rel="stylesheet" href="/assets/marketing.css">' if marketing else '') + '''
<link rel="stylesheet" href="/assets/components.css">
<script src="/docs/assets/docs.js" defer></script><script src="/assets/site.js" defer></script>'''



def render_public(text, page, year, version=None):
    for marker in ('SITE_HEAD','SITE_HEADER','SITE_FOOTER','SITE_UTILITIES'):
        if text.count('{{'+marker+'}}') != 1:
            raise ValueError('Public page needs one '+marker+' marker: '+page)
    bilingual = page in ('home', 'start')
    for marker, value in [('SITE_HEAD',head(marketing=not bilingual)),('SITE_HEADER',header(page,bilingual)),
                          ('SITE_FOOTER',footer(year,bilingual,version,setup_tr=page == 'setup-tr')),('SITE_UTILITIES',utilities())]:
        text = text.replace('{{'+marker+'}}',value)
    return text


def mobile_navigation(nav):
    """Reserve the same menu space before and after progressive enhancement."""
    return ('<div class="docs-menu-bar"><button type="button" class="drawer-open" aria-controls="docs-drawer" aria-expanded="false">Documentation menu</button>'
            '<details class="mobile-nav"><summary>Documentation menu</summary><nav aria-label="Mobile documentation">'+nav+'</nav></details></div>'
            '<dialog class="drawer-dialog" id="docs-drawer" aria-label="Documentation menu"><button type="button" class="drawer-close">Close menu</button></dialog>')


SCROLL_CLASSES = {'table-scroll', 'comparison-wrapper', 'cmp-wrap', 'terminal-body',
                  'demo-body', 'demo-code', 'architecture-diagram', 'sidebar', 'page-toc'}


def is_scroll_region(tag, attrs, parents=()):
    classes = set(attrs.get('class', '').split())
    return (tag == 'pre' or bool(classes & SCROLL_CLASSES)
            or (tag == 'nav' and 'mobile-nav' in parents)
            or ('container' in classes and 'section-nav' in parents))


def accessible_scroll_regions(document):
    """Annotate the actual scroller, including source HTML and Markdown output."""
    lines = document.splitlines(keepends=True)
    offsets = [0]
    for line in lines: offsets.append(offsets[-1]+len(line))
    class Regions(HTMLParser):
        def __init__(self):
            super().__init__(convert_charrefs=True)
            self.stack=[];self.edits=[]
        def handle_starttag(self, tag, attrs):
            a=dict(attrs);parents={c for _,cs in self.stack for c in cs}
            if is_scroll_region(tag,a,parents):
                line,col=self.getpos();start=offsets[line-1]+col;end=start+len(self.get_starttag_text())
                label=a.get('aria-label')
                if not label:
                    if tag=='pre':
                        labels=re.findall(r'<div class="code-label">(.*?)</div>',document[:start],re.S)
                        label=(re.sub('<[^>]+>','',labels[-1])+' example') if labels else 'Code example'
                    elif set(a.get('class','').split()) & {'table-scroll','comparison-wrapper','cmp-wrap'}:
                        table=document[end:].split('</table>',1)[0]
                        caption=re.search(r'<caption[^>]*>(.*?)</caption>',table,re.S)
                        label=re.sub('<[^>]+>','',caption[1]) if caption else 'Data table'
                    else:
                        label=next((v for k,v in [('sidebar','Documentation navigation'),('page-toc','On this page'),('terminal-body','Terminal example'),('demo-code','Code example'),('demo-body','Reload demonstration'),('architecture-diagram','Architecture diagram')] if k in a.get('class','').split()),'Navigation links')
                values={**a,'tabindex':'0','role':'region','aria-label':' '.join(unescape(label).split())}
                opening='<'+tag+''.join(' '+key+('="'+escape(value,quote=True)+'"' if value is not None else '') for key,value in values.items())+'>'
                self.edits.append((start,end,opening))
            if tag not in {'area','base','br','col','embed','hr','img','input','link','meta','param','source','track','wbr'}:
                self.stack.append((tag,set(a.get('class','').split())))
        def handle_endtag(self,tag):
            for i in range(len(self.stack)-1,-1,-1):
                if self.stack[i][0]==tag:
                    del self.stack[i:];break
    parser=Regions();parser.feed(document)
    for start,end,value in reversed(parser.edits):document=document[:start]+value+document[end:]
    return document


def enhance_document(document):
    """Apply shared presentation without editing source prose or fragment IDs."""
    for glyph, name in [('↳','chevron-right'),('◈','book-open')]:
        document = document.replace(glyph, icon(name))
    # External-link and pager glyphs are chrome; marketing emoji remain Stage 4.
    document = re.sub(r'(<a\b[^>]*>)(.*?)(</a>)', lambda m: m[1]+m[2].replace('↗',icon('external-link')).replace('→',icon('arrow-right')).replace('←',icon('arrow-left'))+m[3], document, flags=re.S)
    document = re.sub(r'(<button[^>]*id="search-open"[^>]*>)<svg.*?</svg>', lambda m:m[1]+icon('search'),document,flags=re.S)
    document = re.sub(r'(<button[^>]*id="theme-toggle"[^>]*>)<svg.*?</svg>', lambda m:m[1]+icon('moon'),document,flags=re.S)
    document = re.sub(r'(<summary aria-label="Site navigation">)<svg.*?</svg>',lambda m:m[1]+icon('menu'),document,flags=re.S)
    for glyph, name in [('&#10003;','check'),('✓','check'),('&#9888;','triangle-alert'),('⚠','triangle-alert'),('&#10007;','x'),('✗','x')]:
        document = re.sub(r'(<span class="(?:icon|check|cross)"[^>]*>)'+re.escape(glyph)+r'(</span>)',lambda m:m[1]+icon(name)+m[2],document)
    document = re.sub(r'(<span class="faq-arrow"[^>]*>)\s*\+\s*(</span>)',lambda m:m[1]+icon('chevron-down')+m[2],document)
    document = document.replace('&larr; Back to Reclazz',icon('arrow-left')+' Back to Reclazz')
    # Native details remains the no-JS mobile navigation. A modal drawer is added by JS.
    toc = re.search(r'<aside class="page-toc"><nav aria-label="On this page">(.*?)</nav>',document,re.S)
    if toc:
        links = toc[1]
        links = re.sub(r'^<p>.*?</p>','',links,flags=re.S)
        document = document.replace('<article>', '<details class="mobile-toc"><summary>On this page</summary><nav aria-label="On this page">'+links+'</nav></details><article>',1)
    # Existing guide/source heading levels are semantic markup, never text/anchor edits.
    previous = [0]
    def heading(match):
        level = min(int(match[1]), previous[0]+1) if previous[0] else int(match[1])
        previous[0] = level
        return '<h'+str(level)+match[2]+'>'+match[3]+'</h'+str(level)+'>'
    document = re.sub(r'<h([1-6])([^>]*)>(.*?)</h\1>',heading,document,flags=re.S)
    document = re.sub(r'(font-size\s*:\s*)([0-9.]+)px',lambda m:m[1]+str(max(13,float(m[2]))).removesuffix('.0')+'px',document)
    # Render only trusted, non-code blockquote notes as the callout component.
    document = re.sub(r'<blockquote>\s*<p>(?:<strong>)?(Note|Tip|Warning|Restart):(?:</strong>)?\s*(.*?)</p>\s*</blockquote>', lambda m:'<aside class="callout" data-kind="'+m[1].lower()+'"><div class="callout-label">'+icon('info' if m[1] in ('Note','Tip') else 'triangle-alert')+m[1]+'</div><p>'+m[2]+'</p></aside>', document, flags=re.S)
    document = re.sub(r'(<span class="badge (works|conditional|restart|unverified|not-reloaded)">)', lambda m:m[1]+icon({'works':'check','conditional':'triangle-alert','restart':'rotate-cw','unverified':'circle-help','not-reloaded':'circle-minus'}[m[2]]),document)
    document = re.sub(r'(<a\b[^>]*class="path-card"[^>]*>)',lambda m:m[1]+icon('book-open'),document)
    return accessible_scroll_regions(document)
