package skillbill.desktop.feature.skillbill.state

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.ScreenScope
import skillbill.desktop.core.datastore.DesktopFirstRunPreferences
import skillbill.desktop.core.datastore.DesktopPreferenceStore
import skillbill.desktop.core.domain.model.AuthoredContentDocument
import skillbill.desktop.core.domain.model.AuthoringSaveResult
import skillbill.desktop.core.domain.model.BaselineReviewLayerSuggestion
import skillbill.desktop.core.domain.model.ChangedFile
import skillbill.desktop.core.domain.model.ChangedFileGroup
import skillbill.desktop.core.domain.model.ChangesSnapshot
import skillbill.desktop.core.domain.model.CommitEntry
import skillbill.desktop.core.domain.model.ConfirmDeletionState
import skillbill.desktop.core.domain.model.DesktopSkillRemovalRequest
import skillbill.desktop.core.domain.model.DesktopSkillRemovalResult
import skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget
import skillbill.desktop.core.domain.model.DirtyEditorPrompt
import skillbill.desktop.core.domain.model.DirtyEditorPromptReason
import skillbill.desktop.core.domain.model.DockTab
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.FirstRunApplyResult
import skillbill.desktop.core.domain.model.FirstRunDiscoveryResult
import skillbill.desktop.core.domain.model.FirstRunInstallDetail
import skillbill.desktop.core.domain.model.FirstRunInstallDetailSeverity
import skillbill.desktop.core.domain.model.FirstRunInstallOutcome
import skillbill.desktop.core.domain.model.FirstRunInstallStatus
import skillbill.desktop.core.domain.model.FirstRunPlanResult
import skillbill.desktop.core.domain.model.FirstRunPlatformSelectionMode
import skillbill.desktop.core.domain.model.FirstRunSetupDiscovery
import skillbill.desktop.core.domain.model.FirstRunSetupRequest
import skillbill.desktop.core.domain.model.FirstRunSetupState
import skillbill.desktop.core.domain.model.FirstRunSetupStep
import skillbill.desktop.core.domain.model.FirstRunTelemetryLevel
import skillbill.desktop.core.domain.model.GitOperationResult
import skillbill.desktop.core.domain.model.GitPublishingStatus
import skillbill.desktop.core.domain.model.GitPushTarget
import skillbill.desktop.core.domain.model.PartialMutationPostMortem
import skillbill.desktop.core.domain.model.PostPublishReinstallState
import skillbill.desktop.core.domain.model.PrPublishingErrorType
import skillbill.desktop.core.domain.model.PrPublishingRequest
import skillbill.desktop.core.domain.model.PrPublishingResult
import skillbill.desktop.core.domain.model.ProvisionResult
import skillbill.desktop.core.domain.model.PublishLink
import skillbill.desktop.core.domain.model.PublishLinkKind
import skillbill.desktop.core.domain.model.RenderBlock
import skillbill.desktop.core.domain.model.RenderRunState
import skillbill.desktop.core.domain.model.RenderSummary
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.ScaffoldBaselineLayerForm
import skillbill.desktop.core.domain.model.ScaffoldBaselineLayerPayload
import skillbill.desktop.core.domain.model.ScaffoldCatalogSnapshot
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.ScaffoldOutcome
import skillbill.desktop.core.domain.model.ScaffoldPayload
import skillbill.desktop.core.domain.model.ScaffoldRunResult
import skillbill.desktop.core.domain.model.ScaffoldWizardFormFields
import skillbill.desktop.core.domain.model.ScaffoldWizardState
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.domain.model.SkillBillStatusBar
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.domain.model.ValidateAgentConfigsSummary
import skillbill.desktop.core.domain.model.ValidationIssue
import skillbill.desktop.core.domain.model.ValidationRunState
import skillbill.desktop.core.domain.model.ValidationSummary
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.DesktopFirstRunGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.InstalledWorkspaceGitProvisioner
import skillbill.desktop.core.domain.service.InstalledWorkspaceLocator
import skillbill.desktop.core.domain.service.PrPublishingGateway
import skillbill.desktop.core.domain.service.RecentRepoRepository
import skillbill.desktop.core.domain.service.RenderGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.RuntimeScaffoldGateway
import skillbill.desktop.core.domain.service.RuntimeSkillRemoveGateway
import skillbill.desktop.core.domain.service.SkillTreeService
import skillbill.desktop.core.domain.service.ValidationGateway
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(ScreenScope::class)
class SkillBillViewModel(
  private val repoSessionService: RepoSessionService,
  private val skillTreeService: SkillTreeService,
  private val authoringGateway: AuthoringGateway,
  private val gitGateway: GitGateway,
  private val prPublishingGateway: PrPublishingGateway,
  private val validationGateway: ValidationGateway,
  private val renderGateway: RenderGateway,
  private val recentRepoRepository: RecentRepoRepository,
  private val scaffoldGateway: RuntimeScaffoldGateway,
  private val firstRunGateway: DesktopFirstRunGateway,
  private val desktopPreferenceStore: DesktopPreferenceStore,
  private val skillRemoveGateway: RuntimeSkillRemoveGateway,
  private val installedWorkspaceLocator: InstalledWorkspaceLocator,
  private val installedWorkspaceGitProvisioner: InstalledWorkspaceGitProvisioner,
) {
  private var installedWorkspaceRoot: String? =
    installedWorkspaceLocator.locate().takeIf { it.availability }?.path?.takeIf { it.isNotBlank() }
  private var normalizedInstalledWorkspaceRoot: String? = installedWorkspaceRoot?.let(::normalizeRepoPath)
  private var repoPathText: String = recentRepoRepository.recentRepoPath().orEmpty()
  private var currentSession: RepoSession? = null
  private var treeItems: List<SkillBillTreeItem> = emptyList()
  private var selectedTreeItemId: String? = null
  private var expandedNodeIds: Set<String> = emptySet()
  private var busyOperation: SkillBillBusyOperation? = null
  private var activeOperationToken = 0L
  private var validation: ValidationSummary = ValidationSummary.unavailable
  private var render: RenderSummary = RenderSummary.unavailable
  private var activeDockTab: DockTab = DockTab.Validation
  private var changesSnapshot: ChangesSnapshot = ChangesSnapshot.empty

  // F-C701: cache the branch label sourced from the most recent ChangesSnapshot so createState()
  // never has to fork `git branch --show-current` on the UI thread per assembly. The cache is
  // populated only inside the async git-refresh triplet (finishGitRefresh) and cleared on repo
  // switch (applyRepoLoadResult).
  private var currentBranchLabel: String? = null
  private var changesBusy: Boolean = false
  private var selectedChangedFilePath: String? = null
  private var selectedDiff: String = ""
  private var selectedDiffStaged: Boolean = false
  private var selectedDiffBusy: Boolean = false
  private var history: List<CommitEntry> = emptyList()
  private var historyBusy: Boolean = false
  private var historyErrorMessage: String? = null
  private var historyPathFilter: String? = null
  private var activeGitOperationToken: Long = 0L
  private var activeGitDiffToken: Long = 0L
  private var activeHistoryToken: Long = 0L
  private var commitMessage: String = ""
  private var selectedPublishPaths: Set<String> = emptySet()
  private var publishSelectionInitialized: Boolean = false
  private var publishSelectionDirty: Boolean = false
  private var publishPrTitle: String = ""
  private var publishPrBody: String = ""
  private var publishDraft: Boolean = true
  private var publishBusy: Boolean = false
  private var publishErrorMessage: String? = null
  private var publishLink: PublishLink? = null
  private var commitBusy: Boolean = false
  private var commitErrorMessage: String? = null
  private var commitValidationFailed: Boolean = false
  private var failedValidationStagedAuthoredPaths: Set<String>? = null
  private var commitValidationRunning: Boolean = false
  private var publishingStatus: GitPublishingStatus = GitPublishingStatus.empty
  private var pushBusy: Boolean = false
  private var pushErrorMessage: String? = null
  private var canonicalPushConfirmationRequired: Boolean = false
  private var canonicalPushConfirmationTarget: GitPushTarget? = null
  private var activeCommitToken: Long = 0L
  private var activePushToken: Long = 0L
  private var activePublishToken: Long = 0L
  private var loadedEditorDocument: AuthoredContentDocument? = null
  private var editorSelectionId: String? = null
  private var editorDraftText: String = ""
  private var editorSaveInProgress: Boolean = false
  private var editorSaveErrorMessage: String? = null
  private var dirtyEditorPrompt: DirtyEditorPrompt? = null
  private var activeSaveToken: Long = 0L
  private var commandPaletteOpen: Boolean = false
  private var commandPaletteQuery: String = ""
  private var commandPaletteSelectedResultIndex: Int = 0
  private var scaffoldWizard: ScaffoldWizardState? = null
  private var activeScaffoldToken: Long = 0L
  private var nextScaffoldBaselineLayerRowId: Long = 1L

  // SKILL-46: dialog state for the tree-context-menu Delete affordance and accompanying
  // validate-agent-configs output slice. Tokens follow the same monotonic-increment pattern as
  // activeScaffoldToken so stale preview/execute responses cannot leak through (F-401).
  private var confirmDeletion: ConfirmDeletionState? = null

  // F-CROSS-REPO-LOCK: persistent post-mortem slot, separate from confirmDeletion so it survives
  // a stale-token finish, dialog dismiss, AND a repo switch. Only acknowledgeRemovalFailure()
  // clears it.
  private var partialMutationPostMortem: PartialMutationPostMortem? = null
  private var activeRemovalToken: Long = 0L
  private var validateAgentConfigsSummary: ValidateAgentConfigsSummary =
    ValidateAgentConfigsSummary.empty
  private var activeValidateAgentConfigsToken: Long = 0L
  private var firstRunSetup: FirstRunSetupState? =
    if (desktopPreferenceStore.firstRunPreferences.value.completed || firstRunGateway.hasExistingInstall()) {
      null
    } else {
      latestInstallSetupRequest()?.toFirstRunSetupState() ?: FirstRunSetupState()
    }
  private var activeFirstRunToken: Long = 0L
  private var postPublishReinstall: PostPublishReinstallState? = null
  private var activePostPublishReinstallToken: Long = 0L

  // AC4: when git provisioning cannot locate the git binary, we surface a non-null error message
  // via the changes snapshot so the session still opens and the editor still works.
  private var installedWorkspaceProvisionErrorMessage: String? = null

  private var currentState = createState()

  init {
    val capturedInstalledRoot = installedWorkspaceRoot
    if (capturedInstalledRoot != null) {
      // NOTE(F-CRITICAL-1): provision() runs synchronously here. In the AlreadyProvisioned fast
      // path (rev-parse exits 0 and top-level == root) this completes in < 100 ms. In the
      // first-open path, up to 6 sequential git sub-processes (each with a 5-second timeout) are
      // executed — worst-case ~30 s. The VM has no coroutine scope at construction time (it is
      // created synchronously by the DI graph before the Compose route is attached), so there is
      // no safe async entry point here. This known limitation is tracked for a follow-up refactor
      // that will move provisioning to a LaunchedEffect on the route.
      val provisionResult = installedWorkspaceGitProvisioner.provision(capturedInstalledRoot)
      currentState = openRepo(capturedInstalledRoot, preserveSelection = false)
      // AC4: apply the provision error AFTER openRepo so the repo-load reset does not clobber it.
      // The error is surfaced via changesSnapshot.errorMessage; the session and editor still work.
      val provisionError = when (provisionResult) {
        is ProvisionResult.GitUnavailable -> provisionResult.errorMessage
        is ProvisionResult.Failed -> provisionResult.errorMessage
        else -> null
      }
      if (provisionError != null) {
        installedWorkspaceProvisionErrorMessage = provisionError
        changesSnapshot = ChangesSnapshot(files = emptyList(), errorMessage = provisionError)
        currentState = createState()
      }
    } else if (repoPathText.isNotBlank()) {
      currentState = openRepo(repoPathText, preserveSelection = false)
    }
  }

  fun state(selectedTreeItemId: String? = currentState.selectedTreeItemId): SkillBillState {
    val resolvedSelection = selectedTreeItemId?.takeIf(::containsTreeItem)
    if (this.selectedTreeItemId != resolvedSelection) {
      if (isEditorDirty()) {
        dirtyEditorPrompt = DirtyEditorPrompt(
          reason = DirtyEditorPromptReason.SELECTION_CHANGE,
          targetTreeItemId = resolvedSelection,
        )
        currentState = createState()
        return currentState
      }
      this.selectedTreeItemId = resolvedSelection
      loadEditorForSelection()
    }
    currentState = createState()
    return currentState
  }

  fun updateRepoPathText(repoPath: String): SkillBillState {
    repoPathText = repoPath
    currentState = createState()
    return currentState
  }

  fun selectRepoPath(repoPath: String = repoPathText): SkillBillState {
    val state = beginSelectRepoPath(repoPath)
    if (state.dirtyEditorPrompt != null) {
      return state
    }
    return finishSelectRepoPath()
  }

  fun beginSelectRepoPath(repoPath: String = repoPathText): SkillBillState {
    val targetRepoPath = repoPath.trim()
    if (isEditorDirty()) {
      dirtyEditorPrompt = DirtyEditorPrompt(
        reason = DirtyEditorPromptReason.REPO_SWITCH,
        targetRepoPath = targetRepoPath,
      )
      currentState = createState()
      return currentState
    }
    repoPathText = targetRepoPath
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.OPEN_REPO
    currentState = createState()
    return currentState
  }

  fun finishSelectRepoPath(repoPath: String = repoPathText): SkillBillState {
    return finishSelectRepoPath(loadRepo(repoLoadRequest(repoPath = repoPath, preserveSelection = false)))
  }

  fun finishSelectRepoPath(result: RepoLoadResult): SkillBillState {
    val active = result.request.token == activeOperationToken
    val state = finishRepoLoad(result)
    if (active && state.repoStatus.state == RepoLoadState.LOADED) {
      state.selectedRepoPath?.let(recentRepoRepository::rememberRepoPath)
    }
    return state
  }

  fun beginReturnToInstalledWorkspace(): SkillBillState {
    val installedRoot = installedWorkspaceRoot ?: return currentState
    if (isEditorDirty()) {
      dirtyEditorPrompt = DirtyEditorPrompt(
        reason = DirtyEditorPromptReason.RETURN_TO_INSTALLED_WORKSPACE,
        targetRepoPath = installedRoot,
      )
      currentState = createState()
      return currentState
    }
    repoPathText = installedRoot
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.OPEN_REPO
    currentState = createState()
    return currentState
  }

  fun selectTreeItem(itemId: String): SkillBillState {
    if (isEditorDirty() && itemId != selectedTreeItemId) {
      dirtyEditorPrompt = DirtyEditorPrompt(
        reason = DirtyEditorPromptReason.SELECTION_CHANGE,
        targetTreeItemId = itemId,
      )
      currentState = createState()
      return currentState
    }
    return selectTreeItemIgnoringDirty(itemId)
  }

  fun resolveGeneratedArtifactTreeItemId(artifactPath: String): String? = skillTreeService
    .resolveGeneratedArtifactTreeItemId(currentSession, artifactPath)
    ?.takeIf(::containsTreeItem)

  private fun selectTreeItemIgnoringDirty(itemId: String): SkillBillState {
    val previousSelectedTreeItemId = selectedTreeItemId
    selectedTreeItemId = itemId.takeIf(::containsTreeItem)
    selectedTreeItemId?.let { selected -> expandedNodeIds = expandedNodeIds + ancestorIdsOf(selected) }
    if (selectedTreeItemId != previousSelectedTreeItemId) {
      // F-202: render output is keyed by tree-item id, so the prior selection's PASSED/FAILED
      // summary must not bleed into a new selection. Mirror the repo-switch reset (F-103).
      resetRenderForSelectionChange()
    }
    loadEditorForSelection()
    currentState = createState()
    return currentState
  }

  fun refresh(): SkillBillState {
    beginRefresh()
    return finishRefresh()
  }

  /**
   * F-403/F-406: triplet begin/run/finish entry points so scaffold-driven refresh can hop off the
   * UI dispatcher without blocking, and the dirty-editor gate (which is meaningful for
   * user-initiated refreshes) does NOT short-circuit a refresh that the scaffold runtime just
   * triggered — the scaffold already mutated the repo, so the gate's "do not lose unsaved edits"
   * invariant does not apply here.
   */
  fun beginRefreshAfterScaffold(): RepoLoadRequest {
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.REFRESH
    currentState = createState()
    val path = currentSession?.repoPath ?: repoPathText
    return repoLoadRequest(repoPath = path, preserveSelection = true)
  }

  fun finishRefreshAfterScaffold(result: RepoLoadResult): SkillBillState = finishRepoLoad(result)

  /**
   * Synchronous helper kept for tests and code paths that already hold the result on a worker
   * dispatcher. Bypasses the dirty-editor gate (see [beginRefreshAfterScaffold]). The route MUST
   * use the begin/run/finish triplet to avoid blocking the UI dispatcher.
   */
  fun refreshAfterScaffold(): SkillBillState {
    val request = beginRefreshAfterScaffold()
    return finishRefreshAfterScaffold(loadRepo(request))
  }

  fun beginRefresh(): SkillBillState {
    activeOperationToken += 1
    currentState = createState()
    return currentState
  }

  fun finishRefresh(): SkillBillState {
    if (dirtyEditorPrompt != null) {
      return currentState
    }
    val path = currentSession?.repoPath ?: repoPathText
    return finishRefresh(loadRepo(repoLoadRequest(repoPath = path, preserveSelection = true)))
  }

  fun finishRefresh(result: RepoLoadResult): SkillBillState {
    if (result.request.token != activeOperationToken) {
      return currentState
    }
    applyRefreshResult(result)
    currentState = createState()
    return currentState
  }

  fun toggleExpanded(itemId: String): SkillBillState {
    expandedNodeIds =
      if (itemId in expandedNodeIds) {
        expandedNodeIds - itemId
      } else {
        expandedNodeIds + itemId
      }
    currentState = createState()
    return currentState
  }

  fun moveSelection(delta: Int): SkillBillState {
    val previousSelectedTreeItemId = selectedTreeItemId
    val visibleIds = treeItems.visibleItems(expandedNodeIds).map(SkillBillTreeItem::id)
    if (visibleIds.isEmpty()) {
      if (isEditorDirty() && selectedTreeItemId != null) {
        dirtyEditorPrompt = DirtyEditorPrompt(reason = DirtyEditorPromptReason.SELECTION_CHANGE)
        currentState = createState()
        return currentState
      }
      selectedTreeItemId = null
      if (selectedTreeItemId != previousSelectedTreeItemId) {
        // F-202: selection changed (cleared); render output is no longer for this selection.
        resetRenderForSelectionChange()
      }
      resetEditorDocument()
      currentState = createState()
      return currentState
    }
    val currentIndex = visibleIds.indexOf(selectedTreeItemId).takeIf { it >= 0 }
    val nextIndex =
      when (currentIndex) {
        null -> if (delta >= 0) 0 else visibleIds.lastIndex
        else -> (currentIndex + delta).coerceIn(0, visibleIds.lastIndex)
      }
    val nextSelection = visibleIds[nextIndex]
    if (isEditorDirty() && nextSelection != selectedTreeItemId) {
      dirtyEditorPrompt = DirtyEditorPrompt(
        reason = DirtyEditorPromptReason.SELECTION_CHANGE,
        targetTreeItemId = nextSelection,
      )
      currentState = createState()
      return currentState
    }
    selectedTreeItemId = nextSelection
    if (selectedTreeItemId != previousSelectedTreeItemId) {
      // F-202: selection changed via keyboard movement; reset render to mirror selectTreeItem.
      resetRenderForSelectionChange()
    }
    loadEditorForSelection()
    currentState = createState()
    return currentState
  }

  private fun resetRenderForSelectionChange() {
    render = RenderSummary.unavailable
    if (activeDockTab == DockTab.Console) {
      activeDockTab = DockTab.Validation
    }
    // F-202 mirror: per-selection-keyed slices (selected diff, history-filtered-by-path) must also
    // reset when the tree selection changes, otherwise stale diffs/history rows attach to the new
    // selection. Invalidate any in-flight git diff/history work so late results cannot overwrite.
    activeGitDiffToken += 1
    activeHistoryToken += 1
    selectedChangedFilePath = null
    selectedDiff = ""
    selectedDiffStaged = false
    selectedDiffBusy = false
    historyPathFilter = null
    historyErrorMessage = null
  }

  fun chooseRepoDirectory(repoPath: String?): SkillBillState {
    val selectedPath = repoPath?.trim().orEmpty()
    if (selectedPath.isBlank()) {
      busyOperation = null
      currentState = createState()
      return currentState
    }
    repoPathText = selectedPath
    return selectRepoPath(selectedPath)
  }

  fun beginChooseRepoDirectory(): SkillBillState {
    if (isEditorDirty()) {
      dirtyEditorPrompt = DirtyEditorPrompt(reason = DirtyEditorPromptReason.CHOOSE_DIRECTORY)
      currentState = createState()
      return currentState
    }
    return busyState(SkillBillBusyOperation.CHOOSE_DIRECTORY)
  }

  fun busyState(operation: SkillBillBusyOperation): SkillBillState {
    activeOperationToken += 1
    busyOperation = operation
    currentState = createState()
    return currentState
  }

  fun repoLoadRequest(repoPath: String, preserveSelection: Boolean): RepoLoadRequest = RepoLoadRequest(
    token = activeOperationToken,
    repoPath = repoPath.trim(),
    preserveSelection = preserveSelection,
    previousRepoPath = currentSession?.repoPath,
    previousSelection = selectedTreeItemId,
    previousExpandedNodeIds = expandedNodeIds,
  )

  fun loadRepo(request: RepoLoadRequest): RepoLoadResult {
    val session = repoSessionService.open(request.repoPath)
    val loadedTreeItems = skillTreeService.treeFor(session).map(SkillBillTreeItem::snapshot)
    return RepoLoadResult(
      request = request,
      session = session,
      treeItems = loadedTreeItems,
    )
  }

  fun finishRepoLoad(result: RepoLoadResult): SkillBillState {
    if (result.request.token != activeOperationToken) {
      return currentState
    }
    applyRepoLoadResult(result)
    currentState = createState()
    return currentState
  }

  private fun openRepo(repoPath: String, preserveSelection: Boolean): SkillBillState =
    finishRepoLoad(loadRepo(repoLoadRequest(repoPath = repoPath, preserveSelection = preserveSelection)))

  private fun isInstalledWorkspaceRoot(repoPath: String?): Boolean {
    val normalizedRoot = normalizedInstalledWorkspaceRoot ?: return false
    val candidate = repoPath?.takeIf { it.isNotBlank() }?.let(::normalizeRepoPath) ?: return false
    return candidate == normalizedRoot
  }

  private fun applyRefreshResult(result: RepoLoadResult) {
    val request = result.request
    val session = result.session
    val sameRecognizedRepo =
      request.preserveSelection &&
        session.isRecognizedSkillBillRepo &&
        request.previousRepoPath == session.repoPath
    if (!sameRecognizedRepo) {
      applyRepoLoadResult(result)
      return
    }

    val loadedTreeItems = result.treeItems
    val previousSelection = selectedTreeItemId
    val preserveDirtyEditor = isEditorDirty()
    currentSession = session
    treeItems = loadedTreeItems
    repoPathText = session.repoPath.ifBlank { request.repoPath }
    selectedTreeItemId = request.previousSelection?.takeIf(::containsTreeItem)
    expandedNodeIds = reconcileExpandedNodeIds(
      request.previousExpandedNodeIds,
      loadedTreeItems,
      preserveExpansion = true,
    )
    busyOperation = null
    if (selectedTreeItemId != previousSelection) {
      resetRenderForSelectionChange()
      loadEditorForSelection()
    } else if (!preserveDirtyEditor) {
      loadEditorForSelection()
    }
  }

  private fun applyRepoLoadResult(result: RepoLoadResult) {
    val request = result.request
    val session = result.session
    val loadedTreeItems = result.treeItems
    currentSession = session
    treeItems = loadedTreeItems
    repoPathText = session.repoPath.ifBlank { request.repoPath }
    val sameRepo = session.isRecognizedSkillBillRepo && request.previousRepoPath == session.repoPath
    val preserveSameRepoUi = request.preserveSelection && sameRepo
    selectedTreeItemId =
      request.previousSelection
        ?.takeIf { preserveSameRepoUi }
        ?.takeIf(::containsTreeItem)
    resetEditorDocument()
    expandedNodeIds =
      reconcileExpandedNodeIds(request.previousExpandedNodeIds, loadedTreeItems, preserveSameRepoUi)
    busyOperation = null
    // Full repo-load resets validation: repo identity or validity may have changed since the last run,
    // so prior PASSED/FAILED results are no longer trustworthy. (F-103)
    validation = ValidationSummary.unavailable
    // F-103: render output mirrors full repo-load state and must reset on repo-switch.
    render = RenderSummary.unavailable
    activeDockTab = if (preserveSameRepoUi) activeDockTab else DockTab.Validation
    // F-103: every per-snapshot git slice mirrors full repo-load state and must reset on repo-switch.
    // Invalidate any in-flight git work so a late finish cannot reseed the stale slice on the new repo.
    activeGitOperationToken += 1
    activeGitDiffToken += 1
    activeHistoryToken += 1
    activeCommitToken += 1
    activePushToken += 1
    activePublishToken += 1
    changesSnapshot = ChangesSnapshot.empty
    selectedPublishPaths = emptySet()
    publishSelectionInitialized = false
    publishSelectionDirty = false
    currentBranchLabel = null
    changesBusy = false
    selectedChangedFilePath = null
    selectedDiff = ""
    selectedDiffStaged = false
    selectedDiffBusy = false
    history = emptyList()
    historyBusy = false
    historyErrorMessage = null
    historyPathFilter = null
    commitMessage = ""
    publishPrTitle = ""
    publishPrBody = ""
    publishDraft = true
    publishBusy = false
    publishErrorMessage = null
    publishLink = null
    commitBusy = false
    commitErrorMessage = null
    commitValidationFailed = false
    failedValidationStagedAuthoredPaths = null
    commitValidationRunning = false
    publishingStatus = GitPublishingStatus.empty
    pushBusy = false
    pushErrorMessage = null
    canonicalPushConfirmationRequired = false
    canonicalPushConfirmationTarget = null
    scaffoldWizard = null
    activeScaffoldToken += 1
    // SKILL-46: any open delete dialog and any captured validate-agent-configs output are scoped
    // to the previous repo; clear both so a repo-switch never bleeds stale state.
    confirmDeletion = null
    activeRemovalToken += 1
    validateAgentConfigsSummary = ValidateAgentConfigsSummary.empty
    activeValidateAgentConfigsToken += 1
    loadEditorForSelection()
  }

  private fun createState(): SkillBillState {
    val session = currentSession
    val resolvedTreeItemId = selectedTreeItemId?.takeIf(::containsTreeItem)
    // F-C701: derive source-control status from cached branch label, not by forking git on the UI
    // thread every time state() is called. `gitGateway.statusFor` is now a pure derivation that
    // does NOT shell out — it returns a placeholder branch label that we override with the cached
    // value populated by finishGitRefresh.
    val baseStatus = gitGateway.statusFor(session)
    val sourceControl = when {
      session == null -> baseStatus
      !session.isRecognizedSkillBillRepo -> baseStatus
      else -> baseStatus.copy(branchLabel = currentBranchLabel ?: baseStatus.branchLabel)
    }
    val editor = editorForSelection(resolvedTreeItemId)
    // F-201: capture the snapshot reference once so the file lookup and the state assembly see a
    // consistent slice even if refresh()/openRepo() rewrites changesSnapshot between reads.
    val capturedSnapshot = changesSnapshot
    val resolvedSelectedFile = selectedChangedFilePath?.let { path ->
      capturedSnapshot.skillContentFiles.firstOrNull { file -> file.path == path }
    }
    val state = SkillBillState(
      selectedRepoPath = session?.repoPath,
      repoPathText = repoPathText,
      repoStatus = session?.loadStatus ?: RepoLoadStatus.empty,
      treeItems = treeItems,
      selectedTreeItemId = resolvedTreeItemId,
      expandedNodeIds = expandedNodeIds,
      busyOperation = busyOperation,
      editor = editor,
      sourceControl = sourceControl,
      statusBar = statusBarFor(session?.repoPath, sourceControl.branchLabel, editor),
      validation = validation,
      render = render,
      activeDockTab = activeDockTab,
      renderable = isRenderableKind(editor.kind),
      changes = capturedSnapshot,
      changesBusy = changesBusy,
      selectedChangedFile = resolvedSelectedFile,
      selectedDiff = selectedDiff,
      selectedDiffBusy = selectedDiffBusy,
      history = history,
      historyBusy = historyBusy,
      historyErrorMessage = historyErrorMessage,
      historyPathFilter = historyPathFilter,
      commitMessage = commitMessage,
      canCommit = canCommit(),
      selectedPublishPaths = selectedPublishPaths,
      publishPrTitle = publishPrTitle,
      publishPrBody = publishPrBody,
      publishDraft = publishDraft,
      canPublish = canPublish(),
      publishDisabledReason = publishDisabledReason(),
      publishBusy = publishBusy,
      publishErrorMessage = publishErrorMessage,
      publishLink = publishLink,
      commitBusy = commitBusy,
      commitErrorMessage = commitErrorMessage,
      commitValidationFailed = commitValidationFailed,
      commitValidationRunning = commitValidationRunning,
      pushTarget = publishingStatus.pushTarget,
      aheadBehind = publishingStatus.aheadBehind,
      compareUrl = publishingStatus.compareUrl,
      pushBusy = pushBusy,
      pushErrorMessage = pushErrorMessage,
      // AC5: when the installed workspace has no remote, suppress the "No Git remotes are
      // configured." error from the push surface. The affordance is hidden (pushTarget == null)
      // which is sufficient — surfacing an error on top would be confusing for a local workspace.
      pushStatusErrorMessage = if (publishingStatus.pushTarget == null && isInstalledWorkspaceRoot(session?.repoPath)) {
        null
      } else {
        publishingStatus.errorMessage
      },
      canonicalPushConfirmationRequired = canonicalPushConfirmationRequired,
      canReturnToInstalledWorkspace =
      installedWorkspaceRoot != null && !isInstalledWorkspaceRoot(session?.repoPath),
      dirtyEditorPrompt = dirtyEditorPrompt,
    )
    val paletteState = buildCommandPaletteState(
      state = state,
      open = commandPaletteOpen,
      query = commandPaletteQuery,
      selectedResultIndex = commandPaletteSelectedResultIndex,
    )
    commandPaletteSelectedResultIndex = paletteState.selectedResultIndex
    val suggestedBaselineLayer = scaffoldWizard?.let(::suggestedBaselineLayer)
    val wizardState = scaffoldWizard?.copy(
      dirtyRepoWarning = computeDirtyRepoWarning(capturedSnapshot),
      baselineLayerSuggestion = suggestedBaselineLayer?.form,
      baselineLayerSuggestionLabel = suggestedBaselineLayer?.label,
    )
    return state.copy(
      commandPalette = paletteState,
      scaffoldWizard = wizardState,
      firstRunSetup = firstRunSetup,
      postPublishReinstall = postPublishReinstall,
      confirmDeletion = confirmDeletion,
      validateAgentConfigs = validateAgentConfigsSummary,
      partialMutationPostMortem = partialMutationPostMortem,
    )
  }

  private fun computeDirtyRepoWarning(snapshot: ChangesSnapshot): Boolean =
    snapshot.files.any { file -> file.group != ChangedFileGroup.GENERATED }

  fun beginFirstRunDiscovery(): FirstRunDiscoveryRequest? {
    val setup = firstRunSetup ?: return null
    if (setup.busy || setup.discoveryLoaded || setup.errorMessage != null) {
      return null
    }
    activeFirstRunToken += 1
    firstRunSetup = setup.copy(busy = true, errorMessage = null)
    currentState = createState()
    return FirstRunDiscoveryRequest(token = activeFirstRunToken)
  }

  fun openFirstRunSetup(): SkillBillState {
    if (busyOperation != null || scaffoldWizard != null || firstRunSetup != null || postPublishReinstall != null) {
      return currentState
    }
    firstRunSetup = latestInstallSetupRequest()?.toFirstRunSetupState() ?: FirstRunSetupState()
    currentState = createState()
    return currentState
  }

  suspend fun runFirstRunDiscovery(request: FirstRunDiscoveryRequest): FirstRunDiscoveryResponse =
    FirstRunDiscoveryResponse(
      request = request,
      result = firstRunGateway.discoverSetup(),
    )

  fun finishFirstRunDiscovery(response: FirstRunDiscoveryResponse): SkillBillState {
    if (response.request.token != activeFirstRunToken) {
      return currentState
    }
    val setup = firstRunSetup ?: return currentState
    firstRunSetup = when (val result = response.result) {
      is FirstRunDiscoveryResult.Success -> setup.applyDiscovery(
        discovery = result.discovery,
        preferredRequest = latestInstallSetupRequest(),
      )
      is FirstRunDiscoveryResult.Failed -> setup.copy(
        busy = false,
        errorMessage = result.message,
      )
    }
    currentState = createState()
    return currentState
  }

  fun selectFirstRunAgent(agentId: String, selected: Boolean): SkillBillState {
    val setup = firstRunSetup ?: return currentState
    if (setup.busy) {
      return currentState
    }
    val selectedAgents = if (selected) {
      setup.selectedAgentIds + agentId
    } else {
      setup.selectedAgentIds - agentId
    }
    firstRunSetup = setup.copy(
      selectedAgentIds = selectedAgents,
      agentOptions = setup.agentOptions.map { option ->
        if (option.agentId == agentId) option.copy(selected = selected) else option
      },
      plan = null,
      outcome = null,
      errorMessage = null,
    )
    currentState = createState()
    return currentState
  }

  fun selectFirstRunPlatform(slug: String, selected: Boolean): SkillBillState {
    val setup = firstRunSetup ?: return currentState
    if (setup.busy) {
      return currentState
    }
    val selectedPlatformSlugs = if (selected) {
      setup.selectedPlatformSlugs + slug
    } else {
      setup.selectedPlatformSlugs - slug
    }
    firstRunSetup = setup.copy(
      selectedPlatformSlugs = selectedPlatformSlugs,
      platformSelectionMode = selectedPlatformSlugs.toFirstRunPlatformSelectionMode(),
      platformPacks = setup.platformPacks.map { pack ->
        if (pack.slug == slug) pack.copy(selected = selected) else pack
      },
      plan = null,
      outcome = null,
      errorMessage = null,
    )
    currentState = createState()
    return currentState
  }

  fun selectFirstRunTelemetry(level: FirstRunTelemetryLevel): SkillBillState {
    val setup = firstRunSetup ?: return currentState
    if (!setup.busy) {
      firstRunSetup = setup.copy(telemetryLevel = level, plan = null, outcome = null, errorMessage = null)
      currentState = createState()
    }
    return currentState
  }

  fun advanceFirstRunStep(): SkillBillState {
    val setup = firstRunSetup ?: return currentState
    if (!setup.canContinue || setup.step == FirstRunSetupStep.RESULT) {
      return currentState
    }
    firstRunSetup = setup.copy(step = setup.step.next())
    currentState = createState()
    return currentState
  }

  fun retreatFirstRunStep(): SkillBillState {
    val setup = firstRunSetup ?: return currentState
    if (setup.busy) {
      return currentState
    }
    firstRunSetup = setup.copy(step = setup.step.previous(), errorMessage = null)
    currentState = createState()
    return currentState
  }

  fun beginFirstRunApply(): FirstRunApplyRequest? {
    val setup = firstRunSetup ?: return null
    if (!setup.canContinue || setup.busy) {
      return null
    }
    activeFirstRunToken += 1
    firstRunSetup = setup.copy(
      step = FirstRunSetupStep.APPLY,
      busy = true,
      errorMessage = null,
      outcome = null,
    )
    currentState = createState()
    return FirstRunApplyRequest(
      token = activeFirstRunToken,
      setupRequest = setup.request(),
    )
  }

  suspend fun runFirstRunApply(request: FirstRunApplyRequest): FirstRunApplyResponse {
    val planResult = firstRunGateway.planSetup(request.setupRequest)
    val applyResult = when (planResult) {
      is FirstRunPlanResult.Planned -> firstRunGateway.applySetup(planResult.plan)
      is FirstRunPlanResult.Failed -> null
    }
    return FirstRunApplyResponse(
      request = request,
      planResult = planResult,
      applyResult = applyResult,
    )
  }

  fun finishFirstRunApply(response: FirstRunApplyResponse): SkillBillState {
    if (response.request.token != activeFirstRunToken) {
      return currentState
    }
    val setup = firstRunSetup ?: return currentState
    val nextSetup = when (val planResult = response.planResult) {
      is FirstRunPlanResult.Failed -> setup.copy(
        step = FirstRunSetupStep.RESULT,
        busy = false,
        errorMessage = planResult.message,
        outcome = FirstRunInstallOutcome(
          status = FirstRunInstallStatus.FAILURE,
          title = "Install planning failed.",
        ),
      )
      is FirstRunPlanResult.Planned -> {
        val outcome = when (val applyResult = response.applyResult) {
          is FirstRunApplyResult.Applied -> applyResult.outcome
          is FirstRunApplyResult.Failed -> applyResult.outcome
          null -> FirstRunInstallOutcome(
            status = FirstRunInstallStatus.FAILURE,
            title = "Install did not run.",
          )
        }
        setup.copy(
          step = FirstRunSetupStep.RESULT,
          busy = false,
          plan = planResult.plan,
          outcome = outcome,
          errorMessage = outcome.takeIf { it.status == FirstRunInstallStatus.FAILURE }?.title,
        )
      }
    }
    firstRunSetup = nextSetup
    if (nextSetup.outcome?.status == FirstRunInstallStatus.FAILURE) {
      desktopPreferenceStore.saveFirstRunPreferences(DesktopFirstRunPreferences(completed = false))
    } else {
      desktopPreferenceStore.markFirstRunCompleted(DesktopFirstRunPreferences())
    }
    currentState = createState()
    return currentState
  }

  fun finishFirstRunSetup(): SkillBillState {
    val setup = firstRunSetup ?: return currentState
    if (setup.busy) return currentState
    if (setup.outcome?.status != FirstRunInstallStatus.FAILURE) {
      firstRunSetup = null
      busyOperation = null
      if (installedWorkspaceRoot == null) {
        val freshRoot = installedWorkspaceLocator.locate().takeIf { it.availability }?.path?.takeIf { it.isNotBlank() }
        installedWorkspaceRoot = freshRoot
        normalizedInstalledWorkspaceRoot = freshRoot?.let(::normalizeRepoPath)
      }
      currentState = if (installedWorkspaceRoot != null) {
        openRepo(installedWorkspaceRoot!!, preserveSelection = false)
      } else {
        createState()
      }
    }
    return currentState
  }

  fun dismissFirstRunSetup(): SkillBillState {
    val setup = firstRunSetup ?: return currentState
    if (setup.busy) {
      return currentState
    }
    firstRunSetup = null
    currentState = createState()
    return currentState
  }

  fun retryFirstRunSetup(): SkillBillState {
    val setup = firstRunSetup ?: return currentState
    firstRunSetup = setup.copy(
      step = FirstRunSetupStep.AGENTS,
      busy = false,
      errorMessage = null,
      plan = null,
      outcome = null,
    )
    currentState = createState()
    return currentState
  }

  fun openCommandPalette(): SkillBillState {
    commandPaletteOpen = true
    commandPaletteSelectedResultIndex = 0
    currentState = createState()
    return currentState
  }

  fun closeCommandPalette(): SkillBillState {
    commandPaletteOpen = false
    currentState = createState()
    return currentState
  }

  fun updateCommandPaletteQuery(query: String): SkillBillState {
    commandPaletteQuery = query
    commandPaletteSelectedResultIndex = 0
    currentState = createState()
    return currentState
  }

  fun moveCommandPaletteSelection(delta: Int): SkillBillState {
    val lastIndex = currentState.commandPalette.results.lastIndex
    commandPaletteSelectedResultIndex =
      if (lastIndex < 0) {
        0
      } else {
        (commandPaletteSelectedResultIndex + delta).coerceIn(0, lastIndex)
      }
    currentState = createState()
    return currentState
  }

  fun updateEditorDraft(text: String): SkillBillState {
    val document = loadedEditorDocument
    if (
      document?.editable != true ||
      editorSaveInProgress ||
      busyOperation != null ||
      changesBusy ||
      publishBusy ||
      commitBusy ||
      commitValidationRunning ||
      pushBusy
    ) {
      currentState = createState()
      return currentState
    }
    editorDraftText = text
    editorSaveErrorMessage = null
    currentState = createState()
    return currentState
  }

  fun revertEditorDraft(): SkillBillState {
    if (editorSaveInProgress) {
      currentState = createState()
      return currentState
    }
    loadEditorForSelection()
    editorSaveErrorMessage = null
    dirtyEditorPrompt = null
    currentState = createState()
    return currentState
  }

  fun beginSaveEditor(): EditorSaveRequest? {
    val document = loadedEditorDocument
    if (
      document?.editable != true ||
      !isEditorDirty() ||
      editorSaveInProgress ||
      busyOperation != null ||
      changesBusy ||
      publishBusy ||
      commitBusy ||
      commitValidationRunning ||
      pushBusy
    ) {
      currentState = createState()
      return null
    }
    activeSaveToken += 1
    editorSaveInProgress = true
    editorSaveErrorMessage = null
    busyOperation = SkillBillBusyOperation.SAVE
    currentState = createState()
    return EditorSaveRequest(
      token = activeSaveToken,
      session = currentSession,
      treeItemId = selectedTreeItemId,
      body = editorDraftText,
    )
  }

  fun runSaveEditor(request: EditorSaveRequest): EditorSaveResult {
    val result = runCatching {
      authoringGateway.saveDocument(request.session, request.treeItemId, request.body)
    }.getOrElse { error -> AuthoringSaveResult.failed(describe(error)) }
    return EditorSaveResult(request = request, result = result)
  }

  fun finishSaveEditor(result: EditorSaveResult): SkillBillState {
    if (result.request.token != activeSaveToken) {
      return currentState
    }
    editorSaveInProgress = false
    busyOperation = null
    if (result.result.success) {
      val savedDocument = result.result.document
        ?: authoringGateway.loadDocument(currentSession, selectedTreeItemId)
      loadedEditorDocument = savedDocument
      editorSelectionId = result.request.treeItemId
      editorDraftText = savedDocument.text
      editorSaveErrorMessage = null
      dirtyEditorPrompt = null
    } else {
      editorSaveErrorMessage = result.result.runtimeErrorMessage ?: "Save failed."
    }
    currentState = createState()
    return currentState
  }

  fun cancelDirtyEditorPrompt(): SkillBillState {
    dirtyEditorPrompt = null
    currentState = createState()
    return currentState
  }

  fun discardDirtyEditorPrompt(): SkillBillState {
    val prompt = dirtyEditorPrompt ?: return currentState
    dirtyEditorPrompt = null
    when (prompt.reason) {
      DirtyEditorPromptReason.SELECTION_CHANGE -> {
        prompt.targetTreeItemId
          ?.let { target -> selectTreeItemIgnoringDirty(target) }
          ?: run {
            selectedTreeItemId = null
            resetRenderForSelectionChange()
            resetEditorDocument()
          }
      }
      DirtyEditorPromptReason.REFRESH -> {
        resetEditorDocument()
        beginRefresh()
      }
      DirtyEditorPromptReason.REPO_SWITCH -> {
        resetEditorDocument()
        prompt.targetRepoPath?.let(::beginSelectRepoPath)
      }
      DirtyEditorPromptReason.RETURN_TO_INSTALLED_WORKSPACE -> {
        resetEditorDocument()
        beginReturnToInstalledWorkspace()
      }
      DirtyEditorPromptReason.CHOOSE_DIRECTORY -> {
        resetEditorDocument()
        busyState(SkillBillBusyOperation.CHOOSE_DIRECTORY)
      }
    }
    currentState = createState()
    return currentState
  }

  fun updateCommitMessage(message: String): SkillBillState {
    if (commitBusy || commitValidationRunning || publishBusy) {
      currentState = createState()
      return currentState
    }
    commitMessage = message
    commitErrorMessage = null
    publishErrorMessage = null
    if (commitValidationFailed) {
      commitValidationFailed = false
      failedValidationStagedAuthoredPaths = null
    }
    currentState = createState()
    return currentState
  }

  fun setPublishPathSelected(path: String, selected: Boolean): SkillBillState {
    if (publishBusy || commitBusy || commitValidationRunning || pushBusy || changesBusy) {
      currentState = createState()
      return currentState
    }
    selectedPublishPaths = changesSnapshot.skillContentFiles.map(ChangedFile::path).toSet()
    publishSelectionInitialized = true
    publishSelectionDirty = false
    publishErrorMessage = null
    if (commitValidationFailed) {
      commitValidationFailed = false
      failedValidationStagedAuthoredPaths = null
    }
    currentState = createState()
    return currentState
  }

  fun updatePublishPrTitle(title: String): SkillBillState {
    if (publishBusy || commitBusy || commitValidationRunning || pushBusy) {
      currentState = createState()
      return currentState
    }
    publishPrTitle = title
    publishErrorMessage = null
    currentState = createState()
    return currentState
  }

  fun updatePublishPrBody(body: String): SkillBillState {
    if (publishBusy || commitBusy || commitValidationRunning || pushBusy) {
      currentState = createState()
      return currentState
    }
    publishPrBody = body
    publishErrorMessage = null
    currentState = createState()
    return currentState
  }

  fun setPublishDraft(draft: Boolean): SkillBillState {
    if (publishBusy || commitBusy || commitValidationRunning || pushBusy) {
      currentState = createState()
      return currentState
    }
    publishDraft = draft
    publishErrorMessage = null
    currentState = createState()
    return currentState
  }

  fun beginCommit(allowFailedValidation: Boolean = false): CommitRunRequest? {
    val hasCommitInputs = hasCommitInputs()
    if (
      !hasCommitInputs ||
      busyOperation != null ||
      changesBusy ||
      pushBusy ||
      publishBusy ||
      (!allowFailedValidation && !canCommit()) ||
      (allowFailedValidation && !hasCurrentFailedValidationOverride())
    ) {
      currentState = createState()
      return null
    }
    activeCommitToken += 1
    val previousValidationSummary = validation
    commitBusy = true
    commitValidationRunning = true
    commitErrorMessage = null
    commitValidationFailed = false
    failedValidationStagedAuthoredPaths = null
    validation = ValidationSummary(state = ValidationRunState.RUNNING)
    currentState = createState()
    return CommitRunRequest(
      token = activeCommitToken,
      session = currentSession,
      message = commitMessage,
      allowFailedValidation = allowFailedValidation,
      previousValidationSummary = previousValidationSummary,
      previousSnapshot = changesSnapshot,
      previousHistory = history,
      previousHistoryErrorMessage = historyErrorMessage,
      historyLimit = DEFAULT_HISTORY_LIMIT,
      historyPathFilter = historyPathFilter,
    )
  }

  fun runCommit(request: CommitRunRequest): CommitRunResult {
    val validationOutcome = runCatching { validationGateway.validate(request.session) }
    val validationSummary = validationOutcome.getOrElse { error ->
      ValidationSummary(
        state = ValidationRunState.FAILED,
        runtimeExceptionName = error::class.simpleName ?: error::class.qualifiedName ?: "Throwable",
        runtimeExceptionMessage = error.message,
      )
    }
    val validationFailed = validationSummary.failedForCommit()
    if (validationFailed && !request.allowFailedValidation) {
      return CommitRunResult(
        request = request,
        validationSummary = validationSummary,
        validationBlockedCommit = true,
      )
    }
    val commitResult = runCatching { gitGateway.commit(request.session, request.message) }
      .getOrElse { error -> GitOperationResult.failed(describe(error)) }
    if (!commitResult.success) {
      return CommitRunResult(
        request = request,
        validationSummary = validationSummary,
        commitResult = commitResult,
      )
    }
    return CommitRunResult(
      request = request,
      validationSummary = validationSummary,
      commitResult = commitResult,
      refreshedSnapshot = runCatching { gitGateway.snapshotFor(request.session) }
        .getOrElse { error -> ChangesSnapshot.failed(describe(error)) },
      refreshedHistory = runCatching {
        gitGateway.recentCommits(request.session, request.historyLimit, request.historyPathFilter)
      }.getOrDefault(request.previousHistory),
      refreshedHistoryErrorMessage = null,
      refreshedPublishingStatus = runCatching { gitGateway.publishingStatus(request.session) }
        .getOrElse { error -> GitPublishingStatus(errorMessage = describe(error)) },
    )
  }

  fun finishCommit(result: CommitRunResult): SkillBillState {
    if (result.request.token != activeCommitToken) {
      return currentState
    }
    commitBusy = false
    commitValidationRunning = false
    validation = result.validationSummary
    if (result.validationBlockedCommit) {
      commitValidationFailed = true
      failedValidationStagedAuthoredPaths = stagedAuthoredPaths(changesSnapshot)
      commitErrorMessage = null
      currentState = createState()
      return currentState
    }
    val commitResult = result.commitResult
    if (commitResult?.success == true) {
      commitMessage = ""
      commitErrorMessage = null
      commitValidationFailed = false
      failedValidationStagedAuthoredPaths = null
      result.refreshedSnapshot?.let { snapshot ->
        if (snapshot.isFailed) {
          changesSnapshot = changesSnapshot.copy(errorMessage = snapshot.errorMessage)
        } else {
          changesSnapshot = snapshot
          reconcilePublishSelection(snapshot)
          if (snapshot.branchLabel.isNotEmpty()) {
            currentBranchLabel = snapshot.branchLabel
          }
        }
      }
      result.refreshedHistory?.let { commits -> history = commits }
      historyErrorMessage = result.refreshedHistoryErrorMessage
      result.refreshedPublishingStatus?.let { status -> replacePublishingStatus(status, clearConfirmation = true) }
    } else {
      commitErrorMessage = commitResult?.errorMessage ?: "Commit failed."
      commitValidationFailed = false
      failedValidationStagedAuthoredPaths = null
    }
    currentState = createState()
    return currentState
  }

  fun beginValidate(): ValidationRunRequest {
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.VALIDATE
    val previousValidationSummary = validation
    validation = ValidationSummary(state = ValidationRunState.RUNNING)
    currentState = createState()
    return ValidationRunRequest(
      token = activeOperationToken,
      session = currentSession,
      selectedOnly = false,
      treeItemId = null,
      previousValidationSummary = previousValidationSummary,
    )
  }

  fun beginValidateSelected(): ValidationRunRequest {
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.VALIDATE
    val previousValidationSummary = validation
    validation = ValidationSummary(state = ValidationRunState.RUNNING)
    activeDockTab = DockTab.Validation
    currentState = createState()
    return ValidationRunRequest(
      token = activeOperationToken,
      session = currentSession,
      selectedOnly = true,
      treeItemId = selectedTreeItemId,
      previousValidationSummary = previousValidationSummary,
    )
  }

  fun runValidate(request: ValidationRunRequest): ValidationRunResult {
    val summary = if (request.selectedOnly) {
      request.treeItemId
        ?.let { treeItemId -> validationGateway.validateSelected(request.session, treeItemId) }
        ?: ValidationSummary.unavailable
    } else {
      validationGateway.validate(request.session)
    }
    return ValidationRunResult(request = request, summary = summary)
  }

  fun finishValidate(result: ValidationRunResult): SkillBillState {
    if (result.request.token != activeOperationToken) {
      // The validation run was preempted by a newer operation. We must unwind the RUNNING marker
      // we set in beginValidate so that the (possibly same) repo does not get stuck displaying
      // RUNNING forever. Restore the pre-validate validation slice. (F-101)
      if (validation.state == ValidationRunState.RUNNING) {
        validation = result.request.previousValidationSummary
        currentState = createState()
      }
      return currentState
    }
    busyOperation = null
    validation = result.summary
    currentState = createState()
    return currentState
  }

  fun beginRender(): RenderRunRequest {
    val selectedTarget = selectedTreeItemId?.let { id ->
      RenderTarget(
        treeItemId = id,
        label = treeItems.flatten().firstOrNull { item -> item.id == id }?.label ?: id,
      )
    }
    return beginRenderForTargets(listOfNotNull(selectedTarget))
  }

  fun beginRenderAll(): RenderRunRequest = beginRenderForTargets(
    treeItems.flatten()
      .filter { item -> item.kind.isRenderableTreeItemKind() }
      .map { item -> RenderTarget(treeItemId = item.id, label = item.label) },
  )

  private fun beginRenderForTargets(targets: List<RenderTarget>): RenderRunRequest {
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.RENDER
    val previousRenderSummary = render
    render = RenderSummary(state = RenderRunState.RUNNING)
    activeDockTab = DockTab.Console
    currentState = createState()
    return RenderRunRequest(
      token = activeOperationToken,
      session = currentSession,
      targets = targets,
      previousRenderSummary = previousRenderSummary,
    )
  }

  fun runRender(request: RenderRunRequest): RenderRunResult {
    val summary = when (request.targets.size) {
      0 -> RenderSummary.unavailable
      1 -> renderGateway.render(request.session, request.targets.single().treeItemId)
      else -> renderAllTargets(request.session, request.targets)
    }
    return RenderRunResult(request = request, summary = summary)
  }

  private fun renderAllTargets(session: RepoSession?, targets: List<RenderTarget>): RenderSummary {
    val renderedTargets = targets.map { target ->
      target to runCatching { renderGateway.render(session, target.treeItemId) }.getOrElse { error ->
        RenderSummary(
          state = RenderRunState.FAILED,
          runtimeExceptionName = error::class.simpleName ?: error::class.qualifiedName ?: "Throwable",
          runtimeExceptionMessage = error.message,
        )
      }
    }
    val failedTargetCount = renderedTargets.count { (_, summary) -> summary.state != RenderRunState.PASSED }
    return RenderSummary(
      state = if (failedTargetCount == 0) RenderRunState.PASSED else RenderRunState.FAILED,
      blocks = renderedTargets.flatMap { (target, summary) -> target.renderBlocks(summary) },
      generatedArtifacts = renderedTargets.flatMap { (_, summary) -> summary.generatedArtifacts },
      durationMillis = renderedTargets.sumOf { (_, summary) -> summary.durationMillis },
      runtimeExceptionName = if (failedTargetCount == 0) null else "RenderAllFailed",
      runtimeExceptionMessage = if (failedTargetCount == 0) null else "$failedTargetCount render target(s) failed.",
    )
  }

  fun finishRender(result: RenderRunResult): SkillBillState {
    if (result.request.token != activeOperationToken) {
      // F-101: stale finish — restore the pre-RUNNING render summary captured at beginRender so the
      // slice does not stay stuck on RUNNING after a preempting op completes.
      if (render.state == RenderRunState.RUNNING) {
        render = result.request.previousRenderSummary
        currentState = createState()
      }
      return currentState
    }
    busyOperation = null
    render = result.summary
    currentState = createState()
    return currentState
  }

  // --- Changes (git status snapshot) ---
  // Mirrors beginValidate/runValidate/finishValidate at SkillBillViewModel.kt:265-298. The triplet
  // captures all VM fields on the caller dispatcher (F-102), routes the gateway call through a
  // separate `run` step so callers can hop to Dispatchers.Default, and uses a per-slice token so
  // stale finishes restore the previously captured snapshot (F-101).

  fun beginGitRefresh(quiet: Boolean = false): GitRefreshRequest {
    activeGitOperationToken += 1
    val previousSnapshot = changesSnapshot
    if (!quiet) {
      changesBusy = true
    }
    currentState = createState()
    return GitRefreshRequest(
      token = activeGitOperationToken,
      session = currentSession,
      previousSnapshot = previousSnapshot,
      quiet = quiet,
    )
  }

  fun runGitRefresh(request: GitRefreshRequest): GitRefreshResult {
    val snapshot = runCatching { gitGateway.snapshotFor(request.session) }
      .getOrElse { error -> ChangesSnapshot(errorMessage = describe(error)) }
    val status = runCatching { gitGateway.publishingStatus(request.session) }
      .getOrElse { error -> GitPublishingStatus(errorMessage = describe(error)) }
    return GitRefreshResult(request = request, snapshot = snapshot, publishingStatus = status)
  }

  fun finishGitRefresh(result: GitRefreshResult): SkillBillState {
    if (result.request.token != activeGitOperationToken) {
      // F-A01: the changes-slice triplet (refresh/stage/unstage) shares a single token, so a newer
      // refresh kicked off after a stage would otherwise see token mismatch and silently restore
      // the pre-stage snapshot, losing the staging effect. Instead, when a newer changes-slice op
      // is in-flight, return `currentState` UNCHANGED — whichever operation finishes LAST is the
      // source of truth for the changes slice. (Diff/history triplets keep prior-snapshot restore
      // semantics because they use their own per-slice tokens.)
      return currentState
    }
    changesBusy = false
    // F-A02: stage/unstage failures return a sentinel `isFailed = true` snapshot. The VM overlays
    // the error message onto the existing snapshot rather than replacing the files list — losing
    // the prior file list on stage failure would be a worse UX than surfacing the error inline.
    if (result.snapshot.isFailed) {
      changesSnapshot = changesSnapshot.copy(errorMessage = result.snapshot.errorMessage)
      result.publishingStatus?.let { replacePublishingStatus(it, clearConfirmation = true) }
      currentState = createState()
      return currentState
    }
    // F-MAJOR-1: if a provision error was recorded for the installed workspace (AC4 — git binary
    // unavailable), preserve it in the snapshot so the error message is not silently overwritten by
    // the git-refresh result. A quiet refresh after openRepo would otherwise replace changesSnapshot
    // with an empty-or-real snapshot, losing the user-visible error from provisioning.
    val provisionError = installedWorkspaceProvisionErrorMessage
    changesSnapshot = if (provisionError != null && isInstalledWorkspaceRoot(currentSession?.repoPath)) {
      result.snapshot.copy(errorMessage = provisionError)
    } else {
      result.snapshot
    }
    reconcilePublishSelection(result.snapshot)
    invalidateFailedValidationOverrideIfStagedAuthoredChanged()
    result.publishingStatus?.let { replacePublishingStatus(it, clearConfirmation = true) }
    // F-C701: cache branch label so createState() can derive sourceControl without forking git.
    if (result.snapshot.branchLabel.isNotEmpty()) {
      currentBranchLabel = result.snapshot.branchLabel
    }
    // If the previously-selected changed file is no longer in the new snapshot, clear it so the diff
    // pane does not stay attached to a stale path.
    val stillExists = selectedChangedFilePath?.let { path ->
      result.snapshot.skillContentFiles.any { it.path == path }
    } ?: false
    if (!stillExists) {
      selectedChangedFilePath = null
      selectedDiff = ""
      selectedDiffStaged = false
      selectedDiffBusy = false
    }
    currentState = createState()
    return currentState
  }

  // Convenience wrapper for synchronous tests and route fan-out seams. Production code should use
  // begin/run/finish so the gateway call runs on Dispatchers.Default.
  fun refreshGit(): SkillBillState {
    val request = beginGitRefresh()
    return finishGitRefresh(runGitRefresh(request))
  }

  // --- Selected diff ---

  fun selectChangedFile(path: String): SelectChangedFileRequest? {
    // F-201: capture the snapshot once so the lookup is consistent even if a parallel refresh swaps
    // changesSnapshot before we resolve the file.
    val captured = changesSnapshot
    val file = captured.skillContentFiles.firstOrNull { it.path == path } ?: run {
      selectedChangedFilePath = null
      selectedDiff = ""
      selectedDiffStaged = false
      selectedDiffBusy = false
      activeGitDiffToken += 1
      currentState = createState()
      return null
    }
    selectedChangedFilePath = file.path
    selectedDiffStaged = file.group == ChangedFileGroup.STAGED
    selectedDiff = ""
    selectedDiffBusy = true
    activeGitDiffToken += 1
    currentState = createState()
    return SelectChangedFileRequest(
      token = activeGitDiffToken,
      session = currentSession,
      file = file,
      staged = selectedDiffStaged,
    )
  }

  fun runDiff(request: SelectChangedFileRequest): SelectChangedFileResult {
    val diff = runCatching { gitGateway.diffFor(request.session, request.file.path, request.staged) }
      .getOrDefault("")
    return SelectChangedFileResult(request = request, diff = diff)
  }

  fun finishDiff(result: SelectChangedFileResult): SkillBillState {
    if (result.request.token != activeGitDiffToken) {
      return currentState
    }
    selectedDiff = result.diff
    selectedDiffBusy = false
    currentState = createState()
    return currentState
  }

  // --- Stage / Unstage ---

  fun beginStage(paths: List<String>): StageRequest {
    activeGitOperationToken += 1
    val previousSnapshot = changesSnapshot
    changesBusy = true
    currentState = createState()
    return StageRequest(
      token = activeGitOperationToken,
      session = currentSession,
      paths = paths.toList(),
      previousSnapshot = previousSnapshot,
      action = StageAction.STAGE,
    )
  }

  fun beginUnstage(paths: List<String>): StageRequest {
    activeGitOperationToken += 1
    val previousSnapshot = changesSnapshot
    changesBusy = true
    currentState = createState()
    return StageRequest(
      token = activeGitOperationToken,
      session = currentSession,
      paths = paths.toList(),
      previousSnapshot = previousSnapshot,
      action = StageAction.UNSTAGE,
    )
  }

  fun beginDiscardChangedFile(path: String): StageRequest? {
    val file = changesSnapshot.skillContentFiles.firstOrNull { changedFile -> changedFile.path == path } ?: return null
    activeGitOperationToken += 1
    val previousSnapshot = changesSnapshot
    changesBusy = true
    currentState = createState()
    return StageRequest(
      token = activeGitOperationToken,
      session = currentSession,
      paths = listOf(file.path),
      previousSnapshot = previousSnapshot,
      action = StageAction.DISCARD,
    )
  }

  fun runStage(request: StageRequest): GitRefreshResult {
    val snapshot = runCatching {
      when (request.action) {
        StageAction.STAGE -> gitGateway.stage(request.session, request.paths)
        StageAction.UNSTAGE -> gitGateway.unstage(request.session, request.paths)
        StageAction.DISCARD -> gitGateway.discard(request.session, request.paths)
      }
      // F-A02: when the gateway itself throws (process failure), use the failed sentinel so the
      // VM overlays the error onto the existing snapshot rather than blanking the file list.
    }.getOrElse { error -> ChangesSnapshot.failed(describe(error)) }
    return GitRefreshResult(
      request = GitRefreshRequest(
        token = request.token,
        session = request.session,
        previousSnapshot = request.previousSnapshot,
      ),
      snapshot = snapshot,
    )
  }

  // --- Push ---

  fun beginPush(allowCanonicalRemote: Boolean = false): PushRunRequest? {
    if (busyOperation != null || pushBusy || commitBusy || commitValidationRunning || changesBusy || publishBusy) {
      currentState = createState()
      return null
    }
    val target = publishingStatus.pushTarget
    if (target == null) {
      pushErrorMessage = publishingStatus.errorMessage ?: "No push target is available."
      currentState = createState()
      return null
    }
    if (target.isLikelyCanonical && !allowCanonicalRemote) {
      canonicalPushConfirmationRequired = true
      canonicalPushConfirmationTarget = target
      pushErrorMessage = null
      currentState = createState()
      return null
    }
    val reusingCanonicalConfirmationAfterValidationFailure =
      commitValidationFailed && target.isLikelyCanonical
    if (
      allowCanonicalRemote &&
      !reusingCanonicalConfirmationAfterValidationFailure &&
      (!canonicalPushConfirmationRequired || canonicalPushConfirmationTarget != target)
    ) {
      currentState = createState()
      return null
    }
    activePushToken += 1
    pushBusy = true
    pushErrorMessage = null
    canonicalPushConfirmationRequired = false
    canonicalPushConfirmationTarget = null
    currentState = createState()
    return PushRunRequest(
      token = activePushToken,
      session = currentSession,
      target = target,
      previousPublishingStatus = publishingStatus,
    )
  }

  fun runPush(request: PushRunRequest): PushRunResult {
    val pushResult = runCatching { gitGateway.push(request.session, request.target) }
      .getOrElse { error -> GitOperationResult.failed(describe(error)) }
    if (!pushResult.success) {
      return PushRunResult(request = request, pushResult = pushResult)
    }
    return PushRunResult(
      request = request,
      pushResult = pushResult,
      refreshedPublishingStatus = runCatching { gitGateway.publishingStatus(request.session) }
        .getOrElse { error -> GitPublishingStatus(errorMessage = describe(error)) },
    )
  }

  fun finishPush(result: PushRunResult): SkillBillState {
    if (result.request.token != activePushToken) {
      return currentState
    }
    pushBusy = false
    if (result.pushResult.success) {
      pushErrorMessage = null
      canonicalPushConfirmationRequired = false
      canonicalPushConfirmationTarget = null
      result.refreshedPublishingStatus?.let { status -> replacePublishingStatus(status, clearConfirmation = true) }
    } else {
      pushErrorMessage = result.pushResult.errorMessage ?: "Push failed."
    }
    currentState = createState()
    return currentState
  }

  fun dismissPostPublishReinstall(): SkillBillState {
    val prompt = postPublishReinstall ?: return currentState
    if (prompt.busy) {
      return currentState
    }
    postPublishReinstall = null
    currentState = createState()
    return currentState
  }

  fun beginPostPublishReinstall(): PostPublishReinstallRequest? {
    val prompt = postPublishReinstall ?: return null
    if (prompt.busy) {
      return null
    }
    val setupRequest = latestInstallSetupRequest() ?: return null
    activePostPublishReinstallToken += 1
    busyOperation = SkillBillBusyOperation.REINSTALL
    postPublishReinstall = prompt.copy(busy = true, outcome = null)
    currentState = createState()
    return PostPublishReinstallRequest(
      token = activePostPublishReinstallToken,
      setupRequest = setupRequest,
    )
  }

  suspend fun runPostPublishReinstall(request: PostPublishReinstallRequest): PostPublishReinstallResponse {
    val planResult = firstRunGateway.planSetup(request.setupRequest)
    val applyResult = when (planResult) {
      is FirstRunPlanResult.Planned -> firstRunGateway.applySetup(planResult.plan)
      is FirstRunPlanResult.Failed -> null
    }
    return PostPublishReinstallResponse(
      request = request,
      planResult = planResult,
      applyResult = applyResult,
    )
  }

  fun finishPostPublishReinstall(response: PostPublishReinstallResponse): SkillBillState {
    if (response.request.token != activePostPublishReinstallToken) {
      return currentState
    }
    if (busyOperation == SkillBillBusyOperation.REINSTALL) {
      busyOperation = null
    }
    val prompt = postPublishReinstall ?: return currentState
    val outcome = when (val planResult = response.planResult) {
      is FirstRunPlanResult.Failed -> FirstRunInstallOutcome(
        status = FirstRunInstallStatus.FAILURE,
        title = "Reinstall planning failed.",
        details = listOf(
          FirstRunInstallDetail(
            label = "Install",
            message = planResult.message,
            severity = FirstRunInstallDetailSeverity.ERROR,
          ),
        ),
      )
      is FirstRunPlanResult.Planned -> when (val applyResult = response.applyResult) {
        is FirstRunApplyResult.Applied -> applyResult.outcome
        is FirstRunApplyResult.Failed -> applyResult.outcome
        null -> FirstRunInstallOutcome(
          status = FirstRunInstallStatus.FAILURE,
          title = "Reinstall did not run.",
        )
      }
    }
    postPublishReinstall = prompt.copy(busy = false, outcome = outcome)
    if (outcome.status != FirstRunInstallStatus.FAILURE) {
      desktopPreferenceStore.markFirstRunCompleted(DesktopFirstRunPreferences())
    }
    currentState = createState()
    return currentState
  }

  // --- Publish ---

  fun beginPublish(allowFailedValidation: Boolean = false, allowCanonicalRemote: Boolean = false): PublishRunRequest? {
    val disabled = publishDisabledReason()
    val canReuseFailedValidationOverride =
      allowFailedValidation &&
        (
          hasCurrentFailedPublishValidationOverride() ||
            (allowCanonicalRemote && commitValidationFailed && publishingStatus.pushTarget?.isLikelyCanonical == true)
          )
    if (disabled != null && !canReuseFailedValidationOverride) {
      publishErrorMessage = disabled
      currentState = createState()
      return null
    }
    val target = publishingStatus.pushTarget
    if (target == null) {
      publishErrorMessage = publishingStatus.errorMessage ?: "No push target is available."
      currentState = createState()
      return null
    }
    if (target.isLikelyCanonical && !allowCanonicalRemote) {
      canonicalPushConfirmationRequired = true
      canonicalPushConfirmationTarget = target
      publishErrorMessage = null
      currentState = createState()
      return null
    }
    if (
      allowCanonicalRemote &&
      !canReuseFailedValidationOverride &&
      (!canonicalPushConfirmationRequired || canonicalPushConfirmationTarget != target)
    ) {
      currentState = createState()
      return null
    }
    if (allowFailedValidation && !canReuseFailedValidationOverride) {
      currentState = createState()
      return null
    }
    val selectedPaths = selectedManagedPublishPaths()
    activePublishToken += 1
    publishBusy = true
    publishErrorMessage = null
    publishLink = null
    commitErrorMessage = null
    pushErrorMessage = null
    canonicalPushConfirmationRequired = false
    canonicalPushConfirmationTarget = null
    val previousValidationSummary = validation
    if (selectedPaths.isNotEmpty()) {
      commitValidationRunning = true
      commitValidationFailed = false
      failedValidationStagedAuthoredPaths = null
      validation = ValidationSummary(state = ValidationRunState.RUNNING)
    }
    currentState = createState()
    return PublishRunRequest(
      token = activePublishToken,
      session = currentSession,
      selectedPaths = selectedPaths,
      message = commitMessage,
      prTitle = publishPrTitle.trim().ifBlank { commitMessage.trim().ifBlank { "Publish Skill Bill changes" } },
      prBody = publishPrBody.trim(),
      draftPr = publishDraft,
      allowFailedValidation = allowFailedValidation,
      target = target,
      compareUrl = publishingStatus.compareUrl,
      previousValidationSummary = previousValidationSummary,
      previousSnapshot = changesSnapshot,
      previousHistory = history,
      previousHistoryErrorMessage = historyErrorMessage,
      historyLimit = DEFAULT_HISTORY_LIMIT,
      historyPathFilter = historyPathFilter,
    )
  }

  fun runPublish(request: PublishRunRequest): PublishRunResult {
    val needsCommit = request.selectedPaths.isNotEmpty()
    val validationSummary =
      if (needsCommit) {
        runCatching { validationGateway.validate(request.session) }
          .getOrElse { error ->
            ValidationSummary(
              state = ValidationRunState.FAILED,
              runtimeExceptionName = error::class.simpleName ?: error::class.qualifiedName ?: "Throwable",
              runtimeExceptionMessage = error.message,
            )
          }
      } else {
        request.previousValidationSummary
      }
    if (needsCommit && validationSummary.failedForCommit() && !request.allowFailedValidation) {
      return PublishRunResult(
        request = request,
        validationSummary = validationSummary,
        validationBlockedCommit = true,
      )
    }
    if (needsCommit) {
      val stageSnapshot = runCatching { gitGateway.stage(request.session, request.selectedPaths) }
        .getOrElse { error -> ChangesSnapshot.failed(describe(error)) }
      if (stageSnapshot.isFailed || stageSnapshot.errorMessage != null) {
        return PublishRunResult(
          request = request,
          validationSummary = validationSummary,
          commitResult = GitOperationResult.failed(stageSnapshot.errorMessage ?: "Selected files could not be staged."),
          refreshedSnapshot = refreshSnapshot(request),
          refreshedHistory = refreshHistory(request),
          refreshedPublishingStatus = refreshPublishingStatus(request),
        )
      }
      val commitResult = runCatching { gitGateway.commit(request.session, request.message, request.selectedPaths) }
        .getOrElse { error -> GitOperationResult.failed(describe(error)) }
      if (!commitResult.success) {
        return PublishRunResult(
          request = request,
          validationSummary = validationSummary,
          commitResult = commitResult,
          refreshedSnapshot = refreshSnapshot(request),
          refreshedHistory = refreshHistory(request),
          refreshedPublishingStatus = refreshPublishingStatus(request),
        )
      }
    }
    val pushResult = runCatching { gitGateway.push(request.session, request.target) }
      .getOrElse { error -> GitOperationResult.failed(describe(error)) }
    if (!pushResult.success) {
      return PublishRunResult(
        request = request,
        validationSummary = validationSummary,
        commitResult = if (needsCommit) GitOperationResult.success else null,
        pushResult = pushResult,
        refreshedSnapshot = refreshSnapshot(request),
        refreshedHistory = refreshHistory(request),
        refreshedPublishingStatus = refreshPublishingStatus(request),
      )
    }
    val refreshedStatus = refreshPublishingStatus(request)
    val prResult = runCatching {
      prPublishingGateway.publish(
        PrPublishingRequest(
          session = request.session,
          pushTarget = refreshedStatus.pushTarget ?: request.target,
          compareUrl = refreshedStatus.compareUrl ?: request.compareUrl,
          title = request.prTitle,
          body = request.prBody,
          draft = request.draftPr,
        ),
      )
    }.getOrElse { error ->
      PrPublishingResult.Failed(
        type = PrPublishingErrorType.PROVIDER,
        message = describe(error),
      )
    }
    return PublishRunResult(
      request = request,
      validationSummary = validationSummary,
      commitResult = if (needsCommit) GitOperationResult.success else null,
      pushResult = pushResult,
      refreshedSnapshot = refreshSnapshot(request),
      refreshedHistory = refreshHistory(request),
      refreshedHistoryErrorMessage = null,
      refreshedPublishingStatus = refreshedStatus,
      prPublishingResult = prResult,
    )
  }

  fun finishPublish(result: PublishRunResult): SkillBillState {
    if (result.request.token != activePublishToken) {
      return currentState
    }
    publishBusy = false
    commitValidationRunning = false
    validation = result.validationSummary
    if (result.validationBlockedCommit) {
      commitValidationFailed = true
      failedValidationStagedAuthoredPaths = result.request.selectedPaths.toSet()
      currentState = createState()
      return currentState
    }
    val commitResult = result.commitResult
    if (commitResult?.success == false) {
      commitErrorMessage = commitResult.errorMessage ?: "Commit failed."
      publishErrorMessage = commitErrorMessage
      commitValidationFailed = false
      failedValidationStagedAuthoredPaths = null
      currentState = createState()
      return currentState
    }
    val pushResult = result.pushResult
    if (pushResult?.success == false) {
      pushErrorMessage = pushResult.errorMessage ?: "Push failed."
      publishErrorMessage = pushErrorMessage
      applyPublishRefreshes(result)
      currentState = createState()
      return currentState
    }
    result.prPublishingResult?.let { prResult ->
      when (prResult) {
        is PrPublishingResult.ExistingPullRequest -> {
          publishLink = PublishLink(PublishLinkKind.EXISTING_PR, prResult.url, "Existing pull request")
          publishErrorMessage = null
        }
        is PrPublishingResult.CreatedDraftPullRequest -> {
          publishLink = PublishLink(PublishLinkKind.DRAFT_PR, prResult.url, "Draft pull request created")
          publishErrorMessage = null
        }
        is PrPublishingResult.CompareUrlFallback -> {
          publishLink = PublishLink(PublishLinkKind.COMPARE_URL, prResult.url, prResult.reason)
          publishErrorMessage = prResult.reason
        }
        is PrPublishingResult.Failed -> {
          publishErrorMessage = prResult.message
        }
      }
    }
    if (commitResult?.success == true) {
      commitMessage = ""
      selectedPublishPaths = emptySet()
      publishSelectionInitialized = false
      publishSelectionDirty = false
    }
    commitErrorMessage = null
    pushErrorMessage = null
    commitValidationFailed = false
    failedValidationStagedAuthoredPaths = null
    applyPublishRefreshes(result)
    if (pushResult?.success == true) {
      showPostPublishReinstallPrompt()
    }
    currentState = createState()
    return currentState
  }

  private fun refreshSnapshot(request: PublishRunRequest): ChangesSnapshot =
    runCatching { gitGateway.snapshotFor(request.session) }
      .getOrElse { error -> ChangesSnapshot.failed(describe(error)) }

  private fun refreshHistory(request: PublishRunRequest): List<CommitEntry> =
    runCatching { gitGateway.recentCommits(request.session, request.historyLimit, request.historyPathFilter) }
      .getOrDefault(request.previousHistory)

  private fun refreshPublishingStatus(request: PublishRunRequest): GitPublishingStatus =
    runCatching { gitGateway.publishingStatus(request.session) }
      .getOrElse { error -> GitPublishingStatus(errorMessage = describe(error)) }

  private fun applyPublishRefreshes(result: PublishRunResult) {
    result.refreshedSnapshot?.let { snapshot ->
      if (snapshot.isFailed) {
        changesSnapshot = changesSnapshot.copy(errorMessage = snapshot.errorMessage)
      } else {
        changesSnapshot = snapshot
        reconcilePublishSelection(snapshot)
        if (snapshot.branchLabel.isNotEmpty()) {
          currentBranchLabel = snapshot.branchLabel
        }
      }
    }
    result.refreshedHistory?.let { commits -> history = commits }
    historyErrorMessage = result.refreshedHistoryErrorMessage
    result.refreshedPublishingStatus?.let { status -> replacePublishingStatus(status, clearConfirmation = true) }
  }

  // --- History ---

  fun beginLoadHistory(limit: Int = DEFAULT_HISTORY_LIMIT, quiet: Boolean = false): HistoryLoadRequest {
    activeHistoryToken += 1
    if (!quiet) {
      historyBusy = true
    }
    val previousHistory = history
    val previousError = historyErrorMessage
    currentState = createState()
    return HistoryLoadRequest(
      token = activeHistoryToken,
      session = currentSession,
      limit = limit,
      pathFilter = historyPathFilter,
      previousHistory = previousHistory,
      previousErrorMessage = previousError,
      quiet = quiet,
    )
  }

  fun runLoadHistory(request: HistoryLoadRequest): HistoryLoadResult {
    val outcome = runCatching {
      gitGateway.recentCommits(request.session, request.limit, request.pathFilter)
    }
    return HistoryLoadResult(
      request = request,
      commits = outcome.getOrDefault(emptyList()),
      errorMessage = outcome.exceptionOrNull()?.let(::describe),
    )
  }

  fun finishLoadHistory(result: HistoryLoadResult): SkillBillState {
    if (result.request.token != activeHistoryToken) {
      // F-101: stale finish — restore prior history slice so it does not stay stuck busy.
      if (historyBusy) {
        history = result.request.previousHistory
        historyErrorMessage = result.request.previousErrorMessage
        historyBusy = false
        currentState = createState()
      }
      return currentState
    }
    historyBusy = false
    history = result.commits
    historyErrorMessage = result.errorMessage
    currentState = createState()
    return currentState
  }

  fun setHistoryPathFilter(pathFilter: String?): SkillBillState {
    val trimmed = pathFilter?.trim()?.takeIf { it.isNotEmpty() }
    if (trimmed == historyPathFilter) {
      return currentState
    }
    historyPathFilter = trimmed
    currentState = createState()
    return currentState
  }

  private fun describe(error: Throwable): String {
    val message = error.message
    val name = error::class.simpleName ?: error::class.qualifiedName ?: "Throwable"
    return if (message.isNullOrBlank()) name else "$name: $message"
  }

  fun setActiveDockTab(tab: DockTab): SkillBillState {
    activeDockTab = tab
    currentState = createState()
    return currentState
  }

  fun revealValidationIssue(issue: ValidationIssue): SkillBillState {
    val sourcePath = issue.sourcePath ?: return currentState
    val resolvedId = validationGateway.resolveTreeItemIdForSource(currentSession, sourcePath) ?: return currentState
    if (!containsTreeItem(resolvedId)) {
      return currentState
    }
    if (isEditorDirty() && resolvedId != selectedTreeItemId) {
      dirtyEditorPrompt = DirtyEditorPrompt(
        reason = DirtyEditorPromptReason.SELECTION_CHANGE,
        targetTreeItemId = resolvedId,
      )
      currentState = createState()
      return currentState
    }
    selectedTreeItemId = resolvedId
    expandedNodeIds = expandedNodeIds + ancestorIdsOf(resolvedId)
    loadEditorForSelection()
    currentState = createState()
    return currentState
  }

  private fun ancestorIdsOf(itemId: String): Set<String> {
    val ancestors = mutableSetOf<String>()
    fun visit(items: List<SkillBillTreeItem>, parents: List<String>): Boolean {
      for (item in items) {
        if (item.id == itemId) {
          ancestors.addAll(parents)
          return true
        }
        if (item.children.isNotEmpty() && visit(item.children, parents + item.id)) {
          return true
        }
      }
      return false
    }
    visit(treeItems, emptyList())
    return ancestors
  }

  private fun editorForSelection(resolvedTreeItemId: String?): EditorPlaceholder {
    val base = resolvedTreeItemId?.let(authoringGateway::describeSelection) ?: EditorPlaceholder.empty
    val document = loadedEditorDocument
      ?.takeIf { editorSelectionId == resolvedTreeItemId }
    val dirty = isEditorDirty()
    return if (document == null) {
      base.copy(
        saveInProgress = editorSaveInProgress,
        saveErrorMessage = editorSaveErrorMessage,
      )
    } else {
      base.copy(
        title = document.title,
        skillName = document.skillName,
        kind = document.kind,
        authoredPath = document.authoredPath,
        editable = document.editable,
        readOnlyLabel = if (document.editable) null else base.readOnlyLabel ?: "RO",
        content = document.text,
        draftContent = editorDraftText,
        dirty = dirty,
        saveInProgress = editorSaveInProgress,
        saveErrorMessage = editorSaveErrorMessage ?: document.runtimeErrorMessage,
        readOnlyReason = document.readOnlyReason,
      )
    }
  }

  private fun loadEditorForSelection() {
    val selection = selectedTreeItemId
    if (selection == null || !containsTreeItem(selection)) {
      resetEditorDocument()
      return
    }
    val document = authoringGateway.loadDocument(currentSession, selection)
    loadedEditorDocument = document
    editorSelectionId = selection
    editorDraftText = document.text
    editorSaveInProgress = false
    editorSaveErrorMessage = document.runtimeErrorMessage
    dirtyEditorPrompt = null
  }

  private fun resetEditorDocument() {
    loadedEditorDocument = null
    editorSelectionId = null
    editorDraftText = ""
    editorSaveInProgress = false
    editorSaveErrorMessage = null
    dirtyEditorPrompt = null
  }

  private fun isEditorDirty(): Boolean {
    val document = loadedEditorDocument ?: return false
    return document.editable && editorDraftText != document.text
  }

  private fun statusBarFor(repoPath: String?, branchLabel: String, editor: EditorPlaceholder): SkillBillStatusBar =
    SkillBillStatusBar(
      targetCount = treeItems.flatten().count { it.children.isEmpty() },
      repoPathLabel = repoPath ?: "no repo",
      branchLabel = branchLabel,
      readOnlyModeLabel = when {
        isEditorDirty() -> "dirty"
        editor.editable -> SkillBillStatusBar.EDITABLE_MODE_LABEL
        else -> SkillBillStatusBar.READ_ONLY_MODE_LABEL
      },
      policyLabel = SkillBillStatusBar.POLICY_LABEL,
    )

  private fun canCommit(): Boolean = hasCommitInputs() &&
    busyOperation == null &&
    !changesBusy &&
    !publishBusy &&
    !commitBusy &&
    !commitValidationRunning &&
    !pushBusy

  private fun hasCommitInputs(): Boolean = currentSession?.isRecognizedSkillBillRepo == true &&
    commitMessage.isNotBlank() &&
    changesSnapshot.files.any { it.group == ChangedFileGroup.STAGED && it.isSkillContent }

  private fun canPublish(): Boolean = publishDisabledReason() == null

  private fun publishDisabledReason(): String? {
    if (currentSession?.isRecognizedSkillBillRepo != true) {
      return "Open a recognized Git repository before publishing."
    }
    if (busyOperation != null || changesBusy || publishBusy || commitBusy || commitValidationRunning || pushBusy) {
      return "Repository operation is already running."
    }
    if (postPublishReinstall != null) {
      return "Finish or dismiss the reinstall prompt before publishing again."
    }
    if (changesSnapshot.nonSkillContentFiles.isNotEmpty()) {
      return "Repository has non-content.md changes. Resolve or stash those files before publishing from Skill Bill."
    }
    val hasLocalSelection = selectedPublishPaths.isNotEmpty()
    val hasUnpushedCommit = publishingStatus.hasUnpushedCommits
    if (!hasLocalSelection && !hasUnpushedCommit) {
      return "No selected local changes or unpushed commits to publish."
    }
    if (hasLocalSelection && commitMessage.isBlank()) {
      return "Commit message is required before publishing local changes."
    }
    return null
  }

  private fun hasCurrentFailedValidationOverride(): Boolean =
    commitValidationFailed && failedValidationStagedAuthoredPaths == stagedAuthoredPaths(changesSnapshot)

  private fun hasCurrentFailedPublishValidationOverride(): Boolean =
    commitValidationFailed && failedValidationStagedAuthoredPaths == selectedManagedPublishPaths().toSet()

  private fun invalidateFailedValidationOverrideIfStagedAuthoredChanged() {
    val failedPaths = failedValidationStagedAuthoredPaths ?: return
    if (failedPaths != stagedAuthoredPaths(changesSnapshot)) {
      commitValidationFailed = false
      failedValidationStagedAuthoredPaths = null
    }
  }

  private fun stagedAuthoredPaths(snapshot: ChangesSnapshot): Set<String> = snapshot.files
    .filter { file -> file.group == ChangedFileGroup.STAGED && file.isSkillContent }
    .map { file -> file.path }
    .toSet()

  private fun selectedManagedPublishPaths(): List<String> {
    val visibleSelectedPaths = selectedPublishPaths
      .filter { path -> changesSnapshot.files.any { file -> file.path == path && file.isSkillContent } }
    if (visibleSelectedPaths.isEmpty()) {
      return emptyList()
    }
    return visibleSelectedPaths
      .plus(changesSnapshot.hiddenManagedSourceFiles.map { file -> file.path })
      .distinct()
      .sorted()
  }

  private fun reconcilePublishSelection(snapshot: ChangesSnapshot) {
    val selectablePaths = snapshot.skillContentFiles
      .map(ChangedFile::path)
      .toSet()
    selectedPublishPaths = selectablePaths
    publishSelectionInitialized = true
    publishSelectionDirty = false
  }

  private fun replacePublishingStatus(status: GitPublishingStatus, clearConfirmation: Boolean) {
    if (clearConfirmation || status.pushTarget != publishingStatus.pushTarget) {
      canonicalPushConfirmationRequired = false
      canonicalPushConfirmationTarget = null
    }
    publishingStatus = status
  }

  private fun showPostPublishReinstallPrompt() {
    val request = latestInstallSetupRequest() ?: return
    postPublishReinstall = PostPublishReinstallState(
      selectedAgentIds = request.selectedAgentIds,
      selectedPlatformSlugs = request.selectedPlatformSlugs,
      telemetryLevel = request.telemetryLevel,
      registerMcp = request.registerMcp,
      platformSelectionMode = request.platformSelectionMode,
    )
  }

  private fun latestInstallSetupRequest(): FirstRunSetupRequest? {
    val legacyFallback = desktopPreferenceStore.firstRunPreferences.value.toLegacySetupRequestOrNull()
    return firstRunGateway.latestReusableSetupRequest(legacyFallback)
  }

  private fun containsTreeItem(itemId: String): Boolean = treeItems.flatten().any { item -> item.id == itemId }

  // --- Scaffold wizard (SKILL-44) ---

  /**
   * Open a wizard for [kind] with a pre-fetched [snapshot]. The catalog snapshot is fetched on a
   * background dispatcher by the route (F-002/F-404) — it walks the `platform-packs/` filesystem,
   * so the view model never invokes it from the main dispatcher. Reuses the existing busy-gate
   * so a wizard cannot open while another repo-scoped operation is mid-flight.
   */
  fun openScaffoldWizard(kind: ScaffoldKind, snapshot: ScaffoldCatalogSnapshot): SkillBillState {
    if (!canStartScaffoldAction() || !kind.creationSupported) {
      return currentState
    }
    scaffoldWizard = ScaffoldWizardState(
      kind = kind,
      formFields = ScaffoldWizardFormFields(),
      optionCatalog = snapshot,
      dryRunPreview = null,
      executionResult = null,
      validationErrors = emptyList(),
      dirtyRepoWarning = computeDirtyRepoWarning(changesSnapshot),
      overrideDirtyRepo = false,
      busy = false,
    )
    currentState = createState()
    return currentState
  }

  /** Whether [openScaffoldWizard] would actually open a wizard. The route checks this before hopping. */
  fun canOpenScaffoldWizard(): Boolean = canStartScaffoldAction()

  /**
   * F-407-arch: build a request to fetch the wizard catalog snapshot. Captures the current
   * `RepoSession?` on the caller dispatcher (Main) so the suspend `run` step never reads VM
   * private state from a background dispatcher. Mirrors the begin/run/finish discipline used by
   * every other suspend operation in this VM (gitRefresh/validate/render/push/commit/repoLoad).
   *
   * Returns null when the wizard cannot be opened (busy gate, equivalent to `canOpenScaffoldWizard`)
   * so the route does not pay for a useless filesystem walk.
   */
  fun beginOpenScaffoldWizard(kind: ScaffoldKind): ScaffoldCatalogRequest? {
    if (!canStartScaffoldAction() || !kind.creationSupported) {
      return null
    }
    return ScaffoldCatalogRequest(kind = kind, session = currentSession)
  }

  /**
   * F-407-arch: fetch the catalog snapshot on `Dispatchers.Default`. Reads only the immutable
   * `request` captured by `beginOpenScaffoldWizard`; does not touch VM mutable state.
   */
  suspend fun runOpenScaffoldWizard(request: ScaffoldCatalogRequest): ScaffoldCatalogResponse {
    val snapshot = scaffoldGateway.catalogSnapshot(request.session)
    return ScaffoldCatalogResponse(kind = request.kind, snapshot = snapshot)
  }

  /**
   * F-407-arch: apply the fetched snapshot on Main. Re-checks `canStartScaffoldAction` so a
   * concurrent open-repo / repo-load that started between begin and finish cannot land a stale
   * wizard.
   */
  fun finishOpenScaffoldWizard(response: ScaffoldCatalogResponse): SkillBillState =
    openScaffoldWizard(response.kind, response.snapshot)

  /**
   * Internal-only catalog fetch used by tests and by the dirty-prompt resume path. Production
   * callers must use the begin/run/finish triplet above so VM private state is read on the
   * caller dispatcher (F-407-arch).
   */
  internal suspend fun fetchScaffoldCatalogSnapshot(): ScaffoldCatalogSnapshot =
    scaffoldGateway.catalogSnapshot(currentSession)

  /**
   * Switch the active wizard to a different [kind]. Resets the form and previews; the catalog
   * snapshot is preserved so the option lists remain stable across kind changes.
   *
   * F-402: if the current wizard is busy with an in-flight Plan/Run, the change is rejected
   * (mirrors `updateScaffoldForm`'s busy guard). The active scaffold token is bumped so any
   * in-flight response from the previous kind is discarded; `busy` is reset to false so the new
   * kind is free to Plan.
   *
   * F-408-plat / F-102 / AC5: a Failed result with `rollbackComplete = false` engages a
   * partial-mutation lock. The user MUST acknowledge that lock via `acknowledgeScaffoldFailure`
   * before scaffolding can resume. Allowing a kind switch to silently clear `executionResult`
   * would unlock Plan without the explicit acknowledgement gesture. Reject the kind switch in
   * that case (parallels the existing `current.busy` rejection branch).
   */
  fun selectScaffoldWizardKind(kind: ScaffoldKind): SkillBillState {
    val current = scaffoldWizard ?: return currentState
    if (current.busy || !kind.creationSupported) {
      return currentState
    }
    if (current.kind == kind) {
      return currentState
    }
    val partialMutationLock = (current.executionResult as? ScaffoldRunResult.Failed)
      ?.let { !it.rollbackComplete } == true
    if (partialMutationLock) {
      // F-408-plat: partial-mutation lock can only be released via acknowledgeScaffoldFailure.
      return currentState
    }
    activeScaffoldToken += 1
    if (busyOperation == SkillBillBusyOperation.SCAFFOLD) {
      busyOperation = null
    }
    scaffoldWizard = current.copy(
      kind = kind,
      formFields = ScaffoldWizardFormFields(),
      dryRunPreview = null,
      executionResult = null,
      validationErrors = emptyList(),
      overrideDirtyRepo = false,
      busy = false,
    )
    currentState = createState()
    return currentState
  }

  /**
   * Apply a freeform form-field transform. Each call resets the dry-run preview so the runtime
   * cannot apply a stale plan against new inputs (AC2).
   */
  fun updateScaffoldForm(transform: (ScaffoldWizardFormFields) -> ScaffoldWizardFormFields): SkillBillState {
    val current = scaffoldWizard ?: return currentState
    if (current.busy) {
      return currentState
    }
    val updatedFields = transform(current.formFields)
    if (updatedFields == current.formFields) {
      return currentState
    }
    scaffoldWizard = current.copy(
      formFields = updatedFields,
      dryRunPreview = null,
      validationErrors = emptyList(),
      // Editing the form after a Failed result keeps the banner so the user can read it; clear it
      // only when the success banner is displayed (we never accept further edits in that mode).
      executionResult = current.executionResult.takeIf { it is ScaffoldRunResult.Failed },
    )
    currentState = createState()
    return currentState
  }

  fun addScaffoldBaselineLayer(layer: ScaffoldBaselineLayerForm? = null): SkillBillState {
    val current = scaffoldWizard ?: return currentState
    if (current.busy || current.kind != ScaffoldKind.PLATFORM_PACK) {
      return currentState
    }
    val nextLayer = ensureBaselineLayerRowId(layer ?: defaultBaselineLayer(current.optionCatalog))
    return updateScaffoldForm { fields ->
      fields.copy(baselineLayers = fields.baselineLayers + nextLayer)
    }
  }

  fun editScaffoldBaselineLayer(
    index: Int,
    transform: (ScaffoldBaselineLayerForm) -> ScaffoldBaselineLayerForm,
  ): SkillBillState {
    val current = scaffoldWizard ?: return currentState
    if (
      current.busy ||
      current.kind != ScaffoldKind.PLATFORM_PACK ||
      index !in current.formFields.baselineLayers.indices
    ) {
      return currentState
    }
    return updateScaffoldForm { fields ->
      fields.copy(
        baselineLayers = fields.baselineLayers.mapIndexed { layerIndex, layer ->
          if (layerIndex == index) transform(layer) else layer
        },
      )
    }
  }

  fun removeScaffoldBaselineLayer(index: Int): SkillBillState {
    val current = scaffoldWizard ?: return currentState
    if (
      current.busy ||
      current.kind != ScaffoldKind.PLATFORM_PACK ||
      index !in current.formFields.baselineLayers.indices
    ) {
      return currentState
    }
    return updateScaffoldForm { fields ->
      fields.copy(baselineLayers = fields.baselineLayers.filterIndexed { layerIndex, _ -> layerIndex != index })
    }
  }

  fun addSuggestedScaffoldBaselineLayer(): SkillBillState {
    val current = scaffoldWizard ?: return currentState
    if (current.busy || current.kind != ScaffoldKind.PLATFORM_PACK) {
      return currentState
    }
    val suggestion = suggestedBaselineLayer(current)?.form ?: return currentState
    return addScaffoldBaselineLayer(suggestion)
  }

  private fun defaultBaselineLayer(catalog: ScaffoldCatalogSnapshot): ScaffoldBaselineLayerForm {
    val pack = catalog.baselineReviewPacks.firstOrNull()
    val skill = pack?.skills?.firstOrNull()
    return ScaffoldBaselineLayerForm(
      platform = pack?.platform.orEmpty(),
      skill = skill?.name.orEmpty(),
      mode = skill?.supportedModes?.firstOrNull().orEmpty(),
      scope = skill?.supportedScopes?.firstOrNull() ?: ScaffoldBaselineLayerForm.DEFAULT_SCOPE,
      required = true,
    )
  }

  private data class SuggestedBaselineLayer(
    val label: String,
    val form: ScaffoldBaselineLayerForm,
  )

  private fun suggestedBaselineLayer(wizard: ScaffoldWizardState): SuggestedBaselineLayer? {
    if (wizard.kind != ScaffoldKind.PLATFORM_PACK) return null
    return wizard.optionCatalog.baselineReviewLayerSuggestions.firstOrNull { suggestion ->
      suggestion.matches(wizard.formFields) &&
        wizard.formFields.baselineLayers.none { layer ->
          layer.platform == suggestion.platform && layer.skill == suggestion.skill
        }
    }?.let { suggestion ->
      SuggestedBaselineLayer(
        label = suggestion.label,
        form = ScaffoldBaselineLayerForm(
          platform = suggestion.platform,
          skill = suggestion.skill,
          scope = suggestion.scope,
          required = suggestion.required,
          mode = suggestion.mode,
        ),
      )
    }
  }

  private fun BaselineReviewLayerSuggestion.matches(fields: ScaffoldWizardFormFields): Boolean {
    val haystack = (
      listOf(fields.platform, fields.displayName, fields.description) +
        fields.strongRoutingSignals +
        fields.tieBreakers
      )
      .joinToString(separator = " ")
      .lowercase()
    return triggerSignals.any { signal -> signal.lowercase() in haystack }
  }

  private fun ensureBaselineLayerRowId(layer: ScaffoldBaselineLayerForm): ScaffoldBaselineLayerForm =
    if (layer.rowId != 0L) {
      layer
    } else {
      layer.copy(rowId = nextScaffoldBaselineLayerRowId++)
    }

  fun setScaffoldDirtyOverride(override: Boolean): SkillBillState {
    val current = scaffoldWizard ?: return currentState
    if (current.overrideDirtyRepo == override) {
      return currentState
    }
    scaffoldWizard = current.copy(overrideDirtyRepo = override)
    currentState = createState()
    return currentState
  }

  /**
   * F-401/F-UI-101: when the wizard is dismissed mid-flight, we must clear the SCAFFOLD
   * `busyOperation` slot too — otherwise the bookkeeping that gates every repo-scoped action
   * stays locked forever. The active-scaffold token bump ensures any in-flight response is
   * discarded as soon as it lands.
   */
  fun dismissScaffoldWizard(): SkillBillState {
    activeScaffoldToken += 1
    if (busyOperation == SkillBillBusyOperation.SCAFFOLD) {
      busyOperation = null
      activeOperationToken += 1
    }
    scaffoldWizard = null
    currentState = createState()
    return currentState
  }

  fun acknowledgeScaffoldFailure(): SkillBillState {
    val current = scaffoldWizard ?: return currentState
    scaffoldWizard = current.copy(executionResult = null)
    currentState = createState()
    return currentState
  }

  // -------------------------------------------------------------------------------------------
  // SKILL-46: tree-context-menu Delete dialog intents + begin/run/finish triplets.
  // -------------------------------------------------------------------------------------------

  /**
   * Intent — opens the confirmation dialog with no preview yet. The route immediately fires the
   * preview triplet after this intent.
   */
  fun showConfirmDeletion(target: DesktopSkillRemovalTarget): SkillBillState {
    // Refuse to stack dialogs or open during another scoped operation. The right-click handler
    // already gates on `kind ∈ {SKILL, PLATFORM_PACK, ADD_ON}` so we don't re-validate here.
    if (confirmDeletion != null || busyOperation != null) {
      return currentState
    }
    activeRemovalToken += 1
    confirmDeletion = ConfirmDeletionState(target = target)
    currentState = createState()
    return currentState
  }

  fun dismissConfirmDeletion(): SkillBillState {
    activeRemovalToken += 1
    // F-401: release the DELETE busy slot if we still own it. Releasing here is critical so the
    // user can cancel mid-preview/execute without wedging the UI.
    if (busyOperation == SkillBillBusyOperation.DELETE) {
      busyOperation = null
      activeOperationToken += 1
    }
    confirmDeletion = null
    currentState = createState()
    return currentState
  }

  fun setRemovalAcknowledged(acknowledged: Boolean): SkillBillState {
    val current = confirmDeletion ?: return currentState
    confirmDeletion = current.copy(acknowledged = acknowledged)
    currentState = createState()
    return currentState
  }

  fun acknowledgeRemovalFailure(): SkillBillState {
    // F-CROSS-REPO-LOCK: acknowledge clears the persistent post-mortem slot too. This is the ONLY
    // path that clears the slot — neither dismissConfirmDeletion nor repo switch touches it.
    partialMutationPostMortem = null
    val current = confirmDeletion
    if (current != null) {
      // F-102/F-408-plat mirror: clear the partial-mutation lock and the stale execution result
      // so the user can re-Preview/re-Delete (or cancel).
      confirmDeletion = current.copy(
        executionResult = null,
        partialMutationLocked = false,
      )
    }
    currentState = createState()
    return currentState
  }

  data class SkillRemovalRunRequest(
    val token: Long,
    val payload: DesktopSkillRemovalRequest,
  )

  /**
   * Begin the preview triplet. Captures the current target + repo path into an immutable
   * [SkillRemovalRunRequest] on Main BEFORE the route hops to Dispatchers.Default — exactly the
   * same pattern as `beginScaffoldDryRun`.
   */
  fun beginPreviewRemoval(): SkillRemovalRunRequest? {
    val current = confirmDeletion ?: return null
    if (current.previewBusy || current.executeBusy || current.partialMutationLocked) {
      return null
    }
    if (!canStartScaffoldAction()) {
      return null
    }
    val repoRoot = currentSession?.repoPath?.takeIf { it.isNotBlank() } ?: return null
    activeRemovalToken += 1
    confirmDeletion = current.copy(previewBusy = true, preview = null, executionResult = null)
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.DELETE
    currentState = createState()
    return SkillRemovalRunRequest(
      token = activeRemovalToken,
      payload = DesktopSkillRemovalRequest(target = current.target, repoRootAbsolutePath = repoRoot),
    )
  }

  suspend fun runPreviewRemoval(request: SkillRemovalRunRequest): DesktopSkillRemovalResult =
    skillRemoveGateway.preview(request.payload)

  fun finishPreviewRemoval(request: SkillRemovalRunRequest, result: DesktopSkillRemovalResult): SkillBillState {
    if (request.token != activeRemovalToken) {
      // Stale: the user dismissed (or another removal started). F-401: release DELETE slot if ours.
      if (busyOperation == SkillBillBusyOperation.DELETE) {
        busyOperation = null
      }
      currentState = createState()
      return currentState
    }
    val current = confirmDeletion ?: return currentState
    busyOperation = null
    confirmDeletion = when (result) {
      is DesktopSkillRemovalResult.Preview -> current.copy(
        previewBusy = false,
        preview = result.preview,
        executionResult = null,
      )
      is DesktopSkillRemovalResult.Failed -> current.copy(
        previewBusy = false,
        preview = null,
        executionResult = result,
        // Preview never mutates, so a Failed-from-preview must NOT set partialMutationLocked.
        partialMutationLocked = false,
      )
      // Preview-triplet must not yield Success; treat as a contract violation.
      is DesktopSkillRemovalResult.Success -> current.copy(
        previewBusy = false,
        preview = null,
        executionResult = DesktopSkillRemovalResult.Failed(
          exceptionName = "IllegalPreviewResponse",
          exceptionMessage = "Gateway returned Success for preview mode.",
          rollbackComplete = true,
        ),
      )
    }
    currentState = createState()
    return currentState
  }

  fun beginExecuteRemoval(): SkillRemovalRunRequest? {
    val current = confirmDeletion ?: return null
    if (!current.deleteEnabled) {
      return null
    }
    if (!canStartScaffoldAction()) {
      return null
    }
    val repoRoot = currentSession?.repoPath?.takeIf { it.isNotBlank() } ?: return null
    activeRemovalToken += 1
    confirmDeletion = current.copy(executeBusy = true, executionResult = null)
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.DELETE
    currentState = createState()
    return SkillRemovalRunRequest(
      token = activeRemovalToken,
      payload = DesktopSkillRemovalRequest(target = current.target, repoRootAbsolutePath = repoRoot),
    )
  }

  suspend fun runExecuteRemoval(request: SkillRemovalRunRequest): DesktopSkillRemovalResult =
    skillRemoveGateway.execute(request.payload)

  fun finishExecuteRemoval(request: SkillRemovalRunRequest, result: DesktopSkillRemovalResult): SkillBillState {
    // F-CROSS-REPO-LOCK: regardless of token freshness OR dialog state, a Failed with
    // rollbackComplete=false MUST populate the persistent post-mortem slot. This way a user who
    // dismissed mid-flight, or switched repos, still gets the partial-mutation warning surfaced.
    capturePartialMutationPostMortem(request, result)
    if (request.token != activeRemovalToken) {
      if (busyOperation == SkillBillBusyOperation.DELETE) {
        busyOperation = null
      }
      currentState = createState()
      return currentState
    }
    val current = confirmDeletion ?: return currentState
    busyOperation = null
    confirmDeletion = when (result) {
      is DesktopSkillRemovalResult.Success -> current.copy(
        executeBusy = false,
        executionResult = result,
      )
      is DesktopSkillRemovalResult.Failed -> current.copy(
        executeBusy = false,
        executionResult = result,
        // F-102/F-408-plat: rollbackComplete=false locks both Preview and Delete buttons until
        // the user acknowledges the failure.
        partialMutationLocked = !result.rollbackComplete,
      )
      is DesktopSkillRemovalResult.Preview -> current.copy(
        executeBusy = false,
        executionResult = DesktopSkillRemovalResult.Failed(
          exceptionName = "IllegalExecuteResponse",
          exceptionMessage = "Gateway returned Preview for execute mode.",
          rollbackComplete = true,
        ),
      )
    }
    currentState = createState()
    return currentState
  }

  /**
   * F-CROSS-REPO-LOCK: populates the persistent post-mortem slot when a Failed result reports
   * `rollbackComplete = false`. The slot is intentionally NOT cleared on repo switch — only
   * [acknowledgeRemovalFailure] clears it.
   */
  private fun capturePartialMutationPostMortem(request: SkillRemovalRunRequest, result: DesktopSkillRemovalResult) {
    if (result !is DesktopSkillRemovalResult.Failed || result.rollbackComplete) return
    val target = request.payload.target
    val label = when (target) {
      is DesktopSkillRemovalTarget.HorizontalSkill -> target.skillName
      is DesktopSkillRemovalTarget.PlatformPack ->
        "platform pack '${target.platform}'"
      is DesktopSkillRemovalTarget.AddOn -> target.relativePath
    }
    partialMutationPostMortem = PartialMutationPostMortem(
      targetLabel = label,
      exceptionName = result.exceptionName,
      exceptionMessage = result.exceptionMessage,
    )
  }

  /**
   * Hooked from the route after [finishExecuteRemoval] returns a Success state. Pushes the
   * dock to the Console tab so the post-delete `scripts/validate_agent_configs` output is
   * visible immediately (AC8).
   */
  fun showValidateAgentConfigsConsole(): SkillBillState {
    activeDockTab = DockTab.Console
    activeValidateAgentConfigsToken += 1
    validateAgentConfigsSummary = ValidateAgentConfigsSummary(
      lines = emptyList(),
      exitCode = null,
      running = true,
    )
    currentState = createState()
    return currentState
  }

  /**
   * Append output lines from the post-delete `scripts/validate_agent_configs` invocation. The
   * route calls this incrementally as the subprocess writes; we keep the token-versioned slice
   * so concurrent invocations after a repo-switch don't bleed into each other.
   */
  fun appendValidateAgentConfigsLines(lines: List<String>): SkillBillState {
    val merged = validateAgentConfigsSummary.lines + lines
    validateAgentConfigsSummary = validateAgentConfigsSummary.copy(lines = merged)
    currentState = createState()
    return currentState
  }

  fun finishValidateAgentConfigs(exitCode: Int): SkillBillState {
    validateAgentConfigsSummary = validateAgentConfigsSummary.copy(running = false, exitCode = exitCode)
    currentState = createState()
    return currentState
  }

  fun beginScaffoldDryRun(): ScaffoldRunRequest? {
    val current = scaffoldWizard ?: return null
    if (current.busy || !canStartScaffoldAction()) {
      return null
    }
    if (!isScaffoldPlanAllowed(current)) {
      return null
    }
    val validationErrors = validateScaffoldWizard(current)
    if (validationErrors.isNotEmpty()) {
      failScaffoldFormValidation(current, validationErrors)
      return null
    }
    val payload = buildScaffoldPayload(current) ?: return null
    activeScaffoldToken += 1
    scaffoldWizard = current.copy(busy = true, dryRunPreview = null, executionResult = null)
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.SCAFFOLD
    currentState = createState()
    return ScaffoldRunRequest(
      token = activeScaffoldToken,
      payload = payload,
      mode = ScaffoldRunMode.DRY_RUN,
    )
  }

  suspend fun runScaffoldDryRun(request: ScaffoldRunRequest): ScaffoldRunResult =
    scaffoldGateway.dryRun(request.payload)

  fun finishScaffoldDryRun(request: ScaffoldRunRequest, result: ScaffoldRunResult): SkillBillState {
    if (request.token != activeScaffoldToken) {
      // F-401 mirror: a stale dry-run response lands after a dismiss/kind-switch. The previous owner
      // bumped the token, so we are no longer the wizard's source of truth. If we still own the
      // SCAFFOLD busy slot (a kind-switch-without-dismiss case), release it so the next operation
      // can proceed.
      if (busyOperation == SkillBillBusyOperation.SCAFFOLD) {
        busyOperation = null
      }
      currentState = createState()
      return currentState
    }
    val current = scaffoldWizard ?: return currentState
    busyOperation = null
    scaffoldWizard = when (result) {
      is ScaffoldRunResult.Preview -> current.copy(
        busy = false,
        dryRunPreview = result.planned,
        executionResult = null,
      )
      is ScaffoldRunResult.Failed -> current.copy(
        busy = false,
        dryRunPreview = null,
        executionResult = result,
      )
      // Success is impossible for dry-run; treat as a failed contract so the UI surfaces it as an error.
      is ScaffoldRunResult.Success -> current.copy(
        busy = false,
        dryRunPreview = null,
        executionResult = ScaffoldRunResult.Failed(
          exceptionName = "IllegalDryRunResponse",
          exceptionMessage = "Runtime returned Success for dry-run mode.",
          rollbackComplete = true,
        ),
      )
    }
    currentState = createState()
    return currentState
  }

  fun beginScaffoldExecute(): ScaffoldRunRequest? {
    val current = scaffoldWizard ?: return null
    if (!current.runEnabled || !canStartScaffoldAction()) {
      return null
    }
    val validationErrors = validateScaffoldWizard(current)
    if (validationErrors.isNotEmpty()) {
      failScaffoldFormValidation(current, validationErrors)
      return null
    }
    val payload = buildScaffoldPayload(current) ?: return null
    activeScaffoldToken += 1
    scaffoldWizard = current.copy(busy = true, executionResult = null)
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.SCAFFOLD
    currentState = createState()
    return ScaffoldRunRequest(
      token = activeScaffoldToken,
      payload = payload,
      mode = ScaffoldRunMode.EXECUTE,
    )
  }

  suspend fun runScaffoldExecute(request: ScaffoldRunRequest): ScaffoldRunResult =
    scaffoldGateway.execute(request.payload)

  fun finishScaffoldExecute(request: ScaffoldRunRequest, result: ScaffoldRunResult): SkillBillState {
    if (request.token != activeScaffoldToken) {
      // F-401 mirror (see finishScaffoldDryRun): release the SCAFFOLD busy slot if still ours.
      if (busyOperation == SkillBillBusyOperation.SCAFFOLD) {
        busyOperation = null
      }
      currentState = createState()
      return currentState
    }
    val current = scaffoldWizard ?: return currentState
    busyOperation = null
    scaffoldWizard = when (result) {
      is ScaffoldRunResult.Success -> current.copy(
        busy = false,
        executionResult = result,
        dryRunPreview = null,
      )
      is ScaffoldRunResult.Failed -> current.copy(
        busy = false,
        executionResult = result,
        // F-102: clear the stale plan after a failed run so `runEnabled` returns false and the
        // user must re-Plan with fresh inputs before another Run can fire.
        dryRunPreview = null,
      )
      is ScaffoldRunResult.Preview -> current.copy(
        busy = false,
        executionResult = ScaffoldRunResult.Failed(
          exceptionName = "IllegalExecuteResponse",
          exceptionMessage = "Runtime returned Preview for execute mode.",
          rollbackComplete = true,
        ),
        dryRunPreview = null,
      )
    }
    currentState = createState()
    return currentState
  }

  /**
   * F-105/F-T01: resolve the tree-item id that surfaces the AUTHORED source for the artifact
   * just created by a successful scaffold. Generated wrappers (TreeItemKind.GENERATED_ARTIFACT)
   * must not be offered for editing (AC7). Returns null when no matching authored tree item
   * exists (the caller leaves the selection alone in that case).
   */
  fun resolveAuthoredTreeItemForScaffold(outcome: ScaffoldOutcome): String? {
    val skillPath = outcome.skillPath.takeIf { it.isNotBlank() } ?: return null
    val authoredCandidates = outcome.createdFiles.filterNot { it.endsWith("SKILL.md") }
    val needle = skillPath.trimEnd('/')
    return treeItems.flatten()
      .filter { it.kind != TreeItemKind.GENERATED_ARTIFACT }
      .firstOrNull { item ->
        val authored = item.authoredPath
        when {
          authored == null -> false
          authoredCandidates.any { it.endsWith(authored) } -> true
          authored.contains(needle) -> true
          else -> false
        }
      }
      ?.id
  }

  /**
   * F-102: Plan must be gated until the user acknowledges a partial-mutation failure. After a
   * Failed result with `rollbackComplete = false`, the repo may be partially mutated; we refuse
   * to let the user fire another scaffold (which could compound the inconsistency) until they
   * dismiss the failure banner via `acknowledgeScaffoldFailure`.
   */
  private fun isScaffoldPlanAllowed(wizard: ScaffoldWizardState): Boolean {
    val failure = wizard.executionResult as? ScaffoldRunResult.Failed ?: return true
    return failure.rollbackComplete
  }

  /**
   * Public read of the partial-mutation Plan gate for the current wizard. Returns true when no
   * wizard is open (the gate only applies when a wizard is on screen). Used by tests and by the
   * route to assert the F-408-plat invariant: a partial-mutation lock can only be released by
   * `acknowledgeScaffoldFailure`.
   */
  fun isScaffoldPlanAllowed(): Boolean = scaffoldWizard?.let(::isScaffoldPlanAllowed) ?: true

  /**
   * Returns true when no other repo-scoped operation is in flight. Mirrors `canStartRepoScopedAction`
   * in `SkillBillRoute`; defined here so VM-only flows can busy-gate without route help.
   */
  private fun canStartScaffoldAction(): Boolean = busyOperation == null &&
    !publishBusy &&
    !commitBusy &&
    !commitValidationRunning &&
    !pushBusy &&
    !changesBusy

  private fun failScaffoldFormValidation(current: ScaffoldWizardState, errors: List<String>) {
    scaffoldWizard = current.copy(
      dryRunPreview = null,
      validationErrors = errors,
      executionResult = null,
    )
    currentState = createState()
  }

  private fun validateScaffoldWizard(wizard: ScaffoldWizardState): List<String> = buildList {
    val fields = wizard.formFields
    when (wizard.kind) {
      ScaffoldKind.HORIZONTAL_SKILL -> if (fields.name.isBlank()) add("Skill name is required.")
      ScaffoldKind.PLATFORM_PACK -> {
        if (fields.platform.isBlank()) add("Platform slug is required.")
        addAll(validateBaselineLayers(wizard))
      }
      ScaffoldKind.PLATFORM_OVERRIDE_PILOTED -> {
        if (fields.platform.isBlank()) add("Platform is required.")
        if (fields.family.isBlank()) add("Family is required.")
      }
      ScaffoldKind.CODE_REVIEW_AREA -> {
        if (fields.platform.isBlank()) add("Platform is required.")
        if (fields.area.isBlank()) add("Code-review area is required.")
      }
      ScaffoldKind.ADD_ON -> {
        if (fields.name.isBlank()) add("Add-on name is required.")
        if (fields.platform.isBlank()) add("Owning platform pack is required.")
      }
    }
  }

  private fun validateBaselineLayers(wizard: ScaffoldWizardState): List<String> = buildList {
    val newPlatform = wizard.formFields.platform.trim()
    val catalog = wizard.optionCatalog
    val packsBySlug = catalog.baselineReviewPacks.associateBy { it.platform }
    val seen = mutableSetOf<Pair<String, String>>()

    wizard.formFields.baselineLayers.forEachIndexed { index, layer ->
      val label = "Baseline layer ${index + 1}"
      val platform = layer.platform.trim()
      val skillName = layer.skill.trim()
      if (platform.isBlank()) {
        add("$label: baseline pack is required.")
        return@forEachIndexed
      }
      val pack = packsBySlug[platform]
      if (pack == null) {
        add("$label: baseline pack '$platform' is not available or has no declared code-review baseline.")
        return@forEachIndexed
      }
      if (skillName.isBlank()) {
        add("$label: baseline skill is required.")
        return@forEachIndexed
      }
      val skill = pack.skills.firstOrNull { it.name == skillName }
      if (skill == null) {
        add("$label: baseline skill '$skillName' is not declared by pack '$platform'.")
        return@forEachIndexed
      }
      if (layer.mode !in skill.supportedModes) {
        add("$label: mode '${layer.mode}' is not supported by '$platform/$skillName'.")
      }
      if (layer.scope !in skill.supportedScopes) {
        add("$label: scope '${layer.scope}' is not supported by '$platform/$skillName'.")
      }
      if (!seen.add(platform to skillName)) {
        add("$label: duplicate baseline layer '$platform/$skillName'.")
      }
      if (newPlatform.isNotBlank() && platform == newPlatform) {
        add("$label: baseline layer self-references the new platform pack '$newPlatform'.")
      } else if (newPlatform.isNotBlank() && compositionPathExists(catalog, from = platform, to = newPlatform)) {
        add("$label: adding '$newPlatform -> $platform' would create a code-review composition cycle.")
      }
    }
  }

  private fun compositionPathExists(catalog: ScaffoldCatalogSnapshot, from: String, to: String): Boolean {
    val graph = catalog.baselineReviewCompositionEdges.groupBy(
      keySelector = { edge -> edge.sourcePlatform },
      valueTransform = { edge -> edge.targetPlatform },
    )
    val visited = mutableSetOf<String>()
    fun visit(platform: String): Boolean {
      if (!visited.add(platform)) return false
      if (platform == to) return true
      return graph[platform].orEmpty().any(::visit)
    }
    return visit(from)
  }

  private fun buildScaffoldPayload(wizard: ScaffoldWizardState): ScaffoldPayload? {
    val fields = wizard.formFields
    val repoRoot = currentSession?.repoPath?.takeIf { it.isNotBlank() } ?: return null
    return when (wizard.kind) {
      ScaffoldKind.HORIZONTAL_SKILL -> if (fields.name.isBlank()) {
        null
      } else {
        val trimmed = fields.name.trim()
        val normalized = if (trimmed.startsWith("bill-")) trimmed else "bill-$trimmed"
        ScaffoldPayload.HorizontalSkill(
          repoRoot = repoRoot,
          name = normalized,
          description = fields.description.trim(),
          contentBody = fields.contentBody.takeIf { it.isNotBlank() },
          subagentSpecialists = fields.subagentSpecialists.filter(String::isNotBlank),
          suppressSubagents = fields.suppressSubagents,
        )
      }
      ScaffoldKind.PLATFORM_PACK -> if (fields.platform.isBlank()) {
        null
      } else {
        ScaffoldPayload.PlatformPack(
          repoRoot = repoRoot,
          platform = fields.platform.trim(),
          displayName = fields.displayName.trim(),
          description = fields.description.trim(),
          strongRoutingSignals = fields.strongRoutingSignals.filter(String::isNotBlank),
          tieBreakers = fields.tieBreakers.filter(String::isNotBlank),
          baselineLayers = fields.baselineLayers.map { layer ->
            ScaffoldBaselineLayerPayload(
              platform = layer.platform.trim(),
              skill = layer.skill.trim(),
              scope = layer.scope.trim(),
              required = layer.required,
              mode = layer.mode.trim(),
            )
          },
          subagentSpecialists = fields.subagentSpecialists.filter(String::isNotBlank),
          suppressSubagents = fields.suppressSubagents,
          contentBody = fields.contentBody.takeIf { it.isNotBlank() },
        )
      }
      ScaffoldKind.PLATFORM_OVERRIDE_PILOTED -> if (
        fields.platform.isBlank() || fields.family.isBlank()
      ) {
        null
      } else {
        ScaffoldPayload.PlatformOverride(
          repoRoot = repoRoot,
          platform = fields.platform.trim(),
          family = fields.family.trim(),
          description = fields.description.trim(),
          contentBody = fields.contentBody.takeIf { it.isNotBlank() },
          subagentSpecialists = fields.subagentSpecialists.filter(String::isNotBlank),
          suppressSubagents = fields.suppressSubagents,
        )
      }
      ScaffoldKind.CODE_REVIEW_AREA -> if (
        fields.platform.isBlank() || fields.area.isBlank()
      ) {
        null
      } else {
        ScaffoldPayload.CodeReviewArea(
          repoRoot = repoRoot,
          platform = fields.platform.trim(),
          area = fields.area.trim(),
          description = fields.description.trim(),
          contentBody = fields.contentBody.takeIf { it.isNotBlank() },
        )
      }
      ScaffoldKind.ADD_ON -> if (fields.name.isBlank() || fields.platform.isBlank()) {
        null
      } else {
        ScaffoldPayload.AddOn(
          repoRoot = repoRoot,
          name = fields.name.trim(),
          platform = fields.platform.trim(),
          description = fields.description.trim(),
        )
      }
    }
  }
}

