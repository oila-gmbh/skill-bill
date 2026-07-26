# SKILL-52.2 - Runtime Boundary Closure

Created: 2026-05-25
Status: Draft
Issue key: SKILL-52.2
Parent: SKILL-52 - Full hexagonal runtime architecture
Predecessor: SKILL-52.1 - Hexagonal Runtime Hardening

## Decomposition

This feature is intentionally decomposed because the remaining hexagonal gaps
cross several stable runtime surfaces. Implement on one branch with commit per
subtask:

1. [Boundary Inventory + Contract Targets](./spec_subtask_1_boundary-inventory-and-contract-targets.md)
2. [Scaffold Typed Command Boundary](./spec_subtask_2_scaffold-typed-command-boundary.md)
3. [Review + Telemetry Typed Boundaries](./spec_subtask_3_review-telemetry-typed-boundaries.md)
4. [Workflow Schema Ownership + Domain Contract Tightening](./spec_subtask_4_workflow-schema-ownership.md)
5. [Adapter Dependency Narrowing + Desktop Gateway Convergence](./spec_subtask_5_adapter-dependency-and-desktop-convergence.md)

## Sources

- Local audit from 2026-05-25:
  - `runtime-kotlin/ARCHITECTURE.md`
  - `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/`
  - `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/ScaffoldService.kt`
  - `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/ReviewService.kt`
  - `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/TelemetryService.kt`
  - `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/WorkflowEngine.kt`
  - `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/scaffold/ScaffoldGateways.kt`
  - `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/persistence/ReviewRepository.kt`
  - `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/telemetry/TelemetryClient.kt`
  - `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/telemetry/TelemetryConfigStore.kt`
  - `runtime-kotlin/runtime-desktop/core/data/src/jvmMain/kotlin/skillbill/desktop/core/data/service/`
- Verification baseline:
  - `(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')` passed on 2026-05-25.
- Predecessor specs:
  - `.feature-specs/SKILL-52-full-hexagonal-runtime-architecture/spec.md`
  - `.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec.md`
  - `.feature-specs/SKILL-53-shared-runtime-install-architecture/spec.md`

## Context

SKILL-52 established the physical hexagonal split. SKILL-52.1 hardened it with
typed boundary foundations, scaffold/install capability ports, inert `Path`
policy, raw-map allow-list governance, and `runtime-core` shrinkage. The runtime
now has a defensible hexagonal module graph and strong architecture tests.

The remaining issue is not dependency direction. It is semantic boundary
closure: several public application and port contracts still carry wire-shaped
maps, some application services are still thin pass-throughs over gateway
methods, workflow-state schema validation still pulls contract machinery into
domain, entry adapters still have broad compile-time visibility, and desktop
has a parallel gateway model that can drift from shared runtime use cases.

## Problem

The runtime is hexagonal in module shape but not fully closed at every boundary.

Known gaps:

- Raw `Map<String, Any?>` remains on public application and port surfaces for
  scaffold command input, review import/feedback/stats, telemetry config/client
  payloads, system payloads, workflow artifacts, and some typed-result payload
  preservation fields.
- `ScaffoldService.scaffold(payload, dryRun)` still accepts the external JSON
  payload shape instead of a typed request model.
- `ReviewRepository` mixes persistence operations with pre-serialized telemetry
  and stats payload maps.
- `TelemetryClient` and `TelemetryConfigStore` expose proxy/config wire maps
  instead of typed port models.
- `WorkflowEngine` directly imports contract JSON/schema helpers for workflow
  state validation, which keeps domain dependent on runtime contract machinery.
- CLI, MCP, and desktop data modules still declare broad direct dependencies on
  domain, ports, infra modules, and `runtime-core`; architecture tests guard
  imports, but Gradle still exposes too much.
- Desktop data gateways wrap runtime services with desktop-specific models.
  That is appropriate at the UI boundary, but several mappings duplicate
  runtime semantics and need stronger convergence tests.
- Documentation drift exists: at least one domain port KDoc still says a JVM
  implementation lives in `runtime-core` after it moved to `runtime-infra-fs`.

## Goals

1. Remove public raw-map contracts from stable application and port APIs unless
   they are intentionally open extension/artifact seams.
2. Replace scaffold command payload input with a typed request model and keep
   JSON/MCP/desktop maps at adapter boundaries only.
3. Replace review and telemetry payload-map ports with typed request/result
   models while preserving byte-equivalent CLI/MCP output where golden tests
   require it.
