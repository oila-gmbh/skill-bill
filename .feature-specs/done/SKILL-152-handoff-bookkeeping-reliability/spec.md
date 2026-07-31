# SKILL-152 Handoff Bookkeeping Must Not Block Correct Runs

## Intended Outcome

A feature-task run whose implementation is complete and correct is never stopped by the runtime's own bookkeeping at a phase handoff. When a schema gate rejects an output, the producing phase learns which constraint it violated and repairs it inside its bounded fix loop. When a review generation restarts, immutable evidence from the prior generation neither collides with nor is destroyed by the new one. The privacy boundary keeping raw agent responses in the private diagnostic store is preserved exactly.

## Problem Statement

Two independent defects in the SKILL-134 evidence and diagnostics layer both end the same way: a run with correct, complete work in the worktree blocks at a handoff on a runtime bookkeeping failure, and the operator is told nothing actionable.

### Defect 1 — schema rejection carries no repairable information

A run blocked at the `implement` → `review` handoff after three identical rejections:

```
Rejected output violated 'producer-projection' at '/reconciliation_evidence'
```

The producer gate composes a precise failure — validator location, violated constraint, offending field — and then discards it. `payloadFreeRejectionReason` reduces it to a rule name and a JSON pointer, and that stripped string is the only thing carried into the retry prompt. The retried agent is told *which field* it got wrong but never *how*, while the prompt continues to show it the same correct example shape it already followed. Nothing distinguishes attempt 2 from attempt 1, so the loop cannot converge and the cap is reached.

The suppression is over-broad rather than intended. SKILL-134 required raw rejected *bodies* to stay out of telemetry and status surfaces; it did not ask for the validator's schema-authored constraint text to be withheld from the agent that authored the payload. Two artifacts confirm the regression: `boundedSchemaGateDetail` is documented as the bound on detail carried into "a blocked row or a retry prompt" and now bounds a fixed-length string with no detail in it, and the same payload-free reason is written to the diagnostic row, so even the private record's `reason` column has lost the constraint.

Underneath sits a second cost: some rejection classes carry no governed meaning. An unknown key on a closed two-field projection object is pure noise, yet it fails validation and burns attempts a deterministic pre-validation canonicalization would never have spent.

### Defect 2 — restarting a review generation collides with immutable evidence

A review retry stopped with a persistence conflict on `wftr-…:review:1`, an immutable key where earlier evidence already existed. No prior evidence was deleted and no findings were lost, but the run could not advance.

`resetInvalidatedReviewGeneration` deliberately clears the review attempt watermark so a fresh generation is not blocked as "fix loop exhausted" before it launches; the code comment states that intent. `producer_output_evidence` is keyed `(workflow_id, phase_id, attempt)` with no generation dimension, and nothing deletes from it except a retention sweep on `recorded_at`. The counter rewinds, the evidence table does not, and the two disagree about what "attempt 1" denotes. The next accepted review output at the rewound attempt fails its read-back equality check and raises `Conflict`.

The write-once guard is correct: `INSERT OR IGNORE` plus a read-back comparison makes an identical re-write idempotent and a differing one fatal, which is what immutable evidence requires. The key is wrong, not the guard. Relaxing the SQL would not help, and `INSERT OR REPLACE` would silently destroy the prior generation's evidence — the exact loss the guard exists to prevent.

## Evidence

### Defect 1

Running the real validator against every way `reconciliation_evidence` can fail:

```
extra-key                 $.reconciliation_evidence: property 'notes' is not defined in the
                          schema and the schema does not allow additional properties
missing-evidence          $.reconciliation_evidence: required property 'evidence' not found
string-instead-of-object  $.reconciliation_evidence: string found, object expected
reconciled-false          $.reconciliation_evidence.reconciled: must be the constant value 'true'
blank-evidence            $.reconciliation_evidence.evidence: must be at least 1 characters long
baseline-valid            ACCEPTED
```

The observed pointer had no child segment, so the incident is one of the first three classes; the last two report at a child path and are excluded. All three surviving classes are the same repair — one malformed field on an otherwise-complete receipt — and all three are invisible to the agent under the current reason text.

