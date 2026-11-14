#!/usr/bin/env python3
"""Deterministic README checks. No network, credentials, or third-party packages."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
START = '<!-- BEGIN AUTO:APP_VERSIONS -->'
END = '<!-- END AUTO:APP_VERSIONS -->'
NAMES = {'app': ('Sahl TV', 'SahlTV.apk'), 'quran': ('Sahl Quran', 'SahlQuran.apk'),
         'pdf': ('Sahl PDF', 'SahlPDF.apk'), 'files': ('Sahl Files', 'SahlFiles.apk')}
DOWNLOAD = 'https://github.com/AliSherif1309/SahlTV-Launcher/releases/download/latest/'


def generated_versions(root):
    rows = ['| App | Source version | Minimum Android API | Download |', '| --- | --- | --- | --- |']
    builds = sorted(root.glob('*/build.gradle.kts'), key=lambda p: (list(NAMES).index(p.parent.name) if p.parent.name in NAMES else 99, p.parent.name))
    count = 0
    for build in builds:
        source = build.read_text(encoding='utf-8')
        if not re.search(r'id\("com\.android\.application"\)', source):
            continue
        version = re.search(r'\bversionName\s*=\s*"([^"]+)"', source)
        minimum = re.search(r'\bminSdk\s*=\s*(\d+)', source)
        if not version or not minimum:
            raise ValueError(f'Cannot read versionName/minSdk in {build.relative_to(root)}')
        name, apk = NAMES.get(build.parent.name, (build.parent.name, None))
        link = f'[{apk}]({DOWNLOAD}{apk})' if apk else 'See release assets'
        rows.append(f'| {name} | {version[1]} | {minimum[1]} | {link} |')
        count += 1
    if not count:
        raise ValueError('No Android application modules found')
    return '\n'.join(rows)


def refresh(text, table):
    if text.count(START) != 1 or text.count(END) != 1 or text.index(START) > text.index(END):
        raise ValueError('README must contain exactly one ordered app-version marker pair')
    before, rest = text.split(START)
    _, after = rest.split(END)
    return before + START + '\n' + table + '\n' + END + after


def local_link_errors(root):
    errors = []
    for doc in [root / 'README.md', *sorted((root / 'docs').rglob('*.md'))]:
        # Ignore code examples before scanning inline Markdown and HTML image paths.
        source = re.sub(r'```.*?```', '', doc.read_text(encoding='utf-8'), flags=re.S)
        targets = re.findall(r'!?\[[^\]]*\]\(([^\s)]+)(?:\s+"[^"]*")?\)', source)
        targets += re.findall(r'<img\b[^>]*\bsrc="([^"]+)"', source)
        for target in targets:
            target = target.strip('<>')
            if target.startswith(('#', '//')) or re.match(r'[a-zA-Z][a-zA-Z0-9+.-]*:', target):
                continue
            path = unquote(target.split('#')[0].split('?')[0])
            if path and not (doc.parent / path).exists():
                errors.append(f'{doc.relative_to(root)}: missing local target {path}')
    for asset in (root / 'docs' / 'images').glob('*.svg'):
        try:
            tree = ET.parse(asset)
            if tree.getroot().tag != '{http://www.w3.org/2000/svg}svg':
                errors.append(f'{asset.relative_to(root)}: not an SVG')
        except ET.ParseError as exc:
            errors.append(f'{asset.relative_to(root)}: {exc}')
    return errors


def review_changes(paths, decision):
    implementation = [p for p in paths if p.startswith(('app/', 'common/', 'quran/', 'pdf/', 'files/', 'tools/', '.github/workflows/'))
                      or p.endswith(('.gradle.kts', '.gradle', '.properties'))]
    documentation = [p for p in paths if p == 'README.md' or p.startswith('docs/') and p.endswith(('.md', '.svg', '.png', '.jpg', '.webp'))]
    # A checkbox alone cannot stand in for an explanation.
    exemption = re.search(r'^README review:\s*not needed\s*(?:—|–|:|-)\s*(\S[^\n]{14,})\s*$', decision, re.M | re.I)
    if implementation and not documentation and not exemption:
        return implementation, documentation, ['Implementation changed without reader documentation. Update README/docs or explain: README review: not needed — <specific reason of at least 15 characters>.']
    return implementation, documentation, []


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--write', action='store_true', help='Refresh the generated app-version table')
    mode.add_argument('--check', action='store_true', help='Validate without modifying files (default)')
    parser.add_argument('--base', help='Base commit for reviewing changed files; defaults to content-only checks')
    parser.add_argument('--summary', type=Path, help='Write the Markdown review report')
    args = parser.parse_args()
    readme = ROOT / 'README.md'
    errors = []
    try:
        old = readme.read_text(encoding='utf-8')
        new = refresh(old, generated_versions(ROOT))
        if args.write:
            readme.write_text(new, encoding='utf-8')
        elif new != old:
            errors.append('App version table is stale. Run python3 tools/readme_review.py --write')
        errors += local_link_errors(ROOT)
        changed = []
        implementation, documentation = [], []
        if args.base:
            # Events supply SHAs; resolving to a commit also handles local branch refs.
            base = git('rev-parse', '--verify', args.base + '^{commit}')
            changed = git('diff', '--name-only', base, 'HEAD', '--').splitlines()
            decision = os.environ.get('README_REVIEW_DECISION', '')
            if os.environ.get('GITHUB_EVENT_NAME') != 'pull_request':
                decision += '\n' + git('log', '--format=%B', base + '..HEAD', '--')
            implementation, documentation, change_errors = review_changes(changed, decision)
            errors += change_errors
        report = ['# README review', '', '**Result: ' + ('needs attention' if errors else 'passed') + '**', '',
                  'Checked source app versions, local Markdown targets and SVG syntax.',
                  'This is a documentation coverage check, not a semantic review of feature descriptions.', '']
        if args.base:
            report += [f'Implementation files changed: {len(implementation)}. Documentation files changed: {len(documentation)}.', '', '## Changed files', '']
            # Encode filenames so Markdown/control characters cannot inject report content.
            report += ['- ' + json.dumps(p, ensure_ascii=True) for p in changed]
        else:
            report += ['No base commit supplied; changed-file coverage was not evaluated.']
        if errors:
            report += ['', '## Action needed', ''] + ['- ' + e for e in errors]
        else:
            report += ['', 'For new user-facing behavior, also review feature wording, setup steps and illustrations.']
        output = '\n'.join(report) + '\n'
        if args.summary:
            args.summary.parent.mkdir(parents=True, exist_ok=True)
            args.summary.write_text(output, encoding='utf-8')
        print(output)
        return bool(errors)
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        print(f'README review failed: {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
