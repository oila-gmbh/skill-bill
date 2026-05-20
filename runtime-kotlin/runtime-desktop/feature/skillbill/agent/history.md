# SkillBill desktop feature — history

## [2026-05-20] SKILL-49 material3-theme-adoption-dialogs-small-surfaces
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/designsystem
- Dialog/setup surfaces now consume `SkillBillTheme.semanticTones` and `SkillBillTheme.colors` directly instead of per-file local color helper palettes.
- `ScaffoldWizardDialog` BasicTextField wrapper maps text, disabled text/container/border, focused border, regular border, and cursor through `SkillBillTheme.textFieldTokens`. reusable
- Confirm deletion success/error, first-run setup status, and scaffold warning/success/error banners use semantic tone containers/content/borders while preserving existing state/callback behavior.
- Follow-up note: a Minor review item remains for replacing `onSurfaceVariant.copy(alpha = 0.55f)` on the disabled first-run close glyph if a general disabled-content token is added later.
- Known limitation: full repo `./gradlew check` remains blocked by untouched `runtime-cli` RemoveCliCommandTest spotless/detekt issues; scoped desktop/KMP validation passes.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-05-20] SKILL-49 material3-theme-adoption-helpers-guardrails
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/designsystem
- Feature UI no longer authors raw Compose colors; workspace constants now reference core/designsystem tokens, with `SkillBillTransparent`, `SkillBillColor`, and `SkillBillOnYellow` covering transparent/type/primary-yellow foreground seams. reusable
- YAML highlighting keeps the SKILL-47 regex tokenizer unchanged but consumes `SkillBillTheme.syntaxTokens.yaml`; tests use design-system-owned fixtures instead of feature-local `Color` palettes.
- Unified diff rendering keeps prefix classification in feature UI as `DiffLineRole` and maps roles to `SkillBillTheme.diffTokens` at render time, preserving the feature/design-system boundary. reusable
- `DesktopColorTokenBoundaryTest` scans runtime-desktop Kotlin source sets under `/src/` outside `core/designsystem` (including `jvmMain`) and fails on raw `Color(0x...)`, `Color.Black/White/Transparent`, or direct Compose `Color` imports.
- Known limitation: full repo `./gradlew check` remains blocked by untouched `runtime-cli` failures; scoped desktop/KMP validation passes.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-05-19] SKILL-47 platform-pack-schema-source-of-truth (desktop schema viewer)
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/domain, runtime-desktop/core/data
- New `TreeItemKind.CONTRACT` exposes runtime contracts (today: `orchestration/contracts/platform-pack-schema.yaml`) as a top-level "Contracts" group under the Skill Bill tree. CONTRACT leaves are read-only (`editable=false`, `readOnlyLabel="RO"`, `readOnlyReason` explains the contract is edited as a repo file), inherit the SKILL-44 subtask-03 read-only `SelectionDetail.contentFile` flow (no second loader), and are excluded from the SKILL-46 right-click-delete predicate via the existing `else -> null` in `resolveDeletionTarget`. Every exhaustive `when (TreeItemKind)` (CommandPaletteBuilder, SkillBillViewModel.isRenderableTreeItemKind, SkillBillFrame markerFor / row icon, ConfirmDeletionDialog kind label) was updated; CONTRACT renders as marker `ct` and is non-renderable. reusable
- `RuntimeRepoBrowserService.loadContracts` builds the Contracts group with one leaf whose `contentFile = root.resolve(PlatformPackSchemaPaths.REPO_RELATIVE_PATH)` — the runtime-core constant is the single source of truth for the path. `renderDetail` has an explicit `"contract"` branch returning an empty `RenderOutcome` so single-target Render on the schema row is a clean no-op (matches `beginRenderAll`'s `isRenderableTreeItemKind` filter for the multi-target path).
- YAML-aware syntax highlighting lives in `YamlSyntaxHighlighter.highlightYaml(text, colors): AnnotatedString` — pure helper, regex-driven, no parser dep. Token rules: line-leading `#` → comment; `---` / `...` → document marker; `<key>:` → key color (accepts `$ref`/`$defs`); single- and double-quoted scalars → string color; unquoted scalars after `:` → scalar color; inline ` #` tail comments → comment color. `CodeEditor`'s read-only branch switches to `CodeLineAnnotated` only when `editor.kind == "contract"` (other kinds keep the existing renderer untouched); AnnotatedString cached via `remember(rawText)` so highlighting runs once per selection. reusable
- The highlighter palette reuses the existing `WorkspaceSteel/Yellow/Green/Amber/Text` color tokens at the top of `SkillBillFrame.kt` — there is no semantic theme palette in runtime-desktop yet, so a follow-up can swap the tokens in one place without touching the tokenizer.
- Tests: `RuntimeRepoBrowserContractsGroupTest` (in `core/data/jvmTest`) exercises the REAL `RuntimeRepoBrowserService.buildTree` against a temp repo containing the canonical schema and asserts the Contracts group + CONTRACT leaf shape (kind, editable=false, readOnlyLabel, contentFile resolves to the canonical path). `PlatformPackSchemaViewerStateTest` covers VM behavior: byte-for-byte `editor.content` round-trip via the gateway, dirty-state isolation across selections (SKILL-44 subtask-04 pattern). `YamlSyntaxHighlighterTest` covers token rules and asserts plain text yields zero spans.
- Reusable: contract-leaf SelectionDetail pattern (any future contract file under `orchestration/contracts/` can be surfaced by adding a single entry in `loadContracts`); `kind == "contract"` gate as the simplest opt-in for YAML highlighting; `highlightYaml` helper is decoupled from the editor and can be reused for any other YAML rendering surface.
Feature flag: N/A
Acceptance criteria: 8/8 implemented (this boundary contributes to AC6, AC7, AC8 desktop UI test)

## [2026-05-18] SKILL-46 tree-context-menu-delete
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/domain, runtime-desktop/core/data, runtime-desktop/core/testing, runtime-domain, runtime-core, runtime-cli
- Right-click on left-panel tree rows of kind SKILL / PLATFORM_PACK / ADD_ON opens a Material3 `DropdownMenu` with a single `Delete…` item that triggers a checkbox-gated confirmation dialog. Two-step gesture is intentional (right-click → menu → dialog); collapsing it to one click changes the destructive-UX contract.
- Right-click is wired via a `Modifier.pointerInput` extension that consumes both Press and Release of `PointerButton.Secondary` (drain Release to keep `combinedClickable` from firing selection); state for the dropdown is hoisted per-row via `remember(node.id) { mutableStateOf(false) }`. Modifier ALSO filters `node.editable` and `DesktopSkillRemovalTarget.isBuiltInName(identifier)` so built-ins (`.bill-shared` / `kotlin` / `kmp`) never reach the dialog. Built-in name set is the single source of truth in `runtime-domain` consumed by modifier, route, and CLI refusal alike.
- Removal uses a new sibling gateway: `RuntimeSkillRemoveGateway` interface in `core/domain/service/` and `JvmRuntimeSkillRemoveGateway` impl in `core/data` — NOT bolted onto `RuntimeRepoBrowserService` (continues SKILL-44 subtask-08 god-class rule). Single `SkillRemoveRequest` payload reused for both `preview` and `execute`. Catch posture: rethrow `CancellationException` first, catch `SkillBillRuntimeException` mapping `rollbackComplete` from typed subclass, catch generic `Exception` forcing `rollbackComplete=false`, let `Error` propagate; `@Suppress("TooGenericExceptionCaught")` scoped to the single function. Gateway constructor uses an `internal var serviceFactory` seam (NOT a `() -> SkillRemove` constructor lambda) to satisfy the KSP/ABI rule.
- ViewModel triplets (`beginPreviewRemoval` / suspend `runPreviewRemoval` / `finishPreviewRemoval` and matching execute) capture VM state into an immutable request on Main BEFORE the `Dispatchers.Default` hop. Success fan-out gates on POST-finish `state.confirmDeletion?.executionResult is Success`, never a local result var (handles mid-flight dismiss). `busyOperation = DELETE` is released in `dismissConfirmDeletion` AND in stale-token branches of `finishRemoval*` (F-401 invariant). `Failed(rollbackComplete=false)` sets `partialMutationLocked=true` on the dialog AND populates a VM-scoped `partialMutationPostMortem` slot that survives dismiss/repo-switch — only `acknowledgeRemovalFailure` clears either.
- Route fan-out launches preview/execute via `coroutineScope.launch { try { withContext(Dispatchers.Default) { … } } finally { withContext(NonCancellable) { finishRemoval*(cancelled = true) } } }` so cancellation always releases the busy slot (F-401). On Success the route runs `beginRefreshAfterScaffold → loadRepo → finishRefreshAfterScaffold`, switches `activeDockTab = DockTab.Console`, fires the validate-agent-configs subprocess via `runInterruptible(Dispatchers.IO)`, then `runGitRefresh(quiet=true)` + `loadHistory(quiet=true)`. Skips reselect (deleted node is gone).
- `ValidateAgentConfigsRunner.jvm.kt` JVM-side subprocess hardening (mirrors SKILL-44 subtask-05): `redirectErrorStream(true)`, daemon stdout drain into 8 MiB cap, UTF-8, prefix-based scrub of every `GIT_CONFIG*` and `GIT_TRACE*` env var plus `GIT_DIR/WORK_TREE/INDEX_FILE/EXTERNAL_DIFF/PAGER/EDITOR/SSH_COMMAND/ASKPASS`, sets `GIT_TERMINAL_PROMPT=0`. `runInterruptible(Dispatchers.IO)` lets coroutine cancellation interrupt the blocking `process.waitFor`; finally-block calls `process.destroyForcibly()` to avoid zombie gradle daemons.
- F-S01 path-traversal: domain-layer `TargetValidation.validateOrRefuse(request)` runs BEFORE every FS call in both `previewRemoval` and `executeRemoval`. Single producer used by CLI and desktop. Rules: skillName/platform must match `[A-Za-z0-9._-]+`, not equal `.` or `..`, not start with `-`; AddOn `relativePath` must be non-absolute, contain no `..`, contain no `\\`, and the resolved normalized path must lie under `<repoRoot>/platform-packs/`. Refusals throw `SkillRemovalRefusedException(reason=INVALID_TARGET)` and never hit disk.
- F-S04 path-disclosure: `SkillRemoveErrorSanitizer.sanitize(message, repoRootAbsolutePath)` relativizes absolute paths in surfaced exception messages to repo-relative form; unrelativizable absolute paths become `<external path>`. Gateway applies it on `Failed.exceptionMessage` BEFORE the dialog/CLI sees it.
- Cascade ordering: applyCascade is (1) stash manifests+README, (2) apply manifest edits, (3) apply README edits, (4) stash file trees + delete listed paths, (5) unlink agent symlinks across Claude/Codex/Opencode/Junie via `InstallNativeAgentOperations.unlink*Agents`. Symlinks LAST so a throw at any earlier step leaves agent homes untouched. Per-provider unlink failures are accumulated (no `runCatching` swallow) and a `SkillBillRollbackException` is thrown if any failed so the gateway maps to `Failed(rollbackComplete=false)` honestly. Rollback restores manifests/README/files from byte stash; symlinks never need rolling back.
- README catalog edits return a `ReadmeEditOutcome` sealed type (`Applied` / `LandmarksMissing(reason)`); landmark-missing outcomes propagate via `AppliedCascade.readmeWarnings: List<ReadmeCatalogWarning>` to the dialog as a non-fatal warning section underneath success.
- Confirmation dialog (`ConfirmDeletionDialog.kt`) lists every `preview.filesystemPaths` / `manifestEdits` / `agentSymlinkUnlinks` / `cascadedSkillNames` so the user audits the full cascade. AC5 gate: `state.deleteEnabled = preview != null && acknowledged && !executeBusy && !partialMutationLocked`. A11y: `Modifier.semantics(mergeDescendants=true) { paneTitle; isTraversalGroup }` on the inner panel; `heading()` on the title; `Modifier.toggleable(role=Role.Checkbox, stateDescription="Checked"/"Not checked")` on the acknowledgment row; `FocusRequester` + `LaunchedEffect.requestFocus()` lands focus on Cancel; `onPreviewKeyEvent` binds Escape→dismiss and Enter→dismiss (never auto-confirm destructive); Delete button uses filled-red background + trash glyph (not color-only); failed banner has red border and recovery copy ("Run `scripts/validate_agent_configs` and inspect the Console tab").
- Tests cover: per-scope `previewRemoval` (HorizontalSkill cascade, PlatformPack paired tree + 4 providers, AddOn) + refusal paths (.bill-shared / kotlin / kmp / acceptance-with-allowShipped); `executeRemoval` Failed-with-rollbackComplete=false; manifest-edit idempotency (2nd call no-op); ReadmeCatalogEdits Applied + LandmarksMissing fallback; ViewModel right-click→preview→confirm→execute→Success with refreshCount assertion; ConfirmDeletionState deleteEnabled permutations; CLI --dry-run no-FS-mutation + refusal exit codes. State-snapshot-style (no ComposeTestRule).
- Reusable: every fix-loop pattern surfaced here is general — single-throw via `String?`-returning `nameProblem`/`addOnPathProblem` for ThrowsCount cleanup; prefix-based env scrub for `GIT_CONFIG_COUNT/KEY_<n>/VALUE_<n>/PARAMETERS` hardening; `runInterruptible(Dispatchers.IO)` + finally destroyForcibly for any blocking subprocess; per-row state in tree composables via `var x by remember(id) { mutableStateOf(...) }`; sibling-gateway pattern with `internal var serviceFactory` test seam.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-05-17] desktop install setup relaunch
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/domain
- Added a repo-scoped `Install` toolbar command and command-palette action that reopens the Skill Bill setup wizard after first-run completion, so changed skills/packs/agent links can be reapplied without deleting desktop preferences.
- Reused the existing first-run setup state machine and stored preferences as defaults; discovery still refreshes detected agents and platform packs before apply.
- Dialog overlap rule: setup relaunch is disabled while busy, publishing, first-run setup, or scaffold wizard state is active; the setup dialog itself can be dismissed while idle and keeps close disabled during discovery/install.
Feature flag: N/A
Acceptance criteria: manual reinstall path available from app

