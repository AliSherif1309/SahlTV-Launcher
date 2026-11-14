import unittest
from check_versions import affected, check, version


def sources(value):
    return dict.fromkeys(('app', 'quran', 'pdf', 'files'), f'versionName = "{value}"')


class Versions(unittest.TestCase):
    def test_legacy_numeric_comparison(self):
        self.assertGreater(version('versionName = "1.24.0"')[1], version('versionName = "1.23"')[1])
        self.assertEqual(version('versionName = "1.10"')[1], (1, 10, 0))

    def test_changed_app_requires_increase(self):
        self.assertEqual(len(check(['app/src/main/Test.kt'], sources('1.23'), sources('1.23.0'))), 1)
        self.assertFalse(check(['app/src/main/Test.kt'], sources('1.23'), sources('1.24.0')))
        self.assertTrue(check(['app/src/main/Test.kt'], sources('1.24.0'), sources('1.23.9')))

    def test_shared_and_generated_inputs(self):
        for path in ('common/src/main/Test.kt', 'tools/make_ayat.py', 'gradle.properties'):
            self.assertEqual(len(affected([path])), 4)

    def test_docs_and_tests(self):
        self.assertFalse(affected(['README.md', 'docs/REFERENCE.md', 'app/src/androidTest/Test.kt', 'tools/test_versions.py']))

    def test_companion_only(self):
        self.assertEqual(affected(['pdf/src/main/Test.kt']), {'pdf'})


if __name__ == '__main__':
    unittest.main()