4. Move workflow-state schema validation out of domain-owned workflow logic, or
   hide it behind a domain-neutral seam that does not make domain import
   runtime contract validators directly.
5. Narrow adapter Gradle dependencies after typed boundaries exist, so CLI/MCP
   and desktop data no longer need broad direct access to domain or infra
   modules except where composition or generated ABI truly requires it.
6. Make desktop gateway mappings consume shared typed application results and
   prove they do not own duplicate business policy.
7. Update `runtime-kotlin/ARCHITECTURE.md`, architecture tests, and boundary
   history/decisions so the new rules fail loudly in future work.
8. Preserve existing CLI, MCP, desktop, install, scaffold, review, telemetry,
   workflow, validation, and persisted data behavior.

## Non-Goals

- Do not redesign CLI commands, MCP tool names, desktop UX, telemetry event
  names, install prompts, persisted database schema, or scaffold payload
  semantics.
- Do not remove intentionally open maps:
  - `PlatformManifest.customFields`;
  - caller-supplied workflow artifacts;
  - schema custom fields;
  - MCP argument maps before parsing;
  - private serializer maps internal to contract emission.
- Do not generate Kotlin types from JSON Schemas.
- Do not split new Gradle modules unless a subtask proves a smaller boundary
  cannot close the gap.
- Do not weaken loud-fail typed errors or schema validation to make migration
  easier.

## Target Architecture

The target graph remains:

```text
runtime-cli / runtime-mcp / runtime-desktop data gateways
  -> runtime-application use cases
    -> runtime-ports
      -> runtime-domain models and rules

runtime-infra-fs / runtime-infra-http / runtime-infra-sqlite
  -> implement runtime-ports

runtime-core
  -> DI composition and runtime metadata only
```

SKILL-52.2 tightens the interpretation:

- Application services expose typed use-case contracts, not wire payload maps.
- Ports express capabilities needed by application use cases, not adapter
  convenience methods or pre-serialized responses.
- Infrastructure maps external systems to typed ports and owns effects.
- Contract/wire serializers live at adapter/application seams.
- Domain may own pure policy and typed state transitions, but must not import
  schema validators, HTTP/SQL/filesystem/process APIs, entry adapters, or
  infrastructure.

## Acceptance Criteria

1. Public declarations in `runtime-application`, `runtime-domain`, and
   `runtime-ports` no longer accept or return `Map<String, Any?>`,
   `Map<String, *>`, or `MutableMap<String, Any?>` except documented
   `@OpenBoundaryMap` seams that represent true extension/artifact boundaries.
2. `ScaffoldService.scaffold(...)` and `ScaffoldGateway.scaffold(...)` are
   replaced by typed request/result contracts; CLI/MCP/Desktop parse external
   payloads before entering application code.
3. Review import, feedback, triage, stats, and review-finished telemetry
   surfaces return typed application/port results. CLI/MCP JSON remains
   byte-equivalent where goldens exist.
4. Telemetry config, capabilities, remote stats, status, mutation, and sync
   surfaces return typed application/port results. Proxy/config maps remain
   only in contracts or infrastructure mappers.
5. `WorkflowEngine` no longer imports `skillbill.contracts.*` schema validators
   directly. Workflow-state validation still loud-fails at approved seams with
   `InvalidWorkflowStateSchemaError`.
6. Adapter Gradle dependencies are narrowed to the minimum direct modules they
   use. Any retained `runtime-core` public ABI edge is documented and enforced
   by architecture tests.
7. Desktop data gateways map from shared typed runtime results and have tests
   proving no duplicate scaffold/review/telemetry/install business policy is
   introduced in desktop-specific code.
8. `runtime-kotlin/ARCHITECTURE.md`, `RuntimeModule`, and architecture tests
   agree on the final boundary rules and raw-map allow-list.
9. Stale documentation that names old module ownership, including
   `SkillRemoveFileSystem` implementation ownership, is corrected.
10. Full validation passes:
    - `(cd runtime-kotlin && ./gradlew check)`
    - `skill-bill validate`
    - `scripts/validate_agent_configs`
    - `npx --yes agnix --strict .`

## Validation Strategy

Run focused checks after each subtask:

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
```

Run full validation at the end:

```bash
(cd runtime-kotlin && ./gradlew check)
skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .
```

## Recommended Next Prompt

Run `bill-feature-implement` on:

```text
.feature-specs/SKILL-52.2-runtime-boundary-closure/spec.md
```

