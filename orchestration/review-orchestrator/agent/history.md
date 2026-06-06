## [2026-06-06] SKILL-69 review-noise-tuning-and-validation-gate
Areas: orchestration/review-orchestrator, docs/review-telemetry.md
- Shared review orchestrator guidance now narrows `Minor` and `Medium`/`Low` confidence findings to explicit contract violations, user-visible bugs, regression risks, quality gate failures, or persisted learnings. reusable
- The same threshold is duplicated in the specialist contract so delegated reviewers inherit the low-value finding filter without weakening actionable evidence requirements. reusable
- Severity preservation rule: evidence-backed `Blocker`/`Major` findings and concrete correctness, security, persistence, lifecycle, testing, accessibility, and contract defects remain reportable.
- Review telemetry docs now spell out the issue category taxonomy: behavior correctness, data persistence, concurrency/lifecycle, UX/accessibility, testing/quality gate, security/privacy, docs/contract, and other.
Feature flag: N/A
Acceptance criteria: 5/5 implemented
