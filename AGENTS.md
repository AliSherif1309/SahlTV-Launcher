# SahlTV Launcher contributor instructions

When changing a feature, default setting, menu path, permission, installation step or companion-app behavior, review `README.md` and update the affected user-facing documentation in the same change. Update `docs/REFERENCE.md` for detailed behavior and setup. Review `docs/images/` when layout or visual behavior changes; illustrations must stay labeled as illustrations.

Run `python3 tools/readme_review.py --write`, then `python3 tools/readme_review.py --check` and `python3 -m unittest discover -s tools -p 'test_readme_review.py'`. Commit generated version-table updates alongside module version changes. Follow the existing build and regression checks for implementation changes.

For internal changes without reader impact, record a specific reason in the PR description as `README review: not needed — <reason of at least 15 characters>`. Do not use that exception for new features. See `docs/DOCUMENTATION.md` for the automated review policy and its limits.

## GitHub workflow

- Across all chats in this project, when pushing changes to GitHub, automatically push a branch, open a pull request, and merge it into `main` without asking for confirmation (never push directly to `main`).

# Planning before editing

- For any task that modifies code or files (unless I say "skip plan"):
  - Inspect the relevant files first without editing anything.
  - Output a concise plan (goal, exact files to change, risks, and test steps).
  - End with "Plan ready. Reply 'Proceed' to execute." and STOP.
  - Only edit files and run tests after I reply "Proceed".
