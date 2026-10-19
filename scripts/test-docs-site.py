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
from website.check import Document, output_differences, source_index_errors

class DocumentationBuildTest(unittest.TestCase):
    def setUp(self):
        tmp=tempfile.TemporaryDirectory(prefix='reclazz docs ');self.addCleanup(tmp.cleanup)
        self.root=Path(tmp.name);(self.root/'docs').mkdir()
        self.write('docs/index.md','# Source index\n\n[First](first.md)\n[Second](second.md)\n')
        self.write('docs/first.md','# First\n\n## Same\nOne\n\n## Same\nTwo\n')
        self.write('docs/second.md','# Second\n\n## Destination\nText.\n')
    def write(self,path,text):
        p=self.root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    def renderer(self):return MarkdownRenderer(self.root,reference_pages(self.root))
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
        self.assertEqual('9.8.8',re.search(r'Source snapshot <code>(.*?)</code>',after['docs/index.html'])[1])
        self.assertIn('Updated introduction.',after['llms.txt']);self.assertNotIn('Canonical introduction.',after['llms.txt'])
        self.assertIn('9.8.8',after['llms.txt'])
    def test_search_targets_sections_without_repeating_evidence_registry(self):
        self.site_fixture();self.write('docs/feature-evidence.md','# Evidence\n\n## Proof\n'+'LONG_REGISTRY '*20000)
        output=self.generate();index=json.loads(output['docs/assets/search-index.json'])
        self.assertTrue(any(x['url']=='/docs/reference/second/#destination' for x in index))
        evidence=[x for x in index if x['url'].startswith('/docs/reference/feature-evidence/')]
        self.assertEqual(1,len(evidence));self.assertLess(len(evidence[0]['text']),1500)

    def test_authored_github_reading_link_cannot_bypass_canonical_routes(self):
        self.site_fixture()
        with self.assertRaisesRegex(ValueError,'canonical REF'):
            expand_guide('<a href="https://github.com/onurkat/reclazz/blob/main/docs/first.md#same">Read</a>',self.renderer(),metadata(self.root))
    def test_duplicate_authored_version_is_rejected(self):
        self.site_fixture();p=self.root/'docs/site/manifest.json';data=json.loads(p.read_text())
        data['source_version']='stale';p.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError,'gradle.properties'):metadata(self.root)
    def test_small_text_and_primary_button_colors_keep_minimum_contrast(self):
        css=(Path(__file__).resolve().parents[1]/'website/static/docs/assets/docs.css').read_text()
        def luminance(value):
            channels=[int(value[i:i+2],16)/255 for i in (0,2,4)]
            linear=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in channels]
            return sum(v*w for v,w in zip(linear,[.2126,.7152,.0722]))
        def ratio(a,b):
            low,high=sorted([luminance(a),luminance(b)]);return (high+.05)/(low+.05)
        for theme,block in zip(('dark','light'),css.splitlines()[:2]):
            colors=dict(re.findall(r'--([a-z-]+):#([a-fA-F0-9]{6})',block))
            for foreground in ('text','muted','dim','accent','green','gold','rose'):
                for background in ('bg','surface','surface-hover','surface-alt'):
                    self.assertGreaterEqual(ratio(colors[foreground],colors[background]),4.5,(theme,foreground,background))
            self.assertGreaterEqual(ratio('ffffff',colors['accent-fill']),4.5,theme+' button')
            hover=''.join(format(min(255,round(int(colors['accent-fill'][i:i+2],16)*1.12)),'02x') for i in (0,2,4))
            self.assertGreaterEqual(ratio('ffffff',hover),4.5,theme+' button hover')

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

if __name__=='__main__':unittest.main(verbosity=2)
