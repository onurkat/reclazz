"""Build independent language routes from the approved page bodies and translations."""
from html import escape, unescape
from html.parser import HTMLParser
import json
import re

ORIGIN = 'https://reclazz.com'
PAIRS = {
    '/': '/tr/',
    '/get-started/': '/tr/baslangic/',
    '/jrebel-alternative/': '/tr/jrebel-alternatifi/',
    '/hybris-hot-reload/': '/tr/hybris-hot-reload/',
}
TURKISH_HEADS = {
    '/tr/': ('Reclazz: SAP Commerce ve Spring için Ücretsiz Hot Reload',
             "Kod değişikliğini görmek için SAP Commerce'i yeniden başlatma. Reclazz değişen Java sınıflarını çalışan sunucuya yükler. Ücretsiz ve açık kaynak."),
    '/tr/baslangic/': ("Reclazz'a Başla: IntelliJ IDEA Kurulumu",
             'Ücretsiz Reclazz eklentisini kur, Java ya da SAP Commerce projeni birkaç tıklamayla hazırla ve ilk değişikliğini yeniden başlatmadan gör.'),
}
# Shared controls carry accessible labels as well as visible copy.
CONTROLS = {
    'Reclazz summary for language models': 'Dil modelleri için Reclazz özeti',
    'Close search': 'Aramayı kapat', 'Demo video, click to play': 'Demo videosu, oynatmak için tıklayın',
    'Hot reload comparison': 'Hot reload karşılaştırması', 'Legal links': 'Yasal bağlantılar',
    'Main navigation': 'Ana gezinme', 'Mobile main navigation': 'Mobil ana gezinme',
    'Product links': 'Ürün bağlantıları', 'Project links': 'Proje bağlantıları',
    'Reclazz home': 'Reclazz ana sayfası', 'Site navigation': 'Site menüsü', 'Switch color theme': 'Renk temasını değiştir',
    'What would you like to reload?': 'Neyi yeniden yüklemek istiyorsunuz?',
    'Search documentation': 'Belgelerde ara',
    'Search guides, changes and limits. Your query stays in this browser.': 'Kılavuzlarda, değişikliklerde ve sınırlarda arayın. Sorgunuz bu tarayıcıda kalır.',
    '↑ ↓ to select · Enter to open · Esc to close': '↑ ↓ ile seç · Enter ile aç · Esc ile kapat',
}


class TranslationSpans(HTMLParser):
    """Collect keyed element interiors while preserving original SVG, scripts and formatting."""
    def __init__(self, text, catalog, route):
        super().__init__(convert_charrefs=False)
        self.lines = [0]
        self.lines.extend(m.end() for m in re.finditer('\n', text))
        self.catalog, self.route, self.text = catalog, route, text
        self.pending = []
        self.edits = []
        self.feed(text)
        self.close()

    def character_offset(self):
        line, column = self.getpos()
        return self.lines[line - 1] + column

    def handle_starttag(self, tag, attrs):
        key = dict(attrs).get('data-i18n')
        if key:
            if not self.catalog.get(key):
                raise ValueError(f'{self.route}: missing translation {key}')
            self.pending.append((tag, key, self.character_offset() + len(self.get_starttag_text())))
        elif self.pending and tag == self.pending[-1][0]:
            self.pending.append((tag, None, 0))

    def handle_endtag(self, tag):
        if self.pending and self.pending[-1][0] == tag:
            _, key, start = self.pending.pop()
            if key:
                end = self.character_offset()
                original = self.text[start:end]
                icons = re.findall(r'<svg\b[^>]*class="icon [^"]*"[^>]*>.*?</svg>', original, re.S)
                self.edits.append((start, end, self.catalog[key] + ''.join(icons)))


def translate(text, catalog, route):
    spans = TranslationSpans(text, catalog, route)
    for start, end, content in sorted(spans.edits, reverse=True):
        text = text[:start] + content + text[end:]
    for en, tr in CONTROLS.items():
        for attr in ('aria-label', 'placeholder', 'title'):
            text = text.replace(f'{attr}="{en}"', f'{attr}="{tr}"')
        text = text.replace('>' + en + '<', '>' + tr + '<')
    return text


def paired_head(text, en, tr):
    alternates = ''.join(f'<link rel="alternate" hreflang="{lang}" href="{ORIGIN}{target}">\n'
                         for lang, target in [('en', en), ('tr', tr), ('x-default', en)])
    return text.replace('</head>', alternates + '</head>', 1)


def breadcrumb(route, title, turkish):
    home = '/tr/' if turkish else '/'
    links = [('Reclazz', home)]
    if route != home:
        links.append((title, route))
    return {'@context':'https://schema.org', '@type':'BreadcrumbList', 'itemListElement':[
        {'@type':'ListItem','position':i,'name':label,'item':ORIGIN+url}
        for i,(label,url) in enumerate(links,1)]}


def schema_tag(obj):
    return '<script type="application/ld+json">' + json.dumps(obj, ensure_ascii=False).replace('<',r'\u003c') + '</script>'


