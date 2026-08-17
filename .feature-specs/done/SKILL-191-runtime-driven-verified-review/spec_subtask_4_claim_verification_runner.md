# SKILL-191 · Subtask 4 — Stage 1, claim verification runner

## Scope

Add the runtime-owned claim-verification stage that runs after the review pass
reaches a terminal state, in both `inline` and `delegated`.

Build `ReviewClaimVerificationRunner` on the shape `ReviewIntegrationPassRunner`
already establishes: the runtime compiles bounded launches, launches workers through
the existing `AgentRunLauncher` path, validates each result against the contract, and
records a durable boundary distinct from the stage before it.

**One finding per worker.** A worker handed the whole register calibrates to an
expected refutation rate and clears findings to meet it. Per-finding isolation is a
correctness property, not a budgeting choice.

**Launch inputs** are the finding verbatim, the cited region, and the delta
reference. Not included: the spec projection — `verification_launch` admits no such
field, so this is enforced by schema — the reviewing worker's narrative, the parent
transcript, or sibling findings.

**Verdict admission.** The runner accepts `confirmed`, `refuted`, or `unresolved`.
A `refuted` verdict requires a `file:line` citation for the construct that makes the
code safe. A verdict claiming refutation without one is not an error that fails the
stage — it is **recorded as `unresolved`** with the rejection reason, so a worker
cannot clear a finding by asserting. `unresolved` is the default for anything the
worker cannot settle.

**Verdicts append.** The runner never edits a finding's text, severity, or location.
A result that returns altered finding text is rejected; the finding is immutable from
the moment the review pass emits it.

**Depth varies by tier, admission does not.** An inline verifier's evidence surface
is the cited region and direct callers. A delegated verifier may expand through the
existing bounded broker and expansion ledger. Both run every finding through the same
admission rules.

A worker that fails to launch or return leaves its finding `unresolved` with the
failure reason recorded. The stage does not silently drop a finding, and a launch
failure does not fail the review.

## Acceptance Criteria

1. The stage runs after the review pass reaches a terminal state in both `inline` and `delegated`, over every finding the pass emitted at every severity.
2. Each finding is verified in its own worker context; no launch carries more than one finding or any sibling finding.
3. A verification launch carries no spec intent projection, no reviewer narrative, and no parent transcript, and a payload attempting to carry a spec field is rejected by schema validation.
4. A returned `refuted` verdict with no `file:line` citation is recorded as `unresolved` with the rejection reason, and the finding stays in the register.
5. A returned result that alters the finding's text, severity, or location is rejected, and the original claim is preserved verbatim.
6. An unsettled finding is recorded `unresolved`, never `refuted`.
7. Inline verifiers are bounded to the cited region and direct callers; delegated verifiers may expand evidence through the existing broker and expansion ledger.
8. A worker that fails to launch or return leaves its finding `unresolved` with the failure reason recorded, and the review still completes.
9. Every verdict is written through subtask 2's durable storage, and the verification stage boundary is recorded independently of the review-pass and integration boundaries.

## Non-Goals

- Weighing findings against a spec; subtask 5 owns that.
- Assembling or emitting the register; subtask 6 owns it.
- Telemetry and refutation-rate measurement; subtask 7 owns it.
- Introducing a new agent launch mechanism, or any `claude -p` path.
- Building, compiling, or running tests as verification evidence.

## Dependency Notes

Depends on subtask 1 for `verification_launch` and `finding_verdict`, and subtask 2
for verdict persistence and the stage boundary.

## Validation Strategy

- One test per admission rule: uncited refutation recorded as `unresolved` (criterion 4),
  altered-finding result rejected (criterion 5), unsettled recorded as `unresolved`
  (criterion 6). These are the rules that stop a verifier laundering findings, and each
  is a distinct realistic failure.
- One test asserting per-finding isolation at the launch boundary, since batching
  reintroduces the quota effect this design exists to prevent.
- One test that a launch failure leaves the finding `unresolved` and the review
  completes.
- No test asserting call ordering or duplicating the runner's internal steps.

## Next Path

Subtask 5 — spec adjudication runner.
