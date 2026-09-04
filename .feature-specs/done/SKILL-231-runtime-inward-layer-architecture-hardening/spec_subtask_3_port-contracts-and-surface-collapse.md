# SKILL-231 · Subtask 3: Port contracts and surface collapse

## Scope

Make a port implementation mean something, then delete the ports that mean
nothing. `runtime-ports` declares 176 interfaces; the count is not the problem,
the contract is.

**Classify the null-object family and act on the classification.** Six
`Unavailable*` implementations in `runtime-ports` main source `error(...)` for
the whole contract they claim:
`UnavailableFeatureTaskRuntimeAuditGenerationRepository`,
`UnavailableUnaddressedFindingsRepository`,
`UnavailableReviewRunLaneCompletenessRepository`,
`UnavailableReviewRunStageCompletenessRepository`, `UnavailableSpecScratchStore`,
`UnconfiguredHttpRequester`. Twenty-two `Noop*`/`Empty*` objects in
`runtime-ports`, `runtime-domain`, and `runtime-application` main source do the
opposite: they satisfy the signature and discard the call. Fifty-two port files
carry 154 defaulted method bodies, which is the same pattern spread across the
interface rather than an object.

Every one of these is one of three things, and the classification is written
down:

- a **total refusal** that fails loudly and uniformly for every method,
- a **sanctioned null object** that emits a `RuntimeDiagnostics` record on every
  swallow, per `../../../docs/observability-policy.md`,
- or **deleted**, because no caller needs it.

`UnavailableUnaddressedFindingsRepository` is the proof the classification is
missing: seven of its eight methods `error(...)` and `issueExists` returns
`false` silently. That is not a design, it is a gap.

**Remove the silent production default.**
`../../../runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/db/DatabaseSessionFactory.kt:55`
declares `get() = EmptyGoalRunnerControlRepository`. Any session factory that
does not override `goalRunnerControls` discards goal control-state writes —
`persistControlState(...) = state`, `clearControlState(...) = Unit` — with no
diagnostic and no failure. This is the one intended behavior change in
SKILL-231: after this subtask, that path fails or records.

**Collapse the thin ports.** Thirty-seven interfaces have at most one production
implementation, at most one consuming type, and no test double. Nine of them
have a real application-level caller and are the highest-confidence set:
`ExternalAddonOverlayPort`, `ExternalAddonSourceConfigPort`,
`RepoSourceDiscoveryGateway`, `ScaffoldCatalogGateway`, `ScaffoldGateway`,
`UnsupportedScaffoldGateway`, `CheckedOutBranchSource`, `RepoValidationGateway`,
`GoalPlanningBoundaryBodyResolver`. A port that crosses a module boundary the DI
graph needs is not a finding; a port whose only justification is that it exists
is.

**Collapse the payoff-free composites.**
`DecompositionManifestFileStore` extends `…ReadStore`, `…DiscoveryStore`,
`…WriteStore`, and `…EncodeStore`; every consumer takes the composite, so three
of the four sub-interfaces have no direct consumer. `GoalRunnerControlRepository`
is the same shape over `GoalRunnerControlStateRepository`,
`GoalRunnerReviewPolicyRepository`, and
`GoalRunnerOutOfBandAcceptanceRepository`. `GoalRunnerPorts.kt` declares four
`…ManifestControlOps` / `LeaseOps` / `ReviewOps` / `WriteOps` ports whose sole
implementation and sole consumer both live inside `runtime-infra-sqlite`.
Segregation that no caller uses is not segregation.

**Delete the three pass-through services.**
`skillbill.application.scaffold.McpRegistrationService` (19 lines),
`NativeAgentInstallService` (22), and `RepoValidationService` (20) each forward
one port method and rename nothing. Their CLI callers resolve the port through
`RuntimeComponent` directly.

**Rename the eight technology-leaking ports.** `UninstallFileSystemGateway`,
`HttpRequester`, `HttpResponse`, and the five `DecompositionManifestFile*Store`
name their adapter, not the capability the inside needs. Rename the port; leave
the adapter name alone. The `*GitOperations` family is explicitly not in this
set — git is the capability the workflow needs, not an implementation choice —
and that exclusion is recorded.

