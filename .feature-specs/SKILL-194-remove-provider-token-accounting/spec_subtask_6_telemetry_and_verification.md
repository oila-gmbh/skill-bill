# SKILL-194 Subtask 6 — Telemetry contract bump, convention-pinning test removal, and integrated verification

## Scope

Close the program: retire the last contract surface, delete the tests that encoded the wrong
convention as intended behaviour, update the governed boundary records, and prove the whole removal
against the scenarios that motivated it.

**Telemetry contract.** Delete the `reviewAccountingUsage` definition from
`orchestration/contracts/telemetry-event-schema.yaml` (`:1219-1227`) and every `$ref` to it. Bump the
telemetry event contract version, landing schema, Kotlin `*_CONTRACT_VERSION`, the contract-version
parity test on the `PlatformPackSchemaContractVersionTest` pattern, and the typed
`Invalid<Contract>SchemaError` together. Legacy telemetry records that still carry usage objects
normalize on read through the subtask 1 seam.

**Convention-pinning tests.** Delete rather than adapt the assertions that pinned the OpenAI
convention as correct:

- `ReviewContextModelsTest.kt:129-130` — `ProviderTokenUsage(cachedInputTokens = 1)` must throw, and
  `ProviderTokenUsage(inputTokens = 1, cachedInputTokens = 2)` must throw. The second is the exact
  assertion that made the SKILL-190 incident shape look like a defect in the data.
- `ReviewContextModelsTest.kt:360` and `ReviewTreeAccountingTest.kt:114`, `:118`, `:22-24` — the
  `freshTokenApproximation` and usage-aggregation expectations.

**Boundary records.** Update `runtime-kotlin/agent/decisions.md` and the review area's
`agent/history.md` to record that provider-reported token accounting was removed, that the two
provider conventions are why, and that local byte-derived estimates are deliberately retained. Any
existing entry that describes provider usage accounting as a live capability must be corrected rather
than left to mislead planning.

**Integrated verification.** Run the full validation set and the load-bearing scenarios end to end.

## Acceptance Criteria

1. `telemetry-event-schema.yaml` no longer defines `reviewAccountingUsage`, and no `$ref` to it remains.
2. The telemetry event contract version is bumped, with a parity test pinning schema against the Kotlin
   `*_CONTRACT_VERSION` on the `PlatformPackSchemaContractVersionTest` pattern.
3. A legacy telemetry record carrying a usage object reads back successfully through the subtask 1 seam
   with one degradation record, and is not quarantined.
4. The convention-pinning assertions are deleted, not adapted: no test asserts that a cached-exceeds-input
   report is invalid, and no test asserts a `freshTokenApproximation` value.
5. `runtime-kotlin/agent/decisions.md` and the review area's `agent/history.md` record the removal, its
   cause, and the retained local estimates, with no entry left describing provider usage accounting as
   live.
6. A goal run whose review lane reports `input_tokens` smaller than `cache_read_input_tokens` completes
   its review phase and records findings.
7. A pre-existing `review_accounting` row, a stored `goal_session_accounting` artifact, and a
   `.skill-bill/config.yaml` carrying `provider_token_thresholds` all read without error and without
   quarantine.
8. `estimated_tokens` on the projection measurement and `estimated_input_tokens` /
   `estimated_output_tokens` in lifecycle telemetry still record.
9. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, and `scripts/validate_agent_configs`
   all pass.
10. A repository-wide search finds no remaining reference to the deleted provider token types, fields,
    or contract definitions outside the retired-key registry from subtask 1.

## Non-Goals

- Re-introducing any provider token measurement, in telemetry or anywhere else.
- Removing or altering the local byte-derived estimates.
- Removing the retired-key registry or its degradation records; they are the permanent tolerance path.
- Migrating historical telemetry records.

## Dependency Notes

Depends on subtasks 1 through 5. This is the closing subtask: the telemetry definition can only be
dropped once nothing emits usage, the convention-pinning tests can only be deleted once the types they
reference are gone, and the integrated verification is only meaningful against the complete removal.

## Validation Strategy

Run the program's load-bearing scenarios as one pass:

- A goal run with a cached Claude review lane, reporting the recorded incident shape, completes its
  review phase with findings attributed.
- A `review_accounting` row written before this program reads back; a stored `goal_session_accounting`
  artifact reads back; a config with `provider_token_thresholds` parses. All three succeed with a
  degradation record and no quarantine.
- Byte, count, coverage, routing, and integration accounting are unchanged across a full review.
- `estimated_tokens` and the lifecycle `estimated_*_tokens` values still record.
- A repository-wide search for the deleted symbols and wire keys returns only the retired-key registry.

Close with `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, and
`scripts/validate_agent_configs`. The known pre-existing `:runtime-infra-fs:sourcesJar` failure on
`./gradlew build` is not introduced by this program; verify with `-x sourcesJar` if that path is used.

## Next Path

```bash
skill-bill goal SKILL-194
```
