# SKILL-146 Subtask 1: Versioned handoff contract and durable evidence boundary

## Scope

Introduce the closed-world, versioned phase-handoff schema and typed domain model. Declare consumer, source, projection id/version, prompt visibility, item and UTF-8 byte budgets, and repository-checkpoint policy. Add schema bundling, pinned Kotlin version, validators, typed failures, durable private-evidence versus delivered-projection storage, compatibility behavior, and privacy-safe measurement fields.

## Acceptance Criteria

1. Parent AC 1–6 are implemented at the contract, typed-model, error, and persistence boundaries.
2. Parent AC 17, 22, and 23 are represented by iteration/checkpoint identity, pre-launch budget primitives, and explicit legacy incompatibility.
3. Parent AC 24–26 are supported by content-free measurement models, presence/absence tests, and aligned schema/constants/mappings/fixtures.
4. Contract YAML follows the repository runtime-contract recipe and closed-world fields cannot originate from arbitrary agent maps.
5. Durable round trips keep private source evidence separate from the exact prompt-visible projection.

## Non-Goals

- Implementing every phase-specific projector.
- Changing phase order, remediation policy, prose, verification, or delegated-review delivery.
- Migrating arbitrary historical workflows or generating installed wrappers.

## Dependency Notes

This is the root subtask. Preserve existing repair caps, decomposition behavior, goal-child isolation, and injectable runtime strategies.

## Validation Strategy

- Schema validity and Kotlin version parity.
- Typed rejection cases for missing, malformed, unsupported, oversized, and unprojectable data.
- UTF-8 multibyte and collection-budget tests.
- Persistence separation and legacy-record loud-fail tests.
- Telemetry serialization tests that assert payload contents are absent.

## Next Path

Implement Subtask 2 after this contract foundation is committed.

