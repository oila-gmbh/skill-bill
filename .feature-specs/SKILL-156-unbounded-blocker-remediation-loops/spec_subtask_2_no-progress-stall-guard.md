# SKILL-156 Subtask 2 - No-Progress Stall Guard For Both Remediation Loops

## Scope

Give the now-unbounded remediation loops an evidence-based terminating condition
shared by `review_fix` and `audit_gap`.

In scope:

- One loop-agnostic convergence policy in `runtime-domain`, pure and free of IO,
  that decides `converging` versus `stalled` from a round fingerprint:
  - the unresolved item identifier set (Blocker finding ids for review, gap ids for
    audit),
  - a repository-change signal for the round (the remediation delta since the
    round's base checkpoint being empty means no repository change),
  - whether any item newly moved to resolved or superseded in the round.
  A round is stalled when the identifier set is identical to the previous round's,
  the repository did not change, and nothing newly resolved.
- Durable persistence of the previous round's fingerprint on the review state and on
  the audit repair ledger, so the comparison survives process death and parent
  resume.
- Wiring: `FeatureTaskRuntimeRunLoop` evaluates the policy before taking either
  backward edge. A stalled round does not re-enter; it stops loudly and resumably
  through the existing pause surface, naming the loop id, iteration count, and the
  unresolved identifier set.
- Release path: the existing operator decision set (`retry_fix`,
  `accept_and_advance`, `abandon_subtask`) releases a stall. `retry_fix` grants one
  further round; if that round is stalled again, the run pauses again.
- Audit parity: the audit loop's existing "same gap set with no repository change"
  rule is expressed through this one policy rather than a separate audit-only path,
  and `FeatureTaskRuntimeAuditRepairReconciler` consumes it.

## Acceptance Criteria

1. A remediation round returning the identical unresolved Blocker identifier set
   with no repository change and no newly resolved disposition does not re-enter
   `implement_fix`; it pauses resumably and names the loop id, iteration count, and
   the unresolved identifiers.
2. The same policy, from one shared implementation, governs `audit_gap`: an audit
   round returning the identical unresolved gap set with no repository change and no
   newly resolved repair item stops loudly with those gap and repair-item
   identifiers.
3. A round that changes the repository, resolves at least one item, or returns a
   different unresolved identifier set is treated as converging and re-enters the
   loop however many rounds have already run.
4. The stall fingerprint is durable: a process death or parent resume between rounds
   preserves the previous round's identifier set and repository-change signal, and a
   resumed run reaches the same stall or non-stall decision as an uninterrupted one.
5. An operator `retry_fix` on a stalled subtask grants exactly one further round; a
   stalled outcome from that round pauses again rather than looping.
6. `accept_and_advance` releases forward to `validate` with the unresolved Blockers
   recorded as accepted; `abandon_subtask` terminates the subtask, both unchanged.
7. The convergence policy is pure — no clock, randomness, or IO — and lives in
   `runtime-domain` with the loop identity passed in rather than branched on.

## Non-Goals

- Reintroducing any iteration-count-based termination.
- Changing what qualifies as a Blocker or an audit gap.
- Changing the operator decision vocabulary.

## Dependency Notes

Depends on Subtask 1: the unbounded loop-accounting shape and the widened review
state are prerequisites for storing a per-round fingerprint.

## Validation Strategy

- Domain unit tests for the convergence policy: identical set + no change + no
  newly resolved → stalled; each of the three conditions individually false →
  converging; empty unresolved set → loop closed.
- Run-loop tests: a stalling review loop pauses at the round it stalls with the
  correct identifiers; a converging loop of many rounds never pauses; `retry_fix`
  grants exactly one round.
- Audit regression: the existing audit no-progress behavior holds through the shared
  policy, with no behavior change for converging audit loops.
- Durability test: kill and resume between rounds, asserting an identical decision.
- `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Subtask 3 — loop warning threshold.
