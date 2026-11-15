#!/usr/bin/env python3
"""Regressions for full Markdown preservation and single-source build failures."""
from pathlib import Path
import sys
sys.dont_write_bytecode = True
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import tempfile
import unittest
import json
import re
from unittest.mock import patch
from website import build, layout
from website.content import expand_guide, metadata, support_records, source_link_errors, readable_surface_errors
from website.markdown import MarkdownRenderer, reference_pages
from website.check import Document, output_differences, source_index_errors, publication_errors

class DocumentationBuildTest(unittest.TestCase):
    def setUp(self):
        tmp=tempfile.TemporaryDirectory(prefix='reclazz docs ');self.addCleanup(tmp.cleanup)
        self.root=Path(tmp.name);(self.root/'docs').mkdir()
        self.write('docs/index.md','# Source index\n\n[First](first.md)\n[Second](second.md)\n')
        self.write('docs/first.md','# First\n\n## Same\nOne\n\n## Same\nTwo\n')
        self.write('docs/second.md','# Second\n\n## Destination\nText.\n')
        self.disposition({p.relative_to(self.root).as_posix():{'publish':'/docs/reference/'+('' if p.stem=='index' else p.stem+'/')} for p in (self.root/'docs').glob('*.md')})
    def write(self,path,text):
        p=self.root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    def renderer(self):return MarkdownRenderer(self.root,reference_pages(self.root))
    def disposition(self, entries):
        self.write('docs/site/manifest.json',json.dumps({'sources':entries}))
    def test_unclassified_source_is_rejected(self):
        self.disposition({'docs/index.md':{'publish':'/docs/reference/'}})
        with self.assertRaisesRegex(ValueError,'Unclassified source'):reference_pages(self.root)
    def test_external_source_is_not_published(self):
        self.disposition({
            'docs/index.md':{'publish':'/docs/reference/'},
            'docs/first.md':{'publish':'/docs/reference/first/'},
            'docs/second.md':{'external':'docs/second.md'}})
        self.assertNotIn('docs/second.md',{p['markdown'] for p in reference_pages(self.root)})
        result=self.renderer().render_inline('docs/first.md','[Read](second.md#destination)')
        self.assertIn('https://github.com/onurkat/reclazz/blob/main/docs/second.md#destination',result)
    def test_external_bad_fragment_is_rejected(self):
        self.disposition({
            'docs/index.md':{'publish':'/docs/reference/'},
            'docs/first.md':{'publish':'/docs/reference/first/'},
            'docs/second.md':{'external':'docs/second.md'}})
        with self.assertRaisesRegex(ValueError,'Missing external source anchor'):
            self.renderer().render_inline('docs/first.md','[Read](second.md#absent)')
    def test_complete_reference_preserves_duplicate_heading_anchors(self):
        rendered=self.renderer().render('docs/first.md')
        self.assertIn('id="same"',rendered);self.assertIn('id="same-1"',rendered)
        self.assertIn('One',rendered);self.assertIn('Two',rendered)
    def test_fences_escape_code_and_do_not_create_fake_headings(self):
        self.write('docs/first.md','# First\n\n```html\n# Not a heading\n<script>unsafe()</script>\n```\n')
        result=self.renderer().render('docs/first.md')
        self.assertIn('&lt;script&gt;unsafe()&lt;/script&gt;',result)
        self.assertNotIn('<script>',result);self.assertNotIn('id="not-a-heading"',result)
        self.assertIn('class="code-block"',result)
    def test_generic_types_and_placeholders_render_as_text_not_html(self):
        self.write('docs/first.md','# First\n\nRoute <route>, type <String> and header <header> stay visible.\n')
        rendered=self.renderer().render('docs/first.md')
        self.assertIn('&lt;header&gt;',rendered)
        self.assertIn('&lt;String&gt;',rendered)
        self.assertNotIn('<header>',rendered)
    def test_explicit_legacy_empty_anchors_remain_linkable(self):
        self.write('docs/first.md','# First\n\n<a id="legacy-section"></a>\n\n## Current title\n')
        rendered=self.renderer().render('docs/first.md')
        self.assertIn('legacy-section',Document(rendered).ids)
    def test_tables_keep_escaped_pipes_in_inline_code(self):
        self.write('docs/first.md','# First\n\n| A | B |\n|---|---|\n| `a\\|b` | value |\n')
        result=self.renderer().render('docs/first.md')
        self.assertIn('<code>a|b</code>',result);self.assertEqual(2,result.count('<td>'))
        self.assertIn('class="table-scroll"',result)
    def test_relative_markdown_links_become_local_reference_routes(self):
        self.write('docs/first.md','# First\n\n[Next](second.md#destination)\n')
        self.assertIn('href="/docs/reference/second/#destination"',self.renderer().render('docs/first.md'))
    def test_source_code_links_keep_the_selected_revision(self):
        self.write('src/Main.java','class Main {}')
        self.write('docs/first.md','# First\n\n[Code](../src/Main.java)\n')
        renderer=MarkdownRenderer(self.root,reference_pages(self.root),'abc123')
        self.assertIn('/blob/abc123/src/Main.java',renderer.render('docs/first.md'))
    def test_source_directory_links_use_a_repository_tree_url(self):
        (self.root/'scripts/probes').mkdir(parents=True)
        self.write('docs/first.md','# First\n\n[Probe](../scripts/probes/)\n')
        self.assertIn('/tree/main/scripts/probes',self.renderer().render('docs/first.md'))
    def test_json_evidence_download_is_copied_without_changing_bytes(self):
        self.write('docs/feature-evidence/proof.json','{"verdict":"UNVERIFIED"}\n')
        self.write('docs/first.md','# First\n\n[Record](feature-evidence/proof.json)\n')
        renderer=self.renderer();result=renderer.render('docs/first.md')
        self.assertIn('/docs/source-assets/docs/feature-evidence/proof.json',result)
        self.assertEqual(b'{"verdict":"UNVERIFIED"}\n',renderer.assets['docs/source-assets/docs/feature-evidence/proof.json'])
    def test_missing_link_is_rejected_instead_of_silently_published(self):
        self.write('docs/first.md','# First\n\n[Missing](missing.md)\n')
        with self.assertRaisesRegex(ValueError,'Missing repository link'):self.renderer().render('docs/first.md')
    def test_links_cannot_escape_the_repository(self):
        self.write('docs/first.md','# First\n\n[Escape](../../private.md)\n')
        with self.assertRaisesRegex(ValueError,'Escaping'):self.renderer().render('docs/first.md')
    def test_new_source_must_be_added_to_the_canonical_index(self):
        self.assertEqual([],source_index_errors(self.root))
        self.write('docs/forgotten.md','# Forgotten\n')
        self.assertEqual(['Unindexed source document: forgotten.md'],source_index_errors(self.root))
        with self.assertRaisesRegex(ValueError,'Unclassified source'): reference_pages(self.root)
        data=json.loads((self.root/'docs/site/manifest.json').read_text())
        data['sources']['docs/forgotten.md']={'publish':'/docs/reference/forgotten/'}
        self.write('docs/site/manifest.json',json.dumps(data))
        self.assertIn('docs/forgotten.md',{p['markdown'] for p in reference_pages(self.root)})
    def test_one_source_edit_changes_rendered_output_and_stale_output_fails(self):
        renderer=self.renderer();before=renderer.render('docs/first.md')
        output=self.root/'output';output.mkdir();(output/'first.html').write_text(before)
        self.assertEqual([],output_differences(output,{'first.html':before}))
        self.write('docs/first.md','# First\n\n## Same\nChanged live reference text.\n')
        after=renderer.render('docs/first.md');self.assertNotEqual(before,after)
        self.assertEqual(['Stale or missing output: first.html'],output_differences(output,{'first.html':after}))
    def test_missing_and_obsolete_output_fail_the_check(self):
        output=self.root/'output';output.mkdir();(output/'retired.html').write_text('old')
        self.assertEqual(['Stale or missing output: current.html','Obsolete output: retired.html'],output_differences(output,{'current.html':'current'}))
    def test_the_reference_library_has_a_stable_root_route(self):
        index=next(p for p in reference_pages(self.root) if p['markdown']=='docs/index.md')
        self.assertEqual('reference',index['slug'])

    def site_fixture(self):
        self.write("SECURITY.md", "# Security\n")
        self.write("requirements-docs.txt", "# Fixture\n")
        self.write('README.md','# Reclazz\n\nCanonical introduction.\n\n## Why Reclazz?\n')
        self.write('gradle.properties','pluginVersion=9.8.7\n')
        self.write('docs/usage.md','# Usage\n\n| Argument | Default | Description |\n|---|---|---|\n| `verbose` | `false` | Logging |\n')
        page=lambda slug: dict(slug=slug,title=slug or 'Overview',description='Guide',
            group='Start here',eyebrow='Guide',nav_title=slug or 'Overview',sources=['docs/first.md'])
        self.write('docs/site/manifest.json',json.dumps(dict(source_commit='a'*40,
            reviewed='2026-10-19',pages=[page(''),page('support-matrix'),page('quickstart')])))
        self.write('docs/site/overview.html','<h1>Overview</h1><p>{{VERSION}} · {{REVIEWED_DATE}}</p>')
        self.write('docs/site/quickstart.html','<h2 id="setup">Setup</h2>{{INCLUDE docs/first.md#same}}')
        self.write('docs/site/support-matrix.html','{{MATRIX}}')
        self.write('docs/reload-support.md','# Reload support reference\n\n'
            '| ID | Area | Change | Outcome | Conditions | Next action | Reference |\n'
            '|---|---|---|---|---|---|---|\n'
            '| example | Java | Synthetic edit | conditional | Watched class only | Compile and verify | [Scope](first.md#same) |\n')
        for asset in ('docs.css','docs.js','theme.js'):
            self.write('website/static/docs/assets/'+asset,'/* fixture */')
        self.write('website/static/sitemap.xml','<urlset></urlset>')
        for asset in ('site.css','site.js','marketing.css'):
            self.write('website/static/assets/'+asset,'/* fixture */')
        p=self.root/'docs/site/manifest.json';data=json.loads(p.read_text())
        data['sources']={p.relative_to(self.root).as_posix():{'publish':'/docs/reference/'+('' if p.stem=='index' else p.stem+'/')} for p in (self.root/'docs').glob('*.md')}
        p.write_text(json.dumps(data))
        self.write('website/routes.json','{}')
        self.write('website/legacy-routes.txt','/docs/')
    def generate(self):
        with patch.object(build,'REPO',self.root),patch.object(build,'SOURCE',self.root/'docs/site'),patch.object(build,'ROOT',self.root/'website/static'):
            return build.generate('fixture-revision')
    def test_canonical_setup_edit_updates_guide_and_reference(self):
        self.site_fixture();before=self.generate()
        self.write('docs/first.md','# First\n\n## Same\nNew setup command.\n')
        after=self.generate()
        for route in ('docs/quickstart/index.html','docs/reference/first/index.html'):
            self.assertNotEqual(before[route],after[route]);self.assertIn('New setup command.',after[route])
    def test_missing_excerpt_heading_is_rejected(self):
        self.site_fixture()
        self.write('docs/site/quickstart.html','{{INCLUDE docs/first.md#missing}}')
        with self.assertRaisesRegex(ValueError,'Missing.*section'):self.generate()
    def test_excerpt_cannot_escape_source_tree(self):
        with self.assertRaisesRegex(ValueError,'Escaping'):
            self.renderer().render('../private.md',section='missing')
    def test_support_edit_updates_interactive_and_readable_reference(self):
        self.site_fixture();before=self.generate()
        p=self.root/'docs/reload-support.md';p.write_text(p.read_text().replace('Watched class only','New bounded condition'))
        after=self.generate()
        for route in ('docs/support-matrix/index.html','docs/reference/reload-support/index.html'):
            self.assertNotEqual(before[route],after[route]);self.assertIn('New bounded condition',after[route])
        self.assertEqual('New bounded condition',json.loads(after['docs/assets/support.json'])[0]['conditions'])
    def test_support_unknown_outcome_and_duplicate_id_are_rejected(self):
        self.site_fixture();p=self.root/'docs/reload-support.md';original=p.read_text()
        p.write_text(original.replace('| conditional |','| certified |'))
        with self.assertRaisesRegex(ValueError,'Unknown support outcome'):support_records(self.root)
        p.write_text(original+original.splitlines()[-1]+'\n')
        with self.assertRaisesRegex(ValueError,'duplicate support ID'):support_records(self.root)
    def test_reading_links_stay_local_and_code_links_keep_revision(self):
        self.site_fixture();self.write('src/Main.java','class Main {}')
        info=metadata(self.root)
        text=expand_guide('<a href="{{REF docs/second.md#destination}}">Read</a><a href="{{REF src/Main.java}}">Code</a>',self.renderer(),info)
        self.assertIn('href="/docs/reference/second/#destination"',text)
        self.assertIn('/blob/main/src/Main.java',text)
        with self.assertRaisesRegex(ValueError,'Missing repository link'):
            expand_guide('{{REF docs/absent.md}}',self.renderer(),info)
    def test_inline_fragment_links_keep_the_canonical_document(self):
        result=self.renderer().render_inline("docs/first.md", "[Scope](#same)")
        self.assertIn("/docs/reference/first/#same",result)
    def test_source_reading_rejects_retired_readme_anchor(self):
        self.write('README.md','# Reclazz\n')
        self.write('SECURITY.md','# Security\n')
        self.write('docs/first.md','# First\n\n[Old](../README.md#retired)\n')
        self.assertIn('missing source anchor',source_link_errors(self.root)[0])
    def test_metadata_and_ai_index_follow_canonical_inputs(self):
        self.site_fixture();before=self.generate()
        self.write('gradle.properties','pluginVersion=9.8.8\n')
        self.write('README.md','# Reclazz\n\nUpdated introduction.\n\n## Why Reclazz?\n')
        after=self.generate()
        self.assertIn('9.8.8',after['docs/index.html']);self.assertNotIn('9.8.7',after['docs/index.html'])
        self.assertIn('Docs for</span> 9.8.8',after['docs/index.html'])
        self.assertIn('Updated introduction.',after['llms.txt']);self.assertNotIn('Canonical introduction.',after['llms.txt'])
        self.assertIn('9.8.8',after['llms.txt'])
    def test_search_targets_sections_without_repeating_evidence_registry(self):
        self.site_fixture();self.write('docs/feature-evidence.md','# Evidence\n\n## Proof\n'+'LONG_REGISTRY '*20000)
        p=self.root/'docs/site/manifest.json';data=json.loads(p.read_text());data['sources']['docs/feature-evidence.md']={'external':'docs/feature-evidence.md'};p.write_text(json.dumps(data))
        from website.markdown import github_anchor_map
        self.write('website/routes.json',json.dumps({'/docs/reference/feature-evidence/':{'destination':'https://github.com/onurkat/reclazz/blob/{revision}/docs/feature-evidence.md','source':'docs/feature-evidence.md','fragments':github_anchor_map(self.root/'docs/feature-evidence.md')}}))
        output=self.generate();index=json.loads(output['docs/assets/search-index.json'])
        self.assertTrue(any(x['url']=='/docs/reference/second/#destination' for x in index))
        evidence=[x for x in index if x['url'].startswith('/docs/reference/feature-evidence/')]
        self.assertEqual([],evidence)
        self.assertNotIn('LONG_REGISTRY',json.dumps(index))

    def test_authored_github_reading_link_cannot_bypass_canonical_routes(self):
        self.site_fixture()
        with self.assertRaisesRegex(ValueError,'canonical REF'):
            expand_guide('<a href="https://github.com/onurkat/reclazz/blob/main/docs/first.md#same">Read</a>',self.renderer(),metadata(self.root))
    def test_duplicate_authored_version_is_rejected(self):
        self.site_fixture();p=self.root/'docs/site/manifest.json';data=json.loads(p.read_text())
        data['source_version']='stale';p.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError,'gradle.properties'):metadata(self.root)
    def test_missing_external_file_and_double_disposition_are_rejected(self):
        data=json.loads((self.root/'docs/site/manifest.json').read_text())
        data['sources']['docs/missing.md']={'external':'docs/missing.md'}
        self.disposition(data['sources'])
        with self.assertRaisesRegex(ValueError,'Missing disposition source'):reference_pages(self.root)
        del data['sources']['docs/missing.md']
        data['sources']['docs/second.md']['external']='docs/second.md'
        self.disposition(data['sources'])
        with self.assertRaisesRegex(ValueError,'exactly one disposition'):reference_pages(self.root)
    def test_missing_legacy_route_and_redirect_chains_are_rejected(self):
        self.site_fixture()
        self.write('website/legacy-routes.txt','/retired/')
        with self.assertRaisesRegex(ValueError,'Legacy route without'):self.generate()
        self.write('website/legacy-routes.txt','')
        self.write('website/routes.json',json.dumps({'/old/':{'destination':'/other/'},'/other/':{'destination':'/docs/'}}))
        with self.assertRaisesRegex(ValueError,'Redirect loop, chain'):self.generate()
        self.write('website/routes.json',json.dumps({'/old/':{'destination':'/old/'}}))
        with self.assertRaisesRegex(ValueError,'Redirect loop, chain'):self.generate()
    def external_fixture(self):
        self.public_fixture()
        from website.markdown import github_anchor_map
        p=self.root/'docs/site/manifest.json';data=json.loads(p.read_text())
        data['sources']['docs/second.md']={'external':'docs/second.md'};p.write_text(json.dumps(data))
        self.write('website/routes.json',json.dumps({'/docs/reference/second/':{
            'source':'docs/second.md','destination':'https://github.com/onurkat/reclazz/blob/{revision}/docs/second.md',
            'fragments':github_anchor_map(self.root/'docs/second.md')}}))
    def test_removed_clean_route_and_html_alias_go_directly_to_github(self):
        self.external_fixture();output=self.generate()
        a=self.markup(output['docs/reference/second/index.html'])
        b=self.markup(output['docs/reference/second.html'])
        self.assertEqual(a,b)
        self.assertIn('/blob/fixture-revision/docs/second.md',a)
        self.assertIn('"destination": "destination"',a)
        self.assertIn('target.search = location.search',a)
        self.assertIn('data.fragments[fragment]',a)
        self.assertIn('location.replace(target.href)',a)
        self.assertIn('http-equiv="refresh"',a)
        self.assertIn('name="robots" content="noindex"',a)
        self.assertNotIn('/docs/reference/second/',output['sitemap.xml'])
        self.assertNotIn('/docs/reference/second/',output['docs/assets/search-index.json'])
        self.assertIn('<h1>Retained content</h1>',self.markup(output['index.html']))
        record=json.loads(output['docs/assets/build-manifest.json'])['documents']['docs/second.md']
        self.assertIn('sha256',record);self.assertEqual('docs/second.md',record['external'])
    def test_missing_moved_fragment_mapping_is_rejected(self):
        self.external_fixture()
        p=self.root/'website/routes.json';data=json.loads(p.read_text())
        del data['/docs/reference/second/']['fragments']['destination'];p.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError,'Moved fragment missing'):self.generate()
    def delivered_fixture(self):
        self.external_fixture();generated=self.generate();folder=self.root/'delivered'
        for name,value in generated.items():
            p=folder/name;p.parent.mkdir(parents=True,exist_ok=True)
            p.write_bytes(value if isinstance(value,bytes) else value.encode())
        return folder,json.loads(generated['docs/assets/build-manifest.json'])
    def delivered_errors(self,folder,manifest):
        with patch.object(build,'REPO',self.root):return publication_errors(folder,manifest)
    def test_delivered_internal_html_link_and_missing_stub_are_rejected(self):
        folder,manifest=self.delivered_fixture()
        self.assertEqual([],self.delivered_errors(folder,manifest))
        p=folder/'docs/quickstart/index.html';p.write_text(p.read_text()+'<a href="/privacy.html?lang=en#policy">Policy</a>')
        self.assertTrue(any('Internal HTML link' in e for e in self.delivered_errors(folder,manifest)))
        (folder/'docs/quickstart.html').unlink()
        self.assertIn('Missing redirect stub: /docs/quickstart.html',self.delivered_errors(folder,manifest))
    def test_stale_external_digest_and_unpublished_search_are_rejected(self):
        folder,manifest=self.delivered_fixture()
        manifest['documents']['docs/second.md']['sha256']='0'*64
        p=folder/'docs/assets/search-index.json';data=json.loads(p.read_text());data.append({'url':'/docs/reference/second/#destination'})
        p.write_text(json.dumps(data))
        errors=self.delivered_errors(folder,manifest)
        self.assertIn('Wrong canonical source digest: docs/second.md',errors)
        self.assertIn('Search points to unpublished page: /docs/reference/second/#destination',errors)
    def test_external_github_anchors_keep_duplicates_and_inline_formatting(self):
        from website.markdown import github_anchor_map
        self.write('docs/second.md',"# Second\n\n## This'll be a _Helpful_ Section About the Greek Letter Θ!\n\n## Same\n\n## Same\n\n## Same-1\n")
        anchors=github_anchor_map(self.root/'docs/second.md')
        self.assertIn('thisll-be-a-helpful-section-about-the-greek-letter-θ',anchors.values())
        self.assertEqual({'same','same-1','same-1-1'},{value for value in anchors.values() if value.startswith('same')})
    def test_footer_only_page_is_not_in_sidebar_or_previous_next_navigation(self):
        self.site_fixture()
        p=self.root/'docs/site/manifest.json';data=json.loads(p.read_text())
        data['pages'].append(dict(slug='evidence',title='Evidence',description='Evidence',group='Footer',eyebrow='Evidence',nav_title='Evidence',sources=['docs/first.md']))
        p.write_text(json.dumps(data));self.write('docs/site/evidence.html','<h2 id="proof">Proof</h2>')
        for name,html in self.generate().items():
            if not name.startswith('docs/') or not name.endswith('/index.html') or name=='docs/evidence/index.html':continue
            for nav in re.findall(r'<nav[^>]*(?:aria-label="Documentation"|aria-label="Mobile documentation"|class="page-pager")[^>]*>(.*?)</nav>',html,re.S):
                self.assertNotIn('href="/docs/evidence/"',nav,name)
    def test_internal_links_do_not_show_external_arrows(self):
        self.public_fixture();output=self.generate()
        html=output['docs/index.html']
        for match in re.finditer(r'<a\b[^>]*href="(/[^"]*)"[^>]*>(.*?)</a>',html,re.S):
            self.assertNotIn('↗',match[2])
        self.assertRegex(output['docs/reference/first/index.html'],r'Edit on GitHub <!-- @license lucide-static[^<]*-->\s*<svg class="icon ')
        self.assertNotIn('Sources &amp; version scope',html)
        self.assertIn('Docs for</span> 9.8.7',html)

    def test_small_text_and_primary_button_colors_keep_minimum_contrast(self):
        css=(Path(__file__).resolve().parents[1]/'website/static/assets/tokens.css').read_text()
        def luminance(value):
            channels=[int(value[i:i+2],16)/255 for i in (0,2,4)]
            linear=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in channels]
            return sum(v*w for v,w in zip(linear,[.2126,.7152,.0722]))
        def ratio(a,b):
            low,high=sorted([luminance(a),luminance(b)]);return (high+.05)/(low+.05)
        from website.check import contrast_errors
        self.assertEqual([],contrast_errors(css))

    def public_fixture(self):
        self.site_fixture()
        for path in ('index.html','compare/index.html','privacy/index.html','license/index.html'):
            self.write('website/static/'+path,'<!doctype html><html lang="en"><head><title>Public page</title>{{SITE_HEAD}}</head><body>{{SITE_HEADER}}<main id="main"><h1>Retained content</h1></main>{{SITE_FOOTER}}{{SITE_UTILITIES}}</body></html>')
    @staticmethod
    def markup(value):return value.decode() if isinstance(value,bytes) else value
    def test_public_and_docs_pages_have_one_shared_navigation_and_footer(self):
        self.public_fixture();output=self.generate()
        for name in ('index.html','compare/index.html','privacy/index.html','license/index.html','docs/index.html'):
            html=self.markup(output[name])
            self.assertEqual(1,len(re.findall(r'<header\b',html)),name)
            self.assertEqual(1,len(re.findall(r'<footer\b',html)),name)
            self.assertEqual(1,len(re.findall(r'<main\b',html)),name)
            self.assertIn('aria-label="Main navigation"',html)
            self.assertIn('id="search-dialog"',html)
            self.assertIn('id="theme-toggle"',html)
            self.assertNotIn('{{SITE_',html)
    def test_shared_footer_change_updates_public_and_docs_output(self):
        self.public_fixture();before=self.generate()
        with patch.object(layout,'footer',return_value='<footer>Changed common footer</footer>'):
            after=self.generate()
        for name in ('index.html','compare/index.html','privacy/index.html','license/index.html','docs/index.html'):
            self.assertNotEqual(before[name],after[name]);self.assertIn('Changed common footer',self.markup(after[name]))
    def test_public_page_cannot_bypass_shared_shell(self):
        self.public_fixture();p=self.root/'website/static/privacy/index.html'
        p.write_text(p.read_text().replace('{{SITE_FOOTER}}','<footer>Private copy</footer>'))
        with self.assertRaisesRegex(ValueError,'SITE_FOOTER'):self.generate()
    def test_public_locale_and_active_navigation_match_page_context(self):
        self.public_fixture();output=self.generate()
        self.assertIn('id="langToggle"',self.markup(output['index.html']))
        for name in ('compare/index.html','privacy/index.html','license/index.html','docs/index.html'):
            self.assertNotIn('id="langToggle"',self.markup(output[name]))
        self.assertIn('<a href="/compare/" aria-current="page">',self.markup(output['compare/index.html']))
    def test_native_license_disclosure_retains_the_complete_text(self):
        source=(Path(__file__).resolve().parents[1]/'website/static/license/index.html').read_text()
        self.assertRegex(source,r'<details class="license-text"')
        self.assertRegex(source,r'<summary[^>]*>Full License Text')
        text=re.search(r'<pre>(.*?)</pre>',source,re.S)[1]
        for number in range(1,10):self.assertRegex(text,r'\b'+str(number)+r'\. ')
        self.assertIn('END OF TERMS AND CONDITIONS',text)
        self.assertIn('Copyright 2024-2026 Onur Kat and Reclazz Contributors',text)

    def test_stale_human_readable_evidence_digest_is_rejected(self):
        self.write('docs/feature-evidence.md','**Shipped surfaces:** `docs/first.md:1` (SHA-256 `'+('0'*64)+'`)\n')
        self.assertEqual(['Human-readable evidence digest drift: docs/first.md:1'],readable_surface_errors(self.root))


