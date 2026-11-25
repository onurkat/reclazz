#!/usr/bin/env python3
"""Focused controls for claim locations, declaration fingerprints and refresh isolation."""
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from website.evidence import block_hash, method_hash
from website.evidence_registry import refresh


class FingerprintTest(unittest.TestCase):
    def test_markdown_hash_ignores_unrelated_sections_and_line_moves(self):
        text = "## A\nquote\n### Child\nbody\n## B\nother\n"
        def observed(value):
            return block_hash("a.md", value, "quote")
        self.assertEqual(observed(text), observed("# Intro\n" + text.replace("other", "new")))
        self.assertNotEqual(observed(text), observed(text.replace("body", "edited")))

    def test_exact_heading_wins_and_ambiguous_fallback_fails(self):
        text = "### P3C-EVENT-RESULT\none\n### P3C-EVENT-RESULT-REFUSAL\ntwo\n"
        h = block_hash("a.md", text, "### P3C-EVENT-RESULT")
        self.assertEqual(h, block_hash("a.md", text.replace("two", "three"), "### P3C-EVENT-RESULT"))
        for quote in ("EVENT-RESULT", "absent"):
            with self.assertRaises(ValueError):
                block_hash("a.md", text, quote)

    def test_xml_cdata_uses_the_enclosing_item_or_paragraph(self):
        for tag in ("li", "p"):
            text = f"<d><![CDATA[<{tag}><b>quote</b>\nbody</{tag}><{tag}>other</{tag}>]]></d>"
            def observed(value):
                return block_hash("a.xml", value, "quote")
            self.assertEqual(observed(text), observed(text.replace("other", "new")))
            self.assertNotEqual(observed(text), observed(text.replace("body", "edit")))

    def test_xml_uses_innermost_element_even_after_unicode(self):
        text = '<root>é<a attr="one">quote</a><a>else</a></root>'
        def observed(value):
            return block_hash("a.xml", value, "quote")
        self.assertEqual(observed(text), observed(text.replace("else", "edit")))
        self.assertNotEqual(observed(text), observed(text.replace("one", "two")))

    def test_java_hashes_declaration_including_comments_and_text_blocks(self):
        text = 'class A {\n void other() {}\\n @Test\n void proof() { /* } */ String s = """\n{\n"""; }\n}'
        text = text.replace("{}\\n", "{}\n")
        self.assertEqual(method_hash(text, "proof"), method_hash(text.replace("other", "outside"), "proof"))
        self.assertNotEqual(method_hash(text, "proof"), method_hash(text.replace("/* } */", "/* }x */"), "proof"))

    def test_member_annotations_and_duplicate_names_in_nested_classes(self):
        text = """class Example {
    void unrelated() {}
    @Tag(
        value = {"unit", "scope"})
    @Test
    void proof() { return; }
    static class Nested { void proof() {} }
}"""
        def observed(value):
            return method_hash(value, "proof", "Example")
        self.assertNotEqual(observed(text), observed(text.replace('"scope"', '"regression"')))
        self.assertEqual(observed(text), observed(text.replace('unrelated() {}', 'unrelated() { run(); }')))

    def test_xml_empty_element_has_no_parent_content(self):
        text = '<a><a attr="quoted > value"/></a>'
        def observed(value):
            return block_hash("p.xml", value, "quoted")
        self.assertEqual(observed(text), observed(text.replace("</a>", "<b/> </a>")))

    def test_refresh_preserves_history_is_idempotent_and_validates_before_writes(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            docs = root/"docs"
            records = docs/"feature-evidence"
            records.mkdir(parents=True)
            java = root/"agent/src/test/java/demo"
            java.mkdir(parents=True)
            (java/"Child.java").write_text("package demo; class Child extends Parent {}")
            declaring = java/"Parent.java"
            declaring.write_text("package demo; class Parent {\n @Test void proof() { /* original */ }\n}")
            (docs/"feature-evidence.md").write_text("### ID\n\nStatement.\n")
            ref = docs/"ref.md"
            ref.write_text("# Ref\nneedle\n")
            test = {"symbol": "demo.Child#proof", "path": (java/"Child.java").relative_to(root).as_posix(),
                    "declaration": {"symbol": "demo.Parent#proof", "path": declaring.relative_to(root).as_posix()}}
            claim = {"id": "ID", "statement": "Statement.", "verdict": "PARTIAL",
                     "tests": [test], "shipped_surfaces": [
                        {"path": "docs/ref.md", "quote": "needle"},
                        {"path": "docs/feature-evidence.md", "quote": "### ID"}]}
            history = {"old": {"cases": [{"source_sha256": "historical"}]}}
            registry = records/"test.json"
            registry.write_text(json.dumps({"lanes": history, "claims": [claim]}))
            self.assertEqual((1, 2), refresh(root))
            self.assertEqual((1, 0), refresh(root))
            initial = json.loads(registry.read_bytes())
            self.assertEqual(history, initial["lanes"])
            first = initial["claims"][0]["tests"][0]["method_sha256"]
            declaring.write_text(declaring.read_text().replace("original", "changed"))
            refresh(root)
            updated = json.loads(registry.read_bytes())
            test = updated["claims"][0]["tests"][0]
            self.assertEqual(test["method_sha256"], test["declaration"]["method_sha256"])
            self.assertNotEqual(first, test["method_sha256"])
            self.assertEqual(history, updated["lanes"])
            products = [registry, docs/"feature-evidence.md"]
            before = [p.read_bytes() for p in products]
            for changed in ("missing", "a needle\nb needle"):
                ref.write_text(changed)
                with self.assertRaisesRegex(ValueError, "ID: quote"):
                    refresh(root)
                self.assertEqual(before, [p.read_bytes() for p in products])


if __name__ == "__main__":
    unittest.main()