data class RepoLoadRequest(
  val token: Long,
  val repoPath: String,
  val preserveSelection: Boolean,
  val previousRepoPath: String?,
  val previousSelection: String?,
  val previousExpandedNodeIds: Set<String>,
)

data class RepoLoadResult(
  val request: RepoLoadRequest,
  val session: RepoSession,
  val treeItems: List<SkillBillTreeItem>,
)

data class ValidationRunRequest(
  val token: Long,
  val session: RepoSession?,
  val selectedOnly: Boolean,
  val treeItemId: String?,
  val previousValidationSummary: ValidationSummary,
)

data class ValidationRunResult(
  val request: ValidationRunRequest,
  val summary: ValidationSummary,
)

data class RenderRunRequest(
  val token: Long,
  val session: RepoSession?,
  val targets: List<RenderTarget>,
  val previousRenderSummary: RenderSummary,
)

data class RenderTarget(
  val treeItemId: String,
  val label: String,
)

data class RenderRunResult(
  val request: RenderRunRequest,
  val summary: RenderSummary,
)

data class EditorSaveRequest(
  val token: Long,
  val session: RepoSession?,
  val treeItemId: String?,
  val body: String,
)

data class EditorSaveResult(
  val request: EditorSaveRequest,
  val result: AuthoringSaveResult,
)

