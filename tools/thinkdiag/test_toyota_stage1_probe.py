import copy
import unittest

from toyota_stage1_probe import translate_categories as classify_existing_status
from toyota_category_translation import translate_categories


class Stage1ProbeTests(unittest.TestCase):
    def test_exact_row_and_statuses_are_classified_without_mutation(self):
        row = {"region":"EU", "functionKey":61, "entrySelector":0x11,
               "categoryStatus":{"pids":"executable", "dtcs":"unresolved", "ecuInfo":"unknown"},
               "categoryReasons":{"dtcs":"translator_not_ported:dtcs"}}
        before = copy.deepcopy(row)
        got = classify_existing_status("EU",61,0x11,row)
        self.assertEqual(got["pids"], ("executable",None))
        self.assertEqual(got["dtcs"], ("rejected","translator_not_ported:dtcs"))
        self.assertEqual(got["ecuInfo"], ("rejected","translator_not_ported"))
        self.assertEqual(row,before)

    def test_sibling_selector_never_inherits_status(self):
        row = {"region":"EU", "functionKey":61, "entrySelector":0x11, "categoryStatus":{}}
        with self.assertRaisesRegex(ValueError,"system_identity_mismatch"):
            classify_existing_status("EU",61,0x51,row)

    def test_missing_status_fails_closed(self):
        row = {"region":"EU", "functionKey":61, "entrySelector":0x11}
        with self.assertRaisesRegex(ValueError,"category_status_missing"):
            classify_existing_status("EU",61,0x11,row)

    def test_compiled_candidate_requires_exact_category_oracle_evidence(self):
        row={"region":"EU", "functionKey":61, "entrySelector":0x11,
             "data":{"pids":[{"id":"pid-1"}]}}
        before=copy.deepcopy(row)
        missing=translate_categories("EU",61,0x11,row)
        self.assertEqual(missing["categories"]["pids"],"rejected")
        self.assertEqual(missing["reasons"]["pids"],"oracle_record_comparison_missing_or_mismatched")
        self.assertEqual(row,before)
        evidence={"pids":{"region":"EU","function":61,"selector":0x11,"category":"pids",
                          "conclusion":"success","evidenceSha256":"a"*64}}
        passed=translate_categories("EU",61,0x11,row,oracle_comparisons=evidence)
        self.assertEqual(passed["categories"]["pids"],"executable")
        evidence["pids"]["selector"]=0x51
        self.assertEqual(translate_categories("EU",61,0x11,row,oracle_comparisons=evidence)["categories"]["pids"],"rejected")


if __name__ == "__main__": unittest.main()
