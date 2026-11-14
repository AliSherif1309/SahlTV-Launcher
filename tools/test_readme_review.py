"""Exercise stale metadata, missing assets, and the documentation coverage policy."""
from pathlib import Path
import tempfile
import unittest

import readme_review as review


class DocumentationReviewTest(unittest.TestCase):
    def test_user_facing_change_requires_docs(self):
        code, docs, errors = review.review_changes(['app/src/main/NewFeature.kt'], '')
        self.assertEqual(len(code), 1)
        self.assertFalse(docs)
        self.assertTrue(errors)

    def test_documentation_change_satisfies_coverage(self):
        self.assertFalse(review.review_changes(['common/Foo.kt', 'README.md'], '')[2])
        self.assertFalse(review.review_changes(['app/Timer.kt', 'docs/REFERENCE.md'], '')[2])

    def test_specific_explanation_accepts_internal_change(self):
        reason = 'README review: not needed — Extracts private helpers with identical behavior.'
        self.assertFalse(review.review_changes(['common/Foo.kt'], reason)[2])

    def test_placeholder_and_short_explanations_fail(self):
        for reason in ['', 'README review: not needed', 'README review: not needed — n/a']:
            self.assertTrue(review.review_changes(['tools/perf.sh'], reason)[2])

    def test_docs_only_and_unrelated_files_need_no_exemption(self):
        for paths in [['README.md'], ['LICENSE']]:
            self.assertFalse(review.review_changes(paths, '')[2])

    def test_build_workflow_and_assets_need_review(self):
        for path in ['app/src/main/assets/whatsnew.json', 'settings.gradle.kts', '.github/workflows/build.yml']:
            self.assertTrue(review.review_changes([path], '')[2])

    def test_refresh_replaces_only_generated_region_and_is_idempotent(self):
        old = 'Intro\n' + review.START + '\nstale\n' + review.END + '\nFooter\n'
        new = review.refresh(old, 'fresh table')
        self.assertEqual(new, 'Intro\n' + review.START + '\nfresh table\n' + review.END + '\nFooter\n')
        self.assertEqual(new, review.refresh(new, 'fresh table'))
        self.assertNotEqual(old, new)

    def test_bad_markers_fail_closed(self):
        for text in ['', review.START, review.END + review.START, review.START * 2 + review.END]:
            with self.assertRaises(ValueError):
                review.refresh(text, 'table')

    def test_version_changes_and_new_modules_are_detected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            app = root / 'app' / 'build.gradle.kts'
            app.parent.mkdir()
            app.write_text('id("com.android.application")\nversionName = "1.23"\nminSdk = 26')
            first = review.generated_versions(root)
            self.assertIn('| Sahl TV | 1.23 | 26 |', first)
            app.write_text(app.read_text().replace('1.23', '1.24'))
            self.assertNotEqual(first, review.generated_versions(root))
            extra = root / 'newapp' / 'build.gradle.kts'
            extra.parent.mkdir()
            extra.write_text('id("com.android.application")\nversionName = "0.1"\nminSdk = 28')
            self.assertIn('| newapp | 0.1 | 28 |', review.generated_versions(root))

    def test_unreadable_version_is_not_silently_skipped(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            app = root / 'app' / 'build.gradle.kts'
            app.parent.mkdir()
            app.write_text('id("com.android.application")\nversionName = dynamicValue\nminSdk = 26')
            with self.assertRaises(ValueError):
                review.generated_versions(root)

    def test_local_images_and_relative_docs_are_checked(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'docs' / 'images').mkdir(parents=True)
            (root / 'README.md').write_text('[Guide](docs/guide.md)\n![Missing](docs/images/missing.svg)\n[Web](https://example.com)\n[Heading](#intro)')
            (root / 'docs' / 'guide.md').write_text('[Back](../README.md)')
            errors = review.local_link_errors(root)
            self.assertEqual(len(errors), 1)
            self.assertIn('missing.svg', errors[0])
            (root / 'docs' / 'images' / 'missing.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg"/>')
            self.assertFalse(review.local_link_errors(root))

    def test_invalid_svg_and_code_examples(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'docs' / 'images').mkdir(parents=True)
            (root / 'README.md').write_text('```md\n[Example](nonexistent.md)\n```')
            (root / 'docs' / 'images' / 'broken.svg').write_text('<svg><broken>')
            errors = review.local_link_errors(root)
            self.assertEqual(len(errors), 1)
            self.assertIn('broken.svg', errors[0])


if __name__ == '__main__':
    unittest.main()
