# SKILL-52.1 - Hexagonal Runtime Hardening

Created: 2026-05-24
Status: Decomposed (5 subtasks, same-branch sequential)
Issue key: SKILL-52.1
Parent: SKILL-52 - Full hexagonal runtime architecture

## Decomposition

This feature has been decomposed into 5 same-branch sequential subtasks on
`feat/SKILL-52.1-hexagonal-runtime-hardening`:

1. [Typed Boundary Foundation](./spec_subtask_1_typed-boundary-foundation.md)
2. [Scaffold Policy Extraction](./spec_subtask_2_scaffold-policy-extraction.md)
3. [Install Policy Extraction](./spec_subtask_3_install-policy-extraction.md)
4. [Path Policy + Runtime-Core Shrink](./spec_subtask_4_path-policy-and-core-shrink.md)
5. [Final Validation + Contract Lock](./spec_subtask_5_final-validation-and-contract-lock.md)

## Sources

- Alistair Cockburn, "Hexagonal architecture": https://alistair.cockburn.us/hexagonal-architecture
- AWS Prescriptive Guidance, "Hexagonal architecture pattern": https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/hexagonal-architecture.html
- AWS Prescriptive Guidance, "Best practices for hexagonal architecture": https://docs.aws.amazon.com/prescriptive-guidance/latest/hexagonal-architectures/best-practices.html
- AWS Prescriptive Guidance, "Improve software quality with hexagonal architecture": https://docs.aws.amazon.com/prescriptive-guidance/latest/hexagonal-architectures/improve-software-quality.html
- Local audit evidence:
  - `runtime-kotlin/ARCHITECTURE.md`
  - `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/ImplementationOwnershipArchitectureTest.kt`
  - `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt`
  - `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/ScaffoldService.kt`
  - `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/InstallService.kt`
  - `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/WorkflowService.kt`
  - `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/scaffold/ScaffoldGateways.kt`
  - `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/install/InstallGateways.kt`
  - `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/ScaffoldService.kt`
  - `runtime-kotlin/runtime-core/build.gradle.kts`

## Context

SKILL-52 moved `runtime-kotlin` from a transitional architecture into an enforced
hexagonal module graph. The current architecture guardrail suite passes, and
`runtime-core` is now mostly composition-only. That is a strong foundation, but
the audit found several areas where the implementation is still hexagonal by
module dependency and tests, not fully hexagonal by responsibility:

- `ScaffoldService` and `InstallService` in `runtime-application` are mostly
  pass-through wrappers over filesystem gateways.
- Significant governed scaffold planning and validation policy still lives in
  `runtime-infra-fs`, close to file and symlink operations.
- Internal application, domain, and port contracts still expose raw
  `Map<String, Any?>` for workflow, scaffold, validation, telemetry, and some
  repository results.
- Domain and port models use `java.nio.file.Path` directly. This is often a
  harmless value type, but the architecture rule says domain should avoid
  filesystem APIs, so the policy is ambiguous.
- `runtime-core` is cleaner than before but still exposes broad `api(...)`
  edges for application/domain/ports. That may be acceptable for current
  adapter wiring, but it should not stay the long-term public runtime umbrella.

This follow-up should harden those seams without changing user-visible runtime
behavior.

## Problem

Hexagonal architecture depends on the application core owning business policy
and depending on ports, while adapters translate between external mechanisms
and those ports. Today the module graph mostly enforces dependency direction,
but some responsibilities are still placed at the wrong semantic level:

- The filesystem adapter owns too much scaffold business policy.
- Some ports are shaped like boundary payloads rather than application
  contracts.
- Raw maps make it easy for CLI/MCP/JSON/YAML shapes to leak inward.
- The `Path` rule is enforced informally rather than as a deliberate contract.
- `runtime-core` can still function as a convenience dependency unless the
  graph is tightened further.

The result is not broken behavior, but it leaves future features room to place
new business behavior in adapters or use untyped payloads inside the core.

