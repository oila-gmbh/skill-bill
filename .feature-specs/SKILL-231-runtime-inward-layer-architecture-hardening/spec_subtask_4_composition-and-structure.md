# SKILL-231 · Subtask 4: Composition and structure

## Scope

Empty the remaining baselines from subtask 1, confine composition to
`skillbill.di`, and make `ARCHITECTURE.md` describe the tree that exists.

**Rename the spillover files.** 112 files split a logical unit across parts with
a suffix that names the split rather than the responsibility:
`runtime-application` 54 (`Continued`, `Continued1`…`Continued6`),
`runtime-infra-fs` 27 (`Helpers2`…`Helpers4`, `Fns2`, `Fns3`), `runtime-core` 27
(`RuntimeComponentBindingsA1`…`B7`, `RuntimeComponentProvides1`…`13`),
`runtime-infra-sqlite` 1. Subtask 3 already renamed the 3 in `runtime-ports`.

Ten clusters exceed 500 lines when their parts are attributed to one unit:

| cluster | lines | files |
| --- | --- | --- |
| `FeatureTaskRuntimeRunLoopCheckpoint` | 1,069 | 7 |
| `FeatureTaskRuntimeOutputVerification` | 981 | 6 |
| `FeatureTaskRuntimePhaseAttempts` | 824 | 4 |
| `FeatureTaskRuntimeValidationGate` | 817 | 5 |
| `FeatureTaskRuntimeAttemptSettlement` | 754 | 4 |
| `FeatureTaskRuntimeLaunch` | 696 | 4 |
| `RuntimeComponentProvides` | 664 | 13 |
| `FeatureTaskRuntimeDrive` | 655 | 5 |
| `FeatureTaskRuntimePhaseRunner` | 556 | 4 |
| `FeatureTaskRuntimeSharedArgs` | 534 | 2 |

The logical-type ceiling does not see them because each part is its own
`@Inject class` taking the run loop as a method parameter, so nothing bills to a
shared receiver. Re-merging the parts is not the fix — a merged unit that
breaches 500 lines fails the ceiling. Each part gets a name for the
responsibility it holds. Where no such name exists, the split is arbitrary and
the parts belong to one type that must then get smaller.

`runtime-core`'s 27 files are the clearest case. `RuntimeComponentBindingsA1`
through `B7` and `RuntimeComponentProvides1` through `13` are one composition
root sliced by ordinal. Slice it by subsystem — the boundary already exists in
the bindings themselves.

**Break the remaining cycles.** `runtime-infra-fs` 9 pairs, `runtime-mcp` 4,
`runtime-infra-sqlite` 1. The `runtime-infra-fs` set is the real work:
`infrastructure` is mutually entangled with `install`, `launcher`,
`nativeagent`, and `scaffold`, and `install` with `launcher`, `nativeagent`, and
`scaffold`. Nine mutual-import pairs across five areas means no area of that
module compiles alone, which is the capability the cycles deny. The
`runtime-mcp` set is `core` against each of `featuretask`, `lifecycle`,
`scaffold`, and `workflow` — the same hub-and-spoke shape SKILL-229 broke in
`runtime-cli`, where `core` held both the shared model and the dispatch.

**Fix the composition leaks.** Five main-source sites construct a collaborator
instead of receiving it:

- `runtime-application/.../telemetry/TelemetryService.kt:77` builds
  `TelemetryLevelMutationService(database, settingsProvider, configStore)` —
  a class `RuntimeComponentProvides1` already binds.
- `runtime-application/.../goalrunner/GoalRunnerTickProgressReader.kt:87` builds
  `GoalRunnerProgressReader(outcomeStore)`.
- `runtime-infra-fs/.../install/InstallPlanBuilder.kt:37` builds
  `InstallPlanWireValidatorAdapter()`.
- `runtime-infra-fs/.../install/InstallApplySideEffects.kt:82` builds
  `FileTelemetryConfigStore(context)`.
- `runtime-infra-fs/.../scaffold/runtime/ScaffoldStandaloneEntrypoint.kt:25-26`
  builds `FileSystemScaffoldGateway` and `FileSystemScaffoldCatalogGateway`.

The last is a second composition root inside an infrastructure module. Either it
resolves through `RuntimeComponent` and stops being one, or
`../../../runtime-kotlin/ARCHITECTURE.md` documents it as a sanctioned second
entrypoint and states why it cannot. Silence is not an option.

**Resolve the `api` edges.** Subtask 1 pinned today's edges, including
`runtime-infra-fs`, `runtime-infra-http`, and `runtime-infra-sqlite` all
exposing `api(":runtime-ports")` and `api(":runtime-domain")`. An adapter
implements a port; it does not usually need to re-export it. For each of the six
edges, either narrow it to `implementation` or record the public signature that
requires it. `runtime-cli`'s `api(":runtime-ports")` gets the same treatment.

**Correct the `skillbill.contracts` package ownership.**
`../../../runtime-kotlin/ARCHITECTURE.md` says the package spans
`runtime-contracts` and `runtime-infra-fs`, justified by schema validators
needing `com.networknt`. The tree is wider: `runtime-infra-fs` declares
`skillbill.contracts`, `.decomposition`, `.featuretask`, `.goalcontinuation`,
`.goalplanning`, `.review`, and `.telemetry` under that root, and holds
non-validator files there — `WorkflowContractResources.kt`,
`GoalRunnerCommitPushResultContractReader.kt`, `ContractLoaderSupport.kt`. The
documented justification covers validators only. Either the non-validator files
move out or the paragraph states the real rule.

