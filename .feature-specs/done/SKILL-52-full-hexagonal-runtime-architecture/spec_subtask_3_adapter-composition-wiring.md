# SKILL-52 Subtask 3: Adapter And Composition Wiring

Parent overview: [spec.md](spec.md)

## Scope

Make `runtime-core` a composition/runtime assembly module and replace broad umbrella dependencies from external adapters with direct, honest dependencies.

This subtask owns:

- Reducing `runtime-core` to composition/runtime assembly and DI wiring only.
- Removing `runtime-core` ownership of install, scaffold, native-agent, launcher, skill-remove, workflow wrapper, or other non-composition implementation packages left after subtask 2.
- Rewiring `runtime-cli`, `runtime-mcp`, and `runtime-desktop` away from `runtime-core` as a broad API umbrella where direct module dependencies are more accurate.
- Keeping adapter behavior stable for CLI commands, MCP tools, desktop entry points, scripts, and launcher behavior.
- Ensuring adapters do not bypass application/use-case ports by reaching into infrastructure details directly except for documented composition-root wiring.

## Acceptance Criteria

1. `runtime-core` contains composition/runtime assembly only and no install, scaffold, native-agent, launcher, workflow wrapper, or other implementation packages.
2. CLI and MCP no longer rely on `runtime-core` as a broad API umbrella where direct dependencies are the honest contract.
3. Infrastructure modules do not depend on CLI, MCP, Desktop, or `runtime-core`.
4. CLI, MCP, Desktop, launcher, and script entry-point behavior is preserved.
5. Adapter wiring goes through application use cases and ports rather than bypassing boundaries, with only composition-root exceptions documented.
6. Repo validation for this subtask passes through `bill-quality-check` or the equivalent routed Kotlin checks.

## Non-Goals

- Do not change command names, MCP tool names, desktop UX, installer semantics, or persisted data formats.
- Do not add feature flags; SKILL-52 has no rollout flag.
- Do not complete final architecture documentation and full violation tests unless they are necessary to keep this wiring honest.
- Do not implement SKILL-53 install-selection persistence.
- Do not add nested `runtime-core:data` or `runtime-core:domain` modules.

## Dependencies

Depends on subtask 2. Adapter rewiring should happen after implementation ownership has moved so direct dependencies point at final owners instead of temporary `runtime-core` packages.

## Validation Strategy

Run `bill-quality-check`. If the routed quality check is unavailable, run `(cd runtime-kotlin && ./gradlew check)` and document the fallback.

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-52-full-hexagonal-runtime-architecture/spec_subtask_3_adapter-composition-wiring.md`.
