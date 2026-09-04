# SKILL-231 · Subtask 1: Guardrails and recorded baselines

## Scope

Put the measurement in place across all ten modules before any structural
change, so subtasks 2 through 4 are enforced rather than trusted.

Five scanners in
`../../../runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture`
are instantiated for `runtime-application` and `runtime-cli` only. Widen them;
do not fork them.

**Extend the parameterized scanners over every module in
`../../../runtime-kotlin/settings.gradle.kts`.**

- *Package acyclicity.* `ApplicationPackageAcyclicityArchitectureTest` already
  takes scan root and package prefix. Add a case per module over its own
  package root (`skillbill.ports.`, `skillbill.` for `runtime-domain` and the
  two infra modules that declare several roots, `skillbill.mcp.`,
  `skillbill.di.`). The `runtime-application` and `runtime-cli` baselines stay
  empty.
- *Ambient clock.* `RuntimeApplicationAmbientClockArchitectureTest` already
  takes the scan root. Add a case per module.
- *Ambient environment.* `AmbientEnvironmentArchitectureTest` is scoped to
  `RUNTIME_CLI_SRC`. Take the scan root as a parameter and add a case per
  module.
- *`@Inject` defaults.* `InjectConstructorDefaultsArchitectureTest` takes
  `scanRoot`. Add a case per module that carries `@Inject` in main source:
  `runtime-ports`, `runtime-infra-fs`, `runtime-infra-http`,
  `runtime-infra-sqlite`, `runtime-mcp`.
- *Spillover filenames.* `RuntimeCliSpilloverFileNameArchitectureTest` scans
  `runtime-cli/src` and matches only `Extras`, `Extras2`, `Extras3`. Both limits
  are wrong. Scan every module, and widen the signature to cover `Continued`,
  `Continued<N>`, `Helpers<N>`, `Fns<N>`, `Support<N>`, a single letter followed
  by a digit (`A1`, `B7`), and a bare trailing digit on a name whose sibling
  without the digit or with a different digit exists in the same package. Rename
  the test to drop the `Cli` prefix.

**Add the module-edge pin.** `RuntimeGradleModuleLayeringTest` bans upward
dependencies and nothing else; `RuntimeCoreCompositionOnlyTest` pins one
module's edge sets. Generalize the latter so every module in
`settings.gradle.kts` has its `api(project(...))` and
`implementation(project(...))` sets pinned. Record today's edges as the
expectation, including the three `runtime-infra-*` modules'
`api(":runtime-ports")` and `api(":runtime-domain")`. Do not narrow them here —
subtask 4 resolves them, and this subtask stays pure measurement.

**Record the baselines.** Each new case ships green against today's state. The
figures below are the census that motivated the work; the recorder produces the
authoritative content. Regenerate with `RECORD_ARCHITECTURE_BASELINES=1` and
reconcile any divergence from this table in the subtask report rather than
editing a baseline by hand.

| baseline | expected entries |
| --- | --- |
| `runtime-infra-fs` package cycles | 9 pairs: `agentaddon`↔`install`, `infrastructure`↔`install`, `infrastructure`↔`launcher`, `infrastructure`↔`nativeagent`, `infrastructure`↔`scaffold`, `install`↔`launcher`, `install`↔`nativeagent`, `install`↔`scaffold`, `nativeagent`↔`scaffold` |
| `runtime-ports` package cycles | 6 pairs: `agentrun`↔`review`, `db`↔`goalrunner`, `db`↔`workflow`, `featuretask`↔`taskruntime`, `featuretask`↔`workflow`, `goalrunner`↔`workflow` |
| `runtime-mcp` package cycles | 4 pairs: `core`↔`featuretask`, `core`↔`lifecycle`, `core`↔`scaffold`, `core`↔`workflow` |
| `runtime-domain` package cycles | 1 pair: `review`↔`workflow` |
| `runtime-infra-sqlite` package cycles | 1 pair: `db`↔`infrastructure` |
| ambient clock | 13 sites: `runtime-ports` 1 (`TelemetryReconciliationRequest.kt:11`), `runtime-infra-fs` 6, `runtime-infra-sqlite` 3, `runtime-infra-http` 1, `runtime-core` 1 (`RuntimeComponentBindingsA6.kt:23`), `runtime-mcp` 1 (`McpScaffoldRuntime.kt:56`) |
| ambient environment | ~127 sites: `runtime-infra-fs` 83 `getenv`/`getProperty` + 19 `Path.of("")`, `runtime-infra-sqlite` 12, `runtime-mcp` 4 + 2, `runtime-infra-http` 4, `runtime-core` 2 + 1 |
| `@Inject` defaults | `McpRuntimeContext`'s 5 default-valued fields plus whatever the recorder finds in `runtime-ports` and the infra modules |
| spillover filenames | 112 files: `runtime-application` 54, `runtime-infra-fs` 27, `runtime-core` 27, `runtime-ports` 3, `runtime-infra-sqlite` 1 |

