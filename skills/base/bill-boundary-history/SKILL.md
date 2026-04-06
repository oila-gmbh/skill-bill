---
name: bill-boundary-history
description: Use when updating module/package/area agent/history.md files with reusable, high-signal feature history entries and history hygiene rules. Use when user mentions update history, write history entry, boundary history, or record feature history.
---

# Boundary History

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-boundary-history` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Inputs Required

- Feature name
- Feature size (`SMALL` / `MEDIUM` / `LARGE`)
- Primary module/package/area (main boundary where the feature lives)
- Affected module/package/area list
- Feature flag name/pattern (or `N/A`)
- Acceptance criteria coverage (`implemented/total`)
- Change summary (what changed, patterns used, reusable components, breaking changes/limits)

## Input Recovery

- If the caller omits part of the context, derive only the missing pieces from the current diff and `.feature-specs/<feature-name>/spec.md` when available
- Do not skip writing solely because the caller forgot to pass a change summary

## Write/Skip Rules

- **Always write** for `MEDIUM` and `LARGE` features.
- For `SMALL`, write only if any applies:
  - Analytics events added/removed/changed (including properties)
  - API contracts or GraphQL schema usage changed
  - UI behavior changed in ways that affect other features
  - Breaking changes to shared interfaces/contracts
- Skip only for trivial `SMALL` changes (pure bug fixes, cosmetic tweaks, isolated additions).

## Entry Format

```markdown
## [<date>] <feature-name>
Areas: <list of affected modules/packages/areas>
- <what changed> (1-2 lines each)
- <new patterns introduced or followed>
- <reusable components created> (mark with "reusable")
- <breaking changes or known limitations>
Feature flag: <name and pattern, or N/A>
Acceptance criteria: <count>/<count> implemented
```

## File Rules

- File path: `<primary-boundary>/agent/history.md`
- If the file does not exist, create it along with any missing parent directories
- Newest entry first
- Max **15 lines** per entry
- No fixed entry cap
- Keep older entries when they still provide reusable context; prune or merge only entries that are obsolete, redundant, or too noisy to help future feature work
- No code snippets; focus on reusable context for future feature work

## Output

Report one concise result:
- Written or skipped
- Target file path
- Top bullets included (if written)
