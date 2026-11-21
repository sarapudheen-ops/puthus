import unittest

from publish_census import MARKER, split_sections


class PublishCensusTest(unittest.TestCase):
    def test_splits_only_at_headings_and_preserves_all_text(self):
        text = "# Title\nfirst\n\n## A\n" + "a" * 500 + "\n## B\n" + "b" * 500
        parts = split_sections(text, limit=800)
        self.assertEqual(2, len(parts))
        self.assertTrue(all(len(part) <= 800 for part in parts))
        self.assertTrue(all(part.startswith(MARKER + "\n# Census part ") for part in parts))
        rendered = "\n".join("\n".join(part.split("\n")[2:]) for part in parts)
        self.assertIn("first", rendered)
        self.assertIn("a" * 500, rendered)
        self.assertIn("b" * 500, rendered)

    def test_rejects_unsplittable_section_instead_of_truncating(self):
        with self.assertRaisesRegex(ValueError, "single Markdown section"):
            split_sections("# One\n" + "x" * 2000, limit=1000)

if __name__ == "__main__": unittest.main()
