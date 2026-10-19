"""One public navigation, footer and search shell for docs and marketing pages."""
from html import escape
import re

PUBLIC_PAGES = {'index.html': 'home', 'compare/index.html': 'compare',
                'privacy/index.html': 'privacy', 'license/index.html': 'license'}


def header(active='docs', bilingual=False):
    def links():
        result = ''
        for key, label, href in [('docs','Docs','/docs/'),('compare','Compare','/compare/'),('github','GitHub ↗','https://github.com/onurkat/reclazz')]:
            current = ' aria-current="' + ('true' if key == 'docs' else 'page') + '"' if active == key else ''
            translation = ' data-i18n="nav.'+key+'"' if bilingual and key != 'github' else ''
            result += '<a href="'+href+'"'+current+translation+'>'+label+'</a>'
        return result
    language = '<button class="site-language" id="langToggle" type="button" aria-label="Switch language to Turkish">TR</button>' if bilingual else ''
    return '''<a class="skip-link" href="#main">Skip to content</a>
<header class="docs-header site-header">
<a class="brand" href="/" aria-label="Reclazz home"><img src="/favicon.svg" alt="" width="32" height="32"><span>Reclazz</span></a>
<span class="header-divider" aria-hidden="true"></span><nav class="header-links" aria-label="Main navigation">''' + links() + '''</nav>
<button class="search-trigger" id="search-open" type="button" aria-controls="search-dialog" hidden><svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4 4"/></svg><span>Search documentation</span><kbd>⌘ K</kbd></button>''' + language + '''
<button class="theme-button" id="theme-toggle" type="button" aria-label="Switch color theme" hidden><svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M20.6 13.1A8.5 8.5 0 0 1 10.9 3.4 8.5 8.5 0 1 0 20.6 13.1Z"/></svg></button>
<a class="header-install" href="/docs/quickstart/"''' + (' data-i18n="site.start"' if bilingual else '') + '''>Get started <span aria-hidden="true">↗</span></a>
<details class="site-menu"><summary aria-label="Site navigation"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16"/></svg></summary><nav aria-label="Mobile main navigation">''' + links() + '''<a href="/docs/quickstart/">Get started ↗</a></nav></details>
</header>'''


def footer(year='2026', bilingual=False):
    def label(key, text):
        return (' data-i18n="site.'+key+'"' if bilingual else '') + '>'+text
    return '''<footer class="site-footer"><div class="site-footer-inner">
<div class="footer-brand"><a class="brand" href="/" aria-label="Reclazz home"><img src="/favicon.svg" width="28" height="28" alt=""><span>Reclazz</span></a><p''' + label('tagline','Make the next edit count.') + '''</p><span class="footer-license">Apache 2.0 · Built in the open.</span></div>
<nav aria-label="Product links"><p''' + label('product','Product') + '''</p><a href="/docs/"''' + label('documentation','Documentation') + '''</a><a href="/docs/quickstart/"''' + label('quickstart','Quickstart') + '''</a><a href="/docs/support-matrix/"''' + label('support','Reload support') + '''</a><a href="/compare/"''' + label('compare','Compare') + '''</a></nav>
<nav aria-label="Project links"><p''' + label('project','Project') + '''</p><a href="https://github.com/onurkat/reclazz">GitHub ↗</a><a href="https://github.com/onurkat/reclazz/releases"''' + label('releases','Releases ↗') + '''</a><a href="https://github.com/onurkat/reclazz/issues"''' + label('issues','Report an issue ↗') + '''</a></nav>
<nav aria-label="Legal links"><p''' + label('legal','Legal') + '''</p><a href="/license/"''' + label('license','License') + '''</a><a href="/privacy/"''' + label('privacy','Privacy') + '''</a></nav>
<div class="footer-bottom"><span>© ''' + escape(year) + ''' Onur Kat</span><a href="https://github.com/sponsors/onurkat"''' + label('sponsor','Support development ↗') + '''</a></div></div></footer>'''


def utilities():
    return '''<dialog class="search-dialog" id="search-dialog" aria-labelledby="search-title"><div class="search-top"><label id="search-title" for="docs-search">Search documentation</label><button id="search-close" class="dialog-close" type="button" aria-label="Close search">Esc</button></div><input id="docs-search" type="search" placeholder="What would you like to reload?" autocomplete="off"><p id="search-status" role="status" aria-live="polite">Search guides, changes and limits. Your query stays in this browser.</p><div id="search-results"></div><div class="search-hint">↑ ↓ to select · Enter to open · Esc to close</div></dialog><div class="sr-only" id="copy-status" role="status" aria-live="polite"></div>'''


def head(marketing=False):
    return '''<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;450;500;550;600;650;700;750;800&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
<script src="/docs/assets/theme.js"></script>
<link rel="stylesheet" href="/docs/assets/docs.css"><link rel="stylesheet" href="/assets/site.css">''' + ('<link rel="stylesheet" href="/assets/marketing.css">' if marketing else '') + '''
<script src="/docs/assets/docs.js" defer></script><script src="/assets/site.js" defer></script>'''


def render_public(text, page, year):
    for marker in ('SITE_HEAD','SITE_HEADER','SITE_FOOTER','SITE_UTILITIES'):
        if text.count('{{'+marker+'}}') != 1:
            raise ValueError('Public page needs one '+marker+' marker: '+page)
    bilingual = page == 'home'
    for marker, value in [('SITE_HEAD',head(marketing=True)),('SITE_HEADER',header(page,bilingual)),
                          ('SITE_FOOTER',footer(year,bilingual)),('SITE_UTILITIES',utilities())]:
        text = text.replace('{{'+marker+'}}',value)
    return text