// Default cap on the number of recent commits surfaced in the History tab. Kept here so tests can
// exercise the same default and route fan-out can override per-call when needed.
const val DEFAULT_HISTORY_LIMIT: Int = 50

data class GitRefreshRequest(
  val token: Long,
  val session: RepoSession?,
  val previousSnapshot: ChangesSnapshot,
  val quiet: Boolean = false,
)

data class GitRefreshResult(
  val request: GitRefreshRequest,
  val snapshot: ChangesSnapshot,
  val publishingStatus: GitPublishingStatus? = null,
)

data class SelectChangedFileRequest(
  val token: Long,
  val session: RepoSession?,
  val file: ChangedFile,
  val staged: Boolean,
)

data class SelectChangedFileResult(
  val request: SelectChangedFileRequest,
  val diff: String,
)

enum class StageAction {
  STAGE,
  UNSTAGE,
  DISCARD,
}

data class StageRequest(
  val token: Long,
  val session: RepoSession?,
  val paths: List<String>,
  val previousSnapshot: ChangesSnapshot,
  val action: StageAction,
)

data class CommitRunRequest(
  val token: Long,
  val session: RepoSession?,
  val message: String,
  val allowFailedValidation: Boolean,
  val previousValidationSummary: ValidationSummary,
  val previousSnapshot: ChangesSnapshot,
  val previousHistory: List<CommitEntry>,
  val previousHistoryErrorMessage: String?,
  val historyLimit: Int,
  val historyPathFilter: String?,
)

