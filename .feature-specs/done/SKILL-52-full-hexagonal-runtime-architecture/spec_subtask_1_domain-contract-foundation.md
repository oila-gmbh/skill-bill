# SKILL-52 Subtask 1: Domain And Contract Foundation

Parent overview: [spec.md](spec.md)
Status: Complete

## Scope

Create the foundation for the final hexagonal split by cleaning the domain, application, ports, and runtime-contract ownership seams before broad package movement.

This subtask owns:

- Moving or extracting domain/application code paths that currently perform concrete filesystem, JDBC, HTTP, process environment, Clikt, MCP, Desktop, or infrastructure work behind explicit ports.
- Placing newly required ports in `runtime-ports` and public app/domain/port DTOs in their owning `model` packages.
- Clarifying runtime contract/schema validation at the owning parse seams for the areas touched in this subtask, using `runtime-contracts` typed errors and schema-first patterns where applicable.
- Updating or adding focused tests for the moved/extracted domain and application behavior so existing runtime behavior stays preserved.

## Acceptance Criteria

1. Domain modules touched by this subtask no longer import concrete IO, JDBC, HTTP clients, process environment access, Clikt, MCP, Desktop, or infrastructure packages.
2. Application use cases touched by this subtask access outside-world behavior only through ports, or document exact temporary blockers in code-adjacent architecture coverage for later SKILL-52 subtasks.
3. Runtime contract/schema validation touched by this subtask is owned at the parse seam rather than added for pure domain convenience.
4. Public app/domain/port models introduced or moved by this subtask live under `model` packages.
5. Existing behavior for affected install, workflow, review, telemetry, scaffold, native-agent, or contract flows is preserved.
6. Repo validation for this subtask passes through `bill-quality-check` or the equivalent routed Kotlin checks.

## Non-Goals

- Do not move all `runtime-core` implementation packages in this subtask.
- Do not make `runtime-core` composition-only yet.
- Do not rewire CLI, MCP, or Desktop Gradle dependencies unless needed to keep this foundation compiling.
- Do not implement SKILL-53 install-selection persistence.
- Do not redesign persisted data formats, CLI prompts, MCP tools, desktop UI behavior, installer semantics, or public command names.
- Do not add nested `runtime-core:data` or `runtime-core:domain` modules.
- Do not generate Kotlin types from schemas.

## Dependencies

None. This is the first subtask because later package movement and adapter cleanup need honest domain/application seams and reusable ports first.

## Validation Strategy

Run `bill-quality-check`. If the routed quality check is unavailable, run `(cd runtime-kotlin && ./gradlew check)` and document the fallback.

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-52-full-hexagonal-runtime-architecture/spec_subtask_1_domain-contract-foundation.md`.
