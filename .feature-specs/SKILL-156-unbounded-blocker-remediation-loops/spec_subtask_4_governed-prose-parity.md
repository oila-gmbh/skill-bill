# SKILL-156 Subtask 4 - Governed Prose And Contract Parity

## Scope

Bring every governed surface that states the old cap into agreement with the
implemented policy, in both runtime and prose modes.

In scope:

- `skills/bill-feature-task-runtime/content.md`: rewrite the review-fix loop section
  to state Blocker-unbounded remediation, the inline light remediation tier for
  passes two and beyond, the no-progress stall guard and its operator release, and
  the warning threshold. Align the audit-gap section with the same shared guard and
  warning policy.
- `skills/bill-feature-task-prose/content.md`: replace the two-pass review budget
  ("Reserve at most one inline re-review, for two total review passes", "Never start
  pass three", "The two-pass cap applies to every feature task") with the
  Blocker-unbounded policy, the stall stop, and the warning threshold. Update the
  `abandoned_at_review` stop condition to trigger on a stall or an operator
  `abandon_subtask`, not on a pass budget.
- `skills/bill-feature-task-subtask-runner/content.md`: replace the two-pass
  unresolved-Blocker cap reference with the stall-and-pause condition while keeping
  the location-bearing evidence requirement.
- `skills/bill-feature-goal/content.md`: correct the reserved-later-pass wording so
  it describes repeating remediation passes on the remediation delta rather than a
  single reserved pass.
- Parity tests binding the governed prose to the runtime constants, following the
  SKILL-142 blocker-only-reopen parity-test pattern: the stated threshold matches
  `REMEDIATION_LOOP_WARNING_THRESHOLD`, and no governed surface asserts a finite
  remediation cap.
- `AGENTS.md` if it states remediation loop bounds, and any
  `orchestration/` playbook that repeats them.
- `./install.sh` after content changes so local agent installs pick up the new
  staging hash.

## Acceptance Criteria

1. Runtime and prose governed content state the same policy: unresolved Blockers
   reopen remediation without an iteration cap, non-Blocker findings go to the
   ledger, a non-convergent round stops loudly and resumably, and iterations past
   three warn while continuing.
2. No governed skill content, playbook, or `AGENTS.md` statement asserts a two-pass
   review budget, a one-iteration review-fix cap, or "never start pass three".
3. Prose mode's `abandoned_at_review` terminal path is triggered by a stalled loop or
   an operator `abandon_subtask`, not by an exhausted pass budget.
4. A parity test fails if governed prose and the runtime warning threshold disagree,
   and a test asserts no governed surface reintroduces a finite remediation cap.
5. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
   `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass.
6. `./install.sh` has been run so installed skills reflect the updated content.
7. `.feature-specs/SKILL-156-unbounded-blocker-remediation-loops/spec.md` is
   reconciled to its final state: `Status: Complete`, open questions resolved, and
   any corrections the implementation forced applied.

## Non-Goals

- Behavior changes; this unit is content and parity only.
- Standalone `bill-code-review` and `bill-feature-verify` content.

## Dependency Notes

Depends on Subtasks 1, 2, and 3. The prose must describe shipped behavior, and the
parity tests reference constants those units introduce.

## Validation Strategy

- Parity tests over governed content and runtime constants.
- Full validation command set from AGENTS.md.
- Grep-based assertion that the retired cap phrasing appears nowhere in governed
  surfaces.

## Next Path

Feature complete; reconcile the parent spec and open the PR.