data class CommitRunResult(
  val request: CommitRunRequest,
  val validationSummary: ValidationSummary,
  val validationBlockedCommit: Boolean = false,
  val commitResult: GitOperationResult? = null,
  val refreshedSnapshot: ChangesSnapshot? = null,
  val refreshedHistory: List<CommitEntry>? = null,
  val refreshedHistoryErrorMessage: String? = null,
  val refreshedPublishingStatus: GitPublishingStatus? = null,
)

data class PushRunRequest(
  val token: Long,
  val session: RepoSession?,
  val target: GitPushTarget,
  val previousPublishingStatus: GitPublishingStatus,
)

data class PushRunResult(
  val request: PushRunRequest,
  val pushResult: GitOperationResult,
  val refreshedPublishingStatus: GitPublishingStatus? = null,
)

data class PublishRunRequest(
  val token: Long,
  val session: RepoSession?,
  val selectedPaths: List<String>,
  val message: String,
  val prTitle: String,
  val prBody: String,
  val draftPr: Boolean,
  val allowFailedValidation: Boolean,
  val target: GitPushTarget,
  val compareUrl: String?,
  val previousValidationSummary: ValidationSummary,
  val previousSnapshot: ChangesSnapshot,
  val previousHistory: List<CommitEntry>,
  val previousHistoryErrorMessage: String?,
  val historyLimit: Int,
  val historyPathFilter: String?,
)

