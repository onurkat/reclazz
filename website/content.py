"""Canonical documentation inputs shared by rendering and validation."""
from collections import Counter
from datetime import date
from html import escape
from pathlib import Path
import json
from website.evidence_registry import HINT, RegistrySources, references, registries
import re
from urllib.parse import urlsplit, unquote

from markdown_it import MarkdownIt
from website.markdown import EVIDENCE_GUIDES, slug

LABELS = {'works': 'Reloads', 'conditional': 'Reloads, with a condition', 'restart': 'Needs a restart',
          'unverified': 'Not verified yet', 'not-reloaded': 'No change applied'}
TABLE_LABELS = dict(LABELS, conditional='With conditions', restart='Restart', unverified='Unverified', **{'not-reloaded': 'No reload'})
NOTE_IDS = {'jdk-new','condition-recheck','serializer','messaging-advanced','unknown-files'}
OVERVIEW_IDS = ['method-body','added-method','bean','mvc','boot-properties','items']
COMMON_IDS = ['method-body','added-method','instance-field','bean','component','bean-factory','mvc','boot-properties','value','items','sap-config','backoffice-config']

GUIDE_IDS = {'sap-commerce': ['items', 'sap-schema', 'sap-config', 'sap-localization', 'impex', 'interceptor', 'occ', 'backoffice-config', 'backoffice-socket', 'backoffice-module', 'sap-process', 'sap-solr-reindex', 'extension'], 'spring': ['bean', 'component', 'bean-factory', 'injection', 'new-lifecycle', 'service-advice', 'security', 'aspect', 'scheduled', 'event', 'transaction-event', 'async', 'constructor-helper', 'custom-scope', 'mvc', 'exception-handler', 'binder-model', 'jackson', 'constraint', 'web-security', 'kafka', 'jms', 'jms-reply', 'rabbit', 'listener-expression', 'property-route', 'consumer-helper', 'rabbit-helper', 'mixed-listener', 'messaging-advanced', 'condition-recheck', 'serializer'], 'configuration': ['boot-properties', 'boot-yaml', 'key-removal', 'config-topology', 'value', 'xml-property', 'xml-recreate', 'xml-policy', 'xml-unsupported-noop', 'xml-resource', 'message', 'template', 'packaged-resource', 'logging', 'unknown-files'], 'persistence': ['jpa-existing', 'jpa-new', 'jpa-retry', 'jpa-active', 'repository', 'cache', 'custom-cache'], 'java': ['method-body', 'added-method', 'instance-field', 'field-caller', 'static-field', 'static-reset', 'inlined-constant', 'enum-tail', 'enum-reorder', 'interface', 'superclass', 'record-components', 'reflection', 'jdk-new'], 'troubleshooting': ['request-boundary', 'build-failure', 'dependency']}
OPTION_GROUPS = {'Common': ['watchDirs', 'excludeClasses', 'excludePatterns', 'debounceMs', 'startupDelaySec', 'structuralReload', 'platform', 'mavenCompiler'], 'SAP Commerce': ['hybrisHome', 'watchExtensions', 'autoCompile', 'autoImpex', 'impexAllowRemove', 'autoUpdateRunningSystem', 'backofficeConfigReset', 'autoSolrReindex'], 'Opt-in behaviour': ['jpaRefresh', 'resetStatics', 'reloadBoundary', 'refreshOwnersOnHelper'], 'Diagnostics': ['verbose', 'statusPort', 'portFile', 'wrapOutput', 'transformDumpDir', 'verifyTransform', 'sessionLog']}

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
                            conditions=conditions, action=action, source=source,
                            presentation='note' if key in NOTE_IDS else 'row'))
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


def guide_evidence(renderer, guide):
    """Show the recorded observation and its bounds, with the original data alongside."""
    sections = []
    for name, (target, title) in EVIDENCE_GUIDES.items():
        if target != guide:
            continue
        relative = 'docs/feature-evidence/' + name + '.json'
        data = (renderer.repo / relative).read_bytes()
        record = json.loads(data)
        asset = 'docs/source-assets/' + relative
        renderer.assets[asset] = data
        if record.get('claims'):
            example = record['claims'][0]
            description = example['statement']
            limit = example['limitation']
            label = 'Example from the recorded checks'
        else:
            description = record.get('scope') or record['existing_behavior']
            limit = (record.get('limits') or record['limitations'])[0]
            label = 'What the record covers'
        sections.append(
            '<section><h2 id="evidence-' + name + '">' + escape(title) + ' evidence</h2>'
            '<p><strong>' + label + '.</strong> ' + escape(description) + '</p>'
            '<p><strong>Scope.</strong> ' + escape(limit) + '</p>'
            '<p class="source-link"><a download href="/' + asset + '">Download data (JSON)</a></p></section>')
    return ''.join(sections)


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
    repo = Path(repo).resolve()
    source = repo / 'docs/feature-evidence.md'
    if not source.is_file():
        return []
    sections = {}
    current = None
    for line in source.read_text(encoding='utf-8').splitlines():
        if line.startswith('### '):
            current = line[4:]
        elif line.startswith('**Shipped surfaces:**'):
            sections.setdefault(current, []).append(line)
    errors = []
    sources = RegistrySources(repo)
    for _, record in registries(repo):
        for claim in record['claims']:
            name = claim['id']
            for item in claim['shipped_surfaces']:
                try:
                    if sources.surface(item) != item.get('block_sha256'):
                        raise ValueError('block digest changed')
                except (OSError, ValueError) as error:
                    errors.append(f"{name}: {item['path']} {item['quote']}: {error}; {HINT}")
            if sections.pop(name, []) != [references(claim)]:
                errors.append(f"Human-readable evidence digest drift: {name}; {HINT}")
    for unknown in sections:
        errors.append(f"Evidence references have no claim: {unknown}")
    return errors


