# SKILL-204 — Goal children build early; validate only on the last subtask

## Intended Outcome

Multi-subtask goals stop paying the full pack validation gate on every child.
Every non-last child runs a new first-class `build` phase that proves the tree
compiles (or otherwise meets the pack's declared build command). The last
non-skipped subtask in manifest array order keeps today's `validate` phase
unchanged — collect-all discovery, repair-all, confirm. Solo feature-task runs
and single-subtask goals stay on `validate` only; they never see `build`.

`validate` is not weakened, renamed, or depth-switched. A separate phase owns
the cheap proof.

## Background

After `ValidationDepth.BUILD_ONLY` was retired (shell contract 1.6), every goal
child runs full `./gradlew check --continue` (discover + confirm) plus agent
repair. On a four-subtask goal that cost repeats four times for little
incremental safety: later children recompile earlier commits on the same
branch, and suite debt is cheap to defer to one final full pass.

Compile breakage is not cheap to defer. A red build on an early child still
blocks later work. Restoring a validate *depth* recreates the dual-argv /
dual-prompt / resume-conflict surface that retirement removed. A dedicated
`build` phase keeps `validate` as the single full-gate story and gives early
children an explicit, runtime-owned compile step.

Measured pain: SKILL-203 subtask 1 spent over an hour in `validate` on an
intermediate child while competing full-check processes ran in the same
worktree.

## Decisions

1. **New phase `build`**, not a `ValidationDepth` variant and not a skipped
   `validate`. Phase id `build`. It sits on the happy path after a clean
   `review` (and after remediation returns to a clean review) and before
   `write_history`.
2. **Last** = last non-skipped entry in the decomposition manifest `subtasks`
   array order (numeric ids do not matter). That child runs `validate`. Every
   earlier non-skipped child runs `build` instead of `validate`.
3. **Skipped-last safety:** if the ordinal-last subtask is `skipped` at launch,
   the last non-skipped entry gets `validate` so the goal still runs one full
   gate.
4. **Single-subtask and non-goal** launches never enter `build`; they keep the
   current `review → validate → …` path.
5. **Pack-declared build command** under `validation_gate` (name TBD in
   implementation; e.g. `build_command` / `collect_all_build_command`) — Kotlin
   packs point at compile/buildability (`./gradlew compileKotlin` or an
   equivalent the pack owns). Not `check`, not tests, not detekt/spotless as a
   suite.
6. **Runtime owns the build gate** the same way it owns validate: run the pack
   command, parse failures, hand the agent a bounded finding set, repair,
   re-run to confirm. No agent-invented substitute check.
7. **Downstream receipts:** `write_history` and `commit_push` today require
   `validation_receipt`. After this change they accept either a completed
   `validation_receipt` or a completed `build_receipt` from the quality-gate
   phase that child actually ran. No parent-level validate in `finalizeGoal`.
8. **Debt on last is accepted** — test/lint/format failures from earlier
   children may surface during the final `validate` and be repaired there.

## Acceptance Criteria

1. Goal-continuation children carry an explicit quality-gate selection
   (`build` or `validate`) on the continuation context; absence or non-goal
   launches resolve to `validate`.
2. When `GoalRunner` launches a child, selection is `validate` iff that
   subtask is the last non-skipped entry in manifest array order; every
   earlier non-skipped child is `build`. A single-subtask goal is always
   `validate`.
3. Under `build`, the child phase loop runs the new `build` phase and does not
   enter `validate`. Under `validate`, behavior matches today's Phase 6.
4. The pack schema declares a build command; shipped Kotlin/KMP packs set it;
   a pack missing the field when a child needs `build` fails loudly with a
   typed error.
5. `write_history` and `commit_push` advance after either a settled build
   receipt or a settled validation receipt for that child.
6. Status, watch, and phase accounting name `build` when that phase is active
   (not "validate" with a depth footnote).
7. Tests cover: three-subtask routing `build`/`build`/`validate`;
   single-subtask `validate`; last-skipped promotion of `validate`; build does
   not invoke the collect-all full gate; CLI/continuation round-trip.
8. `./gradlew check --continue` passes for the change (this feature's own
   validate when it lands).

## Non-Goals

- No return of `ValidationDepth.BUILD_ONLY` or `build_only_command` as a
  validate depth.
- No parent-level validate inside `finalizeGoal`.
- No change to review/audit severity gates or remediation loops.
- No change to `same_branch_commit_per_subtask` commit ownership.
- No requirement that intermediate children run tests.

## Constraints

- Selection is goal-continuation-only; standalone feature-task runs remain
  validate-only.
- Schema bumps for pack / phase-output / handoff contracts follow the usual
  loud-fail + parity-test path; legacy in-flight children without the new
  field keep `validate`.
- Waiting/fix-loop policy for `build` is bounded like validate; this feature
  changes which gate runs, not unbounded retry.

## Out of Scope Follow-ups

- Re-running full validate at `finalizeGoal` as a second safety net.
- Per-module or path-scoped build commands beyond what the pack declares.
- Restoring a compile-only *validate* depth for non-goal launches.