data class PublishRunResult(
  val request: PublishRunRequest,
  val validationSummary: ValidationSummary,
  val validationBlockedCommit: Boolean = false,
  val commitResult: GitOperationResult? = null,
  val pushResult: GitOperationResult? = null,
  val refreshedSnapshot: ChangesSnapshot? = null,
  val refreshedHistory: List<CommitEntry>? = null,
  val refreshedHistoryErrorMessage: String? = null,
  val refreshedPublishingStatus: GitPublishingStatus? = null,
  val prPublishingResult: PrPublishingResult? = null,
)

data class HistoryLoadRequest(
  val token: Long,
  val session: RepoSession?,
  val limit: Int,
  val pathFilter: String?,
  val previousHistory: List<CommitEntry>,
  val previousErrorMessage: String?,
  val quiet: Boolean = false,
)

data class HistoryLoadResult(
  val request: HistoryLoadRequest,
  val commits: List<CommitEntry>,
  val errorMessage: String?,
)

enum class ScaffoldRunMode {
  DRY_RUN,
  EXECUTE,
}

data class ScaffoldRunRequest(
  val token: Long,
  val payload: ScaffoldPayload,
  val mode: ScaffoldRunMode,
)

/**
 * F-407-arch: request payload for the open-wizard begin/run/finish triplet. Captures the current
 * repo session on the Main dispatcher so the suspend `run` step does not race concurrent
 * mutations of `currentSession` (e.g. open-repo, repo-load completion) on background dispatchers.
 */
