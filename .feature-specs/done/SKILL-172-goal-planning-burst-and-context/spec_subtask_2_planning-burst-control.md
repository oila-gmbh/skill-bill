# SKILL-172 Subtask 2 - Planning burst control: launch pacing and empty-turn backoff

Parent spec: [.feature-specs/SKILL-172-goal-planning-burst-and-context/spec.md](spec.md)
Issue key: SKILL-172

## Scope

Stop the planning sweep from issuing its launches as fast as the provider will accept them,
in the two places where it currently does.

**Pacing.** `producePlan` (`GoalPlanningSweep.kt:241`) calls `producePhase` once per subtask
with no gap, so a 14-subtask goal fires 15 sequential launches of a large, near-identical
payload back to back. Introduce a configurable interval applied *between* consecutive plan
launches.

**Backoff.** Commit `50bbdf38` made an `EmptyProviderTurn` retryable under the fix-loop cap,
but the retry relaunches immediately. Against a rate ceiling that spends all three attempts
inside roughly ninety seconds and blocks the goal anyway — the observed 4:18 / 4:19 / 4:19
triple. Make the retry back off, growing per attempt.

Both are provider-neutral: neither site may read agent identity.

Primary files:

- `runtime-application/src/main/kotlin/skillbill/application/goalrunner/GoalPlanningSweep.kt`
- `runtime-application/src/main/kotlin/skillbill/application/model/GoalPlanningSweepModels.kt`
- `runtime-core/src/main/kotlin/skillbill/di/RuntimeComponent.kt` (seam binding)
- `runtime-application/src/test/kotlin/skillbill/application/GoalPlanningSweepTest.kt`

## Acceptance Criteria

1. Consecutive per-subtask plan launches are separated by a configurable pace interval. The
   interval is applied between launches only — not before the first and not after the last —
   asserted by a test that records launch ordinals and observed waits.
2. `EmptyProviderTurn` retries wait before relaunching, and the wait grows per attempt. A test
   drives all three attempts and asserts the observed schedule, including that attempt 1 is not
   preceded by a wait.
3. Waiting goes through an injected seam that a test can drive without real elapsed time,
   following the injected `java.time.Clock` precedent in `GoalRunner` and
   `GoalRunnerExecutionCoordinator`. The whole suite must not become measurably slower.
4. No bare `Thread.sleep` in application code.
5. Neither the pacing site nor the backoff site reads agent identity, model, or any
   provider-specific field.
6. Waiting is interruptible and does not sleep through a durable pause boundary: a sweep paced
   or backing off still observes `planningPauseOutcome` and the spawn-authorization boundary
   promptly. Covered by a test that pauses mid-sweep.
7. A thread interrupt during a wait terminates the sweep the same way an interrupt during a
   launch does — it does not surface as an unexpected planning failure or a swallowed exception.
8. Defaults are stated with their arithmetic: the subtask records the chosen pace and backoff
   values and the added wall-clock for a 15-subtask goal, and confirms that total stays inside
   the default `--planning-budget-minutes` and `--max-wall-clock-minutes` bounds.
9. Existing `GoalPlanningSweepTest` cases continue to pass unchanged in intent — in particular
   the empty-turn retry, exhaustion, and evidence tests added in `50bbdf38`.
10. `./gradlew build -x sourcesJar` and `detekt` pass.

## Non-Goals

- No change to `MAX_FIX_LOOP_ITERATIONS` or the number of retry attempts.
- No agent failover after N empty turns, and no provider-specific special casing.
- No pacing of non-planning launches (implement, audit, review, validate). This subtask is
  scoped to the planning sweep, which is the burst source under investigation.
- No adaptive or feedback-driven rate control. A fixed pace and a fixed backoff schedule are
  sufficient; anything adaptive needs evidence this does not already resolve it.
- No change to how an empty turn is classified or recorded — that landed in `50bbdf38`.

## Dependency Notes

Depends on commit `50bbdf38`, which introduced `GoalPlanningPhaseProduction.EmptyProviderTurn`
and the retry branch this subtask adds backoff to. Independent of subtask 1; either may land
first, though landing subtask 1 first reduces payload per launch and makes any measurement of
this subtask's effect cleaner.

## Validation Strategy

1. Pacing test: multi-subtask sweep with a fake wait seam, asserting waits occur between
   launches only and match the configured interval.
2. Backoff test: all-empty sweep, asserting the per-attempt wait schedule and that attempt 1 is
   unpreceded.
3. Pause test: pause the sweep while a wait is pending; assert it stops at the pause boundary
   rather than completing the wait and launching.
4. Interrupt test: interrupt during a wait; assert the same terminal shape as an interrupt
   during launch.
5. Re-run the `50bbdf38` empty-turn tests unchanged.
6. `(cd runtime-kotlin && ./gradlew :runtime-application:test detekt)` and
   `(cd runtime-kotlin && ./gradlew build -x sourcesJar)`.
7. Manual, after merge: run a wide decomposed goal against Cursor and compare single-run
   planning completion against the 2 → 4 → 6 → 9 → 12 → 15 resume pattern.

## Next Path

Measure a real wide goal. If empties persist at the same depth after pacing and backoff, the
remaining burst is upstream of this change and provider failover becomes the next candidate.