## Goals

1. Move scaffold and install business policy inward so infrastructure adapters
   mostly perform filesystem, process, symlink, serialization, and rollback
   mechanics behind ports.
2. Replace internal raw-map application and port APIs with typed request/result
   models where the data has stable semantics.
3. Keep raw maps only at true external seams: JSON, YAML, MCP arguments,
   CLI JSON payloads, schema custom fields, and intentionally open extension
   fields.
4. Clarify the rule for `Path` in domain and port models, either by allowing it
   explicitly as an inert value type or by introducing narrow domain path value
   wrappers for core concepts.
5. Continue shrinking `runtime-core` so adapters depend directly on the modules
   they use and `runtime-core` remains DI/runtime assembly, not an API umbrella.
6. Preserve all current CLI, MCP, install, scaffold, workflow, telemetry,
   desktop, and validation behavior.
7. Add or update architecture tests so the tightened rules fail loudly when
   future code drifts.

## Non-Goals

- Do not redesign CLI prompts, command names, MCP tool names, desktop UX,
  installer semantics, or persisted data formats.
- Do not reimplement scaffold or install behavior from scratch.
- Do not remove intentionally open extension maps such as
  `PlatformManifest.customFields`.
- Do not generate Kotlin types from YAML schemas.
- Do not add nested `runtime-core:data` or `runtime-core:domain` modules.
- Do not implement SKILL-53 shared install-selection persistence.
- Do not add feature flags; this is behavior-preserving architecture hardening.

## Target Architecture Refinement

The SKILL-52 module graph remains the target:

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

SKILL-52.1 tightens the meaning of that graph:

- Application use cases should own workflow and policy decisions.
- Ports should describe what the application needs, not mirror adapter methods.
- Infrastructure should translate and execute external effects.
- Boundary payloads should be converted to typed models as soon as they enter
  the application core.
- Contract serialization should happen at adapter/application boundaries, not
  inside pure policy code by convenience.

## Scope

### 1. Scaffold Policy Extraction

Current signals:

- `runtime-application/src/main/kotlin/skillbill/application/ScaffoldService.kt`
  delegates nearly every method straight to `ScaffoldGateway`.
- `runtime-ports/src/main/kotlin/skillbill/ports/scaffold/ScaffoldGateways.kt`
  returns raw maps for list/show/explain/validate/upgrade/fill/edit.
- `runtime-infra-fs/src/main/kotlin/skillbill/scaffold/ScaffoldService.kt`
  contains scaffold kind detection, payload validation, platform-pack planning,
  file planning, manifest edit planning, install target planning, and rollback
  coordination.

Required direction:

- Move pure scaffold planning and validation into `runtime-domain` or
  `runtime-application` depending on whether it is deterministic policy or
  use-case orchestration.
- Keep actual file reads/writes, symlinks, manifest persistence, install target
  mutation, rollback file operations, and process/environment details in
  `runtime-infra-fs`.
- Replace broad gateway methods with smaller ports that express the external
  capabilities needed by the planner, for example source loading, manifest
  persistence, generated-file staging, install link application, and repo
  validation.
- Preserve atomic rollback semantics.

### 2. Install Policy Extraction

Current signals:

- `InstallService` delegates `planInstall`, `applyInstall`, and `linkSkill`
  directly to `InstallPlanGateway`.
- Install domain models exist in `runtime-domain`, but policy and adapter
  mechanics are still coupled through the gateway shape.

Required direction:

- Keep install request/plan/result models in domain-owned model packages.
- Ensure install plan construction policy is in domain/application code.
- Keep filesystem detection, symlink operations, Windows preflight checks,
  runtime binary discovery, agent config mutation, and rollback mechanics in
  `runtime-infra-fs`.
- If a policy cannot move cleanly, add a narrow port and document why the
  external mechanism must remain outside the core.

### 3. Typed Internal Contracts

Current signals:

