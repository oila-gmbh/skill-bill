# SKILL-229 · Subtask 2: Single-owner run inputs

## Scope

Give every run-scoped setting exactly one owner, and stop `runtime-cli` from
re-deriving inputs the layers beneath it already receive injected.

**Collapse the duplication.** Six settings exist twice today.
`CliRuntimeContext` carries `dbPathOverride`, `environment`, `userHome`,
`externalCommandRunner`, `liveStdout`, and `liveStderr`, becomes a
`RuntimeContext`, and is captured into `RuntimeComponent` *before* argument
parsing. `CliRunState` carries the same six as mutable `var` fields that Clikt
writes during `SkillBillCommand.run()`. `CliRuntime.run` copies five of the six
and never copies `dbPathOverride` at all.

Resolve precedence between the embedding context and a parsed flag once, at one
seam, before `RuntimeComponent` is created, so no consumer has to reconcile.
`SQLiteDatabaseSessionFactory`'s `dbOverride ?: resolvedContext.dbPathOverride`
and `InstallCliCommands.kt:176`'s `state.dbOverride ?: runtimeContext.dbPathOverride`
both become reads of a settled value.

**Remove `CliRunState`'s JVM-seeded defaults.** Its eight default-valued fields
include `environment = System.getenv()` and
`userHome = Path.of(System.getProperty("user.home"))`, so an
`@Inject`-constructed instance is seeded from the JVM before anything overrides
it. Whatever per-run mutable state survives lives in an explicit run-scoped
object built from resolved inputs. This empties subtask 1's `@Inject`-defaults
baseline.

**Use the repository root that already exists.** SKILL-227 added
`repositoryRoot` to `CliRuntimeContext`, resolved once through
`canonicalRepositoryRoot`, and threaded it into `RuntimeContext` — so
`runtime-application` and the `runtime-infra-*` modules receive an injected
root. `CliRunState` carries no `repositoryRoot`, so commands have nothing
injected to read and fall back to the process working directory at fourteen
`Path.of("")` sites: ten in `goal/` (seven in `GoalCliControlCommands.kt`), two
in `featuretask/`, one in `scaffold/` as a default argument on `findRepoRoot`,
and one in `CliRuntimeContext` as the resolution seed. Route the existing
coordinate to the commands; do not add a second derivation.

**Move the remaining process inputs behind injected surfaces.**
`UninstallCommand.kt:278` branches on `System.getProperty("os.name")`;
`GoalCliCommands.kt:177-179` reads `java.class.path` and `path.separator`;
`FeatureTaskRuntimeCliCommandsExtras.kt:105` reads
`SKILL_BILL_QUALITY_GATE_SELECTION` from `System.getenv` rather than the
injected `environment` map that exists for exactly this; and
`ScaffoldCliPayloadHelpersExtras.kt:68` calls `LocalDate.now()`, which an
injected `Clock` replaces. Ports added here belong in `runtime-ports` with
adapters in the matching `runtime-infra-*` module.

## Acceptance Criteria

1. `CliRuntimeContext` and `CliRunState` no longer both carry `dbPathOverride`,
   `environment`, `userHome`, `externalCommandRunner`, `liveStdout`, or
   `liveStderr`; precedence between the embedding context and a parsed flag is
   resolved once, at one seam, before `RuntimeComponent` is created.
2. `dbPathOverride` supplied only through `CliRuntimeContext` reaches every
   consumer that reads the CLI-side value today, closing the one field
   `CliRuntime.run` never copied.
3. `CliRunState` holds no mutable field seeded from the JVM; surviving per-run
   mutable state lives in an explicit run-scoped object built from resolved
   inputs. Subtask 1's `@Inject`-defaults baseline for `runtime-cli` is empty.
4. Commands resolve the repository root from the coordinate SKILL-227 already
   resolves. `Path.of("")` no longer appears in `runtime-cli`, `findRepoRoot`
   no longer takes it as a default argument, and a command run from a
   subdirectory resolves the same root as the runtime beneath it.
5. Host platform, JVM classpath, path separator, and
   `SKILL_BILL_QUALITY_GATE_SELECTION` reach their call sites through ports or
   the injected `environment` map. Neither `UninstallCommand` nor
   `GoalCliCommands` calls `System.getProperty` directly.
6. Time reaches `ScaffoldCliPayloadHelpersExtras` through an injected `Clock`.
   Subtask 1's ambient-clock baseline for `runtime-cli` is empty.
7. Subtask 1's ambient-environment baseline for `runtime-cli` is empty.
8. `--home` reaches adapter-resolved paths, not only the `state`-threaded ones.
   A test drives a command whose home resolution goes through
   `EnvironmentContext.userHome` under `--home` and asserts the flag wins;
   `RemoveCliCommandTest`'s existing precedence assertion still passes
   unchanged.
9. Observable CLI behavior is unchanged: same command names, aliases, options,
   exit codes, help text, and payload keys.
10. `runtime-kotlin/gradlew check` passes and `skill-bill validate` passes with
    no new suppression or exemption.

## Non-Goals

- Changing the Clikt command surface, adding commands, or reorganizing the
  user-facing hierarchy.
- The `uninstall` failure policy, the telemetry-drain record, the
  `skillbill.cli.core` split, and the `*Extras` renames. Those are subtask 3.
- Migrating `runtime-mcp` or `intellij-plugin`, which embed `CliRuntime`. They
  change only where criterion 1's seam alters the call they already make.
- Adding a second repository-root derivation, or widening
  `RuntimeAdapterDependencyAllowlistTest`'s pinned allow-list.

## Dependency Notes

Depends on subtask 1. Criteria 3, 6, and 7 are stated as emptying baselines that
subtask 1 records, so they cannot be verified before those guards exist. The
ambient-environment guard is the only mechanical check that every one of the
twenty-two process-input sites is gone.

Must land before subtask 3: subtask 3 splits `skillbill.cli.core`, which moves
`CliRunState`, and this subtask rewrites `CliRunState`'s shape and every seam
that feeds it. Interleaved, the two would conflict line-for-line across the same
files.

## Validation Strategy

- The split-ownership fix is proven by the criterion-8 test: a command resolving
  home through `EnvironmentContext.userHome`, run under `--home`. That is the
  divergence no current test covers — all sixteen existing `--home` tests
  exercise install, uninstall, and remove, which thread `state.userHome`.
  `RemoveCliCommandTest`'s deliberate `CliRuntimeContext(userHome = contextHome)`
  versus `--home selectedHome` assertion is the regression guard in the other
  direction.
- Repository-root injection is proven by running a `goal` and a `scaffold`
  command from a working directory that is not the repository root.
- The three emptied baselines are the mechanical proof for criteria 3, 6, and 7;
  a baseline that shrinks but is not empty fails.
- Observable equivalence for criterion 9 rests on the existing `runtime-cli`
  suite passing unchanged, since it asserts payload keys and exit codes.
- `runtime-kotlin/gradlew check` in a clean checkout, plus `skill-bill validate`.

## Next Path

```bash
skill-bill goal SKILL-229
```
