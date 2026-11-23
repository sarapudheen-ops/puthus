import copy
import unittest
from unittest.mock import patch

from toyota_category_translation import translate_categories, normalize_ecu_file


class Stage1TranslatorTests(unittest.TestCase):
    def test_only_exact_ecmect_identity_uses_pinned_translator(self):
        row = {"id":"SYSLIST_NA.BIN@00000001", "region":"NA", "functionKey":61,
               "entrySelector":0x11, "ecuFile":"NA/Ecu_Data_0081.BIN"}
        before = copy.deepcopy(row)
        got = translate_categories("NA", 61, 0x11, row, ecmect_review={})
        self.assertEqual(got["categories"]["pids"], "rejected")
        self.assertEqual(got["reasons"]["pids"], "ecmect_specific:mandatory_system_and_live_read_review")
        self.assertEqual(got["reasons"]["dtcs"], "ecmect_specific:mandatory_system_and_p3p4_workflow")
        self.assertEqual(got["reasons"]["ecuInfo"], "ecmect_specific:mandatory_system_and_ecu_identification")
        self.assertEqual(row, before)

    def test_exact_ecmect_calls_existing_data_builder_and_counts(self):
        row = {"id":"SYSLIST_EU.BIN@0009d8f2", "region":"EU", "functionKey":61,
               "entrySelector":0x11, "ecuFile":"Ecu_Data_0081.BIN"}
        self.assertEqual("EU/Ecu_Data_0081.BIN", normalize_ecu_file("EU", row["ecuFile"]))
        data = {"pids":[{"id":str(i)} for i in range(30)], "ecus":[{
            "diagnostics":{"faults":{"layout":{"labels":[{"id":str(i)} for i in range(1414)]}},
                           "identification":[{"id":"ecu"}]}}]}
        lifecycle = object()
        review = {"verified":True}
        with patch("toyota_category_translation.verify_source_read_review"), \
             patch("toyota_category_translation.data_for", return_value=data) as builder:
            got = translate_categories("EU", 61, 0x11, row, ecmect_review=review,
                                       lifecycle=lifecycle, reference_data=data)
        builder.assert_called_once_with(review, lifecycle)
        self.assertEqual({"pids":30,"dtcs":1414,"ecuInfo":1},
                         {c:got["counts"][c] for c in ("pids","dtcs","ecuInfo")})
        self.assertEqual("executable", got["categories"]["pids"])
        self.assertTrue(got["ecmectControlByteIdentical"])
        self.assertFalse(got["oracleApproved"])

    def test_selector_mismatch_is_rejected_without_mutation(self):
        row = {"id":"SYSLIST_EU.BIN@0009d8f2", "region":"EU", "functionKey":61,
               "entrySelector":0x11, "ecuFile":"EU/Ecu_Data_0081.BIN"}
        before = copy.deepcopy(row)
        with self.assertRaisesRegex(ValueError, "system_identity_mismatch"):
            translate_categories("EU", 61, 0x51, row)
        self.assertEqual(row, before)


if __name__ == "__main__":
    unittest.main()
