# SKILL-185 — Validation-gate repair turns collide on the producer-evidence key and crash the run

## Context

A SKILL-16 subtask run (`wftr-20260811-201509-n7hz`) died with an uncaught
exception and exit status 1, leaving the goal runner to report a non-terminal
child:

```
Exception in thread "main" skillbill.ports.persistence.model.RejectedOutputDiagnosticError$Conflict:
  Rejected output diagnostic 'wftr-20260811-201509-n7hz:validate:0:1:cursor…
  at …FeatureTaskRuntimeValidationGateCoordinator.execute(FeatureTaskRuntimeValidationGateCoordinator.kt:145)
  at …FeatureTaskRuntimeRunLoop.runDeclaredValidationGateCycle(FeatureTaskRuntimeRunLoop.kt:2604)
  at …FeatureTaskRuntimeRunLoop.runPhase(FeatureTaskRuntimeRunLoop.kt:2085)
  at …FeatureTaskRuntimeRunner.run(FeatureTaskRuntimeRunner.kt:93)
```

The failure key `validate:0:1:cursor` is
`phaseId:generation:attempt:agentId` — the format of
`ProducerOutputEvidence.evidenceKey()`
(`SqliteRejectedOutputDiagnosticRepository.kt:266`), not a
`rejected_output_diagnostics` identity (those are opaque `rod_<sha256>`
hashes). The thrower is `retainProducerOutput`, not the diagnostic insert.

### Root cause

`FeatureTaskRuntimeValidationGateCoordinator.kt:145` drives a repair loop that
launches a **fresh agent** on each turn, up to
`MAX_VALIDATE_GATE_REPAIR_ITERATIONS = 3`
(`FeatureTaskRuntimeValidationGatePolicy.kt:6`), and passes the turn ordinal to
the launcher:

```kotlin
when (val repair = agentRepairLauncher.launch(projection, repairsUsed + 1)) {
```

The interface declares that ordinal
(`ValidationGateCycleModels.kt:43`, `repairIteration: Int`), but the run loop's
implementation **discards** it (`FeatureTaskRuntimeRunLoop.kt:2612`):

```kotlin
agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, _ ->
  launchValidationGateRepair(run = run, state = state, iteration = iteration, …)
```

`launchValidationGateRepair` (`FeatureTaskRuntimeRunLoop.kt:2661`) then calls
`attemptOnce(repairRun, state, iteration, …)` with the unchanged outer phase
iteration on every turn. Both evidence writers keyed off that iteration —
`gateOutput`'s acceptance retain (`FeatureTaskRuntimeRunLoop.kt:3639`) and
`recorder.recordRejectedOutput` → `retainProducerOutput`
(`FeatureTaskRuntimePhaseRecorder.kt:135`) — therefore address the identical
primary key `(workflow_id, validate, generation 0, attempt 1, cursor)` for every
repair turn, with different agent bytes each time.

`retainProducerOutput` writes with `INSERT OR IGNORE`, reads the row back, and
throws `Conflict` when the stored sha/byte-size/payload differ from what it just
tried to write. Turn 2 therefore always conflicts with turn 1's committed row.
Nothing catches it, so the process exits 1.

### Durable evidence confirming the mechanism

| Fact (from `review-metrics.db`) | Value |
| --- | --- |
| `producer_output_evidence` rows for `validate` | exactly one: `validate\|0\|1\|cursor`, 997 bytes, `8f6ac8b0d9da…`, `21:07:48.326Z` |
| `rejected_output_diagnostics` rows for `validate` | none |
| durable `validate` step record | `status=blocked`, `attempt_count=1` |
| workflow row | `workflow_status=blocked`, `current_step_id=validate`, updated `21:09:06` |
| crash timing | 78 s after the evidence write — one further agent launch |
| other diagnostics in the run | `audit` attempts 2 and 3 — distinct attempts, no collision |

Repair turn 1 was *accepted*, so its evidence came from the acceptance path and
committed in its own transaction. Turn 2 produced different bytes, conflicted,
and rolled back the whole recorder transaction — which is why the evidence row
survives while no `validate` diagnostic row exists.

### What this is not

Cross-process attempt reuse is not reachable: `attemptOnce`
(`FeatureTaskRuntimeRunLoop.kt:3417`) persists a `RUNNING` record with
`attemptCount = iteration` before every launch, so the durable watermark is
monotonic and `nextIteration` (`FeatureTaskRuntimeRunState.kt:604`) never
rewinds a resumed phase onto a used attempt number. The gate-repair loop is the
only writer that re-runs an agent without advancing the number, which is exactly
why `GENERATION_SCOPED_PHASE_IDS`
(`FeatureTaskRuntimePhaseWorkflowDefinition.kt:104`) does not cover it: that set
addresses review-generation restarts, and its stated premise — that every other
phase's re-write is byte-identical — does not hold for gate repair turns.

