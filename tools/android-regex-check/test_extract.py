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

    def test_baseline_pattern_constructors_are_ignore_case(self):
        src = 'val n = Baseline.Pattern("[?]\\\\s*$", "yes", "no", "q")\n' \
              'val t = Pattern("""\\bcopy\\b""", "yes", "no", "copy")\n' \
              'data class Pattern(\n    val pattern: String,\n)\n' \
              'val d = Baseline.Pattern(s("pattern"), "a", "b", "c")\n'
        self.assertEqual(found(src), [("[?]\\s*$", None, 66), ("\\bcopy\\b", None, 66), (None, "not a literal", 66)])

    def test_comments_and_strings_are_not_code(self):
        src = '// Regex("no")\n/* Regex("no /* nested */") */\nval s = "Regex(\\"no\\")"\n'
        self.assertEqual(found(src), [])

    def test_unterminated_strings_fail_instead_of_looping(self):
        for src in ['val s = """never closed', 'val s = "never closed', 'val s = """a ${x', 'val s = "a ${x']:
            with self.assertRaises(ValueError, msg=src):
                extract.code_mask(src)
        with self.assertRaises(ValueError):
            extract.skip_string('"""abc', 0)


if __name__ == "__main__":
    unittest.main()
