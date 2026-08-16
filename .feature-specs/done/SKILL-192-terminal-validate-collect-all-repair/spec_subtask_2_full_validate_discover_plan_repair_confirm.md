# SKILL-192 · Subtask 2: FULL validate is discover, plan, repair-all, confirm

## Intended Outcome

`ValidationDepth.FULL` validate stops using fail-fast `full_gate_command` in an
unbounded repair loop. It runs collect-all discovery, persists the complete
finding set, drives one repair pass over every finding, then runs collect-all
confirmation. `ValidationDepth.BUILD_ONLY` keeps today's cycle.

## Scope

- Change `FeatureTaskRuntimeValidationGateCoordinator` so FULL depth:
  1. Runs collect-all cache-eligible discovery (subtask 1 mode).
  2. Persists the complete finding set (and dropped-count `0` unless paging
     metadata is recorded; findings are not discarded).
  3. If discovery passed with zero findings, goes to confirmation.
  4. Otherwise launches repair against the **entire** persisted set (plan
     object may be a stub in this subtask; subtask 3 fills receipts).
  5. Runs collect-all cache-bypassing confirmation.
  6. On confirmation failure, treats that run's complete set as the next
     repair input. Does not run a separate discovery. Caps extra repair
     passes explicitly (parent: small cap). Exhaustion blocks with remaining
     findings.
- Happy path dirty discovery + green confirmation measures **two** collect-all
  gate runs. Clean discovery still runs confirmation (two runs, or one
  confirmation after a passed discovery — still the cache-bypassing collect-all
  as the satisfying evidence). A cache-eligible discovery green is never
  terminal evidence.
- Zero executed work on confirmation never satisfies (keep SKILL-180).
- BUILD_ONLY continues to use `build_only_command`, current cache-eligible then
  forced-full BUILD_ONLY attestation, and current repair looping for compile
  failures. Do not point BUILD_ONLY at collect-all full check.
- Goal continuation already stamps FULL only on the last non-skipped child
  (SKILL-173). This subtask consumes that stamp; it does not restamp. Standalone
  feature-task FULL uses the same cycle.
- If the persisted set exceeds one prompt budget, page remaining plan items to
  further agent launches **without** rerunning the gate. Do not silently drop
  findings.
- Remove FULL's unbounded `while (true)` over fail-fast `full_gate_command`.
  Do not restore that path as a fallback when collect-all argv exists.
- Persist `gate_run_count` / `gate_runs` with cache mode, duration, executed
  work, and outcome as today. Surface counts in goal status.
- Prompts in this subtask must say FULL repair receives the complete discovery
  set and must not invoke the gate. Detailed substantiation schema is subtask 3;
  this subtask may keep today's `validation_result` shape for repair segments.

## Acceptance Criteria

1. FULL validate discovery uses pack collect-all argv, not `full_gate_command`.
2. FULL confirmation uses pack cache-bypassing collect-all argv; a zero-work
   confirmation never satisfies `validation_status`.
3. After a dirty discovery, the first repair launch receives every finding from
   that discovery (or an explicit page of a persisted complete set whose
   remainder is scheduled in the same pass), not only JUnit from a fail-fast
   run.
4. The gate is not rerun between pages of one repair pass, and not rerun after
   individual finding fixes.
5. A dirty discovery followed by a green confirmation records exactly two
   collect-all runs on `gate_runs` for that successful cycle.
6. A failed confirmation's findings become the next complete repair set without
   an extra discovery run; fail-fast `./gradlew check` is not used.
7. Exhausting the confirmation-retry cap is a durable blocked validate with
   remaining findings persisted, never a silent pass.
8. BUILD_ONLY still runs `build_only_command` only and does not execute the
   collect-all full check.
9. Last vs intermediate goal-child depth assignment is unchanged: intermediate
   children remain BUILD_ONLY; last non-skipped remains FULL.
10. Regression coverage proves: FULL discover-repair-confirm two-run happy path;
    compile-plus-later-test both present on discovery; confirmation zero-work
    rejected; confirmation retry uses confirmation findings; cap exhaustion
    blocks; BUILD_ONLY cycle unchanged; missing collect-all declaration still
    loud-fails from subtask 1 rather than falling back to fail-fast FULL.

## Non-Goals

- Per-finding substantiation receipt schema and confirmation identity closure
  (subtask 3). This subtask may treat a completed repair launch as "pass
  attempted" the way today's coordinator treats `ValidationGateAgentRepairResult.Completed`.
- Suppression-justification harvest changes other than running after green
  confirmation as today.
- Agent-run absent-gate fallback.
- Changing who is the last subtask.

## Dependency Notes

Depends on subtask 1 (collect-all argv, union findings, runner mode).

Subtask 3 tightens the repair launch contract on top of this cycle.

## Validation Strategy

- Coordinator tests with a scripted runner: FULL dirty discovery (compiler +
  test findings) → one repair → green confirmation = two collect-all calls,
  none of them `full_gate_command`.
- Scripted runner: fail-fast-shaped short run is not selected for FULL.
- Confirmation zero-work → blocked, not satisfied.
- Confirmation failure → one more repair with those findings, no third
  discovery argv.
- Cap exhaustion → blocked with remaining findings.
- BUILD_ONLY scripted path still uses build-only argv and is unaffected.
- GoalRunner depth-stamping tests from SKILL-173 still expect BUILD_ONLY,
  BUILD_ONLY, FULL on a three-child goal.

## Next Path

Subtask 3 adds the repair plan, per-finding substantiation, and confirmation
closure so "fixed" is proven against finding identities, not only a second
green code.
