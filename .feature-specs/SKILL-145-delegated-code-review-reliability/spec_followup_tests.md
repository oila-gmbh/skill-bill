# Follow-up specification: delegated review acceptance suite

**Order:** 9 of 9  
**Depends on:** all preceding follow-up specifications  
**Purpose:** combine positive, negative, isolation, and end-to-end coverage
for the reliability contract.

## Scope and targets

- `.feature-specs/SKILL-145-delegated-code-review-reliability/spec_followup_tests.md`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/contracts/review`
- `runtime-kotlin/runtime-domain/src/test/kotlin/skillbill/review`
- `runtime-kotlin/runtime-application/src/test/kotlin/skillbill/application/review`
- `runtime-kotlin/runtime-infra-sqlite/src/test/kotlin/skillbill/review`
- `runtime-kotlin/runtime-infra-fs/src/test/kotlin/skillbill/launcher`

## Required coverage

Map every acceptance criterion to at least one positive and one negative test.
Include schema version and parse-seam rejection, fake-clock lifecycle
transitions, fake launcher and provider isolation, fake persistence and crash
recovery, coordinator/worker capacity, cancellation/watchdog boundaries,
retry idempotency, exact declared-area aggregation, bounded diagnostics,
installed identity mismatch, explicit unsupported providers, and normal
zero-exit-only schema repair.

The end-to-end fixtures cover small, medium, and multi-area reviews and
interruption before launch, during a worker, between waves, during aggregation,
and before terminal persistence. Provider canaries are conditional on an
installed authenticated provider and are not required for deterministic unit
coverage.

## Repository validation owned by validate phase

The later validate phase runs, records, and owns outcomes for:

- `skill-bill validate`
- `(cd runtime-kotlin && ./gradlew check)`
- `npx --yes agnix --strict .`
- `scripts/validate_agent_configs`

This implementation phase only authors or reconciles the tests and
specification; it does not execute them.

## Exclusions

Do not use installation refresh as validation. Do not persist prompts, diffs,
transcripts, source bodies, or raw tool logs in fixtures or diagnostics.
