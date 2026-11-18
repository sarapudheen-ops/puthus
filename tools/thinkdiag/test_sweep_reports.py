import copy
import json
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

from sweep_reports import make_parts, parts_from_checks, publish_check, unpack_parts


class SweepReportBundleTest(unittest.TestCase):
    def test_lossless_utf8_roundtrip_with_small_check_count_and_api_bound(self):
        reports = [{"file": "sweep-test.md", "text": "\U0001f697\0\n" * 10000 + os.urandom(50000).hex()}]
        parts = make_parts(reports)
        self.assertGreater(len(parts), 1)
        for part in parts:
            self.assertLess(len(json.dumps(part).encode()), 60000)
        self.assertEqual(reports, unpack_parts(list(reversed(parts))))

    def test_rejects_missing_duplicate_or_corrupt_parts(self):
        parts = make_parts([{"file": "sweep-test.md", "text": os.urandom(4000).hex()}], chunk_chars=1000)
        self.assertGreater(len(parts), 2)
        for invalid in [[], parts[:-1], parts + [parts[0]]]:
            with self.assertRaises(ValueError):
                unpack_parts(invalid)
        invalid = copy.deepcopy(parts)
        invalid[0]["dataBase64"] = "A" + invalid[0]["dataBase64"][1:]
        with self.assertRaises(ValueError):
            unpack_parts(invalid)
        invalid = copy.deepcopy(parts)
        invalid[0]["uncompressedSha256"] = "0" * 64
        with self.assertRaises(ValueError):
            unpack_parts(invalid)

    def test_rejects_unsafe_names_and_duplicate_files_before_use(self):
        for name in ["../escape.md", "/tmp/escape.md", "sweep-../escape.md", "sweep-a\\b.md", "unrelated.md"]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                make_parts([{"file": name, "text": "no"}])
        record = {"file": "sweep-test.md", "text": "ok"}
        with self.assertRaises(ValueError):
            make_parts([record, record])


class PublicationRetryTest(unittest.TestCase):
    @patch("sweep_reports.time.sleep")
    @patch("sweep_reports.subprocess.run")
    def test_retries_truncated_accepted_post_timeout_and_server_errors(self, run, sleep):
        for error in ("unexpected end of JSON input", "EOF", "HTTP 502", "HTTP 429", "connection reset"):
            run.reset_mock(); sleep.reset_mock()
            run.side_effect = [subprocess.CompletedProcess([], 1, "", error),
                               subprocess.TimeoutExpired("gh", 60),
                               subprocess.CompletedProcess([], 0, "1234\n", "")]
            publish_check("owner/repo", "a" * 40, "name", "summary")
            self.assertEqual(3, run.call_count)
            self.assertEqual([5, 10], [c.args[0] for c in sleep.call_args_list])

    @patch("sweep_reports.time.sleep")
    @patch("sweep_reports.subprocess.run")
    def test_fatal_errors_do_not_retry_and_transient_errors_are_bounded(self, run, sleep):
        run.return_value = subprocess.CompletedProcess([], 1, "", "HTTP 403 forbidden")
        with self.assertRaisesRegex(RuntimeError, "403"):
            publish_check("owner/repo", "a" * 40, "name", "summary")
        self.assertEqual(1, run.call_count)
        sleep.assert_not_called()
        run.reset_mock()
        run.return_value = subprocess.CompletedProcess([], 1, "", "HTTP 503")
        with self.assertRaisesRegex(RuntimeError, "503"):
            publish_check("owner/repo", "a" * 40, "name", "summary")
        self.assertEqual(5, run.call_count)
        self.assertEqual(4, sleep.call_count)

    def test_recovery_deduplicates_identical_accepted_retries_but_never_conflicts(self):
        reports = [{"file": "sweep-retry.md", "text": os.urandom(3000).hex()}]
        parts = make_parts(reports, chunk_chars=1000)
        checks = [{"name": f"Toyota sweep evidence {p['part']:03d}", "conclusion": "success",
                   "output": {"summary": json.dumps(p)}} for p in parts]
        duplicated = checks + [copy.deepcopy(checks[0]), copy.deepcopy(checks[-1])]
        self.assertEqual(reports, unpack_parts(parts_from_checks(duplicated)))
        bad = copy.deepcopy(checks[0])
        part = json.loads(bad["output"]["summary"])
        part["dataBase64"] = "A" + part["dataBase64"][1:]
        bad["output"]["summary"] = json.dumps(part)
        with self.assertRaisesRegex(ValueError, "Conflicting"):
            parts_from_checks(checks + [bad])
        with self.assertRaisesRegex(ValueError, "complete"):
            parts_from_checks(checks[:-1])


if __name__ == "__main__":
    unittest.main()
