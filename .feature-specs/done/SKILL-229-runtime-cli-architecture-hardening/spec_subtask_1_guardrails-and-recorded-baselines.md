# SKILL-229 · Subtask 1: Guardrails and recorded baselines

## Scope

Put the measurement in place before any structural change, so the later
subtasks are enforced rather than trusted.

Three of the four guards on `main` are scoped to `runtime-application`. Widen
them rather than copying them, and add the one guard `runtime-cli` needs that
`runtime-application` never did.

**Parameterize the shared scanners** in
`../../../runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture`:

- *Package acyclicity.* `ArchitectureScanSupport.applicationPackageCycleViolations`
  hard-codes `../../../runtime-kotlin/runtime-application/src/main/kotlin` and the
  `skillbill.application.` prefix. Take scan root and package prefix as
  parameters and add a `runtime-cli` case over `skillbill.cli.*`. The
  `runtime-application` baseline stays empty.
- *Ambient clock.* `ArchitectureScanGuardSupport.ambientClockViolations`
  hard-codes the same root. Take the scan root as a parameter, add a
  `runtime-cli` case, and extend the banned set to `LocalDate.now()` alongside
  `Instant.now()`, `LocalDateTime.now()`, and `Clock.systemUTC()`.
- *`@Inject` defaults.* `injectConstructorDefaultViolations` already takes
  `scanRoot` with a `runtime-application` default. Add a `runtime-cli` case.

**Add an ambient-environment guard.** No existing guard covers process-wide
inputs other than time. Ban `System.getenv`, `System.getProperty`,
`Path.of("")`, and `Paths.get("")` in `runtime-cli` main source.

**Record the baselines.** Each new case ships green against today's state:

| baseline | entries |
| --- | --- |
| `runtime-cli` package cycles | 15 pairs: `core` ↔ each of `model`, `goal`, `featuretask`, `workflow`, `scaffold`, `system`, `telemetry`, `review`, `codereview`, `learning`, `skillremove`, `config`, `work`, `repovalidation`, `agentaddon` |
| `runtime-cli` ambient clock | 1: `ScaffoldCliPayloadHelpersExtras.kt:68` |
| `runtime-cli` ambient environment | 22: 14 `Path.of("")` plus 8 `System.getenv`/`getProperty` |
| `runtime-cli` `@Inject` defaults | 8: `CliRunState`'s default-valued fields |

The `@Inject` baseline is deliberately non-empty here. `CliRunState`'s defaults
cannot be removed without the single-owner seam, which is subtask 2; recording
them keeps this subtask pure measurement and gives subtask 2 something to empty.

The logical-type ceiling guard is already repo-wide and already passes on
`runtime-cli`, whose largest logical unit is `FeatureTaskRuntimeCliFormatting`
at 467 lines. Confirm it stays empty; add no `runtime-cli` case.

Register every new guard in `PrincipleEnforcementInventory` and document it in
`../../../runtime-kotlin/ARCHITECTURE.md`.

## Acceptance Criteria

1. The package-acyclicity scanner takes scan root and package prefix as
   parameters, a `runtime-cli` case covers `skillbill.cli.*`, and the
   `runtime-application` baseline is still empty.
2. The ambient-clock scanner takes the scan root as a parameter, a `runtime-cli`
   case exists, `LocalDate.now()` is banned alongside the three existing forms,
   and the `runtime-application` baseline is still empty.
3. The `@Inject`-defaults guard runs a `runtime-cli` case through its existing
   `scanRoot` parameter, with a recorded baseline of `CliRunState`'s eight
   default-valued fields.
4. A new ambient-environment guard bans `System.getenv`, `System.getProperty`,
   `Path.of("")`, and `Paths.get("")` in `runtime-cli` main source, with a
   recorded baseline of today's twenty-two sites.
5. Each new or widened guard ships an acceptance case and a rejection case, in
   the synthetic-fixture style the existing guards use. Every rejection case is
   demonstrated to fail before the guard is in place, not merely asserted.
6. No scanner is duplicated. A second copy of an existing scanner scoped to a
   different module does not satisfy criteria 1 through 3.
7. Every new guard is registered in `PrincipleEnforcementInventory` and
   documented in `../../../runtime-kotlin/ARCHITECTURE.md`.
8. `runtime-kotlin/gradlew check` passes and `skill-bill validate` passes with
   no new suppression or exemption.

## Non-Goals

- Fixing any site a baseline records. Subtasks 2 and 3 empty them.
- Touching `runtime-application`'s interior or its four empty baselines.
- Adding a `runtime-cli` case to the logical-type ceiling guard, which is
  already repo-wide and already green.
- Changing the 500-line per-file ceiling or the `LongParameterList`,
  `ReturnCount`, or Spotless configuration.

## Dependency Notes

No dependencies. This subtask must land first: subtasks 2 and 3 are specified as
emptying the baselines recorded here, and that cannot be expressed before the
baselines exist. The ambient-environment guard in particular has no predecessor,
so without it the repository-root and process-input fixes in subtask 2 would be
unverifiable.

## Validation Strategy

- Each guard's rejection case is verified by removing the guard and observing
  the case fail, then restoring it. A test that passes both with and without the
  change under test pins nothing.
- The recorded baselines are checked against a fresh census of `runtime-cli`, so
  a miscounted baseline cannot silently absorb a real violation.
- `runtime-kotlin/gradlew check` covers detekt and the Spotless ratchet as well
  as the test suites; run it in a clean checkout, since the ratchet needs a real
  `.git` directory and does not work in a linked worktree.
- `skill-bill validate` gates the governed-artifact surface.

## Next Path

```bash
skill-bill goal SKILL-229
```
