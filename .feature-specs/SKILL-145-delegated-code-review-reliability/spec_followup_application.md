# Follow-up specification: delegated review application orchestration

**Order:** 3 of 9  
**Depends on:** `spec_followup_domain.md`  
**Purpose:** connect durable lifecycle state to coordinator, workers, watchdogs,
recovery, cancellation, and aggregation without provider branching.

## Scope and targets

- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/review/ParallelCodeReviewRunner.kt`
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/review/DelegatedReviewLaunchBroker.kt`
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/review/DelegatedReviewExecutionBroker.kt`
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/review/DelegatedReviewWorkerLauncher.kt`
- `runtime-kotlin/runtime-application/src/test/kotlin/skillbill/application/review`

## Required orchestration

Prepare and persist the coordinator snapshot before launch. Persist selection,
queue, launch, running, durable progress, worker terminal result, aggregation,
and terminal reconciliation at their ownership boundaries. Account for both
coordinator and worker slots. Launch only the immutable assignment projection;
provider behavior remains behind injected strategies.

The watchdog must enforce startup, progress-idle, per-worker, aggregation, and
whole-review deadlines using a fake-clock seam. Recovery must reconcile an
interrupted process from durable state and must not use an in-memory runner
summary as authority. Only a normal zero-exit result with an invalid envelope
may enter schema repair.

## Interruption tests

Add deterministic interruption fixtures before launch, during a worker, between
waves, during aggregation, and after aggregation but before terminal
persistence. Each produces exactly one durable terminal classification. Add
fixtures proving coordinator and worker slots are both counted, selected areas
are neither dropped nor duplicated, and completed assignments are not relaunched.

## Exclusions

Do not redesign routing, fallback, least-context handoff, declared-area
selection, or provider command construction.
