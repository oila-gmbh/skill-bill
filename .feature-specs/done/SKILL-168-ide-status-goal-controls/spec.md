# SKILL-168 — Trustworthy IDE status and operator goal controls

## Intended Outcome

The IntelliJ status widget must tell the truth about goal execution, and let the operator
act on it. Today it does neither reliably: it can blank to `idle` while a goal runs, and it
offers no way to stop or pause a goal from the IDE.

This feature covers two related halves:

- **Read correctness.** A transient bad sample must not blank a live display, and the
  runtime must not produce that bad sample.
- **Operator control.** The operator can stop a goal immediately, or ask it to pause after
  the current subtask, from the status widget — and the runtime durably records that they
  did, so an operator stop is never indistinguishable from a crash.

### Already landed (context, not scope)

Three defects found during investigation on 2026-08-07 are **already fixed and merged**;
they are recorded here because the evidence below assumes them:

- `IdeStatusService.scopeToBranch` dropped every candidate whose issue key was absent from
  the checked-out branch name. On `main` — where a goal sits until it creates
  `feat/{KEY}-…` at first subtask launch — a running goal read as `no_matching_work`/idle.
  Branch scoping is now disabled on protected base branches (`main`/`master`/`trunk`).
- `IdeStatusProjector.goalLifecycle` mapped durable `current_state = "running"` to `ACTIVE`
  with no liveness check, so a stopped or killed goal reported `active` for up to 24h with
  an elapsed clock ticking upward. `ExecutionLiveness.IDLE` now maps to `PAUSED`, and
  `updated_at` is anchored to `execution_lease.heartbeat_at` so the settled elapsed clock
  shows the real stop time.
- A paused goal mid-planning still summarised as "is planning subtasks".

Those fixes make a *stopped* goal display honestly. They do not give the operator any way
to stop one, and they do not address transient flicker during a genuinely live run.

## Evidence

**Transient flicker (observed 2026-08-07 during the SKILL-165 run).** The widget tooltip
read `State: idle` / `Goal elapsed: —` / `Subtask elapsed: —` while goal SKILL-165 was
durably `running`. The CLI returned its `no_matching_work` problem snapshot for a single
poll (`IdeStatusProblemSnapshots.kt:47-56`); every observed field follows from it. Three
plugin seams turn one bad sample into a blanked display: `IdeStatusJsonMapper.kt:96-104`
maps `no_matching_work` to an unmarked `Idle`; `LastKnownDisplayCache.kt:117-120` excludes
`Idle` from the display cache; `StatusRefreshCoordinator.kt:103-112` falls back to cache
only for `Unavailable`/`Incompatible`. 40 consecutive samples during the live run were
otherwise `feature-goal | active | fresh`, so this is a narrow transition window.

**Leading hypothesis for the bad sample (not reproduced).**
`IdeStatusService.collectCandidates` issues dozens of separate SELECTs on a read-only
connection opened *without* a transaction
(`SQLiteDatabaseSessionFactory.kt:29-39` invokes the block directly, unlike `transaction()`
which uses `BEGIN IMMEDIATE` at `:49-57`). With no cross-statement snapshot, a concurrent
writer can tear the read and empty the candidate set.

**Operator stop is not recorded (confirmed).** Goal `wfl-20260807-172713-nmrx` was stopped
by the operator via the harness stop button, which killed the `skill-bill goal` process.
Its control row shows `pause_requested: false`, `paused: false`, `pause_reason: null`,
`stop_after_subtask_id: null`, and no `goal_run_sessions` row. The only surviving trace is
an execution lease that stopped heartbeating at `17:30:34Z` and expired at `17:31:04Z`. A
killed process cannot record its own stop, so operator-stop and crash are today
indistinguishable.

**No pause timestamp exists.** `GoalRunnerControlState`
(`runtime-domain/.../GoalRunnerModels.kt:245-253`) carries `paused`, `pauseRequested`,
`pauseConsumed`, `pauseReason` — and no timestamp. Even a graceful pause cannot say when.

**Pause granularity is per-subtask, not per-phase.** `requiresPauseBoundary` is evaluated
only at subtask and planning boundaries (`GoalRunner.kt:516`, `GoalPlanningSweep.kt:497`,
`GoalRunnerWorkflowStores.kt:1078-1141`). `goal pause` therefore already means "pause after
the current subtask"; there is no immediate pause in the runtime at all.

**Reusable termination seam.** `FeatureTaskRuntimeWorkerSupervisor` already exposes
`inspect` / `terminateGracefully` / `terminateForcibly` with `OwnershipMismatch` guarding,
and `GoalRunnerExecutionCoordinator.asWorkerOwnership()` already adapts a
`GoalRunnerExecutionLease` into `FeatureTaskRuntimeWorkerOwnership`.

