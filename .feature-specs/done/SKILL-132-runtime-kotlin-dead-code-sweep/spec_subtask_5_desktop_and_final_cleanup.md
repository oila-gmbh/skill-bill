# Subtask 5: Verify Desktop Boundaries and Finish Module Cleanup

## Scope

Audit remaining desktop candidates and perform the final cross-module cleanup after Subtasks 1-4.

Inspect generated KSP/DI code before changing desktop providers. The preparation audit already confirmed generated consumers for `LocalDesktopDatabaseProvider` and `RoomRecentRepoRepository`; retain them unless generated composition changes as part of this work. Evaluate unused placeholder services, design tokens, models, empty packages, resources, libraries, and module edges.

Repeat the full declaration, resource, and dependency sweep to catch code made unreachable by earlier deletions. Close the evidence ledger with no unresolved high-confidence candidate.

## Acceptance Criteria

1. Desktop KSP/DI output and composition roots are generated and inspected before any provider, repository, binding, or module removal.
2. `LocalDesktopDatabaseProvider` and `RoomRecentRepoRepository` remain unless their generated consumers are deliberately replaced or removed and desktop behavior remains covered.
3. Placeholder services, design tokens, models, previews, and resources are removed only when source, resource, reflection, preview, and generated consumers are all absent.
4. Project and library dependencies used only by deleted code are removed; retained dependencies have a production, generated, packaging, or test-fixture consumer.
5. No unresolved high-confidence dead-code candidate remains. Every retained compatibility-sensitive or non-lexically-reached declaration has a rationale in the evidence ledger.
6. Desktop JVM compilation, KSP generation, tests, and packaging pass.
7. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` all pass.
8. The user's pre-existing working-tree changes remain intact and unrelated files are not reformatted or rewritten.

## Non-Goals

- Desktop redesign or visual changes.
- Replacing Room, KSP, Kotlin Inject, or Compose.
- Removing generated-code-only consumers as dead.
- Broad dependency upgrades.

## Dependency Notes

Depends on Subtasks 2, 3, and 4 so the final sweep sees the stable reduced surface.

## Validation Strategy

- Generate and inspect desktop KSP/DI sources.
- Run desktop compilation, tests, and packaging tasks.
- Review Gradle dependency graphs and built artifacts.
- Repeat repository-wide symbol, string, resource, schema, CLI, MCP, and module-edge searches.
- Run the complete repository validation gate.

## Next Path

When this subtask passes, the SKILL-132 cleanup program is complete.