**Close the sealed `else` branches.** Thirty-seven `when` expressions with a
trailing `else` have a `sealed` or `enum` subject: 26 in `runtime-application`,
10 in `runtime-domain`, 1 in `runtime-ports`
(`GoalSubtaskReviewSummarySanitize.kt:67`, a private `CompactFindingSeverity`
falling through to `OTHER`). Each becomes exhaustive or gets a line in
`../../../runtime-kotlin/agent/decisions.md` naming why the set is genuinely
open. The `../../../docs/code-principles.md` amendment for pack-authored open surfaces
applies where it applies; it is not a blanket excuse.

**Move the port-shaped interfaces that live in the wrong module.**
`skillbill.domain.skillremove.SkillRemoveFileSystem` and
`skillbill.workflow.idestatus.IdeStatusValidator` are declared in
`runtime-domain`, implemented in `runtime-infra-fs`, and consumed across module
boundaries. An interface declared in the module of its only implementation, or
in a module that is not `runtime-ports` while crossing a boundary, is inverted
the wrong way. Move it to `runtime-ports` or delete it. The role-port bindings
inside `runtime-application` (`FeatureTaskRuntimePhaseGate*Port`,
`GoalRunner*BoundariesPort`, `GoalPlanningSweep*Port`,
`ParallelCodeReviewRunner*Port`) are intra-module decomposition seams, not
boundary ports; they stay, and that stays recorded.

**Empty the `runtime-ports` and `runtime-domain` baselines.** The single
`runtime-ports` ambient-clock site is
`TelemetryReconciliationRequest.kt:11`'s `val now: Instant = Instant.now()` — a
port model that defaults to reading the clock. The `runtime-ports` cycle
baseline holds 6 pairs and `runtime-domain` holds 1 (`review`↔`workflow`); both
reach empty. The 3 `runtime-ports` spillover files
(`FeatureTaskRuntimePhaseArtifactDecodersContinued`,
`GoalContinuationArtifactCodecContinued`,
`GoalTerminalOutcomeDerivationContinued`) are renamed here rather than in
subtask 4, because they sit in the module this subtask is already opening.

**Fix `runtime-application`'s filesystem reach.** Twenty-four files perform
filesystem work outside any port — 38 `Path.of(` and 22 `.toRealPath()`
occurrences, concentrated in `IdeStatusService` (lines 183, 188, 203),
`GoalPreflightInputValidation:28`, `GoalRunnerSubtaskLaunchPrepare` (171, 173,
176), `GoalPlanningSweepOutcomeDerivation:21`, and
`RuntimeProvenanceService:75`. There are no `Files.*` calls, so no guard sees
them, but `.toRealPath()` is a filesystem probe. Route them through the
repository-root port subtask 2 introduces, or through an existing filesystem
port. `Path` as an inert value type in an application signature stays
sanctioned.

## Acceptance Criteria

1. Every `Unavailable*`, `Noop*`, and `Empty*` port object in `runtime-ports`,
   `runtime-domain`, and `runtime-application` main source is classified as a
   total refusal, a recording null object, or deleted. The classification is
   stated in `../../../runtime-kotlin/ARCHITECTURE.md` and enforced by a test
   that fails when a new unclassified one appears.
2. A total refusal refuses uniformly.
   `UnavailableUnaddressedFindingsRepository.issueExists` no longer returns
   `false` while its siblings throw.
3. A sanctioned null object emits a `RuntimeDiagnostics` record on every swallow.
   No production path discards a call silently.
4. `DatabaseSessionFactory.goalRunnerControls` no longer defaults to
   `EmptyGoalRunnerControlRepository`. A session factory without that binding
   fails loudly or records a degradation; a test drives that path and asserts
   the outcome, not the absence of a crash.
5. The nine thin ports with an application-level caller are collapsed into their
   consumer or deleted. The `runtime-ports` interface count falls from 176, and
   the subtask report states the new count and which interfaces went.
