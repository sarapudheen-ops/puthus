import copy
import unittest

from toyota_coverage import NOT_APPLICABLE_REASONS, summarize_dispositions


def row(disposition='unresolved', reason='unknown_transport'):
    source = 'SYSTEMENTER.BIN@00002f63'
    return {'id': source, 'sourceRecords': [source], 'disposition': disposition,
            'reason': reason, 'definitionIds': []}


class ToyotaCoverageTest(unittest.TestCase):
    def test_unknowns_cannot_be_not_applicable(self):
        for reason in ('unknown_transport', 'record_schema_untranslated', 'missing_system_entry',
                       'missing_function', 'missing_ecu_file'):
            with self.subTest(reason=reason):
                self.assertEqual(summarize_dispositions([row(reason=reason)])['unresolved'], 1)
                with self.assertRaises(ValueError):
                    summarize_dispositions([row('notApplicable', reason)])

    def test_only_explicit_policy_reasons_with_source_identity(self):
        for reason in NOT_APPLICABLE_REASONS:
            self.assertEqual(summarize_dispositions([row('notApplicable', reason)])['notApplicable'], 1)
        bad = row('notApplicable', 'flash_download')
        bad['sourceRecords'] = []
        with self.assertRaises(ValueError):
            summarize_dispositions([bad])

    def test_duplicate_identity_is_not_double_counted_or_conflicted(self):
        original = row()
        self.assertEqual(summarize_dispositions([original, copy.deepcopy(original)])['unresolved'], 1)
        conflict = row(reason='missing_function')
        with self.assertRaises(ValueError):
            summarize_dispositions([original, conflict])

    def test_converted_requires_definition_and_unresolved_cannot_hide_one(self):
        converted = row('converted', None)
        with self.assertRaises(ValueError):
            summarize_dispositions([converted])
        converted['definitionIds'] = ['pid-1']
        self.assertEqual(summarize_dispositions([converted])['converted'], 1)
        unresolved = row()
        unresolved['definitionIds'] = ['pid-1']
        with self.assertRaises(ValueError):
            summarize_dispositions([unresolved])


if __name__ == '__main__':
    unittest.main()
