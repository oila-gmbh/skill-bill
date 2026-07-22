# SKILL-137 Subtask 1 - Handoff Projection Contracts and Private Evidence Boundary

Parent spec: [.feature-specs/SKILL-137-phase-context-minimization/spec.md](./spec.md)
Issue key: SKILL-137

## Scope

Introduce the shared domain and runtime-contract foundation for least-context phase handoffs. Replace the assumption that a dependency means “forward the complete producing phase payload” with explicit consumer-specific projection declarations. Separate private durable phase evidence from prompt-visible delivered projections, add checkpoint and budget policy types, and make invalid projections fail before agent launch.

This subtask establishes the reusable boundary only. Later subtasks populate the complete feature-task and feature-verification projection matrices.

## Acceptance Criteria

1. A typed `PhaseHandoffProjectionDeclaration` or equivalently named domain model identifies consumer phase, source kind and identity, projection contract id/version, prompt visibility, collection/UTF-8 byte budgets, and repository-checkpoint policy.
2. Projection declarations are static workflow-owned configuration. An executing agent, phase output, resumed prompt, or caller argument cannot add an undeclared source, request private evidence, or widen a projection.
3. A versioned Draft 2020-12 contract schema defines the durable prompt-visible handoff envelope. Its Kotlin version constant, parity test, typed invalid-schema error, validator, and classpath copy follow the repository runtime-contract recipe.
4. The handoff envelope contains named typed projections and compact references; it has no generic `upstream_outputs_by_phase_id`, raw payload, raw prompt, transcript, tool output, log, source-body, diff-body, or telemetry field.
5. Durable persistence stores complete validated phase output as private evidence separately from the exact projected handoff delivered to a phase. Serialization and database round trips cannot substitute the private artifact for the delivered projection.
6. The phase launch briefing model removes or makes private-only the complete upstream payload map. Prompt composition consumes only the validated projected envelope plus phase-specific directives.
7. Projection validation rejects missing required sources, malformed fields, unsupported versions, undeclared fields, duplicate projection names, budget overflow, invalid compact references, and checkpoint-policy violations before launching an agent.
8. Each rejection uses a typed error naming workflow id when available, consumer phase, projection name, contract id/version, and actionable reason without echoing sensitive payload bodies.
9. Budget enforcement counts UTF-8 bytes and collection items before prompt serialization. It never truncates JSON, drops required fields, or replaces an oversized projection with its full source artifact.
10. A contract may declare a lossless private artifact reference as an alternative to inline content only when the consumer is expected to inspect it through a runtime-owned deterministic operation; arbitrary model retrieval is not introduced.
11. Repository checkpoint types support a deterministic base/head or equivalent fingerprint, working-tree ownership metadata when required, and explicit policies `not_required`, `must_match`, or `refresh_from_repository` (names may vary while semantics remain exact).
12. Run identity remains durable runtime state but prompt-visible invariants are selected by per-phase allowlists. The model distinguishes identity fields from acceptance-contract, policy, ceremony, review, add-on, and finalization fields.
13. Add-on identities remain verifiable durable state, while hydrated add-on content is included only for declared consumer assignments and is budgeted independently from phase receipts.
14. Unit tests prove private/delivered separation, declaration immutability, schema validation, byte/item budgets, no-truncation behavior, checkpoint policies, and forbidden-field absence.
15. Architecture documentation describes the new four-part boundary: private evidence, consumer projection, repository-derived context, and phase-local instructions.

## Non-Goals

- Defining the final field set for every feature-task phase; subtasks 2 through 4 own those projections.
- Migrating prose workflow artifacts or feature-verification dependencies.
- Removing private full phase evidence used for diagnostics.
- Adding agent-controlled artifact discovery or retrieval.

## Dependency Notes

Depends on: none.

This is the shared foundation required by every later subtask.

## Validation Strategy

- Schema/version/error parity tests.
- Domain tests for projection declarations and invariant allowlists.
- Persistence round-trip tests proving private evidence is never decoded as delivered context.
- Prompt snapshots proving complete phase envelopes and forbidden fields are absent.
- UTF-8, collection-limit, invalid-reference, and stale-checkpoint rejection tests.
- Focused `runtime-domain`, `runtime-contracts`, `runtime-infra-fs`, `runtime-infra-sqlite`, and `runtime-application` Gradle tests.

## Next Path

Continue with subtasks 2, 6, and 7 after this subtask commits.

## Spec Path

.feature-specs/SKILL-137-phase-context-minimization/spec_subtask_1_handoff-projection-contracts-and-private-evidence-boundary.md
