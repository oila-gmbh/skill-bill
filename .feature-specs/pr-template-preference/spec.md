# Feature: pr-template-preference
Created: 2026-04-02
Status: In Progress
Sources: chat discussion for SKILL-2 about preferring repo-native PR templates in `bill-pr-description`

## Acceptance Criteria
1. `bill-pr-description` checks whether the target repository has its own PR description template before using Skill Bill's built-in template.
2. If a single default repo-native template exists, the skill uses that template shape first.
3. If no repo-native template exists, the skill falls back to the current built-in Skill Bill PR description template.
4. If multiple repo-native templates exist and there is no obvious default, the skill does not guess silently; it asks the user which template to use.
5. The skill documentation and tests are updated to describe and enforce this behavior.

## Non-Goals
- Changing the canonical built-in fallback template
- Redesigning GitHub's template semantics
- Adding repo-specific hardcoded paths beyond standard PR template locations

## Open Questions
None

---

## Consolidated Spec

Update `bill-pr-description` so it first checks whether the target repository already defines a PR description template. If a clear repo-native template exists, the skill should use that template structure instead of always emitting Skill Bill's built-in PR description format.

The preferred search space should stay within standard repository template locations such as `.github/pull_request_template.md`, `.github/PULL_REQUEST_TEMPLATE.md`, root-level `pull_request_template.md`, root-level `PULL_REQUEST_TEMPLATE.md`, and `.github/PULL_REQUEST_TEMPLATE/*.md`.

If there is no repo-native template, the current built-in Skill Bill PR description template remains the fallback and should continue to work as it does today.

If multiple repo-native templates exist and there is no clear default, the skill should not silently pick one. It should ask the user which template to use so the output remains faithful to the repository's actual process instead of inventing a heuristic that may be wrong.

The skill instructions and repository tests should be updated so this behavior is explicit, documented, and resistant to regression.
