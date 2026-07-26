# Feature: go-platform-skills
Created: 2026-03-28
Status: In Progress
Sources: User request in chat, confirmed to mirror the PHP platform shape for Go; Jira issue key BILL-0000; Go references: Effective Go, Go Code Review Comments, Google Go Style docs

## Acceptance Criteria
1. Add a first-class Go platform package under `skills/go/` using the repo's approved naming rules.
2. Add base-facing Go overrides for shared entry points, including `bill-go-code-review` and `bill-go-quality-check`.
3. Add approved Go `code-review` specialist skills mirroring the governed taxonomy used for other platforms.
4. Update routing and validation so the repo recognizes Go signals, allows the `go` package, and routes shared skills to Go when appropriate.
5. Update installer/docs/catalogs so Go appears as a selectable platform and README counts stay accurate.
6. Add or update tests covering validator acceptance/rejection, routing contracts, and any installer behavior affected by the new platform.
7. Run the repo validation suite and leave the repo in a merge-ready state.

---

Add Go platform skills to this governed skill repository, using the existing PHP platform package as the structural template while adapting the content to Go projects. Use the canonical package name `go` so the resulting taxonomy follows the repo's established `bill-<platform>-<capability>` and `bill-<platform>-code-review-<area>` rules.

The new platform should be first-class everywhere the repo currently models supported stacks:

- `skills/go/` should contain the platform-owned installable skills
- shared routing should classify Go repos and route to the correct Go implementations
- validation should allow the package and enforce the same rules already applied to Kotlin/KMP/backend-kotlin/PHP
- documentation and installer output should expose Go as a supported platform
- tests should cover both acceptance and rejection behavior for the new package and routing

Non-goals:

- Creating new taxonomy shapes outside the existing platform model
- Adding unapproved `code-review` specialization names
- Building or testing external Go applications outside this repository