# Marketing facts are extracted from repository sources, never from saved output.
def marketing_claims(repo):
    repo = Path(repo)
    specs = json.loads((repo/'website/claims.json').read_text())
    installation = (repo/'docs/installation.md').read_text()
    gradle = (repo/'build.gradle.kts').read_text()
    def match(pattern, text):
        result = re.search(pattern, text, re.M)
        if not result: raise ValueError('Marketing source extractor did not match: '+pattern)
        return result
    first = match(r'sinceBuild = "(\d+)"', gradle)[1]
    last = match(r'untilBuild = "(\d+)\.\*"', gradle)[1]
    def idea(value): return '20'+value[:2]+'.'+value[2:]
    values = {
        'sap_scenarios': match(r'default suite now contains \*\*(\d+) scenarios', (repo/'docs/sap-commerce-live-tests.md').read_text())[1],
        'intellij_builds': idea(first)+' to '+idea(last),
        'agent_java_min': match(r'target \*\*Java (\d+)\+', installation)[1],
        'jdk_tested': match(r'JDK (\d+/\d+) are the baseline test', installation)[1]+'; JDK '+match(r'\*\*JDK (\d+) runtime:\*\*', installation)[1]+' runtime tested locally',
        'version': match(r'^pluginVersion=(.+)$', (repo/'gradle.properties').read_text())[1].strip(),
    }
    if set(specs) != set(values): raise ValueError('Unknown or missing marketing claim')
    for k, value in values.items():
        if specs[k]['value'] != value: raise ValueError('Marketing claim mismatch: '+k)
    return values


def expand_claims(text, values, locale="en"):
    def replace(m):
        if m[1] not in values: raise ValueError('Unknown marketing claim: '+m[1])
        value=values[m[1]]
        if locale=='tr' and m[1]=='jdk_tested': value=value.replace('runtime tested locally','çalışma zamanı yerel olarak test edildi')
        if locale=='tr' and m[1]=='intellij_builds': value=value.replace(' to ',' ile ')
        return escape(value, quote=True)
    return re.sub(r'\{\{CLAIM:([^}]+)\}\}', replace, text)


HOME_FAQ = [
    ('Which changes can I reload?',
     'Try changing the text returned by a method. For a new endpoint, Spring configuration or a generated SAP Commerce type, check the conditions in its guide.',
     'Hangi değişiklikleri yenileyebilirim?',
     'Bir metodun döndürdüğü metni değiştirmeyi deneyin. Yeni bir endpoint, Spring yapılandırması veya üretilen bir SAP Commerce tipi için ilgili rehberdeki koşullara bakın.'),
    ('Will I keep my application state?',
     'Changing a method body alone keeps existing object fields. If Spring recreates a bean as part of the edit, that bean starts with fresh state.',
     'Uygulamamın durumu korunur mu?',
     'Yalnızca bir metot gövdesini değiştirdiğinizde nesnelerin mevcut alanları korunur. Spring değişiklik sırasında bir bean’i yeniden oluşturursa o bean başlangıç durumuna döner.'),
    ('Is everything free?',
     'Yes. The agent, the IntelliJ plugin and all reload features are free and open source under Apache 2.0.',
     'Tüm özellikler ücretsiz mi?',
     'Evet. Agent, IntelliJ eklentisi ve tüm yenileme özellikleri Apache 2.0 lisansıyla ücretsiz ve açık kaynaklıdır.'),
    ('What happens when I save items.xml?',
     'Reclazz runs SAP Commerce code generation and reloads supported generated classes. If you add a database column, you still need to update the schema.',
     'items.xml dosyasını kaydedince ne olur?',
     'Reclazz, SAP Commerce kod üretimini çalıştırıp desteklenen sınıfları yeniler. Veritabanına sütun eklediyseniz şemayı da güncellemeniz gerekir.'),
    ('Can I use Gradle or Maven?',
     'Yes. Choose one way to attach the agent to each JVM. The quickstart covers IntelliJ, Gradle, Maven and the standalone agent.',
     'Gradle veya Maven kullanabilir miyim?',
     'Evet. Her JVM için agent’ı bağlamanın tek bir yolunu seçin. Hızlı başlangıç rehberinde IntelliJ, Gradle, Maven ve bağımsız agent seçenekleri var.'),
]


