---
name: mdp-module-history
description: Update module-level agent/history.md with reusable, high-signal feature history entries and retention rules.
---

# Module History

Update `agent/history.md` in the **primary module** for the implemented feature.

## Inputs Required

- Feature name
- Feature size (`SMALL` / `MEDIUM` / `LARGE`)
- Primary module (main module where the feature lives)
- Affected modules list
- Feature flag name/pattern (or `N/A`)
- Acceptance criteria coverage (`implemented/total`)
- Change summary (what changed, patterns used, reusable components, breaking changes/limits)

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
Modules: <list of affected modules>
- <what changed> (1-2 lines each)
- <new patterns introduced or followed>
- <reusable components created> (mark with "reusable")
- <breaking changes or known limitations>
Feature flag: <name and pattern, or N/A>
Acceptance criteria: <count>/<count> implemented
```

## File Rules

- File path: `<primary-module>/agent/history.md`
- Newest entry first
- Max **15 lines** per entry
- Keep only the **last 5 entries** (remove oldest when adding 6th)
- No code snippets; focus on reusable context for future feature work

## Output

Report one concise result:
- Written or skipped
- Target file path
- Top bullets included (if written)
