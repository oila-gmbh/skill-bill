# SKILL-230 · Subtask 1 — Bounded parallel plan fan-out

## Scope

Replace the one-plan-at-a-time loop in the goal planning sweep with bounded
concurrent dispatch, capped at 5 in-flight plan sessions, after the shared
preplan is prepared.

Deliver:

- A concurrency port in `runtime-ports` that runs a bounded set of units of work
  and returns one result per input in input order, capturing a per-unit failure
  instead of losing sibling results. Model it on `RuntimeTimingPort`: a small
  interface plus a sequential implementation usable as a test default.
  `CancellationException` rethrows before any broad catch, per the failure
  contract in `../../../docs/code-principles.md`.
- A JDK implementation in `runtime-infra-fs` alongside `JdkRuntimeTimingPort`,
  using daemon threads with a recognizable name prefix, sizing the pool to
  `min(cap, unitCount)`, and shutting the pool down on every exit path
  including failure.
- DI wiring in `runtime-core`, and the port added to
  `GoalPlanningSweepLaunchPort` so `DefaultGoalPlanningSweep` receives it the
  same way it receives `timingPort`.
- `GoalPlanningBurstSchedule` carries the concurrency cap with default 5 and
  retires `planLaunchPace` as an inter-launch gate. If a short stagger is still
  wanted so 5 provider handshakes do not land in the same instant, it becomes an
  explicitly named intra-wave stagger with a default no greater than 2s. The
  20s serial gate and its 15-subtask wall-clock reasoning are removed from the
  KDoc.
- Rewrite `produceMissingPlansLoop` to resolve the missing subtask set once and
  dispatch it in waves of at most the cap, instead of re-deriving
  `firstMissingSubtaskId` per launch. Each unit runs today's `producePlan`
  unchanged, keeping its attempt loop, empty-turn backoff, schema-rejection
  retry, projection gate, and checkpoint write.
- Pause handling: check the durable pause boundary before dispatching a wave,
  dispatch no further wave once pause is requested, let the in-flight wave
  drain so its valid plans persist, and return the `PAUSED` stop.
- Deterministic stop selection when more than one unit in a wave stops, so the
  reported `currentSubtaskId`, `blockedReason`, and `lastResumableStep` do not
  vary between runs with identical inputs.
- Concurrency safety at the shared seams the sweep already owns:
  `planningAttemptRecorder` and `planningRejectionRecorder` are invoked from
  several units at once, and `request.outputSink` receives both the sweep's own
  progress lines and, because `streamOutputForLiveness` is true, each agent's
  streamed output.

`GoalPlanningResolvedBoundaryBodies` is immutable and stays shared across units
as-is.

## Acceptance Criteria

1. With the shared preplan prepared and more than one plan missing, plan
   sessions run concurrently: a goal with 5 missing plans has all 5 launches
   issued before the first one is allowed to complete.
2. In-flight plan sessions never exceed the cap of 5; a goal with 12 missing
   plans is dispatched in bounded waves and no observation records a sixth
   concurrent session.
3. No plan launch is gated on a fixed 20s inter-launch delay.
4. `runtime-application` main source contains no threading or executor
   reference, concurrency is reached only through the injected port, and
   `RuntimeLayerBoundaryArchitectureTest` passes unchanged.
5. When one unit in a wave stops and its siblings produce schema-valid output,
   every sibling plan is durably checkpointed, and a resume dispatches only the
   subtasks still missing a plan.
6. The shared preplan is prepared before the first plan launch on the fresh
   path and on the heading-set refresh path; no plan launches while the preplan
   is unsettled.
7. A pause requested while a wave is in flight dispatches no further wave,
   persists the valid plans of the draining wave, and returns a stop whose
   reason is `PAUSED`.
8. When several units in one wave stop, the returned stop is deterministic
   across repeated runs on identical inputs, and each stopped subtask's
   planning rejection is recorded independently.
9. Streamed planning output remains line-atomic under concurrency and each line
   is attributable to the subtask that produced it.

## Non-Goals

- Parallel preplan.
- Passing sibling plan payloads into a plan session.
- Status and observability wire changes, which are subtask 2. This subtask keeps
  `currentPlanningSubtaskId` populated with a single value so no consumer
  breaks.
- An operator-facing cap flag or config key.
- Implement or audit-gap fan-out (SKILL-226).
- Changing the plan phase prompt contract, the phase output schema, or the
  planning preparation contract.

## Dependency Notes

None. First subtask; base branch is `main`.

## Validation Strategy

- Sweep tests use a deterministic fan-out test double that records concurrent
  entry counts and maximum observed concurrency, so overlap and wave bounds are
  asserted without depending on wall-clock timing. A sequential double keeps
  existing single-plan expectations meaningful.
- Named bugs the new tests are there to catch: launches that satisfy "5 plans"
  by running them one after another; a wave that drops a completed sibling
  checkpoint when a peer stops; a pause mid-wave that discards valid plans; a
  stop whose reported subtask varies by thread completion order; interleaved
  output that cannot be attributed to a subtask.
- Architecture test coverage for the `runtime-application` threading ban.
- Targeted Gradle module tests for `runtime-application`, `runtime-infra-fs`,
  and `runtime-core`. Build and test execution belongs to the validate phase.

## Next Path

Subtask 2 reports concurrent planning on the status surfaces.