def marketing_faq(values):
    from html import unescape
    visible=[]; schema=[]
    for i,(q,a,_,_) in enumerate(HOME_FAQ):
        q=expand_claims(q,values);a=expand_claims(a,values)
        visible.append('<details class="marketing-faq"><summary data-i18n="faq.q'+str(i)+'">'+q+'</summary><p data-i18n="faq.a'+str(i)+'">'+a+'</p></details>')
        schema.append({'@type':'Question','name':unescape(q),'acceptedAnswer':{'@type':'Answer','text':unescape(a)}})
    return ''.join(visible), '<script type="application/ld+json">'+json.dumps({'@context':'https://schema.org','@type':'FAQPage','mainEntity':schema},ensure_ascii=False).replace('<','\\u003c')+'</script>'


HOME_CARD_COPY = {
    'method-body': ('Change a method',
        'Build the edited class and call the method again to see the new result.'),
    'added-method': ('Add a method',
        'Use your new method from a caller compiled with the change.'),
    'bean': ('Refresh a Spring bean',
        'Try a supported bean change in the running Spring application.'),
    'mvc': ('Update a web route',
        'Try the changed Spring MVC mapping with a new request.'),
    'boot-properties': ('Change a Boot setting',
        'Edit a supported property and check how your app responds.'),
    'items': ('Edit a Commerce type',
        'Save items.xml to regenerate and reload supported SAP Commerce classes.'),
}


def marketing_cards(records, renderer):
    by_id = {row['id']: row for row in records}
    cards = []
    for key in OVERVIEW_IDS:
        row = by_id[key]
        status = row['status']
        href = escape(renderer.resolve_link('README.md', row['source']), quote=True)
        title, body = HOME_CARD_COPY[key]
        cards.append(
            f'<article class="change-row" data-support-id="{key}" data-support-status="{status}">'
            f'<h3 data-i18n="card.{key}.title">{escape(title)}</h3>'
            f'<p data-i18n="card.{key}.body">{escape(body)}</p>'
            f'<span class="status status-{status}" data-i18n="card.{key}.status">{LABELS[status]}</span> '
            f'<a href="{href}" data-i18n="card.{key}.link">Details</a></article>')
    return ''.join(cards)


def compare_source_key(source):
    if 'key' in source: return source['key']
    if 'record' in source: return 'record:'+source['record']
    if 'doc' in source: return 'doc:'+source['doc']
    return ''


def comparison_table(repo, renderer, values, summary=False):
    model=json.loads((Path(repo)/'website/compare.json').read_text()); products=model['products']
    rows=[x for x in model['rows'] if not summary or x['id'] in model['home']]
    def translated(key,text):
        return (' data-i18n="'+key+'"' if summary else '')+'>'+escape(text)
    out='<div class="cmp-wrap" tabindex="0" role="region" aria-label="Hot reload comparison"><table class="cmp"><caption'+translated('cmp.caption','Compare the development workflow')+'</caption><thead><tr><th scope="col"'+translated('cmp.capability','Capability')+'</th>'
    out+=''.join('<th scope="col">'+escape(p)+'</th>' for p in products.values())+'</tr></thead><tbody>'
    for row in rows:
        out+='<tr data-compare-row="'+row['id']+'"><th scope="row"'+translated('cmp.'+row['id']+'.label',row['label'])+'</th>'
        for product,c in row['cells'].items():
            source=c['source'];href=''
            if 'key' in source: href=next(s['url'] for s in model['sources'].values() if s['key']==source['key'])
            elif 'record' in source: href='/docs/support-matrix/#'+source['record']
            elif 'doc' in source: href='/license/' if source['doc']=='README.md#license-and-support' else renderer.resolve_link('README.md',source['doc'])
            elif 'measured' in source:
                raise ValueError('Measured comparison sources are not allowed')
            key='cmp.'+row['id']+'.'+product
            text=expand_claims(c['text'],values)
            out+='<td data-product="'+product+'" data-verdict="'+c['verdict']+'"><span'+translated(key,text)+'</span>'
            if href: out+=' <a class="comparison-source" href="'+escape(href,quote=True)+'"'+translated('cmp.source','Source')+'</a>'
            out+='</td>'
        out+='</tr>'
    return out+'</tbody></table></div>'
