#!/usr/bin/env python3
"""Tests for lint.py: python3 tools/android-regex-check/test_lint.py"""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import lint  # noqa: E402


def whats(src):
    return [what for _, what, _ in lint.findings_in("x.kt", src)]


class LintTest(unittest.TestCase):
    def test_engine_classes_in_raw_and_escaped_strings_fail(self):
        for src in ['val a = Regex("""\\d+""")', 'val a = Regex("\\\\s+")', 'val a = "x\\\\bword"',
                    'val a = Regex("""\\p{L}""")', 'val a = Regex("""[\\W]""")', 'val a = Regex("""\\B""")']:
            self.assertEqual(len(whats(src)), 1, src)

    def test_escaped_backslash_and_explicit_classes_pass(self):
        for src in ['val a = Regex("""\\\\d""")', 'val a = Regex("[0-9]+")', 'val a = Regex("""${Rx.SP}+${Rx.WB_END}""")',
                    'val c = \'\\b\'', 'val s = "tab\\there"']:
            self.assertEqual(whats(src), [], src)

    def test_case_insensitive_flags_fail_in_code_only(self):
        self.assertEqual(len(whats('val a = Regex("x", RegexOption.IGNORE_CASE)')), 1)
        self.assertEqual(len(whats('val a = Regex("(?i)x")')), 1)
        self.assertEqual(len(whats('val a = Regex("(?iu:x)")')), 1)
        self.assertEqual(whats('// no IGNORE_CASE here\n/* UNICODE_CASE */ val a = 1'), [])
        self.assertEqual(whats('val a = Regex("(?:x)(?=y)(?<!z)")'), [])

    def test_comments_are_not_checked(self):
        self.assertEqual(whats('// Regex("\\\\d")\n/* """\\s""" */\nval a = 1'), [])

    def test_baseline_patterns_are_translated_but_refused_constructs_fail(self):
        self.assertEqual(whats('val p = Pattern("""\\bcopy\\b""", "yes", "no", "copy")'), [])
        self.assertEqual(whats('val p = Baseline.Pattern("\\\\s*$", "yes", "no", "q")'), [])
        self.assertEqual(len(whats('val p = Baseline.Pattern("""\\p{L}+""", "yes", "no", "q")')), 1)
        # a second literal argument is ordinary text
        self.assertEqual(len(whats('val p = Pattern("x", """\\d""", "no", "q")')), 1)

    def test_the_repository_is_clean(self):
        self.assertEqual(lint.main(), 0)


if __name__ == "__main__":
    unittest.main()
