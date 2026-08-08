# SKILL-173: Goal validate build-only until last subtask

Status: Prepared

## Intended Outcome

Multi-subtask goals stop paying the full validation gate (tests, detekt, spotless, lint,
`bill-code-check`) on every child. Early children only prove the tree is buildable; the last
subtask in manifest order runs today's full validate and may repair accumulated debt. Solo
feature-task runs and single-subtask goals stay on full validate.

## Background

Every goal child today runs the same truncated feature-task phase loop through `commit_push`,
including Phase 6 (`validate`). Validate is prompt-driven from a projected `validation_request`
(plan `validation_strategy` + task `test_obligations`) and is told to run implement-written tests
plus the repository validation gate. There is no last-vs-middle depth switch on
`goalContinuation`, and `finalizeGoal` does not re-validate — each child's full validate is the
only quality gate before that child's commit.

On a long goal that cost is repeated N times for little incremental safety: early children land
commits that will be re-checked by later validates anyway, and static-analysis/format debt is
cheap to defer to one final full pass. Compile breakage is not cheap to defer — a red build on
an early child still blocks later work on the shared branch.

## Decisions

1. **Last** = last entry in the decomposition manifest `subtasks` array order (numeric ids do
   not matter). Example: three subtasks → only the third gets `full`.
2. **Debt on last is accepted** — lint/format/test failures from earlier children may surface
   and be repaired during the final full validate.
3. **Skipped-last safety:** if the ordinal-last subtask is `skipped` at launch, assign `full`
   to the last non-skipped entry in array order so the goal still gets one full gate.
4. **No `bill-code-check` light mode** in this change — `build_only` is enforced by the validate
   directive plus a narrowed `validation_request.required_checks`.

## Acceptance Criteria

1. Goal-continuation children carry an explicit `validation_depth` of `build_only` or `full`
   on the continuation context; absence defaults to `full` so solo and legacy launches are
   unchanged.
2. When `GoalRunner` launches a child, depth is `full` iff that subtask is the last non-skipped
   entry in manifest array order; every earlier non-skipped child is `build_only`. A
   single-subtask goal is always `full`.
3. Under `build_only`, the validate phase proves compile/buildability for the change, fixes
   only compile/build failures via the existing validate fix-loop, and does not run tests,
   detekt, spotless, lint, dependency scanners, or the full `bill-code-check` gate.
4. Under `full`, validate behavior matches today's Phase 6 (tests + repository validation gate).
5. The projected `validation_request` for `build_only` does not forward plan `test_obligations`
   into `required_checks`; it carries a compile/buildability obligation instead. Receipt field
   set (`validation_status`, `checks`, `repository_checkpoint`) stays unchanged.
6. Depth survives CLI launch and resume round-trip on the goal-continuation plumbing.
7. Tests cover: three-subtask depths `build_only`/`build_only`/`full`; single-subtask `full`;
   last-skipped promotion of `full` to the previous non-skipped entry; `build_only` validate
   briefing omits test obligations; CLI round-trip.
8. `./gradlew build -x sourcesJar` and `detekt` pass. (`:runtime-infra-fs:sourcesJar` fails on
   clean `main` for an unrelated task-dependency defect and is out of scope.)

## Non-Goals

- No change to the implement phase (still writes tests and leaves `tests_executed` empty).
- No parent-level validate inside `finalizeGoal`.
- No `bill-code-check` / platform-pack quality-check schema or light-mode additions.
- No change to phase DAG truncation (`suppress_pr` still ends children at `commit_push`).
- No change to planning-context packet work (SKILL-172).

## Constraints

- Depth is goal-continuation-only; non-goal feature-task runs must remain full validate.
- `validation_receipt` consumers (`write_history`, `commit_push`) must keep working without a
  schema bump — encode depth in what `checks` report, not in new required receipt fields.
- Waiting/fix-loop policy for validate stays bounded as today; this feature changes what the
  agent is asked to run, not the retry caps.

## Out of Scope Follow-ups

- A first-class `bill-code-check` compile-only / light mode for stronger enforcement than
  prompt + narrowed `required_checks`.
- Re-running full validate at `finalizeGoal` as a second safety net.
