# SahlTV Launcher contributor instructions

When changing a feature, default setting, menu path, permission, installation step or companion-app behavior, review `README.md` and update the affected user-facing documentation in the same change. Update `docs/REFERENCE.md` for detailed behavior and setup. Review `docs/images/` when layout or visual behavior changes; illustrations must stay labeled as illustrations.

Run `python3 tools/readme_review.py --write`, then `python3 tools/readme_review.py --check` and `python3 -m unittest discover -s tools -p 'test_readme_review.py'`. Commit generated version-table updates alongside module version changes. Follow the existing build and regression checks for implementation changes.

For internal changes without reader impact, record a specific reason in the PR description as `README review: not needed — <reason of at least 15 characters>`. Do not use that exception for new features. See `docs/DOCUMENTATION.md` for the automated review policy and its limits.