The probe also bounds the privacy question empirically: every message is schema-authored text plus, at most, an offending property *name*. No values and no body fragments appear in any of them.

Sites:

- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoop.kt:2185` — `payloadFreeRejectionReason` discards the validator message.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoop.kt:2318` and `:2244` — every gate and catch funnels through it.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoop.kt:1928,1947` — the stripped reason becomes `priorSchemaFailure`.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimePhasePromptComposer.kt:140` — `retryCorrectionDirective` echoes it verbatim; its `:198` comment asserts field-level reasons "already pinpoint the offending field", which no longer holds.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimePlanningProjectionGate.kt:56` — the rich failure is composed here and lost at the caller.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunnerPolicies.kt:131` — the existing bound, and its stale KDoc.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/contracts/workflow/FeatureTaskRuntimePlanningProjectionSchemaValidator.kt:64` — `formatReason` prepends an instance location the networknt message already carries, so every reason double-prints it.
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/FeatureTaskRuntimeProjectionCanonicalization.kt:71` — the existing `reconciliation_evidence` canonicalization arm.

### Defect 2

A live workflow in the local runtime database is already in the collision-primed state:

```
durable phase record:  review  status=running  attempt_count=1
producer evidence:     review  attempt=1  sha cf794f6a0c
                       review  attempt=2  sha e630546f6d
```

The attempt counter has rewound below the highest retained evidence attempt. One of eighteen workflows carrying review evidence is in this state, so the defect is reachable but path-specific, not systemic.

Sites:

- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunState.kt:158` — `resetInvalidatedReviewGeneration` clears the review and implement-fix attempt watermarks; `:145` documents why the reset is required.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunState.kt:530` — `nextIteration` derives the attempt from the cleared watermark and in-memory outputs, neither of which sees retained evidence.
- `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/SqliteRejectedOutputDiagnosticRepository.kt:113` — insert, read-back, and the `Conflict` raise.
- `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/core/DatabaseSchema.kt:67` — the `(workflow_id, phase_id, attempt)` primary key with no generation dimension.
- `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/SqliteRejectedOutputDiagnosticRepository.kt:168` — the only delete, a retention sweep on `recorded_at`.

## Acceptance Criteria

1. A schema-gate rejection that re-enters a phase's bounded fix loop carries the validator's constraint text — the violated rule, the expected shape, and the offending field — into the retry prompt, not a bare rule name and JSON pointer.
2. The retry-prompt reason and the durable operator-facing blocked reason are distinct values, so restoring detail to the retry path leaves every existing payload-free guarantee for blocked rows, telemetry, and status surfaces byte-for-byte unchanged.
3. Constraint text carried into a retry prompt is schema-authored only: it may name a violated keyword, an expected type or shape, and an offending property name, and never contains a field value, a body fragment, or any span of the agent's raw response.
4. All carried constraint detail passes through the single existing bound so a runaway validator message cannot widen a retry prompt.
5. Every rejection path that re-enters a fix loop — producer-projection, consumer-projection, phase-output, and audit-repair-plan — uses this one feedback channel; no gate is left echoing a bare pointer.
6. The private diagnostic row records the constraint text alongside the raw response, so an operator inspecting a blocked run sees why the output was rejected without reconstructing it from the payload.
7. A closed projection object rejected solely for unknown keys that carry no governed meaning is deterministically canonicalized before validation, so that class never consumes a fix-loop attempt.
8. Canonicalization added under criterion 7 never synthesizes a missing field, coerces a type, drops a governed field, or removes a value the schema would have accepted.
9. A rejection reason reports each violated instance location exactly once.
10. A producer rejected for asserting `reconciliation_evidence.reconciled: false` on a `completed` envelope is told in its retry prompt that a completed receipt asserts a reconciled tree and that genuinely incomplete work leaves the phase through a `blocked` or `failed` envelope instead. The directive names the path; it does not implement continuation semantics.
11. Retained producer output evidence is addressed by a key that stays unique when a review generation restarts and the attempt watermark rewinds, so a correct retry can record its evidence.
12. Evidence retained by a prior review generation is never overwritten, silently replaced, or deleted to resolve a key collision.
13. Restarting a review generation remains possible without the phase being blocked as fix-loop exhausted; the existing watermark reset keeps working.
14. Workflows already carrying evidence at an attempt at or above the current watermark reconcile idempotently on the next run, without operator surgery on the database and without discarding retained evidence.
15. A retry that produces byte-identical output to retained evidence stays idempotent rather than raising a conflict.
16. Acceptance and rejection tests cover extra-key, missing-required, and wrong-type rejections on a closed projection object, and assert the retry prompt names the violated constraint in each case.
17. A regression fixture reproduces each blocking run — a malformed `implementation_receipt`, and a review generation restart over retained evidence — and proves the run advances instead of blocking.
18. Privacy tests prove no span of a raw agent response reaches a retry prompt, a blocked reason, a telemetry event, or a status surface.