**Correct the rest of Package Ownership.** `skillbill.model` is documented as
holding `RuntimeContext` alone; it holds `RuntimeContext`, `SkillIdentity`,
`SkillsHome`, and `SkillbillPaths`. `skillbill.idestatus` exists in
`runtime-domain` and is documented nowhere. `RuntimeArchitectureDocumentationTest`
passes on both because it checks for substring presence, not correspondence with
the tree. Make that test compare against `RuntimeModuleCatalog`'s package list
so the next drift fails the build.

## Acceptance Criteria

1. All 112 spillover-named files are renamed for the responsibility they hold.
   Every module's spillover baseline is empty.
2. `runtime-core`'s composition root is sliced by subsystem, not by ordinal. No
   `RuntimeComponentBindings<letter><digit>` or
   `RuntimeComponentProvides<digit>` name remains.
3. The logical-type ceiling baseline stays empty. No cluster was re-merged into a
   file that breaches 500 lines, and no new exemption was added.
4. The `runtime-infra-fs` (9 pairs), `runtime-mcp` (4), and `runtime-infra-sqlite`
   (1) package-cycle baselines are empty, or a remaining pair is recorded in
   `../../../runtime-kotlin/agent/decisions.md` with the reason it is
   irreducible.
5. Each `runtime-infra-fs` area compiles without the areas it currently imports
   mutually. A test or a targeted compile task demonstrates this; the empty
   baseline alone does not.
6. The five direct-construction sites resolve their collaborator through
   `RuntimeComponent`. No main-source site outside `skillbill.di` constructs a
   class the component binds, and a guard enforces it.
7. `ScaffoldStandaloneEntrypoint` either resolves through `RuntimeComponent` or
   is documented in `../../../runtime-kotlin/ARCHITECTURE.md` as a sanctioned
   second entrypoint with the reason it cannot.
8. Each of the seven `api(project(...))` edges under review is narrowed to
   `implementation` or justified by a named public signature recorded in
   `../../../runtime-kotlin/ARCHITECTURE.md`. The module-edge pin from subtask 1
   is updated to the resolved edges.
9. The `skillbill.contracts` paragraph in
   `../../../runtime-kotlin/ARCHITECTURE.md` covers every package
   `runtime-infra-fs` declares under that root, and every file in them is either
   a schema validator or moved out.
10. `Package Ownership` matches the tree: `skillbill.model` lists all four types,
    `skillbill.idestatus` is documented, and no documented package is absent from
    the tree.
11. `RuntimeArchitectureDocumentationTest` compares documented package ownership
    against `RuntimeModuleCatalog` rather than checking substring presence. A
    package added to the tree without a doc entry fails the test.
12. Every baseline recorded in subtask 1 is empty, except the `runtime-infra-*`
    ambient-environment baselines (shrink-only by decision) and the
    `runtime-core` composition seam (a named exemption). The subtask report
    states the final state of each.
13. Behavior is unchanged. Every rename and move is a separate commit from any
    logic change.
14. `runtime-kotlin/gradlew check`, `npx --yes agnix --strict .`,
    `scripts/validate_agent_configs`, and `skill-bill validate` pass with no new
    suppression and no new exemption.

## Non-Goals

- Splitting a Gradle module. The ten-module set does not change.
- Reworking `runtime-application`'s run-loop behavior. The 54 renames are naming
  and file placement; the run loop's logic is untouched.
- Reducing `runtime-infra-fs`'s 381 files or 39,606 lines as a goal in itself.
  Size follows from breaking the cycles, or it does not.
- Merging CLI and MCP presentation. Subtask 2 recorded that decision.
- Adding an area-isolation guard to `runtime-infra-fs`. Criterion 5's targeted
  compile is the proof; the transitive-closure guard is a stronger claim than
  this subtask delivers.
- Touching `runtime-contracts`.
- Reworking the `@OpenBoundaryMap` allow-list or detekt configuration.

## Dependency Notes

Depends on subtask 1 for every baseline this subtask empties and for the
module-edge pin criterion 8 updates.

Depends on subtask 3. Its port renames and collapses move type names across the
tree; doing the 112 file renames first would mean redoing them. Its
`runtime-ports` spillover renames also establish the naming convention the other
109 follow.

Depends on subtask 2 only for the `runtime-mcp` cycles: breaking `core`'s
entanglement is cleaner once `McpRuntimeContext`'s defaults are gone, since those
defaults are part of why `core` reaches into every spoke.

This subtask lands last. It is the largest by file count and the smallest by
risk — renames and moves, with the composition fixes as the only semantic
change.

## Validation Strategy

- The renames are mechanical and the compiler is the proof. The judgment is in
  the names, which the review gate covers, not in the mechanism.
- Criterion 5 is the load-bearing cycle test. An empty cycle baseline proves the
  scanner found no mutual import; compiling one area alone proves the module is
  actually decomposable. Do both.
- The composition guard in criterion 6 is what keeps the five leaks from coming
  back. Its rejection case constructs a bound class outside `skillbill.di` and
  must fail before the guard exists.
- Criterion 11 is the drift guard. Verify it by adding a throwaway package to
  `RuntimeModuleCatalog` without a doc entry and observing the test fail.
- Every commit is either a rename or a logic change, never both, so a
  regression bisects to a semantic commit rather than a 54-file move.
- `runtime-kotlin/gradlew check` in a clean checkout, since the Spotless ratchet
  needs a real `.git` directory. Renames touch the ratchet's surface heavily, so
  run it before each commit rather than at the end.
- `skill-bill validate`, `agnix --strict`, and `scripts/validate_agent_configs`
  gate the governed-artifact and agent-config surfaces that the
  `ARCHITECTURE.md` edits touch.

## Next Path

```bash
skill-bill goal SKILL-231
```
