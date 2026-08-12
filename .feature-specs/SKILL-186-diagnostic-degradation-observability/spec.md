# SKILL-186 — Degraded diagnostic signals are write-only and can point at evidence that was never written

## Context

SKILL-185 stopped a validation-gate repair cycle from killing a run: repair turns
within one phase attempt now key their evidence by a repair-turn ordinal, and a
diagnostic-persistence failure degrades to a durable payload-free
`FeatureTaskRuntimeDiagnosticSignal` instead of propagating an uncaught
`RejectedOutputDiagnosticError.Conflict`.

That fix traded a crash for two diagnosability gaps. Both were raised by the
review of SKILL-185 (run `rvw-20260812-064823-k4qm`, findings F-003 and F-004),
verified against the code, and deliberately deferred because each needs work the
crash fix did not.

### Gap 1 — the degraded signal is write-only, and the read path misattributes it

`FeatureTaskRuntimePhaseRecorder.degradeDiagnosticFailure`
(`FeatureTaskRuntimePhaseRecorder.kt:278`) appends a
`FeatureTaskRuntimeDiagnosticSignal` under
`FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_ARTIFACT_KEY` and returns null. Three
things follow from that:

- `loadDiagnosticSignals` (`FeatureTaskRuntimePhaseRecorder.kt:349`) has no
  production caller. Only tests read it, so nothing an operator or the IDE can
  see reports that evidence was lost. `FeatureTaskRuntimeStatusProjection`
  (`FeatureTaskRuntimeStatusModels.kt`) already surfaces audit-repair progress
  and gate-run counts and is the natural reader.
- No telemetry field is emitted, even though `recordRejectionMeasurement`
  (`FeatureTaskRuntimePhaseRecorder.kt`) sits immediately beside it and is the
  established content-free seam
  (`LifecycleTelemetryRepository.featureTaskRuntimeRejection`,
  `LifecycleTelemetryRepository.kt:27`). The failure class is therefore not
  countable across runs.
- On the read path the degradation changes what the operator is told.
  `producerOutput` converts `Corrupt`, `Permission`, and `Persistence` into null,
  and the caller at `FeatureTaskRuntimeRunLoop.kt:3262` blocks with
  `NEEDS_USER_ACTION` and the reason "exact raw evidence for attempt N is
  unavailable". The store actually threw; the operator reads "absent".

`docs/observability-policy.md` requires a record at exactly this seam — "a
`runCatching` or `catch` that continues instead of rethrowing" — naming the seam,
the value used, the value expected, and why. It also requires each record to name
a specific cause, and warns that a substitution changing a contract the caller
depends on should loud-fail rather than log-and-continue. The current block reason
substitutes "unreadable" with "absent" in the operator's only visible artifact.

A second-order hole: `persistDiagnosticSignal`
(`FeatureTaskRuntimePhaseRecorder.kt:322`) swallows its own failure with a bare
`catch` and leaves no record at all. That is intentional — the store that just
rejected the evidence is the same store the signal would land in — but it is
currently invisible even in aggregate.

### Gap 2 — a quarantine entry can name a diagnostic row that was never written

The run loop's `recordRejectedOutput` returns
`RejectedOutputDiagnosticService.stableIdentity(...)` unconditionally
(`FeatureTaskRuntimeRunLoop.kt:3548`). When the recorder degrades, the whole
`database.transaction` around the retain and the insert has rolled back, so no
`rejected_output_diagnostics` row exists — but the caller still receives a
well-formed `rod_<sha256>` string.

That string is then persisted as `diagnosticIdentity` on a
`FeatureTaskRuntimeQuarantineEntry` (`FeatureTaskRuntimeRunLoop.kt:3306`). The
quarantine contract requires the field to be a non-blank stable identity
(`orchestration/contracts/feature-task-runtime-quarantine-schema.yaml`,
`diagnostic_identity`, `minLength: 1`), and the store is append-only and never
repaired. Reachable path: a reconciliation-class rejection whose producer-evidence
retain conflicts leaves a permanent quarantine entry pointing at an identity that
resolves to nothing, with no field on the entry saying the diagnostic degraded.

This is the inverse of the SKILL-185 constraint that already-persisted identities
stay resolvable: SKILL-185 protected old identities and then allowed a new
dangling one.

## Intended Outcome

Every degraded diagnostic-persistence failure is visible where an operator
already looks and countable across runs; a block reason distinguishes evidence
that is absent from evidence the store refused to hand over; and no durable
quarantine entry ever names a diagnostic row that was not written.

## Acceptance Criteria