6. `DecompositionManifestFileStore` and `GoalRunnerControlRepository` are reduced
   to the interfaces some caller depends on. The four `GoalRunnerManifest*Ops`
   ports whose implementation and consumer both live in `runtime-infra-sqlite`
   are collapsed or moved out of `runtime-ports`.
7. `McpRegistrationService`, `NativeAgentInstallService`, and
   `RepoValidationService` are deleted; their callers reach the port through
   `RuntimeComponent`.
8. `UninstallFileSystemGateway`, `HttpRequester`, `HttpResponse`, and the five
   `DecompositionManifestFile*Store` are renamed to the capability. Adapter names
   are unchanged. The `*GitOperations` exclusion is recorded in
   `../../../runtime-kotlin/agent/decisions.md`.
9. All 37 sealed-subject `else` branches are exhaustive, or the exceptions are
   recorded in `../../../runtime-kotlin/agent/decisions.md` with the reason the
   set is open.
10. `SkillRemoveFileSystem` and `IdeStatusValidator` live in `runtime-ports` or
    are deleted. The intra-module role-port bindings in `runtime-application`
    stay and the reason is recorded.
11. The `runtime-ports` ambient-clock baseline, the `runtime-ports` cycle
    baseline, and the `runtime-domain` cycle baseline are empty. The three
    `runtime-ports` spillover files are renamed and that module's spillover
    baseline is empty.
12. `runtime-application` resolves the repository root and every `.toRealPath()`
    probe through a port. `Path` as an inert value type stays allowed.
13. Behavior is unchanged except where criterion 4 names it. No port collapse
    required a new test to stay correct; one that did was a behavior change and
    landed in its own commit.
14. `runtime-kotlin/gradlew check` and `skill-bill validate` pass with no new
    suppression, no new exemption, and no baseline entry recorded to make a test
    pass.

## Non-Goals

- Adding a port. The repository-root port belongs to subtask 2. No port is added
  here without a second consumer today.
- Renaming the `*GitOperations` family.
- Reworking `runtime-application`'s run-loop decomposition or its spillover
  filenames. Subtask 4 owns the 54 `Continued` files; this subtask touches
  `runtime-application` only for the sealed `else` branches, the filesystem
  reach, and the pass-through deletions.
- Breaking the `runtime-infra-fs` or `runtime-infra-sqlite` cycles.
- Retiring the `@OpenBoundaryMap` sites or shrinking the raw-map allow-list.
  Those are deliberate and inventoried.
- Splitting `FeatureTaskRuntimePhaseWorkflowApi` (52 public functions) or the
  13-parameter `DefaultParallelCodeReviewRunnerPlanningPort`. Both are inside
  `runtime-application` and SKILL-227's guards already pass on them; note them
  and skip.

## Dependency Notes

Depends on subtask 1 for the `runtime-ports` and `runtime-domain` baselines this
subtask empties.

Depends on subtask 2 for the repository-root port that criterion 12 routes
`runtime-application`'s `.toRealPath()` sites through. If subtask 2 has not
landed, criterion 12 waits rather than introducing a second root resolver.

Subtask 4 depends on this one: the port renames in criterion 8 and the
collapses in criteria 5 and 6 move type names that subtask 4's file renames
would otherwise have to redo.

## Validation Strategy

- Criterion 4 is the one behavior change and gets the sharpest test: a session
  factory with no `goalRunnerControls` binding, asserting the loud failure or the
  recorded degradation. The bug it catches is a goal run whose control state
  silently never persists.
- The null-object classification test is the guard that keeps criterion 1 true
  after this subtask. It fails on a new `Noop*` object that neither refuses
  totally nor records.
- Port collapse is proven by the falling interface count plus the existing
  suites staying green. A collapse that broke a test exposed a behavior
  difference and is reported rather than patched.
- The renames in criterion 8 are proven by compilation plus a search showing the
  old names gone from main source.
- Sealed exhaustiveness is proven by the compiler: removing the `else` either
  compiles or names the missing branch.
- The three emptied baselines are set-equality assertions, so a missed site
  fails the build.
- `runtime-kotlin/gradlew check` in a clean checkout; `skill-bill validate` for
  the governed-artifact surface.

## Next Path

```bash
skill-bill goal SKILL-231
```