def translated_page(text, catalog, en, tr):
    title, description = TURKISH_HEADS[tr]
    text = translate(text, catalog, tr).replace('<html lang="en"', '<html lang="tr"', 1)
    text = re.sub(r'<title>.*?</title>', lambda _: '<title>'+escape(title,quote=False)+'</title>', text, count=1)
    for key,value in [('description',description),('og:description',description),('twitter:description',description),
                      ('og:title',title),('twitter:title',title),('og:url',ORIGIN+tr),('og:locale','tr_TR'),
                      ('og:image:alt',title),('twitter:image:alt',title)]:
        text = re.sub(r'(<meta (?:property|name)="'+re.escape(key)+r'" content=")[^"]*',
                      lambda m: m[1]+escape(value),text)
    text = text.replace('rel="canonical" href="'+ORIGIN+en+'"','rel="canonical" href="'+ORIGIN+tr+'"')
    for source, target in PAIRS.items():
        text = text.replace('href="'+source+'"','href="'+target+'"')
    text = text.replace('href="/docs/sap-commerce/"','href="/tr/sap-commerce-kurulum/"').replace('data-sap-setup lang="en"','data-sap-setup lang="tr"')
    def schema(match):
        obj = json.loads(match[1])
        if obj.get('@type') == 'FAQPage':
            for i,qa in enumerate(obj['mainEntity']):
                qa['name'] = unescape(catalog[f'faq.q{i}'])
                qa['acceptedAnswer']['text'] = unescape(catalog[f'faq.a{i}'])
        if obj.get('url') == ORIGIN + en:
            obj['url'] = ORIGIN + tr
        if obj.get('@type') == 'SoftwareApplication':
            obj['description'] = description
        return schema_tag(obj)
    text = turkish_video_assets(text)
    return re.sub(r'<script type="application/ld\+json">(.*?)</script>',schema,text,flags=re.S)


def turkish_video_assets(text):
    for asset in ('live-reload.webm', 'live-reload-poster.webp', 'live-reload-phone.webp'):
        name, extension = asset.rsplit('.', 1)
        text = text.replace('/assets/setup/'+asset, '/assets/setup/'+name+'-tr.'+extension)
    return text


def demo_markup(home, lang):
    match = re.search(r'<figure class="session-film">.*?</figure>',home,re.S)
    if not match:
        raise ValueError('Missing home demo')
    # The localized movie already has captions in its frames.
    figure = match[0]
    if lang == 'tr':
        figure = turkish_video_assets(figure)
    return figure


def language_link(en, tr, lang):
    target, code, label = (en, 'en', 'English') if lang == 'tr' else (tr, 'tr', 'Türkçe')
    return f'<a class="site-language" href="{target}" lang="{code}" hreflang="{code}" aria-label="{label}">{code.upper()}</a>'


def add_pages(output, sources, repo, renderer):
    def key(route):
        return route.lstrip('/') + 'index.html'

    def string(name):
        raw = output[name]
        return raw.decode() if isinstance(raw,bytes) else raw

    catalog = json.loads(string('assets/i18n/tr.json'))
    start_catalog = dict(catalog, **json.loads(string('assets/i18n/start-tr.json')))
    model = json.loads((repo/'website/compare.json').read_text())
    products = {row['id']:row['cells'] for row in model['rows']}
    urls = {entry['key']:entry['url'] for entry in model['sources'].values()}
    home = string('index.html')

    for en,tr in PAIRS.items():
        for route,lang in ((en,'en'),(tr,'tr')):
            name = key(route)
            if route in TURKISH_HEADS:
                used = start_catalog if en == '/get-started/' else catalog
                text = translated_page(string(key(en)), used, en, tr)
                sources[route] = sources[en] | {'website/static/assets/i18n/tr.json','website/localized.py'}
                if en == '/get-started/':
                    sources[route].add('website/static/assets/i18n/start-tr.json')
            else:
                text = string(name)
                if '{{LANDING_DEMO}}' in text:
                    text = text.replace('{{LANDING_DEMO}}', demo_markup(home,lang))
                    sources[route].add('website/static/index.html')
                if '{{LANDING_SOURCE:' in text:
                    def source_link(match):
                        record = products[match[1]][match[2]]['source']
                        if 'key' in record: href = urls[record['key']]
                        elif 'record' in record: href = '/docs/support-matrix/#' + record['record']
                        elif 'doc' in record:
                            href = '/license/' if record['doc'] == 'README.md#license-and-support' else renderer.resolve_link('README.md',record['doc'])
                        elif not record: return ''
                        else: raise ValueError('Unknown comparison source: '+match[0])
                        label = 'Kaynak' if lang == 'tr' else 'Source'
                        return '<a class="comparison-source" href="' + escape(href,quote=True) + '">' + label + '</a>'
                    text = re.sub(r'\{\{LANDING_SOURCE:([\w-]+):(reclazz|jrebel)\}\}',source_link,text)
                    sources[route].add('website/compare.json')
                if lang == 'tr':
                    text = translate(text,catalog,route)
                    sources[route].add('website/static/assets/i18n/tr.json')
                    text = text.replace('href="/"','href="/tr/"').replace('href="/get-started/"','href="/tr/baslangic/"').replace('href="/docs/sap-commerce/"','href="/tr/sap-commerce-kurulum/"').replace('data-sap-setup lang="en"','data-sap-setup lang="tr"')
                    for original,partner in PAIRS.items():
                        text = text.replace('href="'+original+'"','href="'+partner+'"')
            # The English member is also input to a translated template. Finalize each
            # pair only after both bodies exist so metadata cannot be inherited twice.
            if route != en or en not in ('/','/get-started/'):
                title = unescape(re.search(r'<title>(.*?)</title>',text)[1])
                text = text.replace('</head>',schema_tag(breadcrumb(route,title,lang=='tr'))+'</head>',1)
            if lang == 'en':
                english = text
            else:
                for target, page, code in [(en,english,'en'),(tr,text,'tr')]:
                    page = re.sub(r'<a class="site-language"[^>]*>.*?</a>', language_link(en,tr,code),page,count=1)
                    output[key(target)] = paired_head(page,en,tr)