data class ScaffoldCatalogRequest(
  val kind: ScaffoldKind,
  val session: RepoSession?,
)

/**
 * F-407-arch: response payload paired with [ScaffoldCatalogRequest]. Wraps the kind alongside
 * the snapshot so `finishOpenScaffoldWizard` does not have to be told the kind separately.
 */
data class ScaffoldCatalogResponse(
  val kind: ScaffoldKind,
  val snapshot: ScaffoldCatalogSnapshot,
)

data class FirstRunDiscoveryRequest(
  val token: Long,
)

data class FirstRunDiscoveryResponse(
  val request: FirstRunDiscoveryRequest,
  val result: FirstRunDiscoveryResult,
)

data class FirstRunApplyRequest(
  val token: Long,
  val setupRequest: FirstRunSetupRequest,
)

data class FirstRunApplyResponse(
  val request: FirstRunApplyRequest,
  val planResult: FirstRunPlanResult,
  val applyResult: FirstRunApplyResult?,
)

data class PostPublishReinstallRequest(
  val token: Long,
  val setupRequest: FirstRunSetupRequest,
)

data class PostPublishReinstallResponse(
  val request: PostPublishReinstallRequest,
  val planResult: FirstRunPlanResult,
  val applyResult: FirstRunApplyResult?,
)

