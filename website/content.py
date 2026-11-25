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
from website.markdown import slug

LABELS = {'works': 'Reloads', 'conditional': 'Reloads, with a condition', 'restart': 'Needs a restart',
          'unverified': 'Not verified yet', 'not-reloaded': 'No change applied'}
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
 ('Which changes can I reload?', 'Start with a method-body edit. Added methods, Spring beans, endpoints, properties and SAP Commerce generated classes have documented conditions. Check the reload matrix before a larger change.',
  'Hangi değişiklikleri yenileyebilirim?', 'Bir metot gövdesi değişikliğiyle başlayın. Eklenen metotların, Spring bean ve endpoint değişikliklerinin, property dosyalarının ve SAP Commerce sınıflarının belgelenmiş koşulları vardır. Daha büyük bir değişiklikten önce reload tablosuna bakın.'),
 ('Will my application state survive?', 'Method-body edits keep existing object fields on the JVM-only path. A refreshed Spring bean is destroyed and recreated, so its mutable state can reset.',
  'Uygulama durumum korunur mu?', 'Yalnız JVM yolundaki metot gövdesi değişiklikleri mevcut nesne alanlarını korur. Yenilenen Spring bean yok edilip yeniden oluşturulur; değişebilir durumu sıfırlanabilir.'),
 ('Do I need a special JVM?', 'Supported structural changes work on stock JDK {{CLAIM:agent_java_min}}+ through hot-compiled callers. Plain reflection cannot see added members on that path. JetBrains Runtime or DCEVM provides enhanced redefinition; a superclass change still needs a restart.',
  'Özel bir JVM gerekli mi?', 'Desteklenen yapısal değişiklikler standart JDK {{CLAIM:agent_java_min}}+ üzerinde yeniden derlenen çağıranlar üzerinden çalışır. Bu yolda sıradan reflection eklenen üyeleri göremez. JetBrains Runtime veya DCEVM gelişmiş yeniden tanımlama sağlar; üst sınıf değişikliği yine restart gerektirir.'),
 ('What happens after an items.xml save?', 'Reclazz runs platform generation and reloads the generated classes within the documented SAP Commerce scope. A new database column still needs a schema update. Follow the SAP Commerce guide for setup and opt-ins.',
  'items.xml kaydından sonra ne olur?', 'Reclazz belgelenmiş SAP Commerce kapsamı içinde platform kod üretimini çalıştırır ve üretilen sınıfları yeniler. Yeni bir veritabanı sütunu hâlâ şema güncellemesi gerektirir. Kurulum ve isteğe bağlı ayarlar için SAP Commerce rehberini izleyin.'),
 ('Can I use Gradle or Maven instead of IntelliJ?', 'Yes. Attach the agent through one launcher: IntelliJ, the Gradle plugin, Maven or a standalone Java command. Build your edit into a watched output and verify the changed application behaviour.',
  'IntelliJ yerine Gradle veya Maven kullanabilir miyim?', 'Evet. Agent için tek başlatma yolu seçin: IntelliJ, Gradle eklentisi, Maven veya bağımsız Java komutu. Değişikliği izlenen çıktıya derleyin ve uygulamanın değişen davranışını doğrulayın.'),
]


def marketing_faq(values):
    from html import unescape
    visible=[]; schema=[]
    for i,(q,a,_,_) in enumerate(HOME_FAQ):
        q=expand_claims(q,values);a=expand_claims(a,values)
        visible.append('<details class="marketing-faq"><summary data-i18n="faq.q'+str(i)+'">'+q+'</summary><p data-i18n="faq.a'+str(i)+'">'+a+'</p></details>')
        schema.append({'@type':'Question','name':unescape(q),'acceptedAnswer':{'@type':'Answer','text':unescape(a)}})
    return ''.join(visible), '<script type="application/ld+json">'+json.dumps({'@context':'https://schema.org','@type':'FAQPage','mainEntity':schema},ensure_ascii=False).replace('<','\\u003c')+'</script>'


def marketing_cards(records, renderer):
    by_id={x['id']:x for x in records}; result=[]
    for key in OVERVIEW_IDS:
        row=by_id[key];href=renderer.resolve_link('README.md',row['source'])
        result.append('<article class="feature-card" data-support-id="'+key+'" data-support-status="'+row['status']+'"><span class="status status-'+row['status']+'" data-i18n="card.'+key+'.status">'+LABELS[row['status']]+'</span><h3 data-i18n="card.'+key+'.title">'+escape(row['title'])+'</h3><p data-i18n="card.'+key+'.body">'+escape(row['conditions'])+'</p><a href="'+escape(href,quote=True)+'" data-i18n="card.'+key+'.link">Conditions and next step</a></article>')
    return ''.join(result)


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
