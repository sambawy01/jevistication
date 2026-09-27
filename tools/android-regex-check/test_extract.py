#!/usr/bin/env python3
"""Tests for extract.py: python3 tools/android-regex-check/test_extract.py"""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import extract  # noqa: E402


def found(src):
    return [(v, why, flags) for _, v, why, flags in extract.patterns_in(src)]


class ExtractTest(unittest.TestCase):
    def test_regex_to_regex_and_pattern_compile_with_flags(self):
        src = 'val a = Regex("a{2}")\nval b = """x""".toRegex(RegexOption.IGNORE_CASE)\n' \
              'val c = Pattern.compile("[a-z]", Pattern.COMMENTS)\n'
        self.assertEqual(found(src), [("a{2}", None, 0), ("x", None, 66), ("[a-z]", None, 4)])

    def test_baseline_patterns_are_sent_translated_without_flags(self):
        # Was test_baseline_pattern_constructors_are_ignore_case: Baseline.Pattern now compiles
        # PortableRegex.translate(pattern) with no flags (docs/BUILD.md, parity B1), so the device must
        # compile exactly that. The same three constructions are checked.
        rx = extract.global_consts()
        src = 'val n = Baseline.Pattern("[?]\\\\s*$", "yes", "no", "q")\n' \
              'val t = Pattern("""\\bcopy\\b""", "yes", "no", "copy")\n' \
              'data class Pattern(\n    val pattern: String,\n)\n' \
              'val d = Baseline.Pattern(s("pattern"), "a", "b", "c")\n'
        self.assertEqual(found(src), [
            ("[?]" + rx["Rx.SP"] + "*$", None, 0),
            (extract.portable.leading_wb(rx, False) + "copy" + rx["Rx.WB"], None, 0),
            (None, "not a literal", 0),
        ])

    def test_rx_constants_and_guarded_regex_are_resolved(self):
        rx = extract.global_consts()
        self.assertEqual(rx["Rx.SP"], "[ \t\n\x0b\x0c\r\u00a0\u2007\u2009\u202f]")
        self.assertEqual(rx["Rx.DIGIT"], "[0-9\u0660-\u0669\u06f0-\u06f9]")
        src = 'val a = Regex("""x${Rx.SP}+y""")\nval g = GuardedRegex("""(\\d+)${Rx.WB_END}""") { it == \'a\' }\n' \
              'class GuardedRegex(pattern: String)\n'
        self.assertEqual(found(src), [("x" + rx["Rx.SP"] + "+y", None, 0), ("(\\d+)" + rx["Rx.WB_END"], None, 0)])

    def test_translate_refuses_engine_defined_classes(self):
        rx = extract.global_consts()
        for bad in ["\\p{L}", "[\\D]", "\\X", "[\\b]"]:
            with self.assertRaises(extract.portable.TranslateError, msg=bad):
                extract.portable.translate(bad, rx)
        self.assertEqual(extract.portable.translate("(?i)Invoice ٢٠٢٦", rx), "invoice 2026")

    def test_comments_and_strings_are_not_code(self):
        src = '// Regex("no")\n/* Regex("no /* nested */") */\nval s = "Regex(\\"no\\")"\n'
        self.assertEqual(found(src), [])

    def test_unterminated_strings_fail_instead_of_looping(self):
        for src in ['val s = """never closed', 'val s = "never closed', 'val s = """a ${x', 'val s = "a ${x']:
            with self.assertRaises(ValueError, msg=src):
                extract.code_mask(src)
        with self.assertRaises(ValueError):
            extract.skip_string('"""abc', 0)


class PortableTranslateCorpusTest(unittest.TestCase):
    def test_the_python_port_gives_the_corpus_answers(self):
        # tools/parity/corpus.json pins PortableRegex.translate (Kotlin, every platform); the port
        # extract.py uses must give the same patterns, so the device compiles what the app compiles.
        import json
        corpus = json.loads((extract.ROOT / "tools/parity/corpus.json").read_text(encoding="utf-8"))
        rx = extract.global_consts()
        cases = [c for c in corpus["cases"] if c["fn"] == "regex.translate"]
        self.assertGreaterEqual(len(cases), 8)
        for c in cases:
            try:
                got = {"pattern": extract.portable.translate(c["input"], rx)}
            except extract.portable.TranslateError:
                got = {"error": True}
            self.assertEqual(c["expect"], got, c["id"])


if __name__ == "__main__":
    unittest.main()
