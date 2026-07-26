# SKILL-52 Subtask 2: Implementation Ownership

Parent overview: [spec.md](spec.md)

## Status

Complete

## Scope

Move implementation ownership out of `runtime-core` after the domain/application/contract seams are prepared.

This subtask owns:

- Moving packages currently under `runtime-core` that implement install, scaffold, native-agent, launcher, skill removal, workflow wrappers, or similar runtime behavior into the correct existing layer.
- Creating a narrowly scoped new top-level Gradle module only when no existing module honestly owns a package, and documenting the ownership reason in the implementation notes and architecture-adjacent coverage.
- Ensuring infrastructure implementations depend on ports and do not depend on CLI, MCP, Desktop, or `runtime-core`.
- Preserving generated-source boundaries: do not commit generated `SKILL.md` wrappers, support pointers, provider-native outputs, install staging, or desktop package artifacts.
- Updating Gradle wiring required for the moved packages while keeping public behavior intact.

## Acceptance Criteria

1. `runtime-core` no longer owns implementation packages moved in this subtask.
2. Every package moved out of `runtime-core` lands in a correct existing layer or has a narrowly scoped new top-level module justification.
3. Infrastructure modules touched by this subtask implement ports and avoid CLI, MCP, Desktop, and `runtime-core` dependencies.
4. Source/generated boundaries remain intact for skill rendering, support pointers, native-agent generation, install staging, and desktop packaging.
5. Existing runtime behavior for moved install, scaffold, native-agent, launcher, skill-remove, and workflow wrapper paths is preserved.
6. Repo validation for this subtask passes through `bill-quality-check` or the equivalent routed Kotlin checks.

## Non-Goals

- Do not make CLI and MCP stop using `runtime-core` as an umbrella in this subtask unless required by moved-package compilation.
- Do not complete final architecture-test enforcement yet.
- Do not rewrite runtime APIs for style only; movement must serve ownership boundaries.
- Do not implement SKILL-53 install-selection persistence.
- Do not add nested `runtime-core:data` or `runtime-core:domain` modules.
- Do not rename public commands or script entry points.

## Dependencies

Depends on subtask 1. The implementation moves should use the ports, model placement, and parse-seam ownership established there instead of carrying concrete outside-world behavior through the new layer graph.

## Validation Strategy

Run `bill-quality-check`. If the routed quality check is unavailable, run `(cd runtime-kotlin && ./gradlew check)` and document the fallback.

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-52-full-hexagonal-runtime-architecture/spec_subtask_2_implementation-ownership.md`.