class DesignGateTest(unittest.TestCase):
    def test_automatic_requests_cover_script_image_css_and_preconnect(self):
        from website.check import automatic_request_errors
        cases=[('js',"fetch('https://api.example.com')"),('html','<img src="https://example.com/a.svg">'),('css','a{background:url(https://example.com/a.png)}'),('html','<link rel="preconnect" href="https://fonts.googleapis.com">')]
        for kind,text in cases:
            with self.subTest(kind=kind,text=text):self.assertTrue(automatic_request_errors(text,kind))
        self.assertEqual([],automatic_request_errors('<a href="https://example.com">External navigation</a>'))
    def test_total_scripts_and_styles_are_budgeted_per_page(self):
        from website.check import page_quality_errors
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for ext,size in [('js',23000),('css',36000)]:
                for name in ['a','b']:(root/(name+'.'+ext)).write_text(' '*size)
            p=root/'index.html';p.write_text('<script src="a.js"></script><script src="b.js"></script><link rel="stylesheet" href="a.css"><link rel="stylesheet" href="b.css">')
            errors=page_quality_errors(root,p)
            self.assertTrue(any('JS budget' in e for e in errors));self.assertTrue(any('CSS budget' in e for e in errors))
            (root/'b.js').write_text('');(root/'b.css').write_text('')
            self.assertEqual([],page_quality_errors(root,p))
    def test_404_is_checked_and_relative_assets_rejected(self):
        from website.check import page_quality_errors
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);p=root/'404.html';p.write_text('<script src="assets/x.js"></script>')
            errors=page_quality_errors(root,p)
            self.assertIn('404 shared shell missing',errors)
            self.assertIn('404 relative asset/link: assets/x.js',errors)
    def test_heading_skip_is_rejected_at_every_level(self):
        from website.check import page_quality_errors
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);p=root/'index.html'
            for level in (3,4,5,6):
                p.write_text('<h1>Title</h1><h'+str(level)+'>Skipped</h'+str(level)+'>')
                self.assertTrue(any('Heading level skipped' in e for e in page_quality_errors(root,p)),level)
    def test_scroll_wrappers_require_keyboard_access_in_delivered_html(self):
        from website.check import page_quality_errors
        from website.layout import accessible_scroll_regions
        cases=['<pre><code>long code</code></pre>', '<div class="table-scroll"><table><caption>Results</caption><tr><td>Value</td></tr></table></div>', '<div class="terminal-body">Example</div>', '<div class="comparison-wrapper"><table></table></div>', '<aside class="sidebar"><nav><a href="/">Home</a></nav></aside>']
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);p=root/'index.html'
            for markup in cases:
                with self.subTest(markup=markup):
                    p.write_text(markup)
                    self.assertTrue(any('Scrollable region' in e for e in page_quality_errors(root,p)))
                    fixed=accessible_scroll_regions(markup);p.write_text(fixed)
                    self.assertEqual([],page_quality_errors(root,p))
                    for attribute in ['tabindex="0"','role="region"','aria-label=']:
                        p.write_text(fixed.replace(attribute, 'data-removed=' if attribute.endswith('=') else '',1))
                        self.assertTrue(any('Scrollable region' in e for e in page_quality_errors(root,p)),attribute)
    def test_mobile_menu_is_built_before_progressive_enhancement(self):
        output=build.generate()
        page=output['docs/quickstart/index.html']
        self.assertIn('class="docs-menu-bar"',page)
        self.assertIn('class="drawer-open" aria-controls="docs-drawer"',page)
        self.assertIn('<dialog class="drawer-dialog" id="docs-drawer"',page)
        self.assertIn('class="mobile-toc"',page)
        self.assertIn('class="mobile-nav"',page)
    def test_low_contrast_mutation_is_rejected(self):
        from website.check import contrast_errors
        css=(build.ROOT/'assets/tokens.css').read_text()
        self.assertTrue(contrast_errors(css.replace('--muted:#b8c3d4','--muted:#1d2635')))
    def test_preview_nested_missing_route_serves_404_with_working_assets(self):
        from http.server import SimpleHTTPRequestHandler,ThreadingHTTPServer
        from threading import Thread
        from urllib.request import urlopen
        from urllib.error import HTTPError
        from website.check import AssetDocument
        output=build.generate()
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for name,value in output.items():
                p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(value if isinstance(value,bytes) else value.encode())
            class Handler(SimpleHTTPRequestHandler):
                def __init__(self,*args,**kw):super().__init__(*args,directory=str(root),**kw)
                def log_message(self,*args):pass
                def send_error(self,code,message=None,explain=None):
                    if code!=404:return super().send_error(code,message,explain)
                    self.send_response(404);self.send_header('Content-Type','text/html');self.end_headers();self.wfile.write((root/'404.html').read_bytes())
            server=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=Thread(target=server.serve_forever,daemon=True);thread.start()
            try:
                base='http://127.0.0.1:'+str(server.server_port)
                with self.assertRaises(HTTPError) as failure:urlopen(base+'/docs/reference/does/not/exist/')
                self.assertEqual(404,failure.exception.code);text=failure.exception.read().decode()
                self.assertIn('We couldn’t find that page.',text)
                self.assertIn('id="search-dialog"',text)
                for asset in AssetDocument(text).requests:
                    if asset.startswith('/'):
                        with urlopen(base+asset) as response:self.assertEqual(200,response.status,asset)
            finally:server.shutdown();server.server_close();thread.join()


