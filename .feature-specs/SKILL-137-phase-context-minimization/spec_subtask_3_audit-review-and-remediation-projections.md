# SKILL-137 Subtask 3 - Audit, Review, and Remediation Projections

Parent spec: [.feature-specs/SKILL-137-phase-context-minimization/spec.md](./spec.md)
Issue key: SKILL-137

## Scope

Minimize context on the audit-to-review forward edge and both audit/review backward edges. Define compact audit clearance, audit repair request/state, review request, review receipt, and Blocker-only review repair request contracts while preserving audit-first ordering, stable repair ids, non-progress detection, review scope, and the two-pass cap.

## Acceptance Criteria

1. A satisfied audit emits a compact clearance containing verdict, closed criterion refs, audited repository checkpoint, audit iteration, and no raw per-criterion reasoning or evidence body.
2. `review` receives acceptance criteria, review policy/pass metadata, exact immutable review scope/checkpoint, applicable review add-ons/rubrics, and audit clearance only as a gate attestation.
3. Review does not receive the implementation phase response, implementation receipt, executable plan, audit report, audit repair history, validation strategy, or telemetry.
4. Review independently inspects the authoritative diff at the declared scope and emits a typed review receipt containing verdict, bounded normalized findings, reviewed checkpoint, pass number/mode, and compact counts.
5. Review findings use stable ids, exact allowed severities/categories, normalized locations, concise problem/expected-outcome fields, and optional task/criterion references. Raw specialist narratives and complete lane outputs remain private evidence.
6. `review -> implement_fix` projects only unresolved Blocker findings, their stable ids/locations/expected outcomes, relevant task/criterion references, and the reviewed checkpoint.
7. Major, Minor, Nit, approved, addressed, capped, and unrelated findings are absent from the implement-fix prompt, while remaining durable and retrievable through their existing evidence surfaces.
8. Implement-fix receives current repository state and the Blocker repair request; it does not receive the complete initial plan or implementation output unless a referenced affected task commitment is explicitly projected.
9. Implement-fix emits an incremental change receipt and per-finding terminal outcomes tied one-to-one to carried finding ids, with reconciliation and executed-verification evidence.
10. Review pass two receives the same immutable base/current-delta policy and only the remediation-relevant checkpoint/state required by the pass contract. It does not inherit pass-one raw output.
11. A `gaps_found` audit emits a complete typed repair request with stable gap and repair-item ids, criterion references, affected production boundaries, dependencies, expected outcomes, and evidence references.
12. `audit -> implement` remediation receives the immutable executable plan or affected plan commitments, typed repair request, prior terminal repair outcomes required for idempotency, unresolved gap ledger, and current repository checkpoint.
13. Audit remediation excludes the preplan digest, full audit response, settled criteria, review state, validation state, and generic implementation narration.
14. Follow-up audit receives remaining criteria, latest implementation receipt, prior-gap dispositions required by the non-progress contract, and refreshed repository evidence. It does not receive superseded audit/implementation envelopes.
15. Latest-iteration selection is projection-specific and checkpoint-aware across both loops. A crash/resume never uses an older repair plan, review finding set, receipt, or sibling context.
16. Existing audit stable-id validation, dependency ordering, exhaustive terminal-result reconciliation, recurring/new gap classification, and review two-pass accounting remain enforced.
17. Tests assert both required and forbidden context on initial audit, follow-up audit, audit remediation, initial review, review remediation, and second review.

## Non-Goals

- Changing audit test-exclusion policy or review severity disposition.
- Changing the review two-pass cap or audit non-progress policy.
- Implementing validation/finalization projections.
- Removing full review/audit evidence from private diagnostic or findings storage.

## Dependency Notes

Depends on: 2.

Relies on the executable plan, implementation receipt, plan commitment, and repository checkpoint behavior delivered by subtask 2.

## Validation Strategy

- Forward-edge audit-clearance and review-request snapshots.
- Blocker-only finding projection acceptance/rejection tests.
- Audit repair request and terminal-result reconciliation tests.
- Review-pass and audit-gap crash/resume/latest-iteration tests.
- Negative prompt assertions for raw reports, non-blocking findings, settled criteria, telemetry, and sibling context.
- Standalone and goal-child parity tests.

## Next Path

Continue with subtask 4 after this subtask commits.

## Spec Path

.feature-specs/SKILL-137-phase-context-minimization/spec_subtask_3_audit-review-and-remediation-projections.md
