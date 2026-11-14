# Documentation maintenance

[← README](../README.md)

## What runs automatically

The **README review** workflow runs for every pull request, relevant PR-description edit, every push to `main`, and manual dispatch. No API key, model service or write token is needed. It uses `pull_request`, never `pull_request_target`, and runs with read-only repository permissions; fork PRs do not receive secrets.

It checks:

1. The README app-version table against every Android application module's `build.gradle.kts` (including newly added modules).
2. Local Markdown link/image file targets in README and `docs/`, plus SVG syntax. Remote URLs and heading fragments are not checked.
3. Changed implementation/build/workflow/tool files against documentation changes. A code change needs a README/docs update or an explicit decision explaining why readers are unaffected.

The Actions summary lists changed files and the result. Its `readme-review` artifact contains the report and a patch for generated version-table updates. Apply that patch locally with `git apply readme-generated.patch`, or run `python3 tools/readme_review.py --write`, then commit the result. The workflow never pushes to your branch or merges changes.

## The human review

A deterministic check cannot know whether a description accurately explains a new feature. Before merging, verify feature wording, default settings, menu paths, permissions, limitations, downloads and illustrations against the implementation. A documentation file change satisfies coverage; it does not prove the prose is complete.

For user-facing changes, update [README](../README.md) and the relevant [reference section](REFERENCE.md). The SVGs in `images/` are labeled illustrations, not real screenshots. Replace or supplement them with actual TV captures when available; do not present concept art as runtime output.

For internal work with no reader impact, use a standalone line in the PR description:

```text
README review: not needed — Refactors internal helpers without changing settings, behavior or installation.
```

Direct pushes can put this line in a commit message. A reason must contain at least 15 characters. PR descriptions are passed as data through environment variables and never evaluated as shell commands. After changing a PR description, the `edited` event rechecks the decision.

## Commands

```sh
python3 tools/readme_review.py --write
python3 tools/readme_review.py --check
python3 tools/readme_review.py --check --base origin/main --summary /tmp/readme-review.md
python3 -m unittest discover -s tools -p 'test_readme_review.py'
```

`--base` compares committed files through `HEAD`; commit your edits before using it. Content-only `--check` also works on uncommitted edits. Manual workflow runs perform content checks without a change base.

## Enforcement

The workflow reports failures for stale versions, missing local files and undocumented implementation changes. To make its success mandatory before merging, add **review** from the **README review** workflow to the `main` branch ruleset's required status checks. That setting is separate from committing a workflow. A push check reports issues after a direct push and cannot prevent that push. Existing release gates are unchanged.

## Visual asset editing

`images/home-overview.svg` maps the main features; `images/layout-guide.svg` explains independent tile/card sizing and row ordering. Keep readable text, SVG titles/descriptions, and explicit illustration captions. Do not include raw Thmanyah fonts in images or the repository. Version and download metadata stays in the generated table so illustrations do not drift on every version bump.
