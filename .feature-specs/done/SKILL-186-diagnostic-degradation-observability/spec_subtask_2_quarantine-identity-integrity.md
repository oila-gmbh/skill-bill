# SKILL-186 · Subtask 2 — Stop a quarantine entry from naming a diagnostic row that was never written

## Scope

Close the path where a durable, append-only quarantine entry permanently records a
`diagnosticIdentity` that resolves to nothing.

The mechanism, verified in code:

- The run loop's `recordRejectedOutput`
  (`runtime-application/.../featuretask/FeatureTaskRuntimeRunLoop.kt:3548`)
  returns `RejectedOutputDiagnosticService.stableIdentity(...)` unconditionally.
- `FeatureTaskRuntimePhaseRecorder.recordRejectedOutput`
  (`FeatureTaskRuntimePhaseRecorder.kt:179`) wraps the retain, the insert, and the
  rejection measurement in one `database.transaction`. When
  `degradeDiagnosticFailure` catches, that whole transaction has already rolled
  back, so no `rejected_output_diagnostics` row exists.
- The caller persists the returned string as `diagnosticIdentity` on a
  `FeatureTaskRuntimeQuarantineEntry` (`FeatureTaskRuntimeRunLoop.kt:3306`). The
  contract requires it non-blank
  (`orchestration/contracts/feature-task-runtime-quarantine-schema.yaml`,
  `diagnostic_identity`, `minLength: 1`), the store is append-only, and no runtime
  path ever repairs an entry.

In scope: making the degradation observable to the caller, and choosing between
recording the degradation on the entry or refusing the append with a durable
block. Both are acceptable under the parent spec; the entry-marking route needs a
quarantine contract change and the refusal route does not, so weigh that when
deciding.

## Acceptance Criteria

1. The run loop can tell whether a diagnostic write degraded. The identity-returning
   seam no longer reports a well-formed identity for a write that did not land.
2. A quarantine append whose diagnostic degraded either records that fact on the
   entry or is refused with a durable block naming the degradation and its typed
   failure class. A silently dangling `diagnosticIdentity` is not a reachable
   outcome.
3. Whichever route is taken, the outcome is durable: after a crash and resume, the
   state still shows either a marked entry or the block, never an unmarked entry
   pointing at a missing row.
4. Every `diagnosticIdentity` already persisted on an existing quarantine entry
   keeps resolving exactly as it does today, and an existing entry decodes without
   error under the shape this subtask lands.
5. If the quarantine wire shape changes, the change lands in
   `orchestration/contracts/feature-task-runtime-quarantine-schema.yaml` with the
   `FEATURE_TASK_RUNTIME_QUARANTINE_CONTRACT_VERSION` pin
   (`runtime-contracts/.../workflow/FeatureTaskRuntimeSchemaPaths.kt:140`), its
   parity test, `additionalProperties: false` preserved at every object, and
   loud-fail decode at every parse seam.
6. The quarantine store stays append-only: no existing entry is mutated, deleted,
   or rewritten by any runtime path, including on resume and reconciliation.
7. A quarantine append whose diagnostic wrote normally is unchanged in shape and
   behavior, and the regeneration edge it drives still fires as it does today.
8. Every new field or block reason is payload-free: identities, counts, typed
   classes, and sanitized labels only.
9. Regression coverage exercises: a quarantine append whose diagnostic degraded
   reaching the chosen outcome, an unaffected append staying byte-identical in
   shape, an existing pre-change quarantine entry decoding unchanged, and — if the
   contract changed — the schema parity test and a loud-fail on an undeclared wire
   field.
10. `./gradlew check -x sourcesJar` passes from `runtime-kotlin`.

## Non-Goals

- No repair of quarantine entries already written with a dangling identity.
  Existing evidence is not rewritten; only out-of-band operator action may remove
  it.
- No change to the quarantine regeneration cap, the rejection classes, or the
  `REGENERATION_PRODUCER_BY_CONSUMER` edge map.
- No change to which failure classes degrade versus propagate.
- No change to the repair-turn keying, evidence keys, or `stableIdentity`.
- No new operator command surface.

## Dependency Notes

Depends on subtask 1 only for reuse, not correctness: subtask 1's telemetry model
and any signal-inspection helper make reporting a degraded diagnostic cheaper here.
Declared as an optional dependency so this subtask can land first if subtask 1
stalls, at the cost of duplicating a small amount of the reporting path.

## Validation Strategy

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check -x sourcesJar
```

Focused suites: `FeatureTaskRuntimeQuarantineRegenerateTest`,
`FeatureTaskRuntimeDiagnosticDegradationTest`,
`RejectedOutputDiagnosticServiceTest`, and the quarantine schema contract-version
and decode tests.

## Next Path

Verify against durable state that a forced diagnostic-persistence conflict on a
reconciliation-class rejection produces either a marked quarantine entry or a
durable block, and that every `diagnosticIdentity` in the store resolves to a real
row.
