# SkillBill desktop feature — history

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
