# SKILL-52.4 · Subtask 4 — God-object decomposition (Phase 3)

Parent overview: [spec.md](./spec.md)

The largest subtask: behavior-preserving decomposition of three god objects
(F10 `SkillBillViewModel` 1885 LOC, F11 `SkillBillFrame` 2713 LOC, F12
`RuntimeRepoBrowserService` 844 LOC) plus the `RuntimeContext` god-object split
(F14). Golden/wire outputs must stay byte-identical.

Branch: `feat/SKILL-52.4-hexagon-leak-closure-followups` (same-branch model, one commit for this subtask).

> Note: this subtask is large. If a single feature-task run cannot complete it
> reliably, it is a candidate for a future further split (F10+F14 ports/state vs.
> F11 UI vs. F12 data-service+presenter). Keep it one subtask in this
> decomposition; pin golden tests before any change and diff after every commit.

## Dependencies

- depends_on: [1, 3]
- dependency_reason: Requires Phase 0 enforcement (golden-output drift is the top
  risk; the per-module dependency test and raw-map scanner must catch stray
  edges/map-order changes introduced by the refactor) AND Phase 2 desktop leak
  closure, because the ViewModel/Route/service reshaping builds on the
  now-suspend `RecentRepoRepository`, injected `DispatcherProvider`, and
  common-safe `InstalledWorkspaceBaselineService`. F14 (RuntimeContext) depends
  only on Subtask 1 but rides here since the desktop consumers reshaped here are
  among RuntimeContext's consumers.
- dependencies: [{subtask_id: 1, optional: false, skipped: false},
  {subtask_id: 3, optional: false, skipped: false}]

## Scope (owns)

- **F10 — `SkillBillViewModel` (1885 LOC, 30 state fields).** Split into cohesive
  sub-state holders (editor, tree/navigation, scaffold-wizard, first-run,
  removal, validation, command palette) coordinated by a thin shell. Keep the
  public surface the UI consumes stable. Each new unit < ~400 LOC.
- **F11 — `SkillBillFrame` (2713 LOC).** Break into per-surface Composable files
  under a `ui/` subpackage (frame, browser, editor, wizard, palette, first-run,
  confirm, validation console); the frame keeps only layout/slot wiring. Each new
  file < ~400 LOC.
- **F12 — `RuntimeRepoBrowserService` (844 LOC, 3 interfaces).** Split along its
  three implemented interfaces (`RepoSessionService`, `SkillTreeService`,
  `AuthoringGateway`,
  `runtime-desktop/core/data/src/jvmMain/.../service/RuntimeRepoBrowserService.kt:34-40`);
  lift the embedded tree-building / display-label presentation into a presenter
  owned by the feature/state layer (e.g. `SkillTreePresenter`), leaving the data
  services as pure translation. Each new unit < ~400 LOC.
- **F14 — `RuntimeContext` (8 fields).**
  `runtime-ports/.../model/RuntimeContext.kt`: fields `dbPathOverride`,
  `stdinText`, `environment`, `userHome`, `requester`, `workflowGitOperations`,
  `agentRunLauncher`, `goalPullRequestPort`. Extract intent-named sub-contexts —
  `EnvironmentContext` (dbPathOverride/stdinText/environment/userHome),
  `TransportContext` (requester), `WorkflowOpsContext` (workflowGitOperations),
  `OptionalCallbacks` (agentRunLauncher/goalPullRequestPort). Keep ONE composed
  `RuntimeContext` at the composition root; each port takes only the
  sub-context it uses (resolves open question 4: one composed type retained at
  the root to minimize churn at the ~dozen consumer sites).

## SKILL-44 invariants — MUST survive the decomposition

- begin/run/finish telemetry triplet
- capture-mutable-VM-state-into-immutable-request-before-dispatcher-hop
- `activeOperationToken` stale-finish handling
- busy-slot bookkeeping
- `semantics(mergeDescendants)` co-located with `.clickable`

## Reusable patterns / pitfalls

- **Pin golden tests BEFORE refactoring and diff after EVERY commit** — god-object
  decomposition and the RuntimeContext split touch many call sites; a stray
  reordering can change emitted map order.
- Existing desktop tests must pass unchanged (or be mechanically updated for new
  types only — no behavior assertions changed).
- SKILL-79 `RuntimeDesktopGatewayPolicyTest` whitelists desktop mappers — update
  in lockstep.
- `ImplementationOwnershipArchitectureTest.allowedCompositionImports` may trip on
  new sub-context `@Provides` imports — extend the allow-list, never suppress.
- No explanatory comments.

## Acceptance Criteria

1. AC11: `SkillBillViewModel`, `SkillBillFrame`, `RuntimeRepoBrowserService`
   decomposed into cohesive units each < ~400 LOC; existing desktop tests pass
   unchanged (or mechanically updated for new types only); SKILL-44 invariants
   (triplet / capture-before-hop / activeOperationToken / busy-slot /
   semantics+clickable) preserved.
2. AC12: `RuntimeContext` consumers no longer all depend on all 8 fields; ONE
   composed RuntimeContext at the composition root with intent-named sub-contexts
   (EnvironmentContext, TransportContext, WorkflowOpsContext, OptionalCallbacks);
   ports take only the sub-context they use.
3. AC14 (this subtask's slice): all four canonical gates pass.
4. AC15 (this subtask's slice): golden/wire outputs (CLI JSON, MCP payloads,
   install-plan, workflow snapshots) byte-identical to pre-change — pin before,
   diff after.

## Non-goals

- No behavioral change of any kind; this is pure structure.
- Not splitting `GoalRunner`/`GoalRunnerWorkflowStores`/`FeatureTaskRuntimeRunner`
  (F13 — not required by this spec).
- No Tier-4 records (Subtask 5).

## Validation strategy

`bill-code-check` (Kotlin/Gradle → `./gradlew check`). Run all four canonical
gates. Snapshot CLI/MCP/install/workflow golden outputs before Phase 3 and
assert byte-equality after. Exercise the desktop app via `run`/`verify`.

## Handoff prompt

Run bill-feature-task on
`.feature-specs/SKILL-52.4-hexagon-leak-closure-followups/spec_subtask_4_god-object-decomposition.md`.
