# SKILL-191 · Subtask 5 — Stage 2, spec adjudication runner

## Scope

Add the runtime-owned adjudication stage that weighs surviving findings against the
resolved spec intent projection.

**Entry conditions.** The stage runs only on findings stage 1 recorded `confirmed` or
`unresolved`, and only when subtask 3 resolved a spec intent projection. A refuted
finding is settled and is not re-litigated. No spec projection means the stage is
skipped with its `spec_context: none` reason recorded and the run completes normally.

**Dispositions.** Each adjudicated finding receives one of `in_scope`,
`out_of_scope_preexisting`, `spec_deviation`, or `spec_accepted_tradeoff`.
`spec_deviation` is the finding class review cannot currently produce: code that
contradicts a stated constraint or non-goal. Inside feature-task it stays distinct
from the audit phase, which owns "criterion unsatisfied"; this stage owns "the code
contradicts what the spec says".

**Bidirectional.** The stage may raise severity as well as lower it. A finding that
endangers a stated criterion, or that constitutes a `spec_deviation`, may be adjusted
upward. A stage that can only downgrade is a filter with a better name and will drift
toward clearing the register.

**A spec is not a defect waiver.** Lowering severity or assigning
`out_of_scope_preexisting` requires a citation of the spec element — non-goal,
constraint, deferred item — that justifies it. An uncited downgrade is recorded as
`in_scope` with the rejection reason, so the finding survives. Raising severity
requires the same citation discipline. This mirrors subtask 4: the cheap direction
must cost the same as the expensive one.

**Verdicts append.** As in stage 1, the runner never edits finding text, severity, or
location in place. A severity adjustment is a recorded delta with a direction and a
justification beside the original claim, not an overwrite.

One finding per worker, for the same reason as stage 1. Launch inputs are the
surviving finding with its stage 1 verdict, the spec intent projection, and the cited
region.

## Acceptance Criteria

1. The stage runs only on findings recorded `confirmed` or `unresolved` by stage 1, and never on a `refuted` finding.
2. The stage runs only when a spec intent projection resolved; with no projection it is skipped, its `spec_context: none` reason is recorded, and the run completes normally.
3. Each adjudicated finding receives exactly one of `in_scope`, `out_of_scope_preexisting`, `spec_deviation`, or `spec_accepted_tradeoff`.
4. The stage can raise severity as well as lower it, and an upward adjustment is recorded with the same structure as a downward one.
5. A severity downgrade or an `out_of_scope_preexisting` disposition without a citation of the justifying spec element is recorded as `in_scope` with the rejection reason, and the finding survives at its original severity.
6. A severity adjustment is recorded as a delta with direction and justification beside the preserved original claim; no finding text, severity, or location is overwritten.
7. Each finding is adjudicated in its own worker context, and no launch carries a sibling finding.
8. `spec_deviation` is assigned only when the finding contradicts a constraint or non-goal present in the projection, cited by spec element.
9. Every disposition is written through subtask 2's durable storage, and the adjudication stage boundary is recorded independently of the verification boundary.

## Non-Goals

- Re-testing whether a finding's claim is true; stage 1 settled that.
- Detecting unsatisfied acceptance criteria — the feature-task audit phase owns that,
  and duplicating it here would create two authorities over criterion status.
- Assembling the register or emitting telemetry; subtasks 6 and 7 own those.
- Resolving or repairing specs; subtask 3 owns resolution.

## Dependency Notes

Depends on subtask 1 for `adjudication_launch` and `finding_verdict`, subtask 2 for
persistence and the boundary, subtask 3 for the projection, and subtask 4 for the
stage 1 verdicts that gate entry.

## Validation Strategy

- One test that a refuted finding is not adjudicated, since re-litigating settled
  findings is how a two-stage design collapses back into one.
- One test that no projection skips the stage and completes the run.
- One test that an uncited downgrade is recorded `in_scope` and the finding survives —
  the rule that keeps the stage from becoming a filter.
- One test that an upward adjustment is recorded, proving the stage is bidirectional.
- One test that the original claim survives a severity adjustment unmodified.

## Next Path

Subtask 6 — verdict-aware register assembly and downstream consumers.
