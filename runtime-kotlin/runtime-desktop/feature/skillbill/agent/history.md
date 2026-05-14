# SkillBill desktop feature — history

## [2026-05-14] SKILL-44 inspector-artifact-reveal (subtask 10)
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/data, runtime-desktop/core/domain, runtime-desktop/core/testing
- Inspector Generated artifacts rows now reveal the exact generated tree item through `SkillTreeService.resolveGeneratedArtifactTreeItemId`, not `ValidationGateway.resolveTreeItemIdForSource`; keep these resolvers separate because validation intentionally maps `SKILL.md` siblings back to authored `content.md`.
- Artifact activation routes through `SkillBillRoute.runGeneratedArtifactSelection -> runTreeItemSelection`, preserving dirty-editor prompts, ancestor expansion, read-only editor loading, source-route fan-out, and history path filtering.
- Reusable UI pattern: rows compute resolvability from the in-memory tree snapshot before enabling `.clickable`; stale/deleted artifacts render disabled/demoted, use `Modifier.iconButtonSemantics("Open artifact: <path>")`, and support Enter/NumPadEnter/Space.
- Generated artifact `SelectionDetail` now carries `contentFile` so selecting a generated wrapper opens the generated file content in the read-only editor.
- Known limitation: route-level artifact clicks are covered through extracted pure helpers and VM tests, not Compose UI automation.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-13] SKILL-44 dead-affordances (subtask 09)
Areas: runtime-desktop/feature/skillbill
- Closes prior-subtask review debt F-102 (toolbar `NEW...` only opened HORIZONTAL_SKILL) and F-107 (`Role.Button` on non-interactive surfaces): every visible button-affordance element either has a real handler or drops `.clickable` + `Role.Button`.
- `ToolbarButton` API change: default `onClick = {}` is removed — every call site MUST pass an explicit handler. Added optional `contentDescription: String = label` so accessible name can diverge from visual label (used by `NewScaffoldMenuButton`). Mandatory for future toolbar additions in this boundary.
- Reusable composables: `ToolbarStatusItem` (toolbar-shaped) and `RepositoryStatusItem` (sidebar-shaped) render non-interactive labeled status — NO `.clickable`, NO `Role.Button`, `semantics(mergeDescendants = true) { contentDescription = ... }` so screen readers announce the row once. Use these instead of demoting `ToolbarButton`/`RepositoryAction` to an empty-lambda hack.
- Semantics rule (reusable, applies to every existing and future clickable composable here): apply `Modifier.semantics(mergeDescendants = true) { contentDescription = label; if (!enabled) disabled() }` to the SAME node as `.clickable(...)`. A standalone `.semantics{}` chained BEFORE `.clickable` lands on the outer parent and `disabled()` never reaches the click target. `ToolbarButton` and `RepositoryAction` were both fixed.
- `NewScaffoldMenuButton` now uses Material3 `DropdownMenu` + `DropdownMenuItem` (not bare `Popup`) so keyboard nav / focus / Escape-to-close / Enter-to-select are framework-provided. Future menu surfaces should follow the same pattern unless there is concrete reason to manage focus by hand. Parent button announces disclosure via `stateDescription = "Expanded"|"Collapsed"`.
- Repo-scoped gate plumbing: `canActivateRepoScopedAction = state.busyOperation == null && !publishingBusy` mirrors `SkillBillRoute.canStartRepoScopedAction()` and is now threaded through `NavigationPane` so the Validation row renders `disabled()` whenever the route would silently drop the call. Pattern for future repo-scoped sidebar rows: derive the boolean in `SkillBillFrame` from `state`, pass as a prop, AND with the row's local enabled flag.
- Helper `internal fun activateValidationDockAndMaybeRun(...)` in `SkillBillFrame.kt` is the test seam for click logic when the module's convention forbids Compose UI tests — extract similar pure helpers for any future hoisted-state click logic in this module.
- Limitations / deferred polish (do not relitigate next subtask): Validation-row badge count is not in `contentDescription` so screen readers don't hear pending-issue count (F-X-901-K); hardcoded `IntOffset(0, 32)` for popup anchoring not derived from `SkillBillMetrics` (F-X-901-L nit); module-wide hardcoded English strings (F-X-901-E) not introduced by this feature but called out for future localization sweep.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-13] SKILL-44 scaffold-wizards (subtask 08)
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/data, runtime-desktop/core/domain, runtime-desktop/core/testing, runtime-core/scaffold
- Added a single `ScaffoldWizardDialog` driven by `SkillBillState.scaffoldWizard` (one source of truth in commonMain). Kind picker exposes only the five governed kinds — HORIZONTAL_SKILL, PLATFORM_PACK, PLATFORM_OVERRIDE, CODE_REVIEW_AREA, ADD_ON; no governed source is ever hand-written from the UI — every write routes through `skillbill.scaffold.scaffold(payload, dryRun)`.
- New sibling `RuntimeScaffoldGateway` (NOT bolted onto RuntimeRepoBrowserService — the 7th-gateway rule from subtask 04 holds). Sealed `ScaffoldRunResult.Preview/Success/Failed(exceptionName, exceptionMessage, rollbackComplete)` carries the runtime contract across suspend boundaries; the discriminator is the input `dryRun` flag, NEVER a substring of `notes`. Future suspend gateways must follow this typed-result pattern instead of throwing across coroutines.
- Gateway catch posture (reusable template): re-throw `CancellationException` first, catch `SkillBillRuntimeException` and map `rollbackComplete = ex !is ScaffoldRollbackError`, catch defensive `Exception` and force `rollbackComplete = false` (we cannot prove rollback ran), let `Error` propagate. `@Suppress("TooGenericExceptionCaught")` is scoped to the single function, not file-wide.
- Begin/run/finish triplet pattern extended with a third instance: `beginOpenScaffoldWizard / runOpenScaffoldWizard / finishOpenScaffoldWizard`. Filesystem discovery for piloted platform packs (`ScaffoldCatalog.discoverPilotedPlatformPacks`) hops to `Dispatchers.Default` from `SkillBillRoute`. Mandatory rule: a `suspend` VM method MUST capture VM mutable state (e.g. `currentSession`) into an immutable request on Main BEFORE the dispatcher hop — never read VM `var`s from a worker dispatcher.
- Success-path fan-out (AC6/AC7): `beginRefreshAfterScaffold → loadRepo (Dispatchers.Default) → finishRefreshAfterScaffold → resolveAuthoredTreeItemForScaffold → selectTreeItem → onSourceRouteSelected → dismissScaffoldWizard → runGitRefresh → loadHistory`. `refreshAfterScaffold` deliberately bypasses the dirty-editor gate (the scaffold has already mutated the repo). The fan-out is gated on the post-finish `state.scaffoldWizard?.executionResult is Success`, NOT the local result var — so a mid-flight user dismiss is honored and not silently overridden. `resolveAuthoredTreeItemForScaffold` filters out `TreeItemKind.GENERATED_ARTIFACT` and strips `SKILL.md` from `createdFiles`, locking AC7.
- Busy-slot bookkeeping (F-401 invariant): `dismissScaffoldWizard` and the stale-token branches of `finishScaffoldDryRun`/`finishScaffoldExecute` MUST release `busyOperation = SCAFFOLD` if they still own it; otherwise a mid-flight dismiss leaves every repo-scoped action permanently disabled. The same release rule applies on every future stale-token branch that owns a busy slot.
- Partial-mutation safety lock (F-102/F-408-plat invariant): a `Failed(rollbackComplete=false)` result clears `dryRunPreview` AND locks both Plan and Run until the user clicks `acknowledgeScaffoldFailure` explicitly. Kind-switch must REJECT while this lock is engaged — the lock can only be released by intentional acknowledgement. Partial-mutation visual differentiation is color-INDEPENDENT (visible "⚠ [REPO PARTIALLY MUTATED]" monospace badge + `semantics { contentDescription = ... }`).
- Source-of-truth contract: `ScaffoldCatalog` (runtime-core/scaffold) delegates via property getters to `internal val APPROVED_CODE_REVIEW_AREAS / PRE_SHELL_FAMILIES / SHELLED_FAMILIES / PLATFORM_PACK_PRESETS / SCAFFOLD_PAYLOAD_VERSION` in `ScaffoldSupport.kt`. Wizards MUST drive selectors from the catalog — never redeclare slug literals at the UI layer.
- KSP/ABI gotcha (reusable): a JVM gateway's primary constructor MUST NOT take a `(…) -> SomeType` parameter where `SomeType` is in a module the umbrella depends on via `implementation` (not `api`). The umbrella's KSP pass cannot resolve such leaked ABI types and fails with `Unresolved reference: <ERROR TYPE: …>`. Use an `internal var` seam instead (matches the same-feature `RuntimeRepoBrowserService.validator / renderer / authoringSaver` pattern). The new `JvmRuntimeScaffoldGateway.scaffolder` follows this.
- `FakeScaffoldGateway` lives in core/testing; scripts `Preview / Success / Failed` per kind, records `dryRunCalls/executeCalls` plus payloads for parity assertions at the VM seam.
- Wizard payload builder `ScaffoldPayload.toContractMap()` stamps `SCAFFOLD_PAYLOAD_VERSION = "1.0"` and is the SINGLE producer for both dry-run and execute (AC2 invariant: parity at the gateway seam tested for every kind).
- Limitations / deferred review polish (do not relitigate next subtask): `mergeDescendants` cleanup on FailureConsole semantics container (F-202); reorder select-then-dismiss to dismiss-then-select for cleaner assistive-tech announcement (F-203); silent no-op on Plan with blank required fields (F-104); empty piloted-pack-list dead-end UX (F-106 UX); backdrop announces as Role.Button (F-107 UX); TextField labels not associated with inputs (F-108 UX); ASCII glyph dirty-repo "checkbox" without toggleable state semantics (F-109 UX); 14sp "x" close affordance below 24dp hit target (F-110 UX); ScaffoldKind.values() recomposition allocation (F-105 UI); hardcoded `Color(0xFF0B0B0D)` literals duplicated across dialog + toolbar (F-106 UI); toolbar "NEW..." opens only HORIZONTAL_SKILL despite five kinds (F-102 UI); PLATFORM_PACK preset picker drops display name on selection (F-103 UI); BasicTextField focus indicator (F-110 UI); Escape key handler + focus trap inside dialog (F-111 UI); ScaffoldPlan/ScaffoldOutcome are byte-identical mirrors (Arch F-006); `skeletonMode` carries a free-form string where a typed enum exists (Arch F-007); tree right-click context menu was not implemented (NEW... toolbar + 5 palette entries cover the same surface).
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-13] SKILL-44 command-search-quick-open (subtask 07)
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/domain
- Added command palette state derived from `SkillBillState`, with ranked command/source results and no separate discovery or indexing service.
- Palette execution reuses existing route handlers for tree selection, refresh, validate, render, save, repo open, dock tab changes, and Git refresh.
- Reusable pattern: future mutation flows should expose a named ViewModel refresh hook like `refreshAfterScaffold()` so open palette results rebuild through `createState()`.
- Empty repo state shows only repo/session commands; invalid/blocked commands carry disabled prerequisite text in the result model.
- Known limitation: Subtask 08 still owns the actual scaffold wizard and should call `refreshAfterScaffold()` after successful scaffold execution.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-13] SKILL-44 authored-content-editor (subtask 03)
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/data, runtime-desktop/core/domain, runtime-desktop/core/testing
- Added runtime-backed authored `content.md` editing: AuthoringGateway loads editable documents and saves through AuthoringOperations.fill, never direct file writes.
- Editor state now tracks saved text, draft text, dirty status, save progress, save errors, revert, and discard/cancel prompts for dirty selection, refresh, repo switch, and chooser flows.
- Reused the existing runGitRefresh fan-out after successful saves so source-control state updates in the same pattern as validation/render/publishing.
- Generated SKILL.md/support pointers, native-agent output, add-ons, and install cache paths stay read-only; service tests cover generated wrapper/support/native read-only cases.
- Known limitation: final Gradle JVM validation could not start in the local WSL workspace because Gradle FileHasher creation fails with `java.io.IOException: Input/output error`; `skill-bill validate` and `git diff --check` passed.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-13] SKILL-44 publishing (subtask 06)
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/data, runtime-desktop/core/domain, runtime-desktop/core/testing
- Added commit + push publishing flow: commit runs validation first, failed validation requires explicit override, successful commit/push refreshes changes, history, and publishing status.
- Git publishing status now reports push target, ahead/behind, canonical-risk classification, and GitHub compare URL only when remote topology is unambiguous.
- Reused hardened RuntimeGitGateway subprocess pattern; push keeps SSH usable with `ssh -o BatchMode=yes`, while read operations keep `core.sshCommand=` disabled.
- Fork-safety rule: treat `origin` as safe only when every effective push destination is fork-shaped relative to upstream; any canonical pushurl blocks by default.
- UI publishing actions share a busy gate across validation, commit, and push so toolbar/navigation/changes controls do not race source-control operations.
- Known limitation: compare URL is withheld for multiple push destinations because the UI cannot truthfully name a single GitHub target.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-05-12] SKILL-44 changes-history (subtask 05)
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/data (commonMain + jvmMain), runtime-desktop/core/domain, runtime-desktop/core/testing
- New sibling `RuntimeGitGateway` in core/data/jvmMain honors Subtask 04's god-class warning — GitGateway dropped from RuntimeRepoBrowserService. Future gateway work keeps adding siblings.
- JVM subprocess hardening template (reusable for any future Process-shelling gateway): `redirectErrorStream(true)` + concurrent daemon stdout-drain into a size-capped (8 MiB) ByteArrayOutputStream — NEVER `waitFor` before reading (pipe-buffer deadlock); explicit `Charsets.UTF_8` (Windows cp1252 corrupts non-ASCII); scrub GIT_DIR/WORK_TREE/INDEX_FILE/CONFIG*/EXTERNAL_DIFF/FILTER/PAGER/EDITOR/SSH_COMMAND/ASKPASS env vars + `GIT_TERMINAL_PROMPT=0`; prepend `--no-optional-locks` + `-c core.fsmonitor= -c core.hooksPath=/dev/null -c core.pager= -c core.sshCommand= -c protocol.file.allow=user` and `--no-ext-diff` on diff (`-c diff.external=` is rejected by git) to neutralize untrusted `.git/config` (CVE-2022-24765 class).
- `statusFor` must NEVER fork a subprocess on the UI thread (F-C701). Branch label is part of `ChangesSnapshot.branchLabel`, populated only inside the async snapshotFor triplet; VM caches it in `currentBranchLabel` and `createState()` reads cache. Any gateway method called from createState() must be pure.
- Async-op slice-ownership rule (F-A01): when refresh/stage/unstage share one `activeGitOperationToken` and all mutate the SAME slice, stale-finish KEEPS currentState (NOT restore previousSnapshot). The F-101 restore-previous pattern only fits per-selection or per-filter slices; selectedDiff and history triplets still use F-101.
- F-A02: stage/unstage failure must NOT re-query snapshotFor. Use `ChangesSnapshot.failed(errorMessage)` sentinel; VM overlays error onto the existing snapshot (preserve files + branchLabel). AC11 invariant.
- Stale + error coexistence: when slice error appears AND prior data still on screen, render a `Tone.Warning` banner ("Showing previous results — refresh failed.") above the data — do NOT hide stale rows.
- Filter-aware empty-state: an active filter yielding zero must reference the filter and offer a clickable clear affordance, distinct from the no-data empty-state.
- Reusable Compose helpers: `Modifier.iconButtonSemantics(description)` (hit-target min size + Role.Button + contentDescription for every text-based clickable in the dock); `ReadOnlyArtifactTooltip` wraps the RO badge on Generated rows; `recentlyCopiedKey` + 1.5s LaunchedEffect at SkillBillRoute for transient "copied" feedback (clipboard still hoisted via LocalClipboardManager — `@Suppress("DEPRECATION")` intentional until Compose 1.7 migration).
- AC10 fan-out funnels through `runGitRefresh()` and the new `vm.afterValidateFinished()` / `vm.afterRenderFinished()` hooks. Save/scaffold actions don't exist in app yet; once introduced they should call `runGitRefresh()` in the same pattern.
- FakeGitGateway pattern: scripted snapshot/diff/commits + `throwOnX` toggles + per-method `callCount` mirrors FakeValidationGateway/FakeRenderGateway. `recentCommits` returns empty for null session — required for the AC5 empty-state test contract.
- Deferred Major review findings (do not relitigate next subtask): F-U02 LazyColumn for unbounded changed-file list; F-U06 `selectableGroup` row-a11y restructure (project-wide); F-U07 StateFlow + `collectAsStateWithLifecycle` vs hand-rolled `remember{mutableStateOf(viewModel.state())}` (project-wide route pattern); F-T06 AC7 positive-case binding test at the route level.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-05-12] SKILL-44 render-console (subtask 04)
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/data, runtime-desktop/core/domain, runtime-desktop/core/testing, runtime-core/scaffold + nativeagent
- Added on-demand render: SkillBillViewModel.beginRender/runRender/finishRender mirrors the Subtask 02 validation triplet (activeOperationToken + caller-dispatcher-captured RenderRunRequest with previousRenderSummary); stale finish restores previousSummary instead of getting stuck on RUNNING.
- New domain boundary `RenderGateway` in core/domain with `render(session, treeItemId)` returning `RenderSummary(state, blocks, generatedArtifacts, durationMillis, runtimeExceptionName/Message)`; JVM impl on RuntimeRepoBrowserService dispatches by SelectionDetail.kind to `renderAuthoringTarget` (skills), `parseNativeAgentSourceFile`+`renderNativeAgentSource` (native agents), or a `Files.readString` pass-through (add-ons). Internal `renderer: (Path, String) -> AuthoringRenderResult` seam mirrors the F-107 validator seam for test-driven failure paths.
- Render is dry-run only: AC11 proven by sha256 content-hash repoFileSnapshot in RuntimeRepoBrowserServiceTest (mtime alone was too weak — coarse-granularity filesystems pass same-tick rewrites). Reusable: `repoFileSnapshot` + `sha256Hex` test helpers.
- DockTab promoted from private SkillBillFrame.kt enum to `core/domain` (`SkillBillState.activeDockTab`). beginRender flips activeDockTab to Console; selectTreeItem/moveSelection reset render to UNAVAILABLE and fall back from Console to Validation only if Console was active (F-202). Pattern: per-selection-keyed state slices must reset when selection changes, not just on refresh/repo-switch.
- F-201: render() captures `val capturedSnapshot = snapshot` once at entry to avoid torn reads across Dispatchers.Default while open()/refresh() rewrite the field; this is the safe localized fix for the carried-forward non-volatile snapshot hazard. Validation slice still reads `snapshot` directly — apply the same capture pattern when next touched.
- F-601: InstallConsole content uses a single shared `Modifier.horizontalScroll(rememberScrollState())` on the inner column so long failure-line tokens (paths, exception class names) stay readable; do NOT use maxLines/Ellipsis — AC5 requires full exception text.
- Inspector "Generated artifacts" prefers `render.generatedArtifacts` when PASSED/FAILED, otherwise falls back to static `editor.generatedArtifacts`. Status bar gets a new `render:` StatusItem alongside `validation:`. Route exposes `onPostRenderGitRefresh: () -> Unit = {}` seam for Subtask 05 (no-op default).
- Limitations / deferred review polish: god-class growth on RuntimeRepoBrowserService (now 6 gateway interfaces — split into RuntimeRenderGateway before adding a 7th); InstallConsole eagerly composes via `Column`+`forEachIndexed` (switch to LazyColumn when render output grows); console + inspector lists are unstable `List<>` (consider `@Immutable` + `ImmutableList` for skipping); domain encodes raw JVM exception strings (introduce `RenderFailure` sealed class for platform-neutral domain); i18n debt — all toolbar/console strings are hardcoded.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-05-12] SKILL-44 validation-workbench (subtask 02)
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/data, runtime-desktop/core/domain, runtime-desktop/core/testing, runtime-core/scaffold
- Added on-demand validation: SkillBillViewModel.beginValidate/runValidate(request)/finishValidate mirrors the Subtask 01 begin/finish + activeOperationToken pattern, plus a new reusable ValidationRunRequest that captures token + session + previousValidationSummary on the caller dispatcher so stale finishes restore the pre-RUNNING summary instead of leaving the slice stuck on RUNNING.
- New domain boundary `ValidationGateway` in core/domain with `validate(session)` + `resolveTreeItemIdForSource(session, path)`; JVM impl on RuntimeRepoBrowserService delegates to RepoValidationRuntime.validateRepo via an internal `validator: (Path) -> RepoValidationReport` seam — reusable test hook for FAILED-by-exception branches without leaking the seam into production callers.
- `RepoValidationReport.structuredIssues` widened additively in runtime-core; CLI `toPayload()` shape preserved so `skill-bill validate --format json` contract holds.
- Inspector + bottom-dock validation rows are state-driven from `state.validation.issues` (single source); every row surfaces 5 fields (severity name, code, message, sourcePath, exceptionName) and both surfaces render a FAILED-no-details fallback row symmetrically.
- Clipboard side effect for "copy source path" is fully hoisted to SkillBillRoute (LocalClipboardManager.current read in the route); inspector emits `onCopyIssueSource` only. Pattern: keep all Compose-thread side effects at the route boundary so inspector composables stay pure.
- Refresh and repo-switch both reset `validation` to UNAVAILABLE unconditionally (no sameRepo guard) — on-disk state may have changed.
- Limitation: per-issue parsing in RepoValidationIssue.fromRawIssue is a heuristic over the legacy string format (severity defaults to ERROR, code/name unset); future work should let RepoValidationRuntime emit truly structured issues at source so the desktop UI stops paying parser cost. Pre-existing concurrency hazard on `RuntimeRepoBrowserService.snapshot` (non-volatile var, cross-dispatcher) carried forward from Subtask 01; not introduced here.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-12] SKILL-44 state-repo-tree (subtask 01)
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/data
- Closed two test gaps against the read-only repo browser polished in earlier SKILL-44 commits: AC7 collapsed-group keyboard navigation in SkillBillViewModelTest (asserts moveSelection traverses only visibleItems(expandedNodeIds) and clamps at the end), and AC11 failed-open no-write invariant in RuntimeRepoBrowserServiceTest (snapshot-compare around a failed open + recovered open + treeFor + describeSelection).
- Pattern reinforced: state-repo-tree contract verification reuses MutableSkillTreeService, FakeSkillBillServices, seedRepo, and repoFileSnapshot helpers — prefer these over new test scaffolding when extending coverage.
- AC scaffolding was already in place from the prior SKILL-44 commits (SkillBillViewModel activeOperationToken stale-load protection, RuntimeRepoBrowserService SHA-256 repoToken, model-backed status bar, expect/actual JFileChooser); subsequent subtasks should layer on top of this state model rather than reimplementing it.
- Mechanical spotless reformat of SkillBillFrame.RepositoryAction signature; no behavior change.
- Limitation: review surfaced 3 deferred Minor findings (clamp-tied assertEquals at end of moveSelection test, missing expanded-precondition assert, missing failure-result assert on the failed-open call) — tighten in a follow-up testing pass.
Feature flag: N/A
Acceptance criteria: 11/11 implemented