## Constraints

- Preserve the SKILL-134 privacy contract: raw rejected bodies stay in the private diagnostic store, reachable only through `skill-bill rejected-output`.
- Preserve write-once evidence semantics. Do not resolve a key collision with `INSERT OR REPLACE`, a delete, or any path that loses a prior generation's evidence.
- Do not weaken any projection contract, relax a schema constraint, or widen an allowlist to make agent output easier to satisfy.
- Preserve the shared-validation-function parity stated in `AGENTS.md`: one validation function and one validator port across the producer gate and the consumer launch seam.
- Leave fix-loop attempt caps and the bounded retry policy unchanged; this feature improves the information each attempt receives, never the number of attempts.
- Keep canonicalization inside the single shared parse function so the producer gate and the consumer seam observe identical behavior with no per-seam copy.
- Any canonicalization that discards agent-authored text states that behavior explicitly in its contract documentation.
- Any schema change to a runtime table follows the migration recipe in `AGENTS.md`, with a matching contract-version constant, a parity test, a typed error, and loud-fail parse seams.
- Subtask 2 must not depend on SKILL-150 landing first. If a durable review-generation identity already exists when it is implemented, consume it rather than minting a parallel one.

## Non-Goals

- Changing `reconciliation_evidence.reconciled` `const: true` semantics, or building the continuation path for honestly incomplete implementation work. The `const` is not a defect: it enforces that a `completed` receipt asserts a reconciled tree, and the legal escape valve already exists, since the producer gate returns early for any non-`completed` envelope. Routing incomplete work through `blocked`/`failed` rather than the schema-invalid path is SKILL-150 subtask 2, AC-2 and AC-4. This feature adds only the correction directive that points a `reconciled: false` producer at that path (criterion 10).
- Changing which validator runs at which seam; that is SKILL-142 subtask 2.
- Building the durable review-generation and finding-disposition model, carrying Blockers across generations, or changing review approval rules; those are SKILL-150 subtask 4. This feature only stops the evidence key from colliding when a generation restarts.
- Raising, lowering, or making adaptive any fix-loop attempt cap.
- Changing the evidence retention policy or its sweep.
- Storing raw agent responses, full prompts, or diagnostic bodies anywhere new.
- Guaranteeing that a retried agent always repairs its output; this feature guarantees the information it needs is present.

## Decomposition

1. Restore actionable rejection feedback and deterministically absorb meaningless rejection classes.
2. Make retained producer output evidence survive a review generation restart.

## Validation Strategy

- Add acceptance and rejection coverage for each observed malformed-field class against the real planning-projection validator, asserting on the reason text the fix loop receives.
- Assert the retry prompt and the blocked reason independently: constraint text present in the former, absent from the latter.
- Extend the existing rejected-output privacy assertions to the retry-prompt path with raw-response spans as the forbidden detail.
- Drive both regression fixtures through real fix-loop and generation-restart transitions, not unit calls on the gate or the repository, so convergence is proven at the seams that blocked.
- Seed a workflow whose attempt watermark sits below its retained evidence and assert it reconciles on the next run without evidence loss.
- Assert a byte-identical re-write stays idempotent and a differing write at a genuinely reused key still fails loudly.
- Run focused module tests after each subtask, then run:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```