## Acceptance Criteria

1. A `no_matching_work` response is distinguishable at the plugin's domain boundary from a
   lifecycle-derived idle, without changing the meaning of the `no_matching_work` code.
2. An isolated `no_matching_work` sample observed immediately after a live outcome does not
   change what the widget displays; the previously displayed state is retained.
3. A `no_matching_work` result corroborated by a subsequent consecutive `no_matching_work`
   sample does commit to the idle presentation, so a goal that genuinely finishes still
   settles to idle within a bounded number of polls.
4. A repository with no Skill Bill work at all still reads idle promptly, with no added
   delay on the first poll of a session.
5. No held or cached display is ever presented as authoritative-active; the
   `LastKnownDisplayCache` contract that a cached display may surface only as `Stale` holds.
6. `IdeStatusService`'s candidate collection observes a single consistent database snapshot,
   so a concurrent writer commit cannot tear the read.
7. A live, correctly-bound, durably `running` goal is never reported as `no_matching_work`,
   regardless of concurrent write activity during candidate collection.
8. `GoalRunnerControlState` records when a pause took effect, and both the graceful pause
   path and the immediate stop path populate it.
9. An immediate stop verb exists that writes durable operator intent — paused, a reason
   identifying it as an operator stop, and the pause timestamp — *before* terminating, so
   the record survives even if termination is itself interrupted.
10. The immediate stop verb terminates the goal runner's process tree without orphaning the
    child agent process, refuses when the lease's host or boot identity does not match the
    current machine, and is idempotent when no live lease exists.
11. A goal runner terminated externally (for example by a harness stop button) records
    paused state, an interruption reason, and the pause timestamp before exiting, so an
    externally-killed run is distinguishable from a crash.
12. The ide-status contract exposes whether a pause is requested but not yet consumed, and
    when a pause took effect, as additive optional fields that older consumers ignore.
13. The status widget offers a Stop control and a Pause-after-current-subtask control,
    shown only while an incomplete goal exists for the repository.
14. Once a pause has been requested, the pause control is disabled and reflects that the
    request was registered, including when the pause was requested from the CLI and across
    an IDE restart.
15. The status details popup is legible as a panel — padded, aligned, and visually
    structured — rather than an unpadded HTML label.
16. Plugin documentation that declares the plugin read-only with no workflow mutation is
    updated to describe the controls it now offers and the single mutating contract it uses.

## Constraints

- The plugin keeps depending only on `com.intellij.modules.platform`.
- Contract changes are additive and optional; no `contract_version` bump and no change to
  the meaning of existing fields, including `no_matching_work`.
- The plugin invokes the CLI only. It must not read Skill Bill databases, import runtime or
  JDBC types, or terminate processes itself; process termination belongs to the runtime.
- Pause and stop requests must not be coalesced with status polls.
  `ProcessRunner.runCoalesced` coalesces per instance, so a mutating call sharing the poll
  runner would join an in-flight poll and return that poll's exit code as its own result.
- No change to `IdeStatusSelectionPolicy`'s retention windows or ordering.

## Non-Goals

- No Resume control. Launching a goal already clears a pause via `GoalRunner.resumeForRun`
  (`GoalRunner.kt:247-265`), and `goal resume` explicitly does not start child runs, so a
  Resume button would promise execution it cannot deliver.
- No phase-level pause boundary. `goal pause` remains subtask-granular. If revisited later,
  the seam is the per-phase entry gate at `FeatureTaskRuntimeRunLoop.kt:311-320`
  (`phaseEntryBlockReason`) plus the existing mid-subtask pause path (`pauseAt`,
  `FeatureTaskRuntimeRunReport.Paused`, `GoalSubtaskPauseRelease`).
- No Start control and no goal launching from the IDE; that needs a process host with
  streaming output, which is the deferred tool window.
- No goal planning, checkpointing, or `planningStatus` changes.
- No plugin settings UI for tuning the corroboration threshold.
- No Marketplace publish or signing changes.

## Open Item

Subtask 2's trigger is hypothesis-stage: the torn-read mechanism is established by reading
the code, but the specific statement pair that produced the observed flicker has not been
captured. Acceptance criteria 6 and 7 are written to hold regardless of which pair tore, so
the fix is not blocked on reproducing it.

## Subtasks

1. Plugin transient-idle smoothing: distinguish `no_matching_work` at the domain boundary
   and require corroboration before committing to idle. Covers AC 1–5.
2. Runtime read-snapshot consistency for candidate collection. Covers AC 6–7.
3. Goal stop verb, pause timestamp, and external-termination recording. Covers AC 8–11.
4. Expose pause signals on the ide-status contract. Covers AC 12.
5. Plugin goal controls and popup presentation. Covers AC 13–16.
