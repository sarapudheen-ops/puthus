from pathlib import Path
import tempfile
import unittest

from sweep_toyota import json_text, publishable_report, safe_source


class SweepSafetyTest(unittest.TestCase):
    def test_json_evidence_escapes_control_bytes_and_is_reparseable(self):
        import json
        text = json_text({"label": "Speed\0rpm\n\u03a9"})
        self.assertNotIn("\0", text)
        self.assertEqual({"label": "Speed\0rpm\n\u03a9"}, json.loads(text))

    def test_publishable_chunks_preserve_every_byte_within_api_limit(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            text = "hello\n" + "\U0001f697" * 42000 + "\n" + "last line\n" * 2500
            names = publishable_report(root, "test", "Test", text)
            self.assertGreater(len(names), 1)
            recovered = []
            for name in names:
                data = (root / name).read_bytes()
                self.assertLessEqual(len(data), 60000)
                recovered.append(data.decode().split("\n\n", 2)[2])
            self.assertEqual(text, "".join(recovered))

    def test_manifest_rejects_traversal_absolute_and_symlink_escape(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "decoded"
            root.mkdir()
            for name in ["../file", "/file", "EU/../../file", "EU\\file", "."]:
                with self.subTest(name=name), self.assertRaises(ValueError):
                    safe_source(root, name)
            (root / "escape").symlink_to(root.parent, target_is_directory=True)
            with self.assertRaises(ValueError):
                safe_source(root, "escape/file")
            self.assertEqual(root / "EU" / "Ecu_Data_0001.BIN", safe_source(root, "EU/Ecu_Data_0001.BIN"))


if __name__ == "__main__":
    unittest.main()
