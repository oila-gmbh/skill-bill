package skillbill.desktop.feature.skillbill.state

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.ScreenScope
import skillbill.desktop.core.domain.model.ChangedFile
import skillbill.desktop.core.domain.model.ChangedFileGroup
import skillbill.desktop.core.domain.model.ChangesSnapshot
import skillbill.desktop.core.domain.model.CommitEntry
import skillbill.desktop.core.domain.model.DockTab
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.GitOperationResult
import skillbill.desktop.core.domain.model.GitPublishingStatus
import skillbill.desktop.core.domain.model.GitPushTarget
import skillbill.desktop.core.domain.model.RenderRunState
import skillbill.desktop.core.domain.model.RenderSummary
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.domain.model.SkillBillStatusBar
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.ValidationIssue
import skillbill.desktop.core.domain.model.ValidationRunState
import skillbill.desktop.core.domain.model.ValidationSummary
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.RecentRepoRepository
import skillbill.desktop.core.domain.service.RenderGateway
import skillbill.desktop.core.domain.service.RepoSessionService
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
  private val validationGateway: ValidationGateway,
  private val renderGateway: RenderGateway,
  private val recentRepoRepository: RecentRepoRepository,
) {
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
  private var currentState = createState()

  init {
    if (repoPathText.isNotBlank()) {
      currentState = openRepo(repoPathText, preserveSelection = false)
    }
  }

  fun state(selectedTreeItemId: String? = currentState.selectedTreeItemId): SkillBillState {
    this.selectedTreeItemId = selectedTreeItemId?.takeIf(::containsTreeItem)
    currentState = createState()
    return currentState
  }

  fun updateRepoPathText(repoPath: String): SkillBillState {
    repoPathText = repoPath
    currentState = createState()
    return currentState
  }

  fun selectRepoPath(repoPath: String = repoPathText): SkillBillState {
    beginSelectRepoPath(repoPath)
    return finishSelectRepoPath()
  }

  fun beginSelectRepoPath(repoPath: String = repoPathText): SkillBillState {
    repoPathText = repoPath.trim()
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

  fun selectTreeItem(itemId: String): SkillBillState {
    val previousSelectedTreeItemId = selectedTreeItemId
    selectedTreeItemId = itemId.takeIf(::containsTreeItem)
    if (selectedTreeItemId != previousSelectedTreeItemId) {
      // F-202: render output is keyed by tree-item id, so the prior selection's PASSED/FAILED
      // summary must not bleed into a new selection. Mirror the refresh/repo-switch reset (F-103).
      resetRenderForSelectionChange()
    }
    currentState = createState()
    return currentState
  }

  fun refresh(): SkillBillState {
    beginRefresh()
    return finishRefresh()
  }

  fun beginRefresh(): SkillBillState {
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.REFRESH
    currentState = createState()
    return currentState
  }

  fun finishRefresh(): SkillBillState {
    val path = currentSession?.repoPath ?: repoPathText
    currentState = finishRepoLoad(loadRepo(repoLoadRequest(repoPath = path, preserveSelection = true)))
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
      selectedTreeItemId = null
      if (selectedTreeItemId != previousSelectedTreeItemId) {
        // F-202: selection changed (cleared); render output is no longer for this selection.
        resetRenderForSelectionChange()
      }
      currentState = createState()
      return currentState
    }
    val currentIndex = visibleIds.indexOf(selectedTreeItemId).takeIf { it >= 0 }
    val nextIndex =
      when (currentIndex) {
        null -> if (delta >= 0) 0 else visibleIds.lastIndex
        else -> (currentIndex + delta).coerceIn(0, visibleIds.lastIndex)
      }
    selectedTreeItemId = visibleIds[nextIndex]
    if (selectedTreeItemId != previousSelectedTreeItemId) {
      // F-202: selection changed via keyboard movement; reset render to mirror selectTreeItem.
      resetRenderForSelectionChange()
    }
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

  private fun applyRepoLoadResult(result: RepoLoadResult) {
    val request = result.request
    val session = result.session
    val loadedTreeItems = result.treeItems
    currentSession = session
    treeItems = loadedTreeItems
    repoPathText = session.repoPath.ifBlank { request.repoPath }
    val sameRepo = session.isRecognizedSkillBillRepo && request.previousRepoPath == session.repoPath
    selectedTreeItemId =
      request.previousSelection
        ?.takeIf { request.preserveSelection && sameRepo }
        ?.takeIf(::containsTreeItem)
    expandedNodeIds =
      reconcileExpandedNodeIds(request.previousExpandedNodeIds, loadedTreeItems, request.preserveSelection && sameRepo)
    busyOperation = null
    // Reset validation on every successful refresh: on-disk state may have changed since the last run,
    // so prior PASSED/FAILED results are no longer trustworthy. (F-103)
    validation = ValidationSummary.unavailable
    // F-103: render output mirrors on-disk state and must also reset on refresh / repo-switch.
    render = RenderSummary.unavailable
    activeDockTab = DockTab.Validation
    // F-103: every per-snapshot git slice mirrors on-disk state and must reset on refresh / repo-switch.
    // Invalidate any in-flight git work so a late finish cannot reseed the stale slice on the new repo.
    activeGitOperationToken += 1
    activeGitDiffToken += 1
    activeHistoryToken += 1
    activeCommitToken += 1
    activePushToken += 1
    changesSnapshot = ChangesSnapshot.empty
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
    val editor = resolvedTreeItemId?.let(authoringGateway::describeSelection) ?: EditorPlaceholder.empty
    // F-201: capture the snapshot reference once so the file lookup and the state assembly see a
    // consistent slice even if refresh()/openRepo() rewrites changesSnapshot between reads.
    val capturedSnapshot = changesSnapshot
    val resolvedSelectedFile = selectedChangedFilePath?.let { path ->
      capturedSnapshot.files.firstOrNull { file -> file.path == path }
    }
    return SkillBillState(
      selectedRepoPath = session?.repoPath,
      repoPathText = repoPathText,
      repoStatus = session?.loadStatus ?: RepoLoadStatus.empty,
      treeItems = treeItems,
      selectedTreeItemId = resolvedTreeItemId,
      expandedNodeIds = expandedNodeIds,
      busyOperation = busyOperation,
      editor = editor,
      sourceControl = sourceControl,
      statusBar = statusBarFor(session?.repoPath, sourceControl.branchLabel),
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
      commitBusy = commitBusy,
      commitErrorMessage = commitErrorMessage,
      commitValidationFailed = commitValidationFailed,
      commitValidationRunning = commitValidationRunning,
      pushTarget = publishingStatus.pushTarget,
      aheadBehind = publishingStatus.aheadBehind,
      compareUrl = publishingStatus.compareUrl,
      pushBusy = pushBusy,
      pushErrorMessage = pushErrorMessage,
      pushStatusErrorMessage = publishingStatus.errorMessage,
      canonicalPushConfirmationRequired = canonicalPushConfirmationRequired,
    )
  }

  fun updateCommitMessage(message: String): SkillBillState {
    if (commitBusy || commitValidationRunning) {
      currentState = createState()
      return currentState
    }
    commitMessage = message
    commitErrorMessage = null
    if (commitValidationFailed) {
      commitValidationFailed = false
      failedValidationStagedAuthoredPaths = null
    }
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
      previousValidationSummary = previousValidationSummary,
    )
  }

  fun runValidate(request: ValidationRunRequest): ValidationRunResult {
    val summary = validationGateway.validate(request.session)
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
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.RENDER
    val previousRenderSummary = render
    render = RenderSummary(state = RenderRunState.RUNNING)
    activeDockTab = DockTab.Console
    currentState = createState()
    return RenderRunRequest(
      token = activeOperationToken,
      session = currentSession,
      treeItemId = selectedTreeItemId,
      previousRenderSummary = previousRenderSummary,
    )
  }

  fun runRender(request: RenderRunRequest): RenderRunResult {
    val summary = if (request.treeItemId == null) {
      RenderSummary.unavailable
    } else {
      renderGateway.render(request.session, request.treeItemId)
    }
    return RenderRunResult(request = request, summary = summary)
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

  fun beginGitRefresh(): GitRefreshRequest {
    activeGitOperationToken += 1
    val previousSnapshot = changesSnapshot
    changesBusy = true
    currentState = createState()
    return GitRefreshRequest(
      token = activeGitOperationToken,
      session = currentSession,
      previousSnapshot = previousSnapshot,
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
    changesSnapshot = result.snapshot
    invalidateFailedValidationOverrideIfStagedAuthoredChanged()
    result.publishingStatus?.let { replacePublishingStatus(it, clearConfirmation = true) }
    // F-C701: cache branch label so createState() can derive sourceControl without forking git.
    if (result.snapshot.branchLabel.isNotEmpty()) {
      currentBranchLabel = result.snapshot.branchLabel
    }
    // If the previously-selected changed file is no longer in the new snapshot, clear it so the diff
    // pane does not stay attached to a stale path.
    val stillExists = selectedChangedFilePath?.let { path ->
      result.snapshot.files.any { it.path == path }
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
    val file = captured.files.firstOrNull { it.path == path } ?: run {
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

  fun runStage(request: StageRequest): GitRefreshResult {
    val snapshot = runCatching {
      when (request.action) {
        StageAction.STAGE -> gitGateway.stage(request.session, request.paths)
        StageAction.UNSTAGE -> gitGateway.unstage(request.session, request.paths)
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
    if (busyOperation != null || pushBusy || commitBusy || commitValidationRunning || changesBusy) {
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
    if (allowCanonicalRemote && (!canonicalPushConfirmationRequired || canonicalPushConfirmationTarget != target)) {
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

  // --- History ---

  fun beginLoadHistory(limit: Int = DEFAULT_HISTORY_LIMIT): HistoryLoadRequest {
    activeHistoryToken += 1
    historyBusy = true
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
    selectedTreeItemId = resolvedId
    expandedNodeIds = expandedNodeIds + ancestorIdsOf(resolvedId)
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

  private fun statusBarFor(repoPath: String?, branchLabel: String): SkillBillStatusBar = SkillBillStatusBar(
    targetCount = treeItems.flatten().count { it.children.isEmpty() },
    repoPathLabel = repoPath ?: "no repo",
    branchLabel = branchLabel,
    readOnlyModeLabel = SkillBillStatusBar.READ_ONLY_MODE_LABEL,
    policyLabel = SkillBillStatusBar.POLICY_LABEL,
  )

  private fun canCommit(): Boolean = hasCommitInputs() &&
    busyOperation == null &&
    !changesBusy &&
    !commitBusy &&
    !commitValidationRunning &&
    !pushBusy

  private fun hasCommitInputs(): Boolean = currentSession?.isRecognizedSkillBillRepo == true &&
    commitMessage.isNotBlank() &&
    changesSnapshot.files.any { it.group == ChangedFileGroup.STAGED && !it.isGenerated }

  private fun hasCurrentFailedValidationOverride(): Boolean =
    commitValidationFailed && failedValidationStagedAuthoredPaths == stagedAuthoredPaths(changesSnapshot)

  private fun invalidateFailedValidationOverrideIfStagedAuthoredChanged() {
    val failedPaths = failedValidationStagedAuthoredPaths ?: return
    if (failedPaths != stagedAuthoredPaths(changesSnapshot)) {
      commitValidationFailed = false
      failedValidationStagedAuthoredPaths = null
    }
  }

  private fun stagedAuthoredPaths(snapshot: ChangesSnapshot): Set<String> = snapshot.files
    .filter { file -> file.group == ChangedFileGroup.STAGED && !file.isGenerated }
    .map { file -> file.path }
    .toSet()

  private fun replacePublishingStatus(status: GitPublishingStatus, clearConfirmation: Boolean) {
    if (clearConfirmation || status.pushTarget != publishingStatus.pushTarget) {
      canonicalPushConfirmationRequired = false
      canonicalPushConfirmationTarget = null
    }
    publishingStatus = status
  }

  private fun containsTreeItem(itemId: String): Boolean = treeItems.flatten().any { item -> item.id == itemId }
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
  val previousValidationSummary: ValidationSummary,
)

data class ValidationRunResult(
  val request: ValidationRunRequest,
  val summary: ValidationSummary,
)

data class RenderRunRequest(
  val token: Long,
  val session: RepoSession?,
  val treeItemId: String?,
  val previousRenderSummary: RenderSummary,
)

data class RenderRunResult(
  val request: RenderRunRequest,
  val summary: RenderSummary,
)

// Default cap on the number of recent commits surfaced in the History tab. Kept here so tests can
// exercise the same default and route fan-out can override per-call when needed.
const val DEFAULT_HISTORY_LIMIT: Int = 50

data class GitRefreshRequest(
  val token: Long,
  val session: RepoSession?,
  val previousSnapshot: ChangesSnapshot,
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

data class HistoryLoadRequest(
  val token: Long,
  val session: RepoSession?,
  val limit: Int,
  val pathFilter: String?,
  val previousHistory: List<CommitEntry>,
  val previousErrorMessage: String?,
)

data class HistoryLoadResult(
  val request: HistoryLoadRequest,
  val commits: List<CommitEntry>,
  val errorMessage: String?,
)

private fun isRenderableKind(kind: String?): Boolean = when (kind) {
  "horizontal skill", "platform pack skill", "add-on", "native agent" -> true
  else -> false
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
    previousExpandedNodeIds.intersect(expandableIds) + topLevelExpandableIds(treeItems)
  } else {
    topLevelExpandableIds(treeItems)
  }
}

private fun topLevelExpandableIds(treeItems: List<SkillBillTreeItem>): Set<String> =
  treeItems.filter { item -> item.children.isNotEmpty() }.map(SkillBillTreeItem::id).toSet()

private fun ValidationSummary.failedForCommit(): Boolean =
  state == ValidationRunState.FAILED || errorCount > 0 || runtimeExceptionName != null
