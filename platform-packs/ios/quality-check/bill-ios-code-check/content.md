---
name: bill-ios-code-check
description: Use when validating iOS changes with the shared quality-check contract.
---

# Quality-Check Content

## Purpose

Use when validating iOS changes with the shared quality-check contract.

## Execution Steps

1. Determine the files in scope for the current unit of work.
2. Run the platform's quality-check entrypoint and capture the failures.
3. Fix only the failures that belong to the scoped work unless the contract says otherwise.
4. Re-run the quality check until the scoped failures are resolved.

## Fix Strategy

- Prefer root-cause fixes over suppressions or TODO comments.
- Keep changes aligned with the project's existing conventions and build tooling.
- Call out any blocker that requires a maintainer decision instead of guessing.
