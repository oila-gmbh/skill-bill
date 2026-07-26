# SKILL-48 Subtask 2d — `telemetry-event-schema.yaml`

Status: Complete

Parent spec: [spec_subtask_2_runtime-contracts.md](./spec_subtask_2_runtime-contracts.md)
Grandparent: [spec.md](./spec.md)

## Why this is subtask 2d (last)

Telemetry is the largest and most fragmented of the four contracts: roughly 30 events listed in `McpToolRegistry.toolNames` (runtime-mcp, 410 lines), each with its own input schema in `McpToolRegistry.inputSchemas`. Landing it last means the pattern (validator + parity test + validates-existing + violations + parse-seam wiring) is fully proven across three smaller schemas first, so the only novel surface in this subtask is the per-event modeling and the manual-with-parity-test alignment between the canonical YAML and `McpToolRegistry.inputSchemas`.

## Scope

1. Author `orchestration/contracts/telemetry-event-schema.yaml` as a Draft 2020-12 JSON Schema in YAML, mirroring the established template.
2. Schema covers the payload shape of every `mcp__skill-bill__*_started/finished` event currently emitted (~30 events). Recommended structural shape:
   - A top-level `oneOf` keyed by `event_name` (or a discriminator) so each event's required-vs-optional fields are precisely typed.
   - Or a per-event `$defs` table with a discriminator field. Document the chosen shape in `x-coherence-checks`.
   - Cover both request shapes (from `runtime-application/model/LifecycleTelemetryRequests.kt`) and output payloads (from `LifecycleTelemetryPayloads.kt`).