private fun FirstRunSetupRequest.toFirstRunSetupState(): FirstRunSetupState = FirstRunSetupState(
  selectedAgentIds = selectedAgentIds,
  selectedPlatformSlugs = selectedPlatformSlugs,
  platformSelectionMode = platformSelectionMode,
  telemetryLevel = telemetryLevel,
  registerMcp = registerMcp,
)

private fun FirstRunSetupState.applyDiscovery(
  discovery: FirstRunSetupDiscovery,
  preferredRequest: FirstRunSetupRequest?,
): FirstRunSetupState {
  val preferredAgents = preferredRequest?.selectedAgentIds?.takeIf(Set<String>::isNotEmpty)
  val selectedAgents = preferredAgents ?: discovery.agents
    .filter { option -> option.selected }
    .mapTo(mutableSetOf()) { option -> option.agentId }
  val selectedPlatforms = when (preferredRequest?.platformSelectionMode) {
    FirstRunPlatformSelectionMode.ALL -> discovery.platformPacks.mapTo(mutableSetOf()) { pack -> pack.slug }
    FirstRunPlatformSelectionMode.SELECTED -> preferredRequest.selectedPlatformSlugs
    FirstRunPlatformSelectionMode.NONE -> emptySet()
    null -> discovery.selectedPlatformSlugs
  }
  return copy(
    busy = false,
    discoveryLoaded = true,
    errorMessage = null,
    agentOptions = discovery.agents.map { option ->
      option.copy(selected = option.agentId in selectedAgents)
    },
    platformPacks = discovery.platformPacks.map { pack ->
      pack.copy(selected = pack.slug in selectedPlatforms)
    },
    selectedAgentIds = selectedAgents,
    selectedPlatformSlugs = selectedPlatforms,
    platformSelectionMode = preferredRequest?.platformSelectionMode
      ?: selectedPlatforms.toFirstRunPlatformSelectionMode(),
    telemetryLevel = preferredRequest?.telemetryLevel ?: FirstRunTelemetryLevel.default,
    registerMcp = preferredRequest?.registerMcp ?: true,
  )
}