Every one of these baselines is deliberately non-empty. Recording them keeps
this subtask pure measurement and gives subtasks 2 through 4 something to empty.

**State the two scoping decisions in
`../../../runtime-kotlin/agent/decisions.md` now, before the baselines make them
look like oversights.**

- `runtime-infra-*` ambient-environment baselines are shrink-only and are not
  expected to reach zero. Reading the host environment is what a filesystem or
  process adapter does; the boundary is that a policy decision may not depend on
  an ambient read.
- `runtime-core` composition reads ambient input at exactly one seam
  (`RuntimeComponentBindingsA1.runtimeContext`). That is where ambient input
  should enter the process. It becomes a named exemption with a documented
  reason rather than a permanent baseline entry.

Register every widened and new guard in `PrincipleEnforcementInventory` and
document it in `../../../runtime-kotlin/ARCHITECTURE.md`.

## Acceptance Criteria

1. The package-acyclicity scanner has a case for every module in
   `../../../runtime-kotlin/settings.gradle.kts`, each with a recorded baseline, and the
   `runtime-application` and `runtime-cli` baselines are still empty.
2. The ambient-clock scanner has a case for every module, each with a recorded
   baseline, and the two existing baselines are still empty.
3. The ambient-environment scanner takes its scan root as a parameter, has a
   case for every module, and the `runtime-cli` baseline is still empty.
4. The `@Inject`-defaults guard has a case for `runtime-ports`,
   `runtime-infra-fs`, `runtime-infra-http`, `runtime-infra-sqlite`, and
   `runtime-mcp`, each with a recorded baseline, and the two existing baselines
   are still empty.
5. The spillover-filename guard scans all ten modules, its signature matches
   `Continued`, `Continued<N>`, `Helpers<N>`, `Fns<N>`, `Support<N>`, letter-plus-digit,
   and bare trailing digits alongside the existing `Extras` forms, and its
   baseline records the 112 current files. A synthetic fixture proves the
   widened signature catches `FooContinued2.kt` and `RuntimeComponentBindingsA1.kt`
   and does not catch a legitimately numbered domain name.
6. Every module's `api(project(...))` and `implementation(project(...))` edge
   sets are pinned by a test, with today's edges recorded as the expectation.
   The test fails when an edge is added, removed, or changes configuration.
7. Each widened or new guard ships an acceptance case and a rejection case in
   the synthetic-fixture style the existing guards use. Every rejection case is
   demonstrated to fail before the guard is in place, not merely asserted.
8. No scanner is duplicated. A second copy of an existing scanner scoped to a
   different module does not satisfy criteria 1 through 5.
9. The two scoping decisions — shrink-only infra ambient-environment baselines
   and the single `runtime-core` composition seam — are recorded in
   `../../../runtime-kotlin/agent/decisions.md`.
10. Every new guard is registered in `PrincipleEnforcementInventory` and
    documented in `../../../runtime-kotlin/ARCHITECTURE.md`.
11. `runtime-kotlin/gradlew check` passes and `skill-bill validate` passes with
    no new suppression, no new line-ceiling exemption, and no hand-edited
    baseline.

## Non-Goals

- Fixing any site a baseline records. Subtasks 2 through 4 empty them.
- Narrowing the three `runtime-infra-*` `api` edges. Subtask 4 owns that; this
  subtask records them as they are.
- Touching `runtime-application`'s or `runtime-cli`'s interior, or their eight
  empty baselines.
- Adding an area-isolation guard for a module other than `runtime-cli`.
  Transitive-closure isolation is a stronger claim than acyclicity and no other
  module is close enough to it for the guard to land green.
- Changing the 500-line ceiling, detekt thresholds, or the Spotless ratchet.

## Dependency Notes

No dependencies. This subtask must land first: subtasks 2, 3, and 4 are each
specified as emptying baselines recorded here, and that cannot be expressed
before the baselines exist. The widened spillover signature in particular has no
predecessor — without it, the 112 renames in subtask 4 would be unverifiable.

## Validation Strategy

- Each guard's rejection case is verified by removing the guard and observing
  the case fail, then restoring it. A test that passes both with and without the
  change under test pins nothing.
- Each recorded baseline is checked against a fresh census of its module, so a
  miscounted baseline cannot silently absorb a real violation. Divergence from
  the table above is reported, not reconciled by editing the baseline.
- The module-edge pin is verified by adding a throwaway `api(project(...))` edge
  to one module and observing the test fail.
- `runtime-kotlin/gradlew check` covers detekt and the Spotless ratchet as well
  as the test suites; run it in a clean checkout, since the ratchet needs a real
  `.git` directory and does not work in a linked worktree.
- `skill-bill validate` gates the governed-artifact surface.

## Next Path

```bash
skill-bill goal SKILL-231
```