The symmetric case is still open: if repair turn 1 is *rejected* rather than
accepted, turn 2 collides in `RejectedOutputDiagnosticService.record` instead,
because `stableIdentity` is `(workflowId, phaseId, attempt)` and deliberately
generation-blind (`RejectedOutputDiagnosticService.kt:126`). That produces the
same fatal `Conflict` from a different line.

## Intended Outcome

A validation-gate repair cycle runs its full bounded set of turns without any
diagnostic-persistence conflict, each turn's raw evidence is retained and
independently addressable, and no diagnostic-persistence failure can terminate a
run that has otherwise progressed.

## Acceptance Criteria

1. The repair-turn ordinal supplied by
   `ValidationGateAgentRepairLauncher.launch` reaches the producer-evidence key,
   so consecutive repair turns within one phase attempt write distinct rows
   rather than colliding on `(workflow_id, phase_id, generation, attempt,
   agent_id)`.
2. A validation-gate cycle whose repair turns produce different output on each
   turn, up to `MAX_VALIDATE_GATE_REPAIR_ITERATIONS`, completes its cycle
   without a `RejectedOutputDiagnosticError.Conflict` and without a non-zero
   process exit.
3. The durable phase `attempt_count` and the semantic fix-loop budget are
   unchanged by gate repair turns: a phase that ran one attempt with three
   repair turns still records `attempt_count = 1` and is charged one attempt.
4. The rejected-output diagnostic identity distinguishes repair turns within one
   attempt, so two consecutively *rejected* repair turns each record a
   diagnostic instead of the second throwing `Conflict`. Identities already
   persisted on quarantine entries as `diagnosticIdentity` continue to resolve.
5. A diagnostic-persistence failure — conflict, permission, or corrupt-record —
   never terminates the run. It degrades to a durable, payload-free operator
   signal and the run proceeds, matching the rule
   `FeatureTaskRuntimePhaseRecorder.recordRejectionMeasurement` already applies
   to telemetry.
6. When a conflict is degraded rather than thrown, the recorded operator signal
   names the conflicting key, the phase, and the attempt, so the condition is
   diagnosable from durable state alone. The current failure leaves no record of
   what conflicted.
7. `readProducerOutput` continues to resolve the evidence a consumer needs when
   several repair turns exist for one attempt, and the reconciliation and
   quarantine paths that read producer evidence
   (`FeatureTaskRuntimeRunLoop.kt:3247`, `:3333`) keep working unchanged.
8. Regression coverage exercises: multi-turn gate repair with differing output
   per turn, two consecutively rejected repair turns, a repeated byte-identical
   retain (still an idempotent no-op), and a forced persistence conflict proving
   the run survives it.
9. The runtime check suite passes.

## Constraints

- Do not advance the phase `attempt` to separate repair turns: `attempt`
  feeds the durable watermark, the fix-loop budget, and
  `FeatureTaskRuntimeFixLoopPolicy`, so inflating it would charge honest gate
  repairs to the semantic repair budget and block runs early.
- Preserve the conflict check's real purpose — detecting genuinely divergent
  evidence for the same logical capture. Replacing it with a blanket upsert
  would silently overwrite retained evidence a quarantine entry points at.
- `rejected_output_diagnostics.identity` is stored durably on quarantine
  entries; any identity change must keep already-persisted identities readable.
- Keep every degraded signal payload-free: no agent output, prompts, database
  paths, or process output in operator-visible text.
- Migrations must self-heal on existing databases, per the runtime's
  unconditional column-ensure convention.

## Non-Goals

- No change to the validation-gate repair policy itself: the cap stays at
  `MAX_VALIDATE_GATE_REPAIR_ITERATIONS = 3` and the findings projection is
  untouched.
- No change to review-generation scoping or `GENERATION_SCOPED_PHASE_IDS`
  semantics for `review` / `implement_fix`.
- No change to evidence retention windows, cleanup, or the oversized/expired
  lifecycle rules.
- No re-run or repair of the already-blocked `wftr-20260811-201509-n7hz`
  workflow as part of this feature.

## Subtasks

1. Carry the repair-turn ordinal into the producer-evidence and diagnostic
   identity keys so repair turns within one attempt are independently
   addressable, without touching the phase attempt watermark or fix-loop budget.
2. Make diagnostic persistence non-fatal: degrade conflict, permission, and
   corruption failures to a durable payload-free operator signal that names the
   conflicting key, and cover the whole class with regression tests.

## Validation Strategy

Run the runtime check suite, with the sourcesJar exclusion noted in the repo's
known pre-existing failure:

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check -x sourcesJar
```

Focused suites to exercise directly: `RejectedOutputDiagnosticServiceTest`,
`SqliteRejectedOutputDiagnosticRepositoryTest`,
`FeatureTaskRuntimeProjectionRejectionTest`,
`FeatureTaskRuntimeQuarantineRegenerateTest`, and the validation-gate
coordinator tests.

## Next Path

After both subtasks land, resume the blocked SKILL-16 subtask from
`last_resumable_step` and confirm the validate phase completes its gate repair
cycle. Verify against durable state that one phase attempt with multiple repair
turns yields several producer-evidence rows and a single `attempt_count`.
