# SKILL-230: Parallel subtask planning

## Intended Outcome

After the shared preplan settles, goal planning produces the missing per-subtask
plans concurrently under a bounded cap of 5 instead of one at a time. On a goal
with 5 to 10 subtasks, planning wall clock becomes the slowest plan in each wave
rather than the sum of every plan session.

## Current Behavior

`DefaultGoalPlanningSweep.prepare` settles one shared preplan, then
`produceMissingPlansLoop` walks `recoveryProgress().firstMissingSubtaskId` one
subtask at a time. Each iteration launches one planning agent, blocks in
`GoalRunnerSubtaskLauncher.launch` until that agent exits, and waits
`GoalPlanningBurstSchedule.planLaunchPace` (20s) before the next launch.

Two costs stack. Plan sessions never overlap, and a 10-subtask goal spends
`9 * 20s = 180s` deliberately idle between launches.

## Why Concurrency Is Safe Here

The per-subtask plan phase is already independent by construction:

- The phase is non-mutating. Its prompt carries "Do not modify repository files
  during this phase."
- Each plan session receives the immutable shared planning packet, its own
  governed sub-spec, its dependency id metadata, and the boundary-memory bodies
  the preplan selected. It never receives a sibling plan payload.
- Dependency metadata is planning context only, prompted as "Do not execute,
  simulate, edit, or mutate dependency work."
- Each plan checkpoints on its own through `recheckpointSubtaskPlan`, and
  recovery is `firstMissingSubtaskId` over the prepared id set, which is
  insensitive to the order plans complete in.
- `goal replan --subtask <id>` already treats a single plan row as
  independently discardable and regenerable.

Serial execution therefore buys no planning quality. It only serializes wall
clock.

## Constraints

- The shared preplan must settle before any plan launches. The heading-set
  refresh path and its non-terminal plan cascade must complete first.
- `runtime-application` main source may not reference `java.util.concurrent`,
  `Executors`, `Executor`, `Future`, `Callable`, `TimeUnit`, `Thread(`,
  `Thread.sleep`, `Thread.currentThread`, or `.interrupt()`. That ban is
  enforced by `RuntimeLayerBoundaryArchitectureTest`, so the sweep must reach
  concurrency through an injected port with an infrastructure implementation,
  mirroring `RuntimeTimingPort` and `JdkRuntimeTimingPort`.
- SQLite serializes writers behind a write reservation, so concurrent plan
  checkpoint writes queue rather than fail. Checkpoint writes stay short and
  must not be held open across an agent launch.
- The durable pause boundary must still be honored, and valid plans produced
  before a pause must survive it.
- Concurrency cap is 5, fixed in the runtime. No operator flag in this feature.

## Acceptance Criteria

1. With the shared preplan prepared, a goal carrying more than one missing plan
   runs up to 5 plan sessions concurrently, and no launch waits on a fixed 20s
   inter-launch delay.
2. In-flight plan sessions never exceed 5 regardless of how many subtasks are
   missing plans.
3. Concurrency is reached through an injected port; `runtime-application` main
   stays free of threading and executor references and
   `RuntimeLayerBoundaryArchitectureTest` passes unchanged.
4. Every plan whose output is schema-valid is checkpointed even when a
   concurrent plan in the same wave stops, and a resume plans only the subtasks
   still missing a plan.
5. The shared preplan still settles before the first plan launch on both the
   fresh and heading-set-refresh paths.
6. A pause requested while plans are in flight launches no further work, lets
   in-flight sessions finish and persist their valid plans, and reports a
   `PAUSED` stop.
7. When several concurrent plans stop in one wave, the reported stop is
   deterministic and each stopped subtask still records its own planning
   rejection.
8. Goal status reports every subtask the current planning wave covers, while
   `current_planning_subtask_id` keeps a single value so existing IDE consumers
   render without modification.

## Non-Goals

- Parallel preplan. The shared preplan stays a single serial session.
- Passing sibling plan payloads between plan sessions, or any DAG-shaped
  planning where one plan reads another's output.
- Implement or audit-gap repair fan-out, which is SKILL-226 and carries a
  file-disjointness proof this feature does not need.
- Parallel subtask execution, commits, or review. Only the plan phase fans out.
- An operator-configurable cap, CLI flag, or config key for concurrency.
- A new durable planning-liveness table.
- IntelliJ plugin or VS Code extension rendering changes. The status wire change
  stays additive so both keep working untouched.

## Validation Strategy

- `GoalPlanningSweepTest` gains coverage driven by a deterministic fan-out test
  double that records maximum observed concurrency, so wave bounds and
  overlap are asserted without wall-clock timing.
- Architecture tests prove the threading ban still holds in
  `runtime-application`.
- Status coverage runs through schema validator tests, IDE status projector and
  model tests, golden fixtures, `CliWorkStatusTest`, and the MCP mapping.
- Targeted Gradle module tests plus `skill-bill validate`. Build and test
  execution belongs to the validate phase.

## Delivery Plan

1. Bounded parallel plan fan-out: the concurrency port, its JDK implementation,
   the wave dispatch in the sweep, and pause, resume, recording, and output
   behavior under concurrency.
2. Concurrent-planning status wire: report the set of subtasks a planning wave
   covers through the goal status projection, the IDE status contract, the CLI,
   and MCP, keeping `current_planning_subtask_id` backward compatible.
