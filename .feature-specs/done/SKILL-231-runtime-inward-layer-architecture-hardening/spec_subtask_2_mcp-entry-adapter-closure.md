# SKILL-231 · Subtask 2: MCP entry-adapter closure

## Scope

Give `runtime-mcp` the boundary SKILL-229 gave `runtime-cli`, and remove the
duplication that survived that feature.

**Remove `McpRuntimeContext`'s JVM-seeded defaults.**
`../../../runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/core/McpRuntime.kt:28-33`
declares five default arguments:

```
val requester: HttpRequester = UnconfiguredHttpRequester,
val environment: Map<String, String> = System.getenv(),
val userHome: Path = Path.of(System.getProperty("user.home")),
val workflowGitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
val repositoryRoot: Path? = null,
```

This is the shape SKILL-229 AC7 removed from `CliRunState`: a context seeded
from the JVM before anything overrides it. Resolve every input once, at one
seam, before `RuntimeComponent` is created — the same precedence the CLI now
uses. `McpRuntime` is an `object` whose `services(context, …)` builds a
component per call; the resolution seam sits ahead of that call, not inside the
context's default arguments.

`Main.kt:6` reads `System.getenv()` and
`GovernedReviewEvidenceBridge.kt:28` defaults a parameter to `System.getenv()`.
Both read the process environment behind the injected one. `Main` is the one
place a process may read its own environment; the bridge is not.

**Remove `McpScaffoldRuntime`'s ambient reads.** Line 56 calls `LocalDate.now()`
— inject the clock. Line 61 declares
`findRepoRoot(start: Path = Path.of("").toAbsolutePath().normalize())`, the
exact default argument AC8 deleted from the CLI's scaffold. It resolves through
the repository-root coordinate instead.

**Invert `canonicalRepositoryRoot` behind a port.** It exists three times,
byte-identical:

- `runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/CanonicalRepositoryRoot.kt:5`
- `runtime-cli/src/main/kotlin/skillbill/cli/model/CliRuntimeContext.kt:53`
- `runtime-mcp/src/main/kotlin/skillbill/mcp/core/McpRuntime.kt:272`

The two entry-adapter copies exist because neither adapter may import
`skillbill.infrastructure.*`, so each restated the rule. Both call
`toRealPath()` and `.toFile().exists()` — filesystem IO in entry-adapter main
source with no port. Declare the capability in `runtime-ports`, keep the
implementation in `runtime-infra-fs`, and have both entry adapters resolve it
through `RuntimeComponent`. Name the port for what it answers, not for the
filesystem it walks.

This is the one place this subtask touches `runtime-cli`: deleting its copy and
pointing it at the port. `RemoveCliCommandTest` and the `--home` precedence
tests are the regression guard for the CLI side.

**Record the CLI/MCP presentation duplication as a decision, do not merge it.**
`runtime-mcp` mirrors `runtime-cli` across 14 file pairs — `Component`,
`Runtime`, `WorkflowContinueMaps`, `WorkflowContinueBranchMapsCore`,
`WorkflowContinueBranchMapsDecomposition`, `WorkflowGoalObservabilityMapping`,
`WorkflowResultMappers`, `ReviewResultMappers`, `TelemetryResultMappers`,
`LearningPayloads`, `ScaffoldCommandRequestParser`,
`ScaffoldCommandRequestParseHelpers`,
`ScaffoldCommandRequestBaselineLayerParser`, `Main`. Two entry adapters shaping
the same application results into two different payload formats is the expected
shape of a hexagonal boundary; the finding is that nobody has stated whether the
overlap is intentional. Write the decision in
`../../../runtime-kotlin/agent/decisions.md` naming which pairs are genuinely
format-specific and which are copies. Merging them is a separate feature.

## Acceptance Criteria

1. `McpRuntimeContext` holds no default argument seeded from the JVM. Every
   run-scoped input is resolved once, at one seam, before `RuntimeComponent` is
   created.
2. `System.getenv` and `System.getProperty` do not appear in `runtime-mcp` main
   source outside `Main.kt`, which is the process boundary. The
   `GovernedReviewEvidenceBridge` default argument is gone.
3. `LocalDate.now()` does not appear in `runtime-mcp` main source; the scaffold
   runtime reads an injected clock.
4. `Path.of("")` does not appear in `runtime-mcp` main source, and `findRepoRoot`
   does not take the working directory as a default argument.
5. `canonicalRepositoryRoot` has exactly one implementation in the tree, behind a
   port declared in `runtime-ports` with its adapter in `runtime-infra-fs`.
   Neither `runtime-cli` nor `runtime-mcp` main source performs filesystem IO to
   resolve the repository root.
6. The port is named for the capability, not the filesystem walk, and the
   adapter keeps its own name.
7. An MCP tool invoked from a working directory below the repository root
   resolves the same root as the runtime beneath it. A test drives this; it is
   the divergence no current test covers.
8. The `runtime-mcp` ambient-clock, ambient-environment, and `@Inject`-defaults
   baselines are empty. The `runtime-cli` baselines stay empty.
9. `runtime-mcp` main source gains no `skillbill.infrastructure.*` import, and
   the `RuntimeAdapterDependencyAllowlistTest` allow-list does not grow.
10. The CLI/MCP presentation-duplication decision is recorded in
    `../../../runtime-kotlin/agent/decisions.md`, naming which of the 14 pairs
    are format-specific and which are copies.
11. Observable MCP behavior is unchanged: same tool names, same payload keys,
    same error shapes. `runtime-kotlin/gradlew check` and `skill-bill validate`
    pass.

## Non-Goals

- Merging the CLI and MCP presentation layers, or extracting a shared
  presentation module. The decision record is the deliverable.
- Changing the MCP tool surface, adding tools, or reshaping payloads.
- Reworking `runtime-cli`'s interior. The only CLI change is deleting its
  `canonicalRepositoryRoot` copy and resolving the port instead.
- Breaking the four `runtime-mcp` package cycles. Subtask 4 owns those; this
  subtask leaves that baseline as recorded.
- Scoping `McpComponent` or changing how `McpRuntime` builds a component per
  call.
- Touching `runtime-infra-fs`'s ambient-environment sites.

## Dependency Notes

Depends on subtask 1. The `runtime-mcp` ambient-clock, ambient-environment, and
`@Inject`-defaults baselines must exist before this subtask can be specified as
emptying them, and the guards are what prove the fix is complete rather than
partial.

Independent of subtasks 3 and 4. The repository-root port is a new port and does
not collide with the port collapse in subtask 3; if subtask 3 lands first, the
new port is subject to its "no port without a second consumer" rule and passes
on two consumers (`runtime-cli` and `runtime-mcp`).

## Validation Strategy

- The subdirectory test in criterion 7 is the load-bearing one: it catches an
  MCP tool that resolves the process working directory instead of the injected
  root. Name that bug in the test.
- The three emptied baselines are the mechanical proof that no ambient read
  survived. Set equality against an empty baseline is a hard ban, so a missed
  site fails the build rather than being recorded away.
- The CLI-side regression is covered by the existing `--home` and `remove`
  precedence tests, which must pass unchanged.
- Deleting two of the three `canonicalRepositoryRoot` copies is verified by a
  repository-wide search returning one definition.
- `runtime-kotlin/gradlew check` in a clean checkout; `skill-bill validate` for
  the governed-artifact surface.

## Next Path

```bash
skill-bill goal SKILL-231
```