class Stage3ContractTest(unittest.TestCase):
    def setUp(self):
        from website.content import support_records
        self.records=support_records(build.REPO)
        class Renderer:
            def resolve_link(self,*args):return '/docs/reference/'
        self.renderer=Renderer()
    def matrix(self,records=None):return build.support_matrix(self.records if records is None else records,self.renderer)
    def test_T1_every_row_and_note_has_the_source_badge(self):
        from website.check import rendered_records_errors
        html=self.matrix();self.assertEqual([],rendered_records_errors(html,self.records))
        for status in ('works','conditional','restart','unverified','not-reloaded'):
            bad=html.replace('class="badge '+status+'"','class="badge wrong"',1)
            self.assertTrue(any('badge mismatch' in e for e in rendered_records_errors(bad,self.records)),status)
        self.assertTrue(any('badge mismatch' in e for e in rendered_records_errors(html.replace('Reloads</span>','Incorrect</span>',1),self.records)))
    def test_T2_note_omission_duplicate_and_misclassification_fail(self):
        from website.check import rendered_records_errors
        html=self.matrix()
        self.assertEqual({'jdk-new','condition-recheck','serializer','messaging-advanced','unknown-files'},{r['id'] for r in self.records if r['presentation']=='note'})
        from website.check import stage3_contract_errors
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);assets=root/'docs/assets';assets.mkdir(parents=True)
            download=self.records;search=json.loads((build.REPO/'build/site/docs/assets/search-index.json').read_text())
            (assets/'support.json').write_text(json.dumps(download));(assets/'search-index.json').write_text(json.dumps(search))
            self.assertNotIn('Support download coverage/content drift',stage3_contract_errors(build.REPO,root))
            (assets/'support.json').write_text(json.dumps([r for r in download if r['id']!='jdk-new']))
            self.assertIn('Support download coverage/content drift',stage3_contract_errors(build.REPO,root))
            (assets/'search-index.json').write_text(json.dumps([r for r in search if r['url']!='/docs/support-matrix/#jdk-new']))
            self.assertIn('Support search coverage mismatch: jdk-new',stage3_contract_errors(build.REPO,root))
        omitted=re.sub(r'<div class="callout" id="jdk-new".*?</div>','',html,count=1,flags=re.S)
        self.assertNotEqual(html,omitted)
        self.assertTrue(any('coverage mismatch' in e for e in rendered_records_errors(omitted,self.records)))
        duplicate=re.search(r'<tr id="method-body".*?</tr>',html,re.S)[0]
        self.assertTrue(any('coverage mismatch' in e for e in rendered_records_errors(html+duplicate,self.records)))
        bad=html.replace('data-presentation="note"','data-presentation="row"',1)
        self.assertTrue(any('presentation/status mismatch' in e for e in rendered_records_errors(bad,self.records)))
    def test_T3_status_lock_rejects_a_source_change_with_matching_badge(self):
        from website.check import stage3_contract_errors,rendered_records_errors
        import copy
        records=copy.deepcopy(self.records);records[0]['status']='unverified'
        self.assertEqual([],rendered_records_errors(self.matrix(records),records))
        with patch('website.content.support_records',return_value=records):
            self.assertIn('Support status lock drift',stage3_contract_errors(build.REPO,build.REPO/'build/site'))
    def test_T4_overview_is_derived_and_badge_mutation_fails(self):
        from website.check import rendered_records_errors
        import copy
        html=build.support_summary(self.records,self.renderer)
        self.assertEqual([],rendered_records_errors(html,self.records,True))
        self.assertTrue(rendered_records_errors(html.replace('class="badge works"','class="badge restart"',1),self.records,True))
        records=copy.deepcopy(self.records);records[0]['conditions']='A changed canonical condition.'
        updated=build.support_summary(records,self.renderer)
        self.assertIn('A changed canonical condition.',updated)
        self.assertTrue(any('conditions drift' in e for e in rendered_records_errors(updated,self.records,True)))
    def test_T5_missing_frozen_fragment_fails(self):
        from website.check import stage3_contract_errors
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);target=root/'docs/quickstart/index.html';target.parent.mkdir(parents=True)
            original=(build.REPO/'build/site/docs/quickstart/index.html').read_text()
            self.assertIn('id="optional-command-line-plugin-installation"',original)
            target.write_text(original.replace('id="optional-command-line-plugin-installation"','id="removed-legacy-anchor"'))
            self.assertIn('Missing frozen fragment: docs/quickstart/index.html#optional-command-line-plugin-installation',stage3_contract_errors(build.REPO,root))
    def test_T6_panels_remain_available_without_javascript(self):
        from website.check import quickstart_panel_errors
        html=''.join('<section role="tabpanel" id="panel-'+str(i)+'"><h3>Route</h3><p>Steps</p></section>' for i in range(5))
        self.assertEqual([],quickstart_panel_errors(html))
        for bad in [html.replace('<section','<section hidden',1),html.replace('id="panel-0"','',1),html.replace('<h3>Route</h3>','',1),html.replace('role="tabpanel"','role="group"',1)]:self.assertTrue(quickstart_panel_errors(bad))
    def test_T7_em_dash_in_prose_fails_but_code_is_preserved(self):
        from website.check import rewritten_prose_errors
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);p=root/'docs/site/quickstart.html';p.parent.mkdir(parents=True)
            p.write_text('<p>A sentence.</p><pre><code>code — preserved</code></pre>')
            self.assertEqual([],rewritten_prose_errors(root))
            p.write_text('<p>A sentence — in prose.</p>')
            self.assertTrue(rewritten_prose_errors(root))
            p.write_text('<p>No dash.</p>');q=root/'docs/reload-support.md'
            q.write_text('| ID | Area | Change | Outcome | Conditions | Next action | Reference |\n|---|---|---|---|---|---|---|\n| x | Java | Edit | works | `code — kept` | Check | [Scope](usage.md) |\n')
            self.assertEqual([],rewritten_prose_errors(root))
            q.write_text(q.read_text().replace('`code — kept`','prose — rejected'))
            self.assertTrue(rewritten_prose_errors(root))

