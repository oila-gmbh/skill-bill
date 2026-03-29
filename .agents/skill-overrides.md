# Skill Overrides

This file contains project-level overrides for installed skills.
Each section heading matches the skill name it overrides. Skills read this file at runtime and apply the matching section as highest-priority instructions, above AGENTS.md and built-in defaults.

---

## bill-go-code-review

- Do **not** use `bill-go-code-review-testing` as a specialist review pass. This skill is not installed in this project.
- Do **not** use `bill-unit-test-value-check`. This skill is not installed in this project.
- Omit the testing row from the routing table when selecting specialist reviews.
- Do not add `bill-go-code-review-testing` or `bill-unit-test-value-check` to the specialist list even when test files change materially.
- Do not include "If tests changed materially, include `bill-go-code-review-testing`" in Step 4.