1. A degraded diagnostic-persistence failure emits a content-free telemetry
   measurement carrying at minimum the workflow, the phase, the attempt, the
   repair turn when scoped to one, the generation, the operation, the typed
   failure class, and the conflicting key — through the same
   `LifecycleTelemetryRepository` seam `recordRejectionMeasurement` already uses.
2. The telemetry emission cannot fail the run: a throwing telemetry sink leaves
   the degradation behavior and the durable signal unchanged, matching the rule
   `recordRejectionMeasurement` already applies.
3. `FeatureTaskRuntimeStatusProjection` reports the degraded-signal count and the
   most recent signal's typed failure class, phase, and attempt, so the condition
   is visible from the existing status surface without reading the database
   directly.
4. A phase blocked because producer evidence could not be read distinguishes
   "no evidence was retained for this attempt" from "retained evidence exists but
   the diagnostic store refused it", and names the typed failure class in the
   second case. The reason stays payload-free.
5. A quarantine entry is never persisted with a `diagnosticIdentity` whose
   diagnostic row was not written. Either the entry records that the diagnostic
   degraded, or the quarantine append is refused with a durable block naming the
   degradation — never a silently dangling identity.
6. Every `diagnosticIdentity` already persisted on an existing quarantine entry
   keeps resolving exactly as it does today, and an existing entry stays readable
   under whatever contract shape this feature lands.
7. Any change to the quarantine wire shape lands as a schema change in
   `orchestration/contracts/feature-task-runtime-quarantine-schema.yaml` with its
   Kotlin `FEATURE_TASK_RUNTIME_QUARANTINE_CONTRACT_VERSION` pin, its parity
   test, and loud-fail decode at every parse seam, per the runtime contract
   convention in `AGENTS.md`.
8. Every new or changed record stays payload-free: no agent output, prompt text,
   database path, or process output reaches telemetry, the status projection, a
   block reason, or a quarantine entry.
9. Regression coverage exercises: a degraded conflict emitting telemetry, a
   throwing telemetry sink not altering the outcome, the status projection
   reporting a degraded signal, both block-reason branches, a quarantine append
   whose diagnostic degraded, and an existing quarantine entry decoding unchanged.
10. The runtime check suite passes.

## Constraints

- Do not undo the SKILL-185 degradation. A diagnostic-persistence failure must
  still never terminate a run that has otherwise progressed; this feature makes
  the degradation visible, it does not make it fatal again.
- Keep `InvalidRequest` and `InvalidConfiguration` loud. SKILL-185 deliberately
  excluded them from `degradableFailureClass`, and they must keep propagating as
  typed failures rather than becoming signals.
- Telemetry must stay content-free per
  `orchestration/telemetry-contract/PLAYBOOK.md`: identifiers, counts, and
  sanitized labels only, never prompts, payloads, transcripts, or tool output.
- The quarantine store is append-only and never mutated or deleted by any runtime
  path. Do not repair existing entries in place, and do not delete evidence.
- `persistDiagnosticSignal` must remain best-effort. If the store that rejected
  the evidence also rejects the signal, the run still proceeds.
- Migrations and contract changes must self-heal on existing databases, per the
  runtime's unconditional column-ensure convention.

## Non-Goals

- No change to the repair-turn keying, the producer-evidence primary key, the
  `rejected_output_diagnostics` unique key, or `stableIdentity`. SKILL-185 owns
  those and they are correct.
- No change to which failure classes degrade versus propagate.
- No change to the validation-gate repair policy, its cap, or its findings
  projection.
- No change to evidence retention windows, cleanup, or the oversized/expired
  lifecycle rules.
- No new operator command surface beyond the existing status projection and the
  `rejected-output` CLI; a dedicated inspection command is out of scope.

## Subtasks

1. Give the degraded diagnostic-persistence signal a real operator seam:
   content-free telemetry, a status-projection reader, and a block reason that
   distinguishes unreadable evidence from absent evidence.
2. Stop a quarantine entry from recording a `diagnosticIdentity` whose row was
   never written, without mutating any entry already persisted.

## Validation Strategy

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check -x sourcesJar
```

Focused suites to exercise directly:
`FeatureTaskRuntimeDiagnosticDegradationTest`,
`FeatureTaskRuntimeStatusServiceTest`,
`FeatureTaskRuntimeQuarantineRegenerateTest`,
`FeatureTaskRuntimeProjectionRejectionTest`, and
`RejectedOutputDiagnosticServiceTest`.

## Next Path

After both subtasks land, confirm from durable state that a forced
diagnostic-persistence conflict produces a telemetry row, a status projection
entry, and either a marked or refused quarantine append — and that no quarantine
entry in the store names an identity that does not resolve.