class Stage3bContractTest(unittest.TestCase):
    def setUp(self):
        self.records=support_records(build.REPO)
        class Renderer:
            def resolve_link(self,*args):return '/docs/reference/'
        self.renderer=Renderer()
    def test_T8_guide_rows_badges_links_and_unknown_ids(self):
        from website.check import guide_records_errors
        rows=build.guide_rows([r['id'] for r in self.records],self.records,self.renderer)
        assignment={'guide':[r['id'] for r in self.records]}
        rows=rows.replace('>Reloads<','>\n Reloads \n<') # SVG whitespace in delivered badges
        self.assertEqual([],guide_records_errors(self.records,{'guide':rows},assignment))
        for mutation in [rows.replace('class="badge works"','class="badge restart"',1),rows.replace('/docs/support-matrix/#method-body','/docs/support-matrix/#missing',1),rows.replace(self.records[0]['conditions'],'drift',1)]:
            self.assertTrue(guide_records_errors(self.records,{'guide':mutation},assignment))
        with self.assertRaisesRegex(ValueError,'Unknown guide support ID'):build.guide_rows(['absent'],self.records,self.renderer)
    def test_T9_all_83_records_have_their_assigned_guide(self):
        from website.content import GUIDE_IDS
        from website.check import guide_records_errors
        lock=json.loads((build.REPO/'website/support-status.lock.json').read_text())
        self.assertEqual(83,len(lock));self.assertEqual(set(lock),{x for keys in GUIDE_IDS.values() for x in keys})
        self.assertIn('constructor-helper',GUIDE_IDS['spring'])
        pages={name:build.guide_rows(keys,self.records,self.renderer) for name,keys in GUIDE_IDS.items()}
        self.assertEqual([],guide_records_errors(self.records,pages,GUIDE_IDS))
        pages['spring']=pages['spring'].replace('data-guide-id="constructor-helper"','data-omitted="constructor-helper"')
        self.assertTrue(guide_records_errors(self.records,pages,GUIDE_IDS))
    def test_T10_frozen_redirect_fragment_must_exist_on_destination(self):
        from website.check import frozen_fragment_errors
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'website').mkdir();out=root/'output';(out/'docs/spring').mkdir(parents=True)
            (root/'website/fragments.json').write_text(json.dumps({'docs/messaging/index.html':['kafka']}))
            (root/'website/routes.json').write_text(json.dumps({'/docs/messaging/':{'destination':'/docs/spring/#messaging','fragments':{'kafka':'kafka'}}}))
            p=out/'docs/spring/index.html';p.write_text('<h2 id="kafka">Kafka</h2>')
            self.assertEqual([],frozen_fragment_errors(root,out));p.write_text('<h2>Kafka</h2>')
            self.assertTrue(frozen_fragment_errors(root,out))
            p.write_text('<h2 id="kafka">Kafka</h2>');(root/'website/routes.json').write_text(json.dumps({'/docs/messaging/':{'destination':'/docs/spring/','fragments':{}}}))
            self.assertTrue(frozen_fragment_errors(root,out))
    def test_T11_links_to_folded_guides_are_rejected(self):
        from website.check import stage3b_contract_errors
        with tempfile.TemporaryDirectory() as tmp:
            out=Path(tmp);p=out/'test.html';p.write_text('<a href="/docs/messaging/#kafka">Kafka</a>')
            self.assertTrue(any('Link to folded guide' in e for e in stage3b_contract_errors(build.REPO,out,self.records)))
            p.write_text('<a href="docs/boundaries/#pause">Pause</a>')
            self.assertTrue(any('Link to folded guide' in e for e in stage3b_contract_errors(build.REPO,out,self.records)))
            p.write_text('<a href="/docs/spring/#kafka">Kafka</a>')
            self.assertFalse(any('Link to folded guide' in e for e in stage3b_contract_errors(build.REPO,out,self.records)))
    def test_T12_every_option_is_grouped_exactly_once(self):
        from website.content import OPTION_GROUPS
        from website.check import option_group_errors
        options=[x for values in OPTION_GROUPS.values() for x in values]
        html=''.join('<tr data-option="'+key+'" data-option-group="'+group+'"></tr>' for group,keys in OPTION_GROUPS.items() for key in keys)
        self.assertEqual([],option_group_errors(html,options,OPTION_GROUPS))
        for bad in [html.replace('data-option="hybrisHome"','data-omitted="hybrisHome"',1),html+html,html.replace('data-option-group="Common"','data-option-group="Diagnostics"',1)]:self.assertTrue(option_group_errors(bad,options,OPTION_GROUPS))
        with self.assertRaisesRegex(ValueError,'grouped exactly once'):build.grouped_options([{'name':'unknown'}],None)

if __name__=='__main__':unittest.main(verbosity=2)