## [2026-05-17] SKILL-45 final-integration-docs-validation
Areas: runtime-desktop first-run wizard docs, runtime-desktop packaging docs, desktop validation evidence
- Documented the first-run wizard as a thin desktop adapter over `DesktopFirstRunGateway` and shared install plan/apply, including agent/platform/telemetry/MCP choices and structured Windows symlink outcomes.
- Documented native package tasks and host limits for DMG/MSI/Deb/RPM plus Arch/CachyOS RPM or loose-distribution fallback; packaged runtime lookup remains `skill-bill-runtime` app resources or explicit override. reusable
- Existing tests cover wizard state, outcome rendering, gateway mapping, runtime asset lookup, and packaging task wiring; final pass added traceability rather than new desktop behavior.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-17] SKILL-45 desktop-first-run-wizard
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/domain, runtime-desktop/core/data, runtime-desktop/core/datastore, runtime-desktop/core/testing
- Added first-run setup flow for agent selection/detection, platform-pack selection from dynamic discovery, telemetry level, MCP registration, and structured install outcomes.
- Reused begin/run/finish ViewModel token pattern; wizard state is explicit domain state and testable without launching the desktop app.
- Desktop install gateway calls shared `InstallOperations.planInstall/applyInstall` and maps typed success/warning/failure details instead of parsing shell output.
- Reusable: `DesktopFirstRunGateway`, `FirstRunSetupModels`, `FakeDesktopFirstRunGateway`, and outcome-step UI tests cover selection, telemetry, MCP, gateway mapping, and result rendering.
- Known limitation: review left a Minor UX gap where discovery failure on the Agents step has no in-wizard retry button.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-05-15] SKILL-44 create-pr-publishing
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/domain, runtime-desktop/core/data, runtime-desktop/core/testing
- Replaced raw Git-style change publishing with a governed `Publish Changes` flow that groups files by Skill Bill concepts, keeps Git status secondary, and defaults generated/read-only artifacts out of selection.
- Added PR publishing gateway behavior behind the ViewModel publish state machine: validate, selected-file commit, push, existing PR open, draft PR create, or compare URL fallback.
- Reused runtime Git hardening patterns: literal pathspecs, selected rename handling, canonical remote blocking, redacted provider errors, and route-owned browser side effects.
- Reusable: `RuntimePrPublishingGateway`, governed change classifiers, and fake gateway scripts cover provider unavailable, existing PR, draft creation, fallback, and failure paths.
- Known limitation: PR provider support is GitHub CLI based; unavailable/auth/network/provider errors surface without credential setup.
Feature flag: N/A
Acceptance criteria: 16/16 implemented