- `WorkflowService` public methods return `Map<String, Any?>`.
- `WorkflowEngine` produces full/summary/resume payload maps.
- `ScaffoldGateway` returns maps for most command surfaces.
- Telemetry config and HTTP ports still expose maps in several places.

Required direction:

- Introduce typed application result models for stable use cases. Suggested
  first targets:
  - workflow open/update/get/list/latest/resume/continue results;
  - scaffold list/show/explain/validate/upgrade/fill/edit results;
  - repo validation output models;
  - telemetry status/config mutation/sync results where not already typed.
- Keep explicit mappers from typed models to CLI JSON, MCP response maps, and
  contract DTOs at adapter or application boundary packages.
- Keep raw maps for intentionally open payloads only:
  - schema custom fields;
  - arbitrary YAML/JSON artifacts that are not owned by runtime;
  - MCP input argument maps before parse;
  - contract helper internals that exist only to serialize a known DTO.
- Add architecture coverage that prevents new public application/domain/port
  APIs from returning raw maps unless they are annotated or listed as an
  allowed open-boundary exception.

### 4. Path Value-Type Policy

Current signals:

- Domain models import `java.nio.file.Path`, for example scaffold and install
  models.
- Architecture tests ban direct file IO, but not `Path` imports everywhere.
- `ReviewParsingPatterns.expandAndNormalizePath` calls system property access
  from domain-adjacent review code.

Required direction:

Choose and document one policy:

1. **Allowed value type policy**: `Path` is allowed in domain/port models only
   when treated as an inert value. Direct IO, `System.getProperty`, environment
   lookup, normalization from user home, existence checks, and process access
   remain banned outside adapters/application seams.
2. **Wrapper policy**: introduce domain value wrappers such as `RepoRoot`,
   `SkillSourcePath`, `PlatformPackPath`, and `AgentTargetPath`, with adapters
   converting to/from `Path`.

The implementation should prefer the smallest behavior-preserving option. If
the allowed value type policy is chosen, add architecture tests and docs that
make the exception explicit. If wrappers are chosen, migrate incrementally and
avoid touching every call site at once unless needed.

### 5. Runtime-Core API Shrink

Current signals:

- `runtime-core/build.gradle.kts` exposes `runtime-application`,
  `runtime-domain`, and `runtime-ports` via `api(...)`.
- CLI/MCP/Desktop now declare more direct dependencies, but `runtime-core` can
  still act as a convenient umbrella.

Required direction:

- Keep `runtime-core` source limited to `skillbill` and `skillbill.di`.
- Convert `runtime-core` `api(...)` dependencies to `implementation(...)` where
  downstream modules already declare direct dependencies.
- If Kotlin-Inject generated component types require some public API edge, keep
  the narrowest edge and document it in `ARCHITECTURE.md` and architecture
  tests.
- Ensure CLI, MCP, Desktop, tests, and packaged runtime entry points compile by
  declaring direct module dependencies rather than relying on transitive core
  exposure.

## Acceptance Criteria

1. Scaffold planning and validation policy that is independent of filesystem
   effects no longer lives in `runtime-infra-fs`; it is owned by
   `runtime-domain` or `runtime-application` with focused tests.
2. Filesystem scaffold adapters still own file IO, symlink operations, manifest
   persistence, rollback file operations, and generated artifact staging.
3. Install plan policy that is independent of filesystem/process effects is
   owned by `runtime-domain` or `runtime-application`; external effects remain
   behind install ports in `runtime-infra-fs`.
4. Public application/domain/port APIs introduced or touched by this work use
   typed request/result models instead of raw `Map<String, Any?>`, except for
   documented open-boundary exceptions.
5. Workflow, scaffold, and telemetry adapter outputs still match existing CLI
   and MCP contracts after typed internal models are introduced.
6. The chosen `Path` policy is documented in `runtime-kotlin/ARCHITECTURE.md`
   and enforced by architecture tests.
