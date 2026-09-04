# SKILL-232 · Subtask 1 — Unused private/internal deletion pass

## Scope

Remove unused `private` and `internal` symbols from `runtime-kotlin`
production sources in small verified batches.

Work includes:

- Retain the open ports
  `runtime-ports/.../DecompositionManifestWriterSupport.kt` dead-helper
  deletion (`resolvedParentSpecPath`, `asStringAnyMapOrNull` stay); do not
  change the application twin’s live helpers.
- Run detekt; fix only `UnusedPrivateMember` / `UnusedPrivateProperty` /
  `UnusedPrivateClass` (and equivalent) findings in main sources.
- Scan for `internal` declaration-only symbols; verify candidates with
  repo-wide Kotlin references including tests and inject/KSP wiring.
- First-batch candidates to verify (delete only if still unused):
  - `GoalRunnerFinalizationDeps` —
    `runtime-application/.../goalrunner/GoalRunnerSharedArgs.kt`
  - `ADDON_CONTENT_PROJECTION_NAME` —
    `runtime-application/.../featuretask/FeatureTaskRuntimePhasePromptComposer.kt`
  - `inFlightReentry` —
    `runtime-application/.../featuretask/FeatureTaskRuntimeRunStateLoopExtensions.kt`
  - `withAgentActivity` —
    `runtime-application/.../work/IdeStatusActivityProjection.kt`
  - `identityCanonical` —
    `runtime-domain/.../review/context/model/ReviewContextHunkModels.kt`
- Re-scan after each batch; gate with compileKotlin + detekt.

## Acceptance Criteria

1. Confirmed unused `private`/`internal` production symbols identified by
   detekt or declaration-only scan are deleted; retained symbols have at
   least one real consumer (call site, test, or inject/KSP wiring).
2. Ports `DecompositionManifestWriterSupport` keeps only live helpers
   (`resolvedParentSpecPath`, `asStringAnyMapOrNull` or their successors);
   application twin helpers used by writers/extractors remain.
3. No public, MCP, CLI, YAML, or wire-name-only symbols are removed solely
   because Kotlin call sites are absent.
4. Touched-module `compileKotlin` and `runtime-kotlin` `detekt` succeed after
   the final batch.

## Non-Goals

- Expanding into package-public orphan cleanup outside the open ports file.
- Architecture/YAGNI refactors tagged by over-engineering review.
- Rewriting duplicate helpers across packages into a shared util.

## Dependency Notes

- None. Single subtask; base branch is `main`.

## Validation Strategy

```bash
cd runtime-kotlin && ./gradlew \
  :runtime-ports:compileKotlin \
  :runtime-application:compileKotlin \
  :runtime-domain:compileKotlin \
  :runtime-infra-fs:compileKotlin \
  :runtime-infra-sqlite:compileKotlin \
  detekt
```

Add compileTestKotlin / focused tests when a deleted `internal` had test
consumers that must be updated or removed with it.

## Next Path

`skill-bill goal SKILL-232`
