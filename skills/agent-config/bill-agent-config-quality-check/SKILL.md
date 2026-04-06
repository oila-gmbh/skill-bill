---
name: bill-agent-config-quality-check
description: Run the repository's canonical validation commands for governed skill and agent-configuration repositories, then fix issues in the current unit of work without using suppressions. Use when validating skill contracts, routing docs, installers, catalogs, and repo-native validator scripts. Use when user mentions validate skills, validate agent config, run skill validation, or check skill contracts.
---

# Agent-Config Repository Quality Check

This is the current `agent-config` implementation behind the shared `bill-quality-check` router. Invoke it directly only when you already know the repo should use the `agent-config` quality-check path.

Execute the repository's validation flow and systematically fix issues **only in files changed in the current unit of work**. Ignore pre-existing issues in untouched files unless the change clearly exposes them.

## Execution Steps

1. **Determine changed files**: Use `git diff --name-only` (against the base branch or HEAD) to identify files changed in the current unit of work
2. **Run initial validation**: Execute the repository's canonical validation commands and capture complete output
3. **Filter to changed files**: From the command output, only address issues in files from step 1 — skip everything else
4. **Categorize issues**: Group by type (skill contract drift, validator/test failures, installer/script failures, docs/catalog mismatch, formatting/lint failures, etc.)
5. **Fix systematically**: For each issue category in priority order:
   - Mark todo as in_progress
   - Read affected files
   - Implement proper fixes (never suppress)
   - Mark todo as completed
6. **Verify fixes**: Re-run the validation commands after all fixes
7. **Iterate if needed**: If new issues appear, repeat the process

## Fix Strategy

**Always Fix, Never Suppress:**
- Never add suppressions, ignore rules, or fake passing output as the default fix
- Never weaken validator rules just to make the repository pass
- Never skip required project scripts silently
- Implement proper solutions that address the root cause
- Keep docs, tests, installer behavior, and catalogs aligned with the actual repository contract

**Priority Order:**
1. Structural repository contract issues (broken skill references, invalid package names, missing required sidecars, install/uninstall breakage)
2. Validation script or test failures
3. README/catalog/docs mismatch against the actual repository behavior
4. Formatting or lint failures
5. Remaining repo-native quality issues surfaced by the configured validation commands

**Typical Commands In This Repo Type:**
- `python3 -m unittest discover -s tests`
- `npx --yes agnix --strict .`
- `python3 scripts/validate_agent_configs.py`

If the current repository defines different repo-native validator commands, use those instead of hardcoding the examples above.

## When to Ask User

- Taxonomy decisions that materially change the package model
- Breaking changes to stable user-facing command names
- Cases where multiple valid governance directions exist and the repository guidance does not decide between them

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-agent-config-quality-check` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Agent-Config-Specific Guidance

- Prefer the repository's existing validation commands over ad hoc checks
- Treat catalog drift, installer behavior drift, and validator/test drift as first-class quality issues
- When a change affects runtime-facing skills, ensure supporting-file references, README catalog entries, installer behavior, and validation tests stay consistent in the same unit of work
- If a required command cannot be run, report that explicitly with the reason

## Output Format

Provide clear progress updates:
- Show issue count by category
- Report each fix with file path and line number
- Display the final validation result
- Summarize all changes made
- If a required command could not be run, report that explicitly with the reason
