package skillbill.desktop.feature.skillbill.state

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.ScreenScope
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.domain.model.SkillBillStatusBar
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.RecentRepoRepository
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(ScreenScope::class)
class SkillBillViewModel(
  private val repoSessionService: RepoSessionService,
  private val skillTreeService: SkillTreeService,
  private val authoringGateway: AuthoringGateway,
  private val gitGateway: GitGateway,
  private val recentRepoRepository: RecentRepoRepository,
) {
  private var repoPathText: String = recentRepoRepository.recentRepoPath().orEmpty()
  private var currentSession: RepoSession? = null
  private var treeItems: List<SkillBillTreeItem> = emptyList()
  private var selectedTreeItemId: String? = null
  private var expandedNodeIds: Set<String> = emptySet()
  private var busyOperation: SkillBillBusyOperation? = null
  private var activeOperationToken = 0L
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
    selectedTreeItemId = itemId.takeIf(::containsTreeItem)
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
    val visibleIds = treeItems.visibleItems(expandedNodeIds).map(SkillBillTreeItem::id)
    if (visibleIds.isEmpty()) {
      selectedTreeItemId = null
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
    currentState = createState()
    return currentState
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
  }

  private fun createState(): SkillBillState {
    val session = currentSession
    val resolvedTreeItemId = selectedTreeItemId?.takeIf(::containsTreeItem)
    val sourceControl = gitGateway.statusFor(session)
    return SkillBillState(
      selectedRepoPath = session?.repoPath,
      repoPathText = repoPathText,
      repoStatus = session?.loadStatus ?: RepoLoadStatus.empty,
      treeItems = treeItems,
      selectedTreeItemId = resolvedTreeItemId,
      expandedNodeIds = expandedNodeIds,
      busyOperation = busyOperation,
      editor = resolvedTreeItemId?.let(authoringGateway::describeSelection) ?: EditorPlaceholder.empty,
      sourceControl = sourceControl,
      statusBar = statusBarFor(session?.repoPath, sourceControl.branchLabel),
    )
  }

  private fun statusBarFor(repoPath: String?, branchLabel: String): SkillBillStatusBar = SkillBillStatusBar(
    targetCount = treeItems.flatten().count { it.children.isEmpty() },
    repoPathLabel = repoPath ?: "no repo",
    branchLabel = branchLabel,
    readOnlyModeLabel = SkillBillStatusBar.READ_ONLY_MODE_LABEL,
    policyLabel = SkillBillStatusBar.POLICY_LABEL,
  )

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