7. Domain and port code still perform no direct file IO, process environment
   access, JDBC access, HTTP calls, Clikt usage, MCP adapter usage, Desktop UI
   usage, or infrastructure imports.
8. `runtime-core` remains composition/runtime metadata only, with no
   implementation packages and no broad concrete infrastructure API re-exports.
9. CLI, MCP, Desktop, and tests declare direct dependencies on the modules they
   use where possible; `runtime-core` is not relied on as a broad API umbrella.
10. Existing behavior is preserved for install, scaffold, native-agent,
    workflow, review, telemetry, CLI, MCP, Desktop packaging, and repository
    validation flows.
11. Architecture tests fail loudly for:
    - new public raw-map application/domain/port APIs without an allowed
      exception;
    - direct file IO or process environment access outside allowed layers;
    - scaffold/install policy reintroduced into filesystem adapters;
    - `runtime-core` implementation packages or concrete infra re-exports;
    - adapters importing low-level implementation packages instead of using
      application services and ports.
12. Required validation passes:
    - `(cd runtime-kotlin && ./gradlew check)`
    - `skill-bill validate`
    - `scripts/validate_agent_configs`
    - `npx --yes agnix --strict .`

## Suggested Decomposition

This should be decomposed. Recommended subtasks:

1. **Typed Boundary Foundation**
   - Add allowed raw-map exception rules and architecture tests.
   - Introduce typed workflow result models and mapper coverage.
   - Preserve CLI/MCP/golden outputs.

2. **Scaffold Policy Extraction**
   - Split pure scaffold planning/validation from filesystem mutation.
   - Reshape scaffold ports around external capabilities.
   - Keep rollback and generated-output boundaries intact.

3. **Install Policy Extraction**
   - Move pure install planning/application rules inward.
   - Keep symlink, agent config, runtime binary, Windows preflight, and rollback
     mechanics in filesystem adapters.
   - Preserve install CLI and `install.sh` behavior.

4. **Path Policy And Runtime-Core Shrink**
   - Decide and enforce the `Path` policy.
   - Reduce `runtime-core` API exposure where possible.
   - Update architecture docs and module dependency tests.

5. **Final Validation And Contract Lock**
   - Run full validation.
   - Update SKILL-52/SKILL-52.1 history or decision docs if requested.
   - Ensure no generated support pointers, `SKILL.md` wrappers, native-agent
     outputs, or install staging artifacts are committed.

## Implementation Notes

- Prefer small ports named by the application capability rather than by the
  current filesystem implementation.
- Do not move adapter-only code inward just to reduce `runtime-infra-fs` size.
  If code reads/writes files, mutates links, shells out, resolves home
  directories, reads environment, or inspects installed runtimes, it belongs in
  an adapter or a composition seam.
- Do not weaken typed schema errors or loud-fail behavior while replacing maps
  with typed models.
- Preserve `content.md` / generated `SKILL.md` boundaries and install staging
  exclusions from `docs/skill-source-generation.md`.
- Use existing architecture tests as the pattern for new guards. Keep them
  precise enough that intentional open-boundary maps can be listed explicitly
  without suppressing unrelated violations.

## Risks

- The scaffold extraction may reveal adapter methods that currently mix policy,
  IO, rollback, and install side effects in one function. Split by behavior,
  not by file size.
- Replacing maps can cause subtle CLI/MCP output drift. Protect outputs with
  existing golden tests and focused regression tests.
- `runtime-core` dependency changes can break generated Kotlin-Inject component
  visibility. If a public edge must remain, document why and keep it narrow.
- Path wrapper migration could become broad churn. Prefer an explicit value-type
  policy unless wrappers clearly reduce real ambiguity.

## Validation Strategy

Run the architecture-focused suite early after each subtask:

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
```

Run targeted module tests for the touched area, then the full required suite:

```bash
(cd runtime-kotlin && ./gradlew check)
skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .
```

## Recommended Next Prompt

Run `bill-feature-implement` on:

```text
.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec.md
```
