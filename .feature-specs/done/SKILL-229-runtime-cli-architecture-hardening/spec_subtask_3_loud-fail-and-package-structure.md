# SKILL-229 · Subtask 3: Loud-fail seams and package structure

## Scope

Make the module's one destructive command fail loudly, and break the cycle that
stops any command area from being built or tested alone.

**`uninstall` failure policy.** `uninstall` is the module's only destructive
command and its failure mode is a warning string on a zero exit. Six sites wrap
their mutation in `runCatching` and append to a `warnings: MutableList<String>`:
`removeLauncher`, `removeDesktop`, and `removeRecursively` in
`UninstallCommand.kt`, and `cleanupAgentInstallTargets`,
`cleanupNativeAgentInstallLinks`, and `cleanupMcpRegistrations` in
`UninstallCommandApply.kt`. Nothing raises the exit code and nothing emits a
`RuntimeDiagnostics` record, so a partial uninstall — launcher symlink removed,
state tree left behind — reports success. Give all six one policy: a typed error
or a recorded degradation with a non-zero exit code, per
`../../../docs/observability-policy.md`. State the policy in
`../../../runtime-kotlin/ARCHITECTURE.md`.

This is the feature's one intended behavior change. Everything else stays
observable-equivalent.

**Telemetry drain.** `CliCompletionTelemetryDrain` swallows deliberately and
documents why, which is right for a drain: it must not change the run's exit
code or reach stdout or stderr. But it swallows into nothing — the 5-second
timeout abandons the worker with no record that the outbox did not flush. Emit a
record through a bound `RuntimeDiagnostics` while keeping both constraints.

**Break the `core` hub cycle.** `skillbill.cli.core` is bidirectionally coupled
with fourteen of the sixteen sibling packages because it holds both the shared
kernel (`CliRunState`, `DocumentedCliCommand`, `CliOutput`) and the composition
root (`CliCommandGroups`, `CliUtilityCommandGroups`, `SkillBillCommand`,
`TopLevelCliCommands`). The heavy edges run inward — `goal → core` 19,
`scaffold → core` 19, `featuretask → core` 15, `install → core` 14 — against one
to five imports back out. `core` ↔ `model` (7/2) is the same cycle in miniature,
caused by `CliRuntimeContext` in `model` importing `ExternalCommandRunner` from
`core`.

Split the kernel from the composition root so the root no longer imports the
areas that import the kernel. `runtime-cli` reaches zero package cycles and
subtask 1's acyclicity baseline is empty.

**Name the spillover units.** Eleven `*Extras` files carry the metric-evasion
signature: `FeatureTaskRuntimeCliFormatting` at 467 lines across 3 files,
`FeatureTaskRuntimeCliCommands` at 450 across 2, `GoalCliStatusFormatting` at
344 across 3, `ScaffoldCliWizardHelpers` at 327 across 4,
`ScaffoldCliPayloadHelpers` at 277 across 2, `ScaffoldCommandRequestParse` at
261 across 2, `GoalCliCommands` at 261 across 2, and `GoalRunPresenter` at 208
across 2. Every one is under the ceiling as a logical unit, so the repo-wide
logical-type guard passes today. Rename or redistribute them by responsibility
while that is still cheap.

## Acceptance Criteria

1. A failed `uninstall` mutation is a typed error or a recorded degradation with
   a non-zero exit code, not a warning string on a zero exit. Launcher removal,
   desktop removal, recursive tree removal, agent-target cleanup, native-agent
   unlinking, and MCP unregistration share one policy.
2. The `uninstall` failure policy is stated in
   `../../../runtime-kotlin/ARCHITECTURE.md`, and every remaining fallback emits a
   record through a bound `RuntimeDiagnostics` per
   `../../../docs/observability-policy.md`.
3. An abandoned telemetry drain emits a record through a bound
   `RuntimeDiagnostics`. The drain still cannot change the run's exit code or
   write to stdout or stderr.
4. `skillbill.cli.core` is split so the composition root no longer imports the
   areas that import the kernel, and `CliRuntimeContext` no longer reaches into
   `core`. `runtime-cli` has zero package cycles and subtask 1's acyclicity
   baseline is empty.
5. A single command area compiles and tests without the whole command tree,
   which is the capability the hub cycle denies today.
6. No file in `runtime-cli` is named `*Extras`, `*Extras2`, or `*Extras3`, and
   the units are separated by responsibility rather than concatenated. The
   repo-wide logical-type ceiling baseline is still empty and the 500-line
   per-file ceiling is not relaxed.
7. Apart from `uninstall`'s exit code on a failed mutation, observable CLI
   behavior is unchanged: same command names, aliases, options, exit codes, help
   text, and payload keys.
8. `runtime-kotlin/gradlew check` passes and `skill-bill validate` passes with
   no new suppression or exemption.

## Non-Goals

- Re-opening the single-owner seam, the repository-root coordinate, or the
  process-input ports. Those are subtask 2.
- Changing the Clikt command surface, adding commands, or reorganizing the
  user-facing hierarchy. The split is internal to `skillbill.cli.*`.
- Making the telemetry drain synchronous, or letting it affect the exit code.
- Reworking the jlink runtime-image or install-staging wiring in
  `build.gradle.kts`.
- Relaxing the per-file ceiling to absorb re-merged `*Extras` files.

## Dependency Notes

Depends on subtasks 1 and 2. Criteria 4 and 6 are stated against baselines
subtask 1 records. The dependency on subtask 2 is ordering, not logic: this
subtask relocates `CliRunState` out of a split `core`, and subtask 2 rewrites
`CliRunState`'s shape and every seam feeding it, so interleaving them would
conflict across the same files.

This subtask carries the feature's only intended behavior change, which is why
it is separated from subtask 2's behavior-preserving work: one commit mixing a
refactor that must not change behavior with a deliberate exit-code change would
leave neither half judgable on its own.

## Validation Strategy

- The `uninstall` policy gets tests asserting the non-zero exit code and the
  recorded degradation on a failed launcher removal, a failed tree removal, and
  a failed MCP unregistration — the observable outcome, not the absence of a
  crash. Each is verified to fail against today's warning-string behavior.
- The drain record is asserted together with both constraints it must keep: no
  exit-code change and no stdout or stderr output.
- Cycle removal is proven by the empty acyclicity baseline plus the criterion-5
  single-area build, since a baseline can be emptied by moving an import while
  leaving the areas entangled.
- The `*Extras` rename is proven by the absent filenames plus the still-empty
  logical-type baseline, which together rule out satisfying criterion 6 by
  concatenating files up to the ceiling.
- `runtime-kotlin/gradlew check` in a clean checkout, plus `skill-bill validate`.

## Next Path

```bash
skill-bill goal SKILL-229
```
