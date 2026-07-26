# SKILL-52 - Full hexagonal runtime architecture

Status: In Progress
Issue key: SKILL-52

## Context

`runtime-kotlin` currently presents itself as a pragmatic hexagonal JVM runtime:

```text
cli / mcp
  -> application use cases
    -> ports
      -> domain models

infrastructure/sqlite, infrastructure/http, infrastructure/fs
  -> implement ports

di
  -> wires adapters, use cases, repositories, clients, clocks, and runtime
     contexts
```

SKILL-28 established the first real module split and moved several runtime
surfaces toward this shape. The current codebase, however, is still
transitional rather than fully hexagonal:

- `runtime-core` is documented as the integration/composition module, but it
  still owns implementation packages such as `skillbill.install`,
  `skillbill.scaffold`, `skillbill.nativeagent`, `skillbill.launcher`, and
  workflow runtime wrappers.
- `runtime-core` re-exports application, domain, port, contract, and
  infrastructure modules through `api(...)`, making it an aggregator as well as
  a composition root.
- Some domain-layer code still owns schema-validator and file/path concerns
  that are closer to contract or infrastructure seams than pure domain logic.
- Architecture tests enforce useful boundary rules, but they do not yet enforce
  the hard rule that `runtime-core` contains composition only and that all
  implementation behavior lives in the correct hexagonal layer.

This matters because future shared runtime features, including SKILL-53
install-selection persistence, need an unambiguous place to land. Adding new
`runtime-core:data` and `runtime-core:domain` modules would create a second
architecture inside the existing one. Instead, the existing runtime architecture
should become fully hexagonal.

## Problem

The runtime cannot be treated as fully hexagonal while `runtime-core` remains
both a composition module and an implementation owner. "Mostly hexagonal" leaves
too much discretion for new work:

- new domain models may be placed in `runtime-core` because core already owns
  nearby behavior;
- new persistence or filesystem adapters may bypass ports because core already
  sees infrastructure directly;
- CLI/MCP/Desktop can keep depending on the `runtime-core` umbrella instead of
  declaring their actual adapter/application/composition needs;
- architecture tests can pass while the conceptual boundary continues to blur.

SKILL-52 should turn the current architecture from an aspirational target into a
hard, enforced module graph.

## Goals

1. Define the final runtime hexagonal module graph and dependency direction in
   `runtime-kotlin/ARCHITECTURE.md`.
2. Make `runtime-core` a composition/runtime-assembly module only.
3. Move remaining implementation surfaces out of `runtime-core` into the proper
   existing hexagonal modules, or create narrowly named top-level modules only
   when an existing module would become semantically wrong.
4. Remove broad `runtime-core` `api(...)` re-exports where downstream modules can
   depend on their actual required modules.
5. Keep CLI, MCP, and Desktop as adapter surfaces that do not depend on
   infrastructure implementation details except through composition.
6. Keep domain code pure: no Clikt, MCP, Desktop, JDBC, HTTP clients, concrete
   filesystem reads/writes, process environment access, or infrastructure
   imports.
7. Keep ports as interfaces/contracts only, with port-owned DTOs in explicit
   `model` packages.
8. Keep infrastructure implementations behind ports and out of application,
   domain, CLI, MCP, and Desktop business flows.
9. Add architecture tests that fail loudly when new code violates the full
   hexagonal structure.

## Target Architecture

The intended final shape is:

```text
runtime-cli          runtime-mcp          runtime-desktop
     \                  |                     /
      \                 |                    /
       -> runtime-application use cases <-
                    |
                 runtime-ports
                    |
              runtime-domain

runtime-infra-fs       runtime-infra-http       runtime-infra-sqlite
       \                    |                         /
        \                   |                        /
         -------- implement runtime-ports ----------

runtime-core
  -> composition root / runtime assembly only
  -> may depend on application, ports, domain, contracts, and infra modules
  -> must not own business, persistence, filesystem, install, scaffold,
     native-agent, workflow, telemetry, or launcher implementation behavior
```

Layer responsibilities:

- `runtime-domain`: pure models, value rules, parsing/normalization rules, and
  deterministic domain behavior. Public data types live in area-owned
  `model` packages.
- `runtime-ports`: interfaces that application/domain code uses to reach
  persistence, filesystem, telemetry, process, clock, and other outside-world
  capabilities. Port DTOs/results live under `skillbill.ports.*.model`.
- `runtime-application`: use cases and orchestration shared by CLI, MCP, and
  Desktop. It depends on domain and ports, not concrete infrastructure.
- `runtime-infra-*`: concrete adapters implementing ports.
- `runtime-contracts`: external DTOs, schema constants, contract serialization
  helpers, and typed contract errors. Model-to-contract mapping belongs in
  application or adapter packages, not in contracts.
- `runtime-core`: Kotlin-Inject component(s), provider bindings, runtime module
  metadata, and assembly-only helpers.
- `runtime-cli`, `runtime-mcp`, `runtime-desktop`: entry adapters and UI/transport
  concerns. They validate and translate inputs, then call application use cases.

## Acceptance Criteria

1. `runtime-kotlin/ARCHITECTURE.md` no longer describes the architecture as
   transitional or "pragmatic" in a way that permits mixed ownership; it defines
   the full hexagonal module graph, layer responsibilities, and forbidden
   dependency directions.
2. `runtime-core` contains only composition/runtime-assembly code:
   Kotlin-Inject component/provider wiring, runtime module metadata, and
   narrowly scoped assembly helpers. It no longer owns `skillbill.install`,
   `skillbill.scaffold`, `skillbill.nativeagent`, `skillbill.launcher`,
   workflow runtime wrappers, or other business/runtime implementation packages.
