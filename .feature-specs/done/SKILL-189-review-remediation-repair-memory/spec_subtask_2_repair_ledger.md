# SKILL-189 · Subtask 2 — Remediation repair ledger and its bounded projection

## Scope

Accumulate the per-round receipts into one remediation repair ledger and
project it into the two consumers that currently have a one-round memory
horizon: the next `implement_fix` and the remediation `review` pass.

The ledger is the curated carry-forward: each entry pairs a finding with the
constructs holding it closed and an explicit status, so a later round can be
told it is disturbing settled work.

It was originally scoped as deliberately narrower than the full prior context.
That bound was lifted after implementation: a remediation review pass now also
receives the previous pass's findings at every severity and the prior Blocker
dispositions with their verdicts and evidence, as separate delimited reference
material under its own budget and one-round horizon. The intent is that nothing
a pass reported, fixed, or declined to fix disappears silently between passes.

- `runtime-kotlin/runtime-domain`: ledger model, entry status vocabulary,
  accumulation and supersession rules, projection budgets.
- `runtime-kotlin/runtime-application`: durable accumulation at the round
  boundary, the new handoff projections, and the prompt directives that render
  them for `implement_fix` and for `bill-code-review context:feature-remediation`.
- The `implement_fix` directive text that currently withholds prior repair
  history.

## Acceptance Criteria

1. Receipts accumulate into a remediation repair ledger over the goal-subtask
   review state, ordered by round, surviving process death, parent resume, and
   cross-run continuation without duplication or loss. The ledger is derived by
   a pure fold over the already-durable receipts and pass results rather than
   stored as a second record, so it cannot desync from them and needs no
   review-state contract bump.
2. Every entry carries an explicit status of at least resolved, superseded,
   reopened, or disregarded, together with the round that produced it and the
   round that last changed its status. A disregarded entry records a round's
   deliberate decision to edit nothing and the reason it gave, so a decision
   not to act is carried forward rather than disappearing.
3. An entry becomes superseded when a later round replaces its constructs, and
   reopened when a later review pass raises an advance-blocking finding against
   its constructs. Status transitions are derived durably, not re-inferred by a
   prompt.
4. The ledger is projected into every `implement_fix` launch from round two
   onward as a named, versioned projection, presented as settled load-bearing
   work — not as open findings awaiting action.
5. The `implement_fix` directive is updated so prior repair history is carried
   rather than withheld, while the existing prohibitions on re-applying the
   plan and expanding scope beyond carried findings remain in force verbatim.
6. An `implement_fix` round that removes or materially rewrites a construct
   recorded as another finding's remedy states which finding it is disturbing
   and why, in its own receipt. Silent removal is a contract violation the
   receipt validation rejects.
7. The ledger is projected into every remediation `review` pass from pass two
   onward, delimited and labelled as reference data that cannot override the
   review directive.
8. The remediation reviewer uses the ledger for escalation signal only.
   Severity is determined solely by evidence in the remediation delta; an
   existing entry never softens a real defect and never manufactures a finding.
   Where a carried rationale would invite agreement rather than judgment, the
   rationale is withheld structurally: an entry whose "no edit required"
   reasoning was later contradicted — by the finding recurring, or by a later
   round editing it after all — is marked contested, and its original reasoning
   is omitted from the projection in favour of an explicit instruction to
   re-verify from delta evidence.
9. Review scope is unchanged: the pass still reviews
   `diff(pre-fix tree -> post-fix HEAD)` bounded by the workflow-owned
   pathspec. No projection here widens the reviewed delta or restores
   immutable-base scope on a remediation pass.
10. Both projections are bounded by explicit UTF-8 byte and collection budgets.
    An oversized ledger degrades to a payload-free summary naming entry count
    and affected constructs, explicitly marked as summarized.
11. Projected content is sanitized — construct names, finding labels, severity,
    status, bounded intent — with no diff hunks, source bodies, or line numbers.
12. Ledger state is readable through the existing goal findings surface without
    exposing location-bearing detail on goal-facing status or telemetry.

## Non-Goals

- No root-cause analysis or escalation decision; that is subtask 3.
- No pause behavior change; that is subtask 4.
- No change to review scope computation or the remediation base sha.
- No projection of raw prior review output, specialist narratives, or diffs.

## Implementation Notes

Deriving the ledger rather than storing it keeps
`GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION` at `0.5`; a bump would have
rejected every legacy durable record.

Because it is derived on every read, the derivation is best-effort at each
consuming seam: a malformed entry degrades to no ledger and the phase launches
anyway. The review pass in particular always runs. The one read that still
loud-fails is the review-state read itself, which gates the unresolved-Blocker
pause and must not be allowed to report "no Blocker" through silence.

## Dependency Notes

Depends on subtask 1 for receipt identity and construct granularity. Subtasks 3
and 4 both consume this ledger.

## Validation Strategy

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check -x sourcesJar
```

Focused: ledger accumulation and status-transition domain tests,
`FeatureTaskRuntimePhasePromptComposerTest`, projection budget and privacy
tests, and a multi-round integration case asserting scope is unchanged while
context grows.

## Next Path

Subtask 3 adds the `plan_fix` phase that reads this ledger before any edit is
made.
