---
name: bill-feature-guard
description: Enable feature flag mode - all code changes will be guarded by feature flags for safe rollback. Use when implementing new features that need gradual rollout, A/B testing, or safe rollback capability. Applies the Legacy pattern for large changes, factory/DI switching for medium changes, and simple conditionals for small changes. Use when user mentions feature flag, feature toggle, gradual rollout, safe rollback, or guard with flag.
---

# Feature Guard Mode

## Project Overrides

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).

If `.agents/skill-overrides.md` exists in the project root and contains a matching section, read that section and apply it as the highest-priority instruction for this skill.

## Core Principles

**North Star Goal:** Single feature flag check to switch between old and new execution paths. Minimize flag usage by structuring code cohesively.

**Rollback Guarantee:** When the feature flag is OFF, the application MUST behave exactly as it did before any changes.

**Cohesive New Code:** Avoid sprinkling `if (featureEnabled)` checks throughout the codebase. Structure changes so flag decisions happen at the highest practical level.

## Implementation Strategy

### Step 1: Identify Scope
1. What components/files will be affected?
2. Can changes be isolated to a single entry point?
3. What is the minimum number of feature flag checks needed?

### Step 2: Choose Pattern Based on Change Size

- **Small** (1-2 files): simple conditional at the call site
- **Medium** (refactoring a component): new implementation alongside old, single switch point
- **Large** (multiple files, architectural): Legacy Pattern — rename to `*Legacy`, create new, single flag check at routing level

See [patterns.md](patterns.md) for code examples and anti-patterns (DO/DON'T).

### Step 3: Feature Flag Setup

1. **Naming**: Follow project conventions (e.g., `feature-[name]`, `[platform]-[name]`)
2. **Default**: Always `false` (disabled) for new features
3. **Type**: REMOTE for production rollouts, LOCAL for dev/testing
4. **Documentation**: Add clear description of what the flag controls

## Checklist

- [ ] Can I isolate changes to minimize feature flag checks?
- [ ] Is the legacy path completely preserved?
- [ ] When flag is OFF, is behavior 100% identical to before?
- [ ] Are feature flag checks at the highest practical level?
- [ ] Is new code cohesive and self-contained?

## When to Ask User

1. **Feature flag name**: What should this feature flag be called?
2. **Scope clarification**: If changes span many files, confirm the Legacy pattern approach
3. **Existing flags**: Is there an existing flag that should be reused?

## Session Behavior

For the remainder of this session:
1. Every code change proposal MUST include feature flag strategy
2. Show where the feature flag check(s) will be placed
3. Identify what becomes Legacy vs New
4. Confirm rollback safety before implementing
5. Create/update feature flag definition in the codebase
