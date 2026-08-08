# SKILL-168 · Subtask 3 — Goal stop verb, pause timestamp, and external-termination recording

## Scope

Give the runtime a way to stop a goal immediately and to record *when* and *why* a goal
paused, so an operator stop is never indistinguishable from a crash. Entirely runtime-side;
no plugin or wire change (the wire exposure is subtask 4).

Three changes:

1. **Pause timestamp.** `GoalRunnerControlState`
   (`runtime-domain/src/main/kotlin/skillbill/goalrunner/model/GoalRunnerModels.kt:245-253`)
   carries `paused`, `pauseRequested`, `pauseConsumed`, `pauseReason` and no timestamp. Add
   a nullable `pausedAt`, populated wherever `paused` transitions to true — the graceful
   boundary pause (`manifestStore.pauseAtBoundary`), the new stop verb, and the shutdown
   hook. Its existing invariant `require(!paused || pauseReason != null)` is the model for
   the new one: a paused control state must carry a timestamp.

2. **Immediate stop verb.** A new `skill-bill goal stop <ISSUE_KEY> --repo-root <path>`
   alongside `GoalPauseCommand` / `GoalResumeCommand`
   (`runtime-cli/src/main/kotlin/skillbill/cli/goal/GoalCliCommands.kt:368-400`), backed by a
   new use case on `GoalRunnerStatusService` next to `pause` (`:137-166`).

3. **External-termination recording.** A JVM shutdown hook in the goal runner so a process
   killed from outside — the common case, a harness stop button — records paused state
   before exiting. `grep addShutdownHook|SIGTERM` across `runtime-cli` and
   `runtime-application` currently returns nothing.

### Design decision: intent before termination

The stop verb must write durable intent *first*, then terminate. A killed process cannot
record its own stop, which is exactly why goal `wfl-20260807-172713-nmrx` is
indistinguishable from a crash today. Intent-first is also self-healing: if termination
fails or is itself interrupted, `paused = true` already satisfies
`requiresPauseBoundary`, so the still-running goal stops at its next boundary anyway.

### Design decision: reuse the worker supervisor, do not write new process code

`FeatureTaskRuntimeWorkerSupervisor`
(`runtime-ports/src/main/kotlin/skillbill/ports/taskruntime/FeatureTaskRuntimeWorkerSupervisor.kt`)
already exposes `inspect`, `terminateGracefully`, and `terminateForcibly` over a
`FeatureTaskRuntimeWorkerOwnership`, with `OwnershipMismatch` / `Unsupported` results that
encode "cannot safely act". `GoalRunnerExecutionCoordinator.asWorkerOwnership()`
(`runtime-application/.../goalrunner/GoalRunnerExecutionCoordinator.kt:113-126`) already
adapts a `GoalRunnerExecutionLease` into that shape. Reuse both rather than introducing a
second process-control seam.

Terminate gracefully first, then forcibly after a bounded wait, so the child agent gets a
chance to exit cleanly.

### Design decision: refuse rather than guess on identity mismatch

The lease records `host_identity`, `boot_identity`, `pid`, and `process_birth_token`. A pid
is only meaningful on the machine and boot that produced it; acting on a mismatch risks
killing an unrelated process. Refuse with a typed outcome — do not fall back to killing by
pid alone. `NoopFeatureTaskRuntimeWorkerSupervisor` returns `Unsupported` for every
inspection, so a seam wired with the default must never terminate.

### Design decision: shutdown hook writes, never blocks

The hook runs on an already-dying JVM under an unknown time budget. It must do one bounded
durable write and nothing else — no termination, no child supervision, no network. It must
be idempotent with the stop verb (a stop that terminates the process will also trigger the
hook) and must not overwrite a more specific `pauseReason` already written by the stop verb.
SIGKILL cannot be intercepted; the hook covers SIGTERM and normal JVM exit only, and the
lease-expiry inference already landed remains the backstop.

## Acceptance Criteria

1. `GoalRunnerControlState` carries a nullable pause timestamp, and a control state with
   `paused = true` is rejected at construction when the timestamp is absent, mirroring the
   existing `pauseReason` invariant.
2. Every path that sets `paused = true` — graceful boundary pause, the stop verb, and the
   shutdown hook — populates the pause timestamp from the injected clock, never from a
   synthesized or wall-clock-at-read value.