private fun Set<String>.toFirstRunPlatformSelectionMode(): FirstRunPlatformSelectionMode = if (isEmpty()) {
  FirstRunPlatformSelectionMode.NONE
} else {
  FirstRunPlatformSelectionMode.SELECTED
}

private fun DesktopFirstRunPreferences.toLegacySetupRequestOrNull(): FirstRunSetupRequest? {
  if (!completed || selectedAgentIds.isEmpty()) {
    return null
  }
  return FirstRunSetupRequest(
    selectedAgentIds = selectedAgentIds,
    selectedPlatformSlugs = selectedPlatformSlugs,
    telemetryLevel = FirstRunTelemetryLevel.fromId(telemetryLevelId),
    registerMcp = registerMcp,
  )
}

private fun FirstRunSetupStep.next(): FirstRunSetupStep = when (this) {
  FirstRunSetupStep.AGENTS -> FirstRunSetupStep.PLATFORM_PACKS
  FirstRunSetupStep.PLATFORM_PACKS -> FirstRunSetupStep.PREFERENCES
  FirstRunSetupStep.PREFERENCES -> FirstRunSetupStep.APPLY
  FirstRunSetupStep.APPLY -> FirstRunSetupStep.RESULT
  FirstRunSetupStep.RESULT -> FirstRunSetupStep.RESULT
}

private fun FirstRunSetupStep.previous(): FirstRunSetupStep = when (this) {
  FirstRunSetupStep.AGENTS -> FirstRunSetupStep.AGENTS
  FirstRunSetupStep.PLATFORM_PACKS -> FirstRunSetupStep.AGENTS
  FirstRunSetupStep.PREFERENCES -> FirstRunSetupStep.PLATFORM_PACKS
  FirstRunSetupStep.APPLY -> FirstRunSetupStep.PREFERENCES
  FirstRunSetupStep.RESULT -> FirstRunSetupStep.PREFERENCES
}

private fun normalizeRepoPath(repoPath: String): String = repoPath.trim().trimEnd('/').ifEmpty { "/" }

private fun isRenderableKind(kind: String?): Boolean = when (kind) {
  "horizontal skill", "platform pack skill", "add-on", "native agent" -> true
  else -> false
}

private fun TreeItemKind.isRenderableTreeItemKind(): Boolean = when (this) {
  TreeItemKind.SKILL,
  TreeItemKind.ADD_ON,
  TreeItemKind.NATIVE_AGENT,
  -> true
  TreeItemKind.GROUP,
  TreeItemKind.PLATFORM_PACK,
  TreeItemKind.GENERATED_ARTIFACT,
  TreeItemKind.PLACEHOLDER,
  -> false
}

private fun RenderTarget.renderBlocks(summary: RenderSummary): List<RenderBlock> {
  val statusBlock = RenderBlock(
    header = "===== render target: $label ($treeItemId) =====",
    content = buildString {
      append("state: ")
      append(summary.state.name.lowercase())
      append('\n')
      if (summary.runtimeExceptionName != null) {
        append("exception: ")
        append(summary.runtimeExceptionName)
        if (!summary.runtimeExceptionMessage.isNullOrBlank()) {
          append(": ")
          append(summary.runtimeExceptionMessage)
        }
        append('\n')
      }
      append("generated artifacts: ")
      append(summary.generatedArtifacts.size)
      append('\n')
    },
  )
  return listOf(statusBlock) + summary.blocks.map { block ->
    block.copy(header = "[$label] ${block.header}")
  }
}

private fun List<SkillBillTreeItem>.flatten(): List<SkillBillTreeItem> =
  flatMap { item -> listOf(item) + item.children.flatten() }

private fun List<SkillBillTreeItem>.visibleItems(expandedNodeIds: Set<String>): List<SkillBillTreeItem> =
  flatMap { item ->
    if (item.id in expandedNodeIds) {
      listOf(item) + item.children.visibleItems(expandedNodeIds)
    } else {
      listOf(item)
    }
  }

private fun SkillBillTreeItem.snapshot(): SkillBillTreeItem = copy(children = children.map(SkillBillTreeItem::snapshot))

private fun reconcileExpandedNodeIds(
  previousExpandedNodeIds: Set<String>,
  treeItems: List<SkillBillTreeItem>,
  preserveExpansion: Boolean,
): Set<String> {
  val expandableIds = treeItems.flatten().filter { it.children.isNotEmpty() }.map(SkillBillTreeItem::id).toSet()
  return if (preserveExpansion) {
    previousExpandedNodeIds.intersect(expandableIds)
  } else {
    emptySet()
  }
}

private fun ValidationSummary.failedForCommit(): Boolean =
  state == ValidationRunState.FAILED || errorCount > 0 || runtimeExceptionName != null
