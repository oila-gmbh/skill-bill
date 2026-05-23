# SKILL-52 Subtask 4: Architecture Enforcement And Validation

Parent overview: [spec.md](spec.md)

## Scope

Lock the final graph into documentation, runtime module metadata, architecture tests, and repository validation after the code movement is complete.

This subtask owns:

- Updating `runtime-kotlin/ARCHITECTURE.md` to define the full hexagonal graph, module responsibilities, forbidden dependencies, and runtime contract/schema validation ownership without transitional language that permits mixed ownership.
- Aligning `RuntimeModule.declaredGradleModules`, `RuntimeModule.declaredSubsystemPackages`, Gradle settings, and architecture-test module declarations with the final graph.
- Strengthening architecture tests so they fail on non-composition packages in `runtime-core`, forbidden layer imports, adapter bypasses, and public app/domain/port models outside `model` packages.
- Running the full required validation suite and fixing root causes without suppressions.
- Updating the parent spec status and any necessary implementation/audit notes for the completed SKILL-52 work.

## Acceptance Criteria

1. `runtime-kotlin/ARCHITECTURE.md` defines the final full hexagonal graph, responsibilities, and forbidden dependencies without transitional language that permits mixed ownership.
2. `RuntimeModule.declaredGradleModules`, `RuntimeModule.declaredSubsystemPackages`, Gradle settings, and architecture tests match the final graph.
3. Architecture tests fail on listed boundary violations, including non-composition packages in `runtime-core`, forbidden layer imports, adapter bypasses, and public app/domain/port models outside `model` packages.
4. Runtime contract/schema validation ownership is described at the owning parse seam in architecture coverage.
5. Existing runtime behavior remains preserved after final enforcement.
6. Full validation passes with `(cd runtime-kotlin && ./gradlew check)`, `skill-bill validate`, `scripts/validate_agent_configs`, and `npx --yes agnix --strict .`.

## Non-Goals

- Do not perform broad implementation package movement unless previous subtasks left a concrete gap needed to satisfy final enforcement.
- Do not redesign CLI prompts, MCP tools, desktop UI behavior, installer semantics, or persisted data formats.
- Do not implement SKILL-53 install-selection persistence.
- Do not add nested `runtime-core:data` or `runtime-core:domain` modules.
- Do not generate Kotlin types from schemas.
- Do not rename public commands or break existing script entry points.

## Dependencies

Depends on subtask 3. Final documentation, metadata, and architecture tests should reflect the completed graph after domain seams, implementation ownership, and adapter composition have converged.

## Validation Strategy

Run `bill-quality-check`, then explicitly run the full parent-spec validation commands:

```bash
(cd runtime-kotlin && ./gradlew check)
skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .
```

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-52-full-hexagonal-runtime-architecture/spec_subtask_4_architecture-enforcement-validation.md`.