3. Pin `contract_version` to a Kotlin `TELEMETRY_EVENT_CONTRACT_VERSION` constant (in `runtime-kotlin/runtime-mcp` or `runtime-application`) via a parity test.
4. Wire `TelemetryEventSchemaValidator`. Gradle `Copy` task is configuration-cache friendly (F-101).
5. Validate at the telemetry parse seams in `runtime-mcp` (entry points of every `*_started/*_finished` tool). Loud-fail via new typed `InvalidTelemetryEventSchemaError` carrying the field path AND the offending `event_name` for grep-ability.
6. Manual-with-parity-test alignment (per parent spec's stated choice): the per-event input schemas in `McpToolRegistry.inputSchemas` MUST match the corresponding event's payload in the canonical YAML. Add `TelemetryEventInputSchemaParityTest` that walks every name in `McpToolRegistry.toolNames`, extracts its `inputSchemas` entry, and asserts structural equivalence against the corresponding `$defs` entry in `telemetry-event-schema.yaml`. Loud-fail with the event name and divergence path.
7. Tests:
   - `TelemetryEventSchemaContractVersionTest`.
   - `TelemetryEventSchemaValidatesExistingEventsTest` — for every event in `McpToolRegistry.toolNames`, construct a representative payload (reuse existing telemetry-emit test fixtures where they exist; otherwise build minimal valid payloads from request/payload model defaults) and assert it validates clean. The set of event names is the source of truth — discover dynamically, do not hard-code 30 strings.
   - `TelemetryEventSchemaViolationsTest` — per-violation tests for the highest-signal rules: unknown `event_name`, missing required field per event variant, wrong `contract_version`, unknown additional property, type mismatch on a typed field (e.g. timestamp), oneOf/discriminator mismatch (e.g. a `feature_implement_finished` payload submitted as `feature_verify_finished`).
   - `TelemetryEventInputSchemaParityTest` (above).
   - Classpath shadow guard test extended.
8. No desktop or AGENTS.md edits required — both already handled in 2a.

## Acceptance criteria

1. `orchestration/contracts/telemetry-event-schema.yaml` exists, declares Draft 2020-12, has `contract_version` const, `additionalProperties: false`, documents `x-coherence-checks` block (including the chosen discriminator/oneOf strategy), and matches the established template.
2. Every event listed in `McpToolRegistry.toolNames` (~30 events) has a corresponding `$defs`/oneOf branch in the schema, covering both request and output payload shapes.
3. `TELEMETRY_EVENT_CONTRACT_VERSION` Kotlin constant pins the schema's `contract_version`; parity test fails the build if they diverge.
4. Every `McpToolRegistry.inputSchemas` entry is structurally equivalent to the corresponding entry in `telemetry-event-schema.yaml`, proven by `TelemetryEventInputSchemaParityTest`. Loud-fail names the offending event.
5. A representative payload for every event in `McpToolRegistry.toolNames` validates clean against the schema (no event is left out).
6. Telemetry parse seams in `runtime-mcp` validate and loud-fail via `InvalidTelemetryEventSchemaError` carrying field path + event name.
7. Per-violation tests cover unknown event_name, missing required field, wrong contract_version, unknown additional property, type mismatch, oneOf/discriminator mismatch.
8. Build gradle `Copy` task is configuration-cache friendly.
9. The new YAML appears in the desktop "Contracts" tree automatically via 2a's auto-listing.
10. `bill-quality-check` passes.

## Non-goals

- Authoring the other three schemas (already landed in 2a/2b/2c).
- Touching desktop code or `AGENTS.md` (already landed in 2a).
- Renaming or restructuring existing telemetry events — schema describes what is emitted today. If a payload is poorly shaped, fixing it is a separate task.
- Auto-deriving `McpToolRegistry.inputSchemas` from the YAML — parent spec chose manual-with-parity-test. (Implementer MAY auto-derive only if it is trivially cheap inside this PR and does not balloon the diff; otherwise stick with manual + parity test.)
- Multi-version validators, generating Kotlin types from schemas, `x-runtime-anchored` (Subtask 3), replacing `PlatformManifest`.

## Dependencies

- Subtask 1 (SKILL-47 cleanup): C2/C7/C8.
- Subtasks 2a + 2b + 2c: pattern fully proven; this subtask only adds telemetry-specific modeling and the input-schema parity test.

## Validation strategy

`bill-quality-check` (runtime-kotlin Gradle `check`). Pay special attention to the input-schema parity test: it must walk the full `McpToolRegistry.toolNames` set so adding a new telemetry event in the future automatically fails the build until the YAML and parity test are updated.

## Boundaries touched

- `orchestration/contracts/telemetry-event-schema.yaml` (new).
- `runtime-kotlin/runtime-mcp` — `McpToolDispatcher`, `McpToolRegistry`, `McpInputSchemas`: validator wiring at telemetry entry points; constant; parity test.
- `runtime-kotlin/runtime-application` — `LifecycleTelemetryService`, `LifecycleTelemetryPayloads`, `LifecycleTelemetryRequests`: validate at payload emit/parse points.
- `runtime-kotlin/runtime-contracts/error` — new `InvalidTelemetryEventSchemaError`.
- Gradle build file (configuration-cache friendly `Copy`).
- `runtime-kotlin/agent/history.md` — high-signal entry.

## Templates to cite verbatim

- Schema YAML structure: `orchestration/contracts/platform-pack-schema.yaml` + the three schemas landed in 2a/2b/2c.
- Validator class: `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/scaffold/PlatformPackSchemaValidator.kt`.
- Parity test: `PlatformPackSchemaContractVersionTest`.
- Validates-existing test: `PlatformPackSchemaValidatesExistingPacksTest`.
- Violations test: `PlatformPackSchemaViolationsTest`.
- Source of truth for event names: `runtime-kotlin/runtime-mcp/.../McpToolRegistry.kt` (`toolNames`, `inputSchemas`).
- Existing payload sources: `runtime-application/model/LifecycleTelemetryRequests.kt`, `LifecycleTelemetryPayloads.kt`.
- Test helper: `runtime-kotlin/runtime-core/src/testFixtures/kotlin/skillbill/testing/RepoRoot.kt`.

## Recommended next prompt

`Run bill-feature-implement on .feature-specs/SKILL-48-runtime-contracts-expansion/spec_subtask_2d_telemetry-events.md`