3. A `skill-bill goal stop <ISSUE_KEY>` verb exists, accepts `--repo-root`, and returns a
   typed result distinguishing: stopped, already stopped, no live lease, identity mismatch,
   and goal not found.
4. The stop verb writes durable operator intent — `paused = true`, a reason identifying it
   as an operator stop, and the pause timestamp — *before* attempting termination, and that
   write is observable even when termination subsequently fails.
5. The stop verb terminates the goal runner process tree, attempting graceful termination
   before forcible termination, so the child agent process is not orphaned.
6. The stop verb refuses to terminate when the lease's host or boot identity does not match
   the current process, and when the supervisor reports the owner as unsupported or
   ambiguous; it reports the refusal rather than terminating by pid.
7. The stop verb is idempotent: invoking it against a goal with no live lease, or invoking
   it twice, succeeds without error and without a second termination attempt.
8. A goal runner terminated by SIGTERM records `paused = true`, an interruption reason
   distinguishable from an operator stop, and the pause timestamp before the process exits.
9. The shutdown hook performs at most one bounded durable write, does not overwrite a
   `pauseReason` already written by the stop verb, and never blocks JVM shutdown beyond a
   bounded budget.
10. Existing pause, resume, reset, and replan behavior is unchanged, and the existing runtime
    test suites stay green.

## Non-Goals

- No wire or schema change; exposing these signals is subtask 4.
- No phase-level pause boundary; `goal pause` stays subtask-granular.
- No Resume changes and no new resume semantics.
- No attempt to intercept SIGKILL, which is not interceptable.
- No plugin work; the plugin consumes this verb in subtask 5.
- No change to lease acquisition, heartbeat cadence, or `LEASE_DURATION`.

## Dependency Notes

No dependency on subtasks 1 or 2. Subtask 4 depends on this one for the pause timestamp and
the reason vocabulary; subtask 5 depends on the stop verb existing.

Coordinate with the already-landed lease-expiry inference: a goal whose lease has expired
already projects as `paused`. Once this subtask lands, a stopped goal will additionally
carry a durable reason and timestamp, and the projector should prefer that durable record
over the inferred one when both are present.

## Validation Strategy

- Unit tests on the new use case with a fake manifest store and a fake supervisor covering
  each typed outcome in AC 3, asserting for AC 4 that the durable write is observable when
  the fake supervisor's termination throws or returns false.
- A test asserting graceful termination is attempted before forcible (AC 5), via an ordered
  fake supervisor.
- Tests for identity mismatch and `Unsupported` refusing to terminate (AC 6), asserting the
  fake supervisor's terminate methods are never invoked.
- Idempotence tests: no lease, and two consecutive stops (AC 7).
- A construction test for the new `paused`/timestamp invariant (AC 1) and clock-injection
  tests for AC 2.
- Shutdown-hook coverage exercising the hook body directly rather than by killing a JVM,
  asserting the single bounded write, the reason precedence in AC 9, and idempotence with a
  prior stop-verb write.
- Full runtime suite plus the goal-runner CLI tests.

## Resolved Decisions

- **Legacy decode.** A durable record written before `paused_at` existed is backfilled on the raw
  map, before the `GoalRunnerControlState` constructor runs, so no pre-existing paused row can
  hard-fail the new invariant. The backfill prefers `execution_lease.heartbeat_at` and otherwise
  uses `LEGACY_UNKNOWN_PAUSED_AT` (`1970-01-01T00:00:00Z`), which reads as "paused, time unknown"
  and is unmistakably not a real pause time.
- **Pause-reason vocabulary.** Four values, all additive: the existing `operator_request` and
  `stop_after_subtask`, plus `operator_stop` (the stop verb) and `runner_interrupted` (the shutdown
  hook). The last two are what make an operator stop distinguishable from an external kill.
- **Graceful-to-forcible wait.** 5 seconds, polled every 250 ms. Counted polls rather than a
  wall-clock deadline, so a fixed test clock cannot spin the loop forever.
- **Shutdown-write budget.** 2 seconds. The hook joins a daemon writer thread for that long and
  then returns regardless, so a blocked database can never stall JVM exit.
- **Whitelist strictness.** `paused_at` is added to the allowed-key set and nothing is relaxed. An
  older binary reading a record that carries `paused_at` fails loudly; goal runner durable state is
  same-binary-version, so this is accepted rather than worked around.

## Next Path

Subtask 4 — expose the pause signals on the ide-status contract so the IDE can render them.