## [2026-05-14] keyboard-accelerators
Areas: runtime-kotlin/runtime-desktop/feature/skillbill, runtime-kotlin/runtime-desktop/core/domain
- Added desktop accelerators for repo open, commit, save, refresh, render, validate through existing route callbacks.
- Followed route-owned busy/dirty guards; key handlers dispatch on UI state and do not add view-model commands.
- Reusable: `KeyboardAccelerators.kt` centralizes resolver and callback dispatch helpers for focused fake-callback tests.
- Command palette and toolbar discoverability share `SkillBillAcceleratorLabels` with Cmd/Ctrl labels.
- Breaking changes or known limitations: shortcuts are fixed, not user-rebindable.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-05-14] SKILL-44 compare-url-browser (subtask 11)
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/common, runtime-desktop app DI graph
- Compare URL in the Push controls is now an actionable row that opens through route-owned `onOpenCompareUrl`, keeps selectable URL text, and preserves `Role.Button` + `Open compare URL: <url>` semantics on the clickable node.
- Reusable platform service: `BrowserLauncher` lives in core/common with typed `Opened` / `Failed` outcomes; JVM AWT `Desktop.getDesktop().browse` is isolated in `JvmBrowserLauncher`, never in UI code.
- Route pattern: potentially blocking platform browser work runs in `Dispatchers.Default`, then the UI coroutine sets transient opened/copied keys; fallback copies only on typed failure or thrown launcher error.
- Review catch: do not drop `SelectionContainer` when converting selectable text to a clickable row, and provide success feedback (`Opened in browser`) in addition to fallback `Copied`.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

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
