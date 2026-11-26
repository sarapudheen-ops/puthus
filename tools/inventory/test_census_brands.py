import unittest
import census_brands


class StemGateTest(unittest.TestCase):
    def test_exact_groups_without_guessing(self):
        stems = {'MENU': 1, 'SYSLIST': 2, 'ECU_DATA_000A': 3, 'FUNCDATA': 1,
                 'SYSTEMENTER': 1, 'TOYOTA_BIN_00': 1, 'SOME_SYSLIST': 1}
        self.assertEqual(census_brands.stem_groups(stems),
                         ['ECU_DATA', 'FUNCDATA', 'MENU', 'SYSLIST', 'SYSTEMENTER', 'TOYOTA_BIN_00'])
        self.assertEqual(set(census_brands.REQUIRED) - set(census_brands.stem_groups(stems)), set())

    def test_no_implied_name_mapping(self):
        self.assertEqual(census_brands.stem_groups({'BRAND_SYS_DATA': 4, 'ECUDATA': 2}), [])


if __name__ == '__main__':
    unittest.main()
