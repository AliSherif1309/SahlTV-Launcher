#!/usr/bin/env python3
"""Require release versions to advance when shipped app inputs change."""
import argparse
import json
from pathlib import Path
import re
import subprocess

MODULES = ('app', 'quran', 'pdf', 'files')
ROOT = Path(__file__).resolve().parents[1]


def version(source):
    match = re.search(r'versionName\s*=\s*"(\d+\.\d+(?:\.\d+)?)"', source)
    if not match:
        raise ValueError('Expected a numeric versionName')
    parts = tuple(map(int, match[1].split('.')))
    return match[1], parts + (0,) * (3 - len(parts))


def affected(paths):
    result = set()
    for path in paths:
        # Tests and documentation do not change the shipped APK.
        if path.startswith('tools/test_') or '/src/androidTest/' in path or '/src/test/' in path:
            continue
        module = path.split('/')[0]
        if module in MODULES:
            result.add(module)
        elif (module in ('common', 'fonts-locked', 'gradle') or
              path in ('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties') or
              path.startswith('tools/') and path not in ('tools/readme_review.py', 'tools/check_versions.py')):
            result.update(MODULES)
    return result


def check(paths, old, current):
    errors = []
    for module in sorted(affected(paths)):
        name, number = version(current[module])
        previous, prior = version(old[module])
        if name.count('.') != 2 or number <= prior:
            errors.append(f'{module}: bump versionName above {previous} using major.minor.patch (found {name})')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    args = parser.parse_args()
    def git(*args):
        return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()
    base = git('rev-parse', '--verify', args.base + '^{commit}')
    paths = git('diff', '--name-only', base, 'HEAD', '--').splitlines()
    current = {m: (ROOT / m / 'build.gradle.kts').read_text() for m in MODULES}
    old = {m: git('show', f'{base}:{m}/build.gradle.kts') for m in MODULES}
    errors = check(paths, old, current)
    notes = json.loads((ROOT / 'app/src/main/assets/whatsnew.json').read_text())
    if notes['version'] != version(current['app'])[0]:
        errors.append("What's new version must match Sahl TV versionName")
    if errors:
        raise SystemExit('\n'.join(errors))
    print('PASS release versions and What\'s new; affected apps:', ', '.join(sorted(affected(paths))) or 'none')


if __name__ == '__main__':
    main()