3. Every package currently under `runtime-core` is either moved to an existing
   correct layer or explicitly justified in a new top-level module with a
   documented responsibility and dependency rule.
4. CLI and MCP no longer rely on `runtime-core` as a broad API umbrella when a
   direct dependency on `runtime-application`, `runtime-contracts`,
   `runtime-domain`, `runtime-ports`, or a composition module is the honest
   dependency.
5. Domain modules contain no concrete filesystem reads/writes, JDBC, HTTP
   clients, process environment access, Clikt, MCP, Desktop, or infrastructure
   imports. Path value types are allowed only when they are pure values and do
   not perform IO.
6. Application use cases access outside-world behavior only through ports.
   Existing exceptions must be removed or documented as temporary blockers with
   failing or pending architecture coverage that names the exact follow-up.
7. Infrastructure modules implement ports and may depend on domain/port/contract
   types, but they do not depend on CLI, MCP, Desktop, or `runtime-core`.
8. Runtime contract/schema validation ownership is clarified. Schema resources
   and validators live in the layer that owns the parse seam, not in pure domain
   code by convenience.
9. `RuntimeModule.declaredGradleModules`, `RuntimeModule.declaredSubsystemPackages`,
   and architecture tests reflect the final module/package graph.
10. Architecture tests fail if:
    - `runtime-core` gains non-composition packages;
    - domain imports concrete IO, SQL, HTTP, entry adapters, or infrastructure;
    - application imports concrete infrastructure or entry adapters;
    - infrastructure imports entry adapters or `runtime-core`;
    - CLI/MCP/Desktop call low-level runtime implementation packages instead of
      application use cases;
    - public application/domain/port models are declared outside `model`
      packages.
11. Existing runtime behavior is preserved. This task is architectural movement,
    not a product-behavior rewrite.
12. Validation passes with:
    - `(cd runtime-kotlin && ./gradlew check)`
    - `skill-bill validate`
    - `scripts/validate_agent_configs`
    - `npx --yes agnix --strict .`

## Non-goals

- Implement SKILL-53 shared install-selection persistence.
- Add nested `runtime-core:data` or `runtime-core:domain` modules.
- Redesign CLI prompts, MCP tools, desktop UI behavior, installer semantics, or
  persisted data formats except where required to preserve behavior after moving
  code.
- Generate Kotlin types from schemas.
- Rename public commands or break existing script entry points.
- Rewrite all runtime APIs for style only; movement should serve the enforced
  hexagonal boundary.

## Open Questions

1. Where should install/scaffold/native-agent implementation land?
   - Preferred answer: use existing layers where possible:
     domain models/rules in `runtime-domain`, use cases in
     `runtime-application`, filesystem/process adapters in `runtime-infra-fs`,
     contracts/schemas in `runtime-contracts`, and DI in `runtime-core`.
   - If that creates an overly broad module, introduce a top-level module with a
     precise role, for example `runtime-infra-process` or
     `runtime-install-application`, but only after proving existing modules are
     semantically wrong.
2. Should launcher behavior be an adapter or composition concern?
   - Preferred answer: keep script/entrypoint selection adapter code out of
     domain/application. If it needs shared behavior, expose that behavior
     through application use cases and ports.
3. Should schema validators move out of `runtime-domain`?
   - Preferred answer: pure schema constants and typed errors belong in
     `runtime-contracts`; parse-seam validation belongs to the adapter or
     application boundary that consumes the payload.
4. Can `runtime-core` stop re-exporting infra immediately?
   - Preferred answer: yes for most downstream code, but this may require a
     staged dependency cleanup where CLI/MCP declare direct compile-time
     dependencies before `runtime-core` switches from `api` to `implementation`.

## Risks

- The movement is mostly architectural but touches many files. A single PR may
  become hard to review if all package moves land at once.
- Gradle dependency changes can break downstream tests in non-obvious ways,
  especially where CLI/MCP currently see dependencies through `runtime-core`.
- Moving schema validation can accidentally weaken loud-fail behavior if parse
  seams are not preserved.
- Over-splitting into new modules can recreate the same ambiguity under new
  names. New modules must be rare and justified by responsibility, not by
  aesthetics.

## Recommended Implementation Order

This is likely too large for one reliable implementation run. When
`bill-feature-implement` plans this spec, it should strongly consider
decomposition.

1. **Architecture contract and guardrails.**
   Update architecture docs and tests to define the final graph. Add failing
   guardrails for `runtime-core` composition-only ownership and strict layer
   imports before moving large surfaces.
2. **Dependency honesty.**
   Make CLI/MCP/Desktop declare the direct modules they actually use, then
   reduce `runtime-core` API re-exports where possible.
3. **Move implementation packages out of `runtime-core`.**
   Relocate install, scaffold, native-agent, launcher, and workflow runtime
   wrapper code into the correct domain/application/contract/infra layers while
   preserving public behavior.
4. **Parse-seam and schema ownership cleanup.**
   Move schema validation and resource ownership out of pure domain where it is
   not domain behavior. Preserve typed errors and loud-fail semantics.
5. **Final enforcement.**
   Tighten tests so future code cannot reintroduce transitional shortcuts.
   Update history/decision docs with the final architecture decision.

## Relationship To SKILL-53

SKILL-53 should remain the shared runtime install-selection persistence feature.
It depends on the result of SKILL-52: once the runtime architecture is fully
hexagonal, SKILL-53 can add install-selection domain models, ports, use cases,
and persistence adapters in the correct layers without creating a parallel
`runtime-core:data/domain` architecture.
