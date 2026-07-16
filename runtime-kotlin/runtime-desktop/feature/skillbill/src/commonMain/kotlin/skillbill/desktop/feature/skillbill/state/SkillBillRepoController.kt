package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.DirtyEditorPrompt
import skillbill.desktop.core.domain.model.DirtyEditorPromptReason
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.ValidateAgentConfigsSummary
import skillbill.desktop.core.domain.service.InstalledWorkspaceLocator
import skillbill.desktop.core.domain.service.RecentRepoRepository
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService

internal class SkillBillRepoController(
  private val viewState: SkillBillViewState,
  private val repoSessionService: RepoSessionService,
  private val skillTreeService: SkillTreeService,
  private val recentRepoRepository: RecentRepoRepository,
  private val installedWorkspaceLocator: InstalledWorkspaceLocator,
) {
  fun state(selectedTreeItemId: String? = viewState.currentState.selectedTreeItemId): SkillBillState = with(viewState) {
    val resolvedSelection = selectedTreeItemId?.takeIf { containsTreeItem(it) }
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
    currentState
  }

  fun updateRepoPathText(repoPath: String): SkillBillState = with(viewState) {
    repoPathText = repoPath
    currentState = createState()
    currentState
  }

  fun selectRepoPath(repoPath: String = viewState.repoPathText): SkillBillState {
    val state = beginSelectRepoPath(repoPath)
    if (state.dirtyEditorPrompt != null) {
      return state
    }
    return finishSelectRepoPath()
  }

  fun beginSelectRepoPath(repoPath: String = viewState.repoPathText): SkillBillState = with(viewState) {
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
    currentState
  }

  fun finishSelectRepoPath(repoPath: String = viewState.repoPathText): SkillBillState =
    finishSelectRepoPath(loadRepo(repoLoadRequest(repoPath = repoPath, preserveSelection = false)))

  fun finishSelectRepoPath(result: RepoLoadResult): SkillBillState = finishRepoLoad(result)

  suspend fun finishSelectRepoPathAndRemember(result: RepoLoadResult): SkillBillState {
    val active = result.request.token == viewState.activeOperationToken
    val state = finishSelectRepoPath(result)
    if (active && state.repoStatus.state == RepoLoadState.LOADED) {
      state.selectedRepoPath?.let { repoPath -> recentRepoRepository.rememberRepoPath(repoPath) }
    }
    return state
  }

  fun beginReturnToInstalledWorkspace(): SkillBillState = with(viewState) {
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
    currentState
  }

  fun selectTreeItem(itemId: String): SkillBillState = with(viewState) {
    if (isEditorDirty() && itemId != selectedTreeItemId) {
      dirtyEditorPrompt = DirtyEditorPrompt(
        reason = DirtyEditorPromptReason.SELECTION_CHANGE,
        targetTreeItemId = itemId,
      )
      currentState = createState()
      return currentState
    }
    selectTreeItemIgnoringDirty(itemId)
  }

  fun resolveGeneratedArtifactTreeItemId(artifactPath: String): String? = skillTreeService
    .resolveGeneratedArtifactTreeItemId(viewState.currentSession, artifactPath)
    ?.takeIf { viewState.containsTreeItem(it) }

  fun selectTreeItemIgnoringDirty(itemId: String): SkillBillState = with(viewState) {
    selectedTreeItemId = itemId.takeIf { containsTreeItem(it) }
    selectedTreeItemId?.let { selected -> expandedNodeIds = expandedNodeIds + ancestorIdsOf(selected) }
    loadEditorForSelection()
    currentState = createState()
    currentState
  }

  fun refresh(): SkillBillState {
    beginRefresh()
    return finishRefresh()
  }

  fun beginRefreshAfterScaffold(): RepoLoadRequest = with(viewState) {
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.REFRESH
    currentState = createState()
    val path = currentSession?.repoPath ?: repoPathText
    repoLoadRequest(repoPath = path, preserveSelection = true)
  }

  fun finishRefreshAfterScaffold(result: RepoLoadResult): SkillBillState = finishRepoLoad(result)

  fun refreshAfterScaffold(): SkillBillState {
    val request = beginRefreshAfterScaffold()
    return finishRefreshAfterScaffold(loadRepo(request))
  }

  fun beginRefresh(): SkillBillState = with(viewState) {
    activeOperationToken += 1
    currentState = createState()
    currentState
  }

  fun finishRefresh(): SkillBillState = with(viewState) {
    if (dirtyEditorPrompt != null) {
      return currentState
    }
    val path = currentSession?.repoPath ?: repoPathText
    finishRefresh(loadRepo(repoLoadRequest(repoPath = path, preserveSelection = true)))
  }

  fun finishRefresh(result: RepoLoadResult): SkillBillState = with(viewState) {
    if (result.request.token != activeOperationToken) {
      return currentState
    }
    applyRefreshResult(result)
    currentState = createState()
    currentState
  }

  fun toggleExpanded(itemId: String): SkillBillState = with(viewState) {
    expandedNodeIds =
      if (itemId in expandedNodeIds) {
        expandedNodeIds - itemId
      } else {
        expandedNodeIds + itemId
      }
    currentState = createState()
    currentState
  }

  fun moveSelection(delta: Int): SkillBillState = with(viewState) {
    val visibleIds = treeItems.visibleItems(expandedNodeIds).map(SkillBillTreeItem::id)
    if (visibleIds.isEmpty()) {
      if (isEditorDirty() && selectedTreeItemId != null) {
        dirtyEditorPrompt = DirtyEditorPrompt(reason = DirtyEditorPromptReason.SELECTION_CHANGE)
        currentState = createState()
        return currentState
      }
      selectedTreeItemId = null
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
    loadEditorForSelection()
    currentState = createState()
    currentState
  }

  fun chooseRepoDirectory(repoPath: String?): SkillBillState = with(viewState) {
    val selectedPath = repoPath?.trim().orEmpty()
    if (selectedPath.isBlank()) {
      busyOperation = null
      currentState = createState()
      return currentState
    }
    repoPathText = selectedPath
    selectRepoPath(selectedPath)
  }

  fun beginChooseRepoDirectory(): SkillBillState = with(viewState) {
    if (isEditorDirty()) {
      dirtyEditorPrompt = DirtyEditorPrompt(reason = DirtyEditorPromptReason.CHOOSE_DIRECTORY)
      currentState = createState()
      return currentState
    }
    busyState(SkillBillBusyOperation.CHOOSE_DIRECTORY)
  }

  fun busyState(operation: SkillBillBusyOperation): SkillBillState = with(viewState) {
    activeOperationToken += 1
    busyOperation = operation
    currentState = createState()
    currentState
  }

  fun repoLoadRequest(repoPath: String, preserveSelection: Boolean): RepoLoadRequest = with(viewState) {
    RepoLoadRequest(
      token = activeOperationToken,
      repoPath = repoPath.trim(),
      preserveSelection = preserveSelection,
      previousRepoPath = currentSession?.repoPath,
      previousSelection = selectedTreeItemId,
      previousExpandedNodeIds = expandedNodeIds,
    )
  }

  fun loadRepo(request: RepoLoadRequest): RepoLoadResult {
    val session = repoSessionService.open(request.repoPath)
    val loadedTreeItems = skillTreeService.treeFor(session).map(SkillBillTreeItem::snapshot)
    return RepoLoadResult(
      request = request,
      session = session,
      treeItems = loadedTreeItems,
    )
  }

  fun finishRepoLoad(result: RepoLoadResult): SkillBillState = with(viewState) {
    if (result.request.token != activeOperationToken) {
      return currentState
    }
    applyRepoLoadResult(result)
    currentState = createState()
    currentState
  }

  internal fun openRepo(repoPath: String, preserveSelection: Boolean): SkillBillState =
    finishRepoLoad(loadRepo(repoLoadRequest(repoPath = repoPath, preserveSelection = preserveSelection)))

  fun beginStartup(): StartupRequest? = with(viewState) {
    if (startupRequested) {
      return null
    }
    startupRequested = true
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.OPEN_REPO
    currentState = createState()
    StartupRequest(token = activeOperationToken)
  }

  suspend fun runStartup(request: StartupRequest): StartupResult {
    val installedRoot = installedWorkspaceLocator.locate().takeIf { it.availability }?.path?.takeIf { it.isNotBlank() }
    val recentRepoPath = recentRepoRepository.recentRepoPath().orEmpty()
    val targetRepoPath = installedRoot ?: recentRepoPath.takeIf { it.isNotBlank() }
    val repoLoadResult = targetRepoPath?.let { repoPath ->
      loadRepo(
        RepoLoadRequest(
          token = request.token,
          repoPath = repoPath,
          preserveSelection = false,
          previousRepoPath = null,
          previousSelection = null,
          previousExpandedNodeIds = emptySet(),
        ),
      )
    }
    return StartupResult(
      request = request,
      installedWorkspaceRoot = installedRoot,
      recentRepoPath = recentRepoPath,
      repoLoadResult = repoLoadResult,
    )
  }

  fun finishStartup(result: StartupResult): SkillBillState = with(viewState) {
    if (result.request.token != activeOperationToken) {
      return currentState
    }
    installedWorkspaceRoot = result.installedWorkspaceRoot
    normalizedInstalledWorkspaceRoot = installedWorkspaceRoot?.let(::normalizeRepoPath)
    val loadResult = result.repoLoadResult
    if (loadResult != null) {
      applyRepoLoadResult(loadResult)
    } else {
      repoPathText = result.recentRepoPath
      busyOperation = null
    }
    currentState = createState()
    currentState
  }

  private fun applyRefreshResult(result: RepoLoadResult) = with(viewState) {
    val request = result.request
    val session = result.session
    val sameRecognizedRepo =
      request.preserveSelection &&
        session.isRecognizedSkillBillRepo &&
        request.previousRepoPath == session.repoPath
    if (!sameRecognizedRepo) {
      applyRepoLoadResult(result)
      return@with
    }

    val loadedTreeItems = result.treeItems
    val preserveDirtyEditor = isEditorDirty()
    currentSession = session
    repositoryTreeItems = loadedTreeItems
    repoPathText = session.repoPath.ifBlank { request.repoPath }
    selectedTreeItemId = request.previousSelection?.takeIf { containsTreeItem(it) }
    expandedNodeIds = reconcileExpandedNodeIds(
      request.previousExpandedNodeIds,
      loadedTreeItems,
      preserveExpansion = true,
    )
    busyOperation = null
    if (!preserveDirtyEditor) {
      loadEditorForSelection()
    }
  }

  private fun applyRepoLoadResult(result: RepoLoadResult) = with(viewState) {
    val request = result.request
    val session = result.session
    val loadedTreeItems = result.treeItems
    currentSession = session
    repositoryTreeItems = loadedTreeItems
    repoPathText = session.repoPath.ifBlank { request.repoPath }
    val sameRepo = session.isRecognizedSkillBillRepo && request.previousRepoPath == session.repoPath
    val preserveSameRepoUi = request.preserveSelection && sameRepo
    selectedTreeItemId =
      request.previousSelection
        ?.takeIf { preserveSameRepoUi }
        ?.takeIf { containsTreeItem(it) }
    resetEditorDocument()
    expandedNodeIds =
      reconcileExpandedNodeIds(request.previousExpandedNodeIds, loadedTreeItems, preserveSameRepoUi)
    busyOperation = null
    activeScaffoldToken += 1
    confirmDeletion = null
    activeRemovalToken += 1
    validateAgentConfigsSummary = ValidateAgentConfigsSummary.empty
    activeValidateAgentConfigsToken += 1
    loadEditorForSelection()
  }
}
