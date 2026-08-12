# SKILL-186 · Subtask 1 — Give the degraded diagnostic-persistence signal a real operator seam

## Scope

Make a degraded diagnostic-persistence failure visible where an operator already
looks, countable across runs, and honestly attributed in the block reason it
causes.

In scope:

- `FeatureTaskRuntimePhaseRecorder.degradeDiagnosticFailure`
  (`runtime-application/.../featuretask/FeatureTaskRuntimePhaseRecorder.kt:278`)
  gains a content-free telemetry emission alongside the durable
  `FeatureTaskRuntimeDiagnosticSignal` append it already performs.
- A new content-free measurement model in `runtime-domain` beside
  `FeatureTaskRuntimeRejectionMeasurement`
  (`runtime-domain/.../taskruntime/model/FeatureTaskRuntimeHandoffFoundationModels.kt:140`),
  with its `toTelemetryMap()` and contract-version pin, plus the matching
  default-implemented method on `LifecycleTelemetryRepository`
  (`runtime-ports/.../persistence/LifecycleTelemetryRepository.kt:27`) and its
  SQLite binding.
- `FeatureTaskRuntimeStatusProjection`
  (`runtime-application/.../model/FeatureTaskRuntimeStatusModels.kt`) and
  `FeatureTaskRuntimeStatusService.status`
  (`runtime-application/.../featuretask/FeatureTaskRuntimeStatusService.kt:34`)
  read the durable signal list via the existing
  `FeatureTaskRuntimePhaseRecorder.loadDiagnosticSignals`
  (`FeatureTaskRuntimePhaseRecorder.kt:349`), which currently has no production
  caller.
- The producer-evidence read block at
  `runtime-application/.../featuretask/FeatureTaskRuntimeRunLoop.kt:3262`
  distinguishes "no evidence retained" from "the diagnostic store refused it".

The read-path distinction needs a signal the run loop can act on. Today
`recorder.producerOutput` returns null for both "absent row" and "store threw",
so the run loop cannot tell them apart. Resolving that — a richer return, an
out-parameter, or a post-hoc `loadDiagnosticSignals` consultation keyed to the
operation — is part of this subtask.

## Acceptance Criteria

1. A degraded diagnostic-persistence failure emits one content-free telemetry
   measurement through `LifecycleTelemetryRepository`, carrying the workflow, the
   phase, the attempt, the repair turn when the failure was scoped to one, the
   generation, the operation label, the typed failure class, and the conflicting
   key.
2. A telemetry sink that throws does not change the degradation outcome: the
   durable `FeatureTaskRuntimeDiagnosticSignal` is still appended, the caller
   still receives null, and the run still proceeds — the same rule
   `FeatureTaskRuntimePhaseRecorder.recordRejectionMeasurement` already applies.
3. The new measurement model pins a contract version, exposes a `toTelemetryMap()`
   whose keys are asserted by a parity test, and is registered in the
   `@OpenBoundaryMap` allow-list in both `runtime-kotlin/ARCHITECTURE.md` and
   `RAW_MAP_OPEN_BOUNDARY_ALLOWLIST` so the architecture tests stay green.
4. `FeatureTaskRuntimeStatusProjection` reports the degraded-signal count and the
   most recent signal's typed failure class, phase, and attempt; a workflow with
   no degraded signals reports the absent state rather than a fabricated zero-value
   entry.
5. `FeatureTaskRuntimeStatusService.status` populates that field from durable
   state, and a malformed durable signal list loud-fails through the existing
   typed workflow-state error rather than being silently dropped.
6. A phase blocked because producer evidence could not be read states which case
   it hit: no retained evidence for that attempt, or retained evidence the
   diagnostic store refused. The second case names the typed failure class.
7. Both block-reason branches keep the existing `NEEDS_USER_ACTION` disposition
   and `childNeverLaunched` behavior, so resume semantics are unchanged.
8. Every new record is payload-free: no agent output, prompt text, database path,
   or process output reaches the measurement, the status projection, or the block
   reason.
9. Regression coverage exercises: a degraded conflict emitting exactly one
   measurement with the expected content-free fields, a throwing telemetry sink
   leaving the durable signal and the null return intact, the status projection
   reporting a degraded signal and reporting absence when there is none, and both
   block-reason branches.
10. `./gradlew check -x sourcesJar` passes from `runtime-kotlin`.

## Non-Goals

- No change to which failure classes degrade versus propagate;
  `degradableFailureClass` stays as SKILL-185 left it.
- No new operator command; the existing status projection and `rejected-output`
  CLI are the only surfaces.
- No change to the quarantine store or `diagnosticIdentity`; subtask 2 owns that.
- `persistDiagnosticSignal`'s best-effort `catch` stays best-effort. Do not make
  it fatal and do not add a durable record inside it — the store it would write to
  is the one that just failed.
- No change to the repair-turn keying, evidence keys, or `stableIdentity`.

## Dependency Notes

Independent of subtask 2 and safe to land first. Subtask 2 may reuse this
subtask's telemetry model and any signal-inspection helper this subtask adds, so
landing this first reduces subtask 2's surface; the reverse order also works with
a small amount of duplication.

## Validation Strategy

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check -x sourcesJar
```

Focused suites: `FeatureTaskRuntimeDiagnosticDegradationTest`,
`FeatureTaskRuntimeStatusServiceTest`, `FeatureTaskRuntimeProjectionRejectionTest`.

## Next Path

Hand off to subtask 2 with the telemetry model and any signal-inspection helper in
place, so the quarantine path can report a degraded diagnostic without inventing a
second mechanism.
