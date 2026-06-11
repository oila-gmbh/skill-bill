package skillbill.desktop.feature.skillbill.state

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.ScreenScope
import skillbill.desktop.core.datastore.DesktopFirstRunPreferences
import skillbill.desktop.core.datastore.DesktopPreferenceStore
import skillbill.desktop.core.domain.model.AuthoredContentDocument
import skillbill.desktop.core.domain.model.AuthoringSaveResult
import skillbill.desktop.core.domain.model.BaselineReviewLayerSuggestion
import skillbill.desktop.core.domain.model.ConfirmDeletionState
import skillbill.desktop.core.domain.model.DesktopSkillRemovalRequest
import skillbill.desktop.core.domain.model.DesktopSkillRemovalResult
import skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget
import skillbill.desktop.core.domain.model.DirtyEditorPrompt
import skillbill.desktop.core.domain.model.DirtyEditorPromptReason
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.FirstRunApplyResult
import skillbill.desktop.core.domain.model.FirstRunDiscoveryResult
import skillbill.desktop.core.domain.model.FirstRunInstallOutcome
import skillbill.desktop.core.domain.model.FirstRunInstallStatus
import skillbill.desktop.core.domain.model.FirstRunPlanResult
import skillbill.desktop.core.domain.model.FirstRunPlatformSelectionMode
import skillbill.desktop.core.domain.model.FirstRunSetupDiscovery
import skillbill.desktop.core.domain.model.FirstRunSetupRequest
import skillbill.desktop.core.domain.model.FirstRunSetupState
import skillbill.desktop.core.domain.model.FirstRunSetupStep
import skillbill.desktop.core.domain.model.FirstRunTelemetryLevel
import skillbill.desktop.core.domain.model.PartialMutationPostMortem
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
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.DesktopFirstRunGateway
import skillbill.desktop.core.domain.service.InstalledWorkspaceLocator
import skillbill.desktop.core.domain.service.RecentRepoRepository
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.RuntimeScaffoldGateway
import skillbill.desktop.core.domain.service.RuntimeSkillRemoveGateway
import skillbill.desktop.core.domain.service.SkillTreeService
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(ScreenScope::class)
class SkillBillViewModel(
  private val repoSessionService: RepoSessionService,
  private val skillTreeService: SkillTreeService,
  private val authoringGateway: AuthoringGateway,
  private val recentRepoRepository: RecentRepoRepository,
  private val scaffoldGateway: RuntimeScaffoldGateway,
  private val firstRunGateway: DesktopFirstRunGateway,
  private val desktopPreferenceStore: DesktopPreferenceStore,
  private val skillRemoveGateway: RuntimeSkillRemoveGateway,
  private val installedWorkspaceLocator: InstalledWorkspaceLocator,
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

  private var confirmDeletion: ConfirmDeletionState? = null

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

  private var currentState = createState()

  init {
    val capturedInstalledRoot = installedWorkspaceRoot
    if (capturedInstalledRoot != null) {
      currentState = openRepo(capturedInstalledRoot, preserveSelection = false)
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
    selectedTreeItemId = itemId.takeIf(::containsTreeItem)
    selectedTreeItemId?.let { selected -> expandedNodeIds = expandedNodeIds + ancestorIdsOf(selected) }
    loadEditorForSelection()
    currentState = createState()
    return currentState
  }

  fun refresh(): SkillBillState {
    beginRefresh()
    return finishRefresh()
  }

  fun beginRefreshAfterScaffold(): RepoLoadRequest {
    activeOperationToken += 1
    busyOperation = SkillBillBusyOperation.REFRESH
    currentState = createState()
    val path = currentSession?.repoPath ?: repoPathText
    return repoLoadRequest(repoPath = path, preserveSelection = true)
  }

  fun finishRefreshAfterScaffold(result: RepoLoadResult): SkillBillState = finishRepoLoad(result)

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
    if (!preserveDirtyEditor) {
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
    activeScaffoldToken += 1
    confirmDeletion = null
    activeRemovalToken += 1
    validateAgentConfigsSummary = ValidateAgentConfigsSummary.empty
    activeValidateAgentConfigsToken += 1
    loadEditorForSelection()
  }

  private fun createState(): SkillBillState {
    val session = currentSession
    val resolvedTreeItemId = selectedTreeItemId?.takeIf(::containsTreeItem)
    val editor = editorForSelection(resolvedTreeItemId)
    val state = SkillBillState(
      selectedRepoPath = session?.repoPath,
      repoPathText = repoPathText,
      repoStatus = session?.loadStatus ?: RepoLoadStatus.empty,
      treeItems = treeItems,
      selectedTreeItemId = resolvedTreeItemId,
      expandedNodeIds = expandedNodeIds,
      busyOperation = busyOperation,
      editor = editor,
      statusBar = statusBarFor(session?.repoPath, editor),
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
      dirtyRepoWarning = false,
      baselineLayerSuggestion = suggestedBaselineLayer?.form,
      baselineLayerSuggestionLabel = suggestedBaselineLayer?.label,
    )
    return state.copy(
      commandPalette = paletteState,
      scaffoldWizard = wizardState,
      firstRunSetup = firstRunSetup,
      confirmDeletion = confirmDeletion,
      validateAgentConfigs = validateAgentConfigsSummary,
      partialMutationPostMortem = partialMutationPostMortem,
    )
  }

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
    if (busyOperation != null || scaffoldWizard != null || firstRunSetup != null) {
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
      busyOperation != null
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
      busyOperation != null
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

  private fun describe(error: Throwable): String {
    val message = error.message
    val name = error::class.simpleName ?: error::class.qualifiedName ?: "Throwable"
    return if (message.isNullOrBlank()) name else "$name: $message"
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

  private fun statusBarFor(repoPath: String?, editor: EditorPlaceholder): SkillBillStatusBar = SkillBillStatusBar(
    targetCount = treeItems.flatten().count { it.children.isEmpty() },
    repoPathLabel = repoPath ?: "no repo",
    readOnlyModeLabel = when {
      isEditorDirty() -> "dirty"
      editor.editable -> SkillBillStatusBar.EDITABLE_MODE_LABEL
      else -> SkillBillStatusBar.READ_ONLY_MODE_LABEL
    },
    policyLabel = SkillBillStatusBar.POLICY_LABEL,
  )

  private fun latestInstallSetupRequest(): FirstRunSetupRequest? {
    val legacyFallback = desktopPreferenceStore.firstRunPreferences.value.toLegacySetupRequestOrNull()
    return firstRunGateway.latestReusableSetupRequest(legacyFallback)
  }

  private fun containsTreeItem(itemId: String): Boolean = treeItems.flatten().any { item -> item.id == itemId }

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
      dirtyRepoWarning = false,
      overrideDirtyRepo = false,
      busy = false,
    )
    currentState = createState()
    return currentState
  }

  fun canOpenScaffoldWizard(): Boolean = canStartScaffoldAction()

  fun beginOpenScaffoldWizard(kind: ScaffoldKind): ScaffoldCatalogRequest? {
    if (!canStartScaffoldAction() || !kind.creationSupported) {
      return null
    }
    return ScaffoldCatalogRequest(kind = kind, session = currentSession)
  }

  suspend fun runOpenScaffoldWizard(request: ScaffoldCatalogRequest): ScaffoldCatalogResponse {
    val snapshot = scaffoldGateway.catalogSnapshot(request.session)
    return ScaffoldCatalogResponse(kind = request.kind, snapshot = snapshot)
  }

  fun finishOpenScaffoldWizard(response: ScaffoldCatalogResponse): SkillBillState =
    openScaffoldWizard(response.kind, response.snapshot)

  internal suspend fun fetchScaffoldCatalogSnapshot(): ScaffoldCatalogSnapshot =
    scaffoldGateway.catalogSnapshot(currentSession)

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

  fun showConfirmDeletion(target: DesktopSkillRemovalTarget): SkillBillState {
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
    partialMutationPostMortem = null
    val current = confirmDeletion
    if (current != null) {
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
        partialMutationLocked = false,
      )
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

  fun showValidateAgentConfigsConsole(): SkillBillState {
    activeValidateAgentConfigsToken += 1
    validateAgentConfigsSummary = ValidateAgentConfigsSummary(
      lines = emptyList(),
      exitCode = null,
      running = true,
    )
    currentState = createState()
    return currentState
  }

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

  private fun isScaffoldPlanAllowed(wizard: ScaffoldWizardState): Boolean {
    val failure = wizard.executionResult as? ScaffoldRunResult.Failed ?: return true
    return failure.rollbackComplete
  }

  fun isScaffoldPlanAllowed(): Boolean = scaffoldWizard?.let(::isScaffoldPlanAllowed) ?: true

  private fun canStartScaffoldAction(): Boolean = busyOperation == null

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

enum class ScaffoldRunMode {
  DRY_RUN,
  EXECUTE,
}

data class ScaffoldRunRequest(
  val token: Long,
  val payload: ScaffoldPayload,
  val mode: ScaffoldRunMode,
)

data class ScaffoldCatalogRequest(
  val kind: ScaffoldKind,
  val session: RepoSession?,
)

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
