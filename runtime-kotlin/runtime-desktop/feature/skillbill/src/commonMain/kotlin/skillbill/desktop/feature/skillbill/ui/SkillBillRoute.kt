@file:Suppress("FunctionName")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.skillbill.designsystem.generated.resources.Res
import dev.skillbill.designsystem.generated.resources.scaffold_add_on_location_choose_title
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import skillbill.desktop.core.domain.model.CommandPaletteResult
import skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget
import skillbill.desktop.core.domain.model.DirtyEditorPromptReason
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.ui.di.rememberScreenComponent
import skillbill.desktop.feature.skillbill.di.SkillBillComponent

@Suppress("DEPRECATION")
@Composable
fun SkillBillRoute(
  selectedSourceId: String?,
  canNavigateBack: Boolean,
  onNavigateBack: () -> Unit,
  onSourceRouteSelected: (String) -> Unit = {},
) {
  val component = rememberScreenComponent<SkillBillComponent>()
  val dispatcherProvider = component.dispatcherProvider
  val viewModel = component.viewModel
  val coroutineScope = rememberCoroutineScope()
  var state by remember(viewModel, selectedSourceId) { mutableStateOf(viewModel.state(selectedSourceId)) }
  var pendingRepoFileChangeKind by remember(viewModel) { mutableStateOf<RepoFileChangeKind?>(null) }
  var pendingRepoFileChangeRefresh by remember(viewModel) { mutableStateOf(false) }
  var repoFileChangePulse by remember(viewModel) { mutableStateOf(0) }
  var workLoadJob by remember(viewModel) { mutableStateOf<Job?>(null) }
  val addonLocationChooserTitle = stringResource(Res.string.scaffold_add_on_location_choose_title)

  fun runEditorSave() {
    val request = viewModel.beginSaveEditor()
    state = viewModel.state()
    if (request != null) {
      coroutineScope.launch {
        val result = withContext(dispatcherProvider.default) {
          if (request.managedEdit == null) viewModel.runSaveEditor(request) else viewModel.runManagedSaveEditor(request)
        }
        state = viewModel.finishSaveEditor(result)
      }
    }
  }

  fun runRepoLoad(
    preserveSelection: Boolean,
    repoPath: String = state.selectedRepoPath ?: state.repoPathText,
    afterLoad: () -> Unit = {},
  ) {
    val request = viewModel.repoLoadRequest(
      repoPath = repoPath,
      preserveSelection = preserveSelection,
    )
    coroutineScope.launch {
      val result = withContext(dispatcherProvider.default) { viewModel.loadRepo(request) }
      state = if (preserveSelection) {
        viewModel.finishRepoLoad(result)
      } else {
        viewModel.finishSelectRepoPathAndRemember(result)
      }
      afterLoad()
    }
  }

  fun runRefreshLoad() {
    val request = viewModel.repoLoadRequest(
      repoPath = state.selectedRepoPath ?: state.repoPathText,
      preserveSelection = true,
    )
    coroutineScope.launch {
      val result = withContext(dispatcherProvider.default) { viewModel.loadRepo(request) }
      state = viewModel.finishRefresh(result)
    }
  }

  fun loadInstalledWorkspace() {
    val request = viewModel.repoLoadRequest(repoPath = state.repoPathText, preserveSelection = false)
    coroutineScope.launch {
      val result = withContext(dispatcherProvider.default) { viewModel.loadRepo(request) }
      state = viewModel.finishRepoLoad(result)
    }
  }

  fun runChooseRepoDirectory() {
    val repoPath = chooseRepoDirectory(state.repoPathText)
    if (repoPath.isNullOrBlank()) {
      state = viewModel.chooseRepoDirectory(repoPath)
    } else {
      state = viewModel.beginSelectRepoPath(repoPath)
      if (state.dirtyEditorPrompt == null && state.busyOperation == SkillBillBusyOperation.OPEN_REPO) {
        runRepoLoad(preserveSelection = false, repoPath = state.repoPathText)
      }
    }
  }

  fun runChooseAddonLocationPath() {
    val fields = state.scaffoldWizard?.formFields ?: return
    val initialPath = fields.addonLocationPath.takeIf { it.isNotBlank() }
      ?: state.selectedRepoPath
      ?: state.repoPathText
    val selectedPath = chooseDirectory(initialPath, title = addonLocationChooserTitle)
    if (!selectedPath.isNullOrBlank()) {
      state = viewModel.updateScaffoldForm { it.copy(addonLocationPath = selectedPath) }
    }
  }

  fun runDiscardedDirtyPrompt() {
    val prompt = state.dirtyEditorPrompt
    val previousSelection = state.selectedTreeItemId
    state = viewModel.discardDirtyEditorPrompt()
    when (prompt?.reason) {
      DirtyEditorPromptReason.SELECTION_CHANGE -> {
        val selected = state.selectedTreeItemId
        if (selected != null && selected != previousSelection) {
          state = viewModel.state()
          onSourceRouteSelected(selected)
        }
      }
      DirtyEditorPromptReason.REFRESH -> {
        runRefreshLoad()
      }
      DirtyEditorPromptReason.REPO_SWITCH -> {
        if (state.busyOperation == SkillBillBusyOperation.OPEN_REPO) {
          runRepoLoad(preserveSelection = false, repoPath = state.repoPathText)
        }
      }
      DirtyEditorPromptReason.RETURN_TO_INSTALLED_WORKSPACE -> {
        if (state.busyOperation == SkillBillBusyOperation.OPEN_REPO) {
          loadInstalledWorkspace()
        }
      }
      DirtyEditorPromptReason.CHOOSE_DIRECTORY -> {
        if (state.busyOperation == SkillBillBusyOperation.CHOOSE_DIRECTORY) {
          runChooseRepoDirectory()
        }
      }
      null -> Unit
    }
  }

  fun canStartRepoScopedAction(): Boolean = state.busyOperation == null &&
    state.firstRunSetup == null

  fun runFirstRunDiscovery() {
    val request = viewModel.beginFirstRunDiscovery() ?: return
    state = viewModel.state()
    coroutineScope.launch {
      val response = withContext(dispatcherProvider.default) { viewModel.runFirstRunDiscovery(request) }
      state = viewModel.finishFirstRunDiscovery(response)
    }
  }

  fun runFirstRunApply() {
    val request = viewModel.beginFirstRunApply() ?: return
    state = viewModel.state()
    coroutineScope.launch {
      val response = withContext(dispatcherProvider.default) { viewModel.runFirstRunApply(request) }
      state = viewModel.finishFirstRunApply(response)
    }
  }

  fun runTreeItemSelection(itemId: String) {
    if (canStartRepoScopedAction()) {
      if (itemId.startsWith("${skillbill.desktop.feature.skillbill.state.MACHINE_SKILLS_ROOT_ID}:skill:")) {
        coroutineScope.launch {
          state = withContext(dispatcherProvider.default) { viewModel.openMachineSkillTreeItem(itemId) }
          state.selectedTreeItemId?.let(onSourceRouteSelected)
        }
        return
      }
      val previousSelection = state.selectedTreeItemId
      state = viewModel.selectTreeItem(itemId)
      if (state.selectedTreeItemId != previousSelection) {
        state = viewModel.state()
        state.selectedTreeItemId?.let(onSourceRouteSelected)
      }
    }
  }

  fun runGeneratedArtifactSelection(artifactPath: String) {
    if (canStartRepoScopedAction()) {
      executeGeneratedArtifactSelection(
        artifactPath = artifactPath,
        resolveTreeItemId = viewModel::resolveGeneratedArtifactTreeItemId,
        selectTreeItem = ::runTreeItemSelection,
      )
    }
  }

  LaunchedEffect(viewModel, state.firstRunSetup) {
    if (state.firstRunSetup != null) {
      runFirstRunDiscovery()
    }
  }

  LaunchedEffect(viewModel) {
    val request = viewModel.beginStartup() ?: return@LaunchedEffect
    state = viewModel.state()
    val result = withContext(dispatcherProvider.default) { viewModel.runStartup(request) }
    state = viewModel.finishStartup(result)
    state = withContext(dispatcherProvider.default) { viewModel.refreshMachineSkillInventory() }
  }

  fun runRefresh() {
    if (canStartRepoScopedAction()) {
      state = viewModel.beginRefresh()
      if (state.dirtyEditorPrompt == null) {
        runRefreshLoad()
        coroutineScope.launch {
          state = withContext(dispatcherProvider.default) { viewModel.refreshMachineSkillInventory() }
        }
      }
    }
  }

  fun runWorkRequest(request: skillbill.desktop.feature.skillbill.state.WorkListRequest) {
    workLoadJob?.cancel()
    workLoadJob = coroutineScope.launch {
      val response = withContext(dispatcherProvider.io) { viewModel.loadWork(request) }
      state = viewModel.finishWork(request, response)
    }
  }

  fun runWorkToggle() {
    val collapsing = state.workList.expanded
    val request = viewModel.toggleWork()
    state = viewModel.state()
    if (collapsing) workLoadJob?.cancel()
    request?.let(::runWorkRequest)
  }

  fun runWorkRefresh() {
    val request = viewModel.refreshWork()
    state = viewModel.state()
    request?.let(::runWorkRequest)
  }

  LaunchedEffect(state.selectedRepoPath, state.repoStatus.state) {
    val repoPath = state.selectedRepoPath ?: return@LaunchedEffect
    if (state.repoStatus.state != RepoLoadState.LOADED) {
      return@LaunchedEffect
    }
    observeRepoFileChanges(repoPath).collect { changeKind ->
      pendingRepoFileChangeKind = mergeRepoFileChangeKind(pendingRepoFileChangeKind, changeKind)
      repoFileChangePulse += 1
    }
  }

  LaunchedEffect(repoFileChangePulse) {
    if (repoFileChangePulse > 0) {
      delay(REPO_CHANGE_REFRESH_DEBOUNCE_MILLIS)
      pendingRepoFileChangeRefresh = true
    }
  }

  LaunchedEffect(
    pendingRepoFileChangeRefresh,
    pendingRepoFileChangeKind,
    state.selectedRepoPath,
    state.repoStatus.state,
    state.busyOperation,
    state.editor.saveInProgress,
  ) {
    if (!pendingRepoFileChangeRefresh) {
      return@LaunchedEffect
    }
    when (pendingRepoFileChangeKind) {
      RepoFileChangeKind.RepoSnapshot -> {
        if (canAutoRefreshRepoSnapshot(state)) {
          pendingRepoFileChangeKind = null
          pendingRepoFileChangeRefresh = false
          runRefresh()
        }
      }
      null -> pendingRepoFileChangeRefresh = false
    }
  }

  fun runOpenRepository() {
    if (canStartRepoScopedAction()) {
      state = viewModel.beginChooseRepoDirectory()
      if (state.dirtyEditorPrompt == null && state.busyOperation == SkillBillBusyOperation.CHOOSE_DIRECTORY) {
        runChooseRepoDirectory()
      }
    }
  }

  fun runOpenScaffoldWizard(kind: ScaffoldKind) {
    if (canStartRepoScopedAction()) {
      val request = viewModel.beginOpenScaffoldWizard(kind) ?: return
      coroutineScope.launch {
        val response = withContext(dispatcherProvider.default) { viewModel.runOpenScaffoldWizard(request) }
        state = viewModel.finishOpenScaffoldWizard(response)
      }
    }
  }

  fun runInstallSetup() {
    if (canStartRepoScopedAction()) {
      state = viewModel.openFirstRunSetup()
    }
  }

  fun runReturnToInstalledWorkspace() {
    if (!canStartRepoScopedAction()) return
    state = viewModel.beginReturnToInstalledWorkspace()
    if (state.dirtyEditorPrompt == null && state.busyOperation == SkillBillBusyOperation.OPEN_REPO) {
      loadInstalledWorkspace()
    }
  }

  fun runScaffoldDryRun() {
    val request = viewModel.beginScaffoldDryRun()
    state = viewModel.state()
    if (request != null) {
      coroutineScope.launch {
        val result = withContext(dispatcherProvider.default) { viewModel.runScaffoldDryRun(request) }
        state = viewModel.finishScaffoldDryRun(request, result)
      }
    }
  }

  fun runScaffoldExecute() {
    val request = viewModel.beginScaffoldExecute()
    state = viewModel.state()
    if (request != null) {
      coroutineScope.launch {
        val result = withContext(dispatcherProvider.default) { viewModel.runScaffoldExecute(request) }
        state = viewModel.finishScaffoldExecute(request, result)
        val acceptedSuccess = state.scaffoldWizard?.executionResult as?
          skillbill.desktop.core.domain.model.ScaffoldRunResult.Success
        if (acceptedSuccess != null) {
          val refreshRequest = viewModel.beginRefreshAfterScaffold()
          state = viewModel.state()
          val refreshResult = withContext(dispatcherProvider.default) { viewModel.loadRepo(refreshRequest) }
          state = viewModel.finishRefreshAfterScaffold(refreshResult)
          viewModel.resolveAuthoredTreeItemForScaffold(acceptedSuccess.result)?.let { itemId ->
            state = viewModel.selectTreeItem(itemId)
            state.selectedTreeItemId?.let(onSourceRouteSelected)
          }
          state = viewModel.dismissScaffoldWizard()
        }
      }
    }
  }

  fun runPreviewRemoval() {
    val request = viewModel.beginPreviewRemoval() ?: return
    state = viewModel.state()
    coroutineScope.launch {
      var finished = false
      try {
        val result = withContext(dispatcherProvider.default) { viewModel.runPreviewRemoval(request) }
        state = viewModel.finishPreviewRemoval(request, result)
        finished = true
      } finally {
        if (!finished) {
          withContext(NonCancellable) {
            state = viewModel.finishPreviewRemoval(
              request,
              skillbill.desktop.core.domain.model.DesktopSkillRemovalResult.Failed(
                exceptionName = "CancellationException",
                exceptionMessage = "Preview was cancelled.",
                rollbackComplete = true,
              ),
            )
          }
        }
      }
    }
  }

  fun runExecuteRemoval() {
    val request = viewModel.beginExecuteRemoval() ?: return
    state = viewModel.state()
    coroutineScope.launch {
      var finished = false
      try {
        val result = withContext(dispatcherProvider.default) { viewModel.runExecuteRemoval(request) }
        state = viewModel.finishExecuteRemoval(request, result)
        finished = true
        val accepted = state.confirmDeletion?.executionResult as?
          skillbill.desktop.core.domain.model.DesktopSkillRemovalResult.Success
        if (accepted != null) {
          val refreshRequest = viewModel.beginRefreshAfterScaffold()
          state = viewModel.state()
          val refreshResult = withContext(dispatcherProvider.default) { viewModel.loadRepo(refreshRequest) }
          state = viewModel.finishRefreshAfterScaffold(refreshResult)
          state = viewModel.dismissConfirmDeletion()
          state = viewModel.showValidateAgentConfigsConsole()
          val repoRoot = state.selectedRepoPath
          if (!repoRoot.isNullOrBlank()) {
            val lines = withContext(dispatcherProvider.io) { runValidateAgentConfigs(repoRoot) }
            state = viewModel.appendValidateAgentConfigsLines(lines.outputLines)
            state = viewModel.finishValidateAgentConfigs(lines.exitCode)
          } else {
            state = viewModel.finishValidateAgentConfigs(0)
          }
        }
      } finally {
        if (!finished) {
          withContext(NonCancellable) {
            state = viewModel.finishExecuteRemoval(
              request,
              skillbill.desktop.core.domain.model.DesktopSkillRemovalResult.Failed(
                exceptionName = "CancellationException",
                exceptionMessage = "Deletion was cancelled.",
                rollbackComplete = true,
              ),
            )
          }
        }
      }
    }
  }

  fun runPaletteResult(result: CommandPaletteResult) {
    val executed = executeCommandPaletteResult(
      result = result,
      actions = CommandPaletteActions(
        selectTreeItem = ::runTreeItemSelection,
        openRepository = ::runOpenRepository,
        refresh = ::runRefresh,
        save = {
          if (canStartRepoScopedAction()) {
            runEditorSave()
          }
        },
        openInstallSetup = ::runInstallSetup,
        openScaffoldWizard = ::runOpenScaffoldWizard,
        openMachineTool = { action -> state = viewModel.dispatchMachineTool(action) },
      ),
    )
    if (executed) {
      state = viewModel.closeCommandPalette()
    }
  }

  fun runSelectedPaletteResult() {
    val result = state.commandPalette.results.getOrNull(state.commandPalette.selectedResultIndex) ?: return
    runPaletteResult(result)
  }

  SkillBillFrame(
    state = state,
    canNavigateBack = canNavigateBack,
    onNavigateBack = { if (canStartRepoScopedAction()) onNavigateBack() },
    onRepoPathChanged = { repoPath ->
      if (canStartRepoScopedAction()) {
        state = viewModel.updateRepoPathText(repoPath)
      }
    },
    onRepoSelected = { repoPath ->
      if (canStartRepoScopedAction()) {
        state = viewModel.beginSelectRepoPath(repoPath)
        if (state.dirtyEditorPrompt == null) {
          runRepoLoad(preserveSelection = false, repoPath = state.repoPathText)
        }
      }
    },
    onChooseRepoDirectory = {
      if (canStartRepoScopedAction()) {
        state = viewModel.beginChooseRepoDirectory()
        if (state.dirtyEditorPrompt == null && state.busyOperation == SkillBillBusyOperation.CHOOSE_DIRECTORY) {
          runChooseRepoDirectory()
        }
      }
    },
    onRefresh = ::runRefresh,
    onInstallSetup = ::runInstallSetup,
    onReturnToInstalledWorkspace = ::runReturnToInstalledWorkspace,
    onEditorDraftChanged = { draft ->
      state = viewModel.updateEditorDraft(draft)
    },
    onEditorSave = {
      if (canStartRepoScopedAction()) {
        runEditorSave()
      }
    },
    onEditorRevert = {
      if (canStartRepoScopedAction()) {
        state = viewModel.revertEditorDraft()
      }
    },
    onDirtyPromptDiscard = {
      runDiscardedDirtyPrompt()
    },
    onDirtyPromptCancel = {
      state = viewModel.cancelDirtyEditorPrompt()
    },
    onTreeItemSelected = ::runTreeItemSelection,
    onTreeItemExpandedToggled = { itemId ->
      if (canStartRepoScopedAction()) {
        val expandingMachineRoot = itemId == skillbill.desktop.feature.skillbill.state.MACHINE_SKILLS_ROOT_ID &&
          itemId !in state.expandedNodeIds
        state = viewModel.toggleExpanded(itemId)
        if (expandingMachineRoot) {
          coroutineScope.launch {
            state = withContext(dispatcherProvider.default) { viewModel.refreshMachineSkillInventory() }
          }
        }
      }
    },
    onMoveTreeSelection = { delta ->
      if (canStartRepoScopedAction()) {
        state = viewModel.moveSelection(delta)
      }
    },
    onWorkToggled = ::runWorkToggle,
    onWorkRefreshed = ::runWorkRefresh,
    onGeneratedArtifactResolvable = { artifactPath ->
      viewModel.resolveGeneratedArtifactTreeItemId(artifactPath) != null
    },
    onGeneratedArtifactSelected = ::runGeneratedArtifactSelection,
    onCommandPaletteOpen = {
      state = viewModel.openCommandPalette()
    },
    onCommandPaletteDismiss = {
      state = viewModel.closeCommandPalette()
    },
    onCommandPaletteQueryChanged = { query ->
      state = viewModel.updateCommandPaletteQuery(query)
    },
    onCommandPaletteMoveSelection = { delta ->
      state = viewModel.moveCommandPaletteSelection(delta)
    },
    onCommandPaletteExecuteSelected = ::runSelectedPaletteResult,
    onCommandPaletteExecuteResult = ::runPaletteResult,
    onOpenScaffoldWizard = ::runOpenScaffoldWizard,
    onMachineToolAction = { action ->
      state = viewModel.dispatchMachineTool(action)
      if (action == skillbill.desktop.core.domain.model.MachineToolAction.MANAGE_SKILLS) {
        coroutineScope.launch { state = viewModel.refreshMachineSkillInventory() }
      }
    },
    onMachineToolsDismiss = { state = viewModel.dismissMachineTools() },
    machineToolsCallbacks = MachineToolsCallbacks(
      chooseSource = {
        coroutineScope.launch { state = viewModel.chooseMachineSkillSource() }
      },
      toggleTarget = { id -> state = viewModel.toggleMachineSkillTarget(id) },
      setInstallStep = { step -> state = viewModel.setMachineSkillInstallStep(step) },
      preview = { coroutineScope.launch { state = viewModel.previewMachineSkillInstall() } },
      apply = { coroutineScope.launch { state = viewModel.applyMachineSkillInstall() } },
      retry = {
        state = viewModel.setMachineSkillInstallStep(
          skillbill.desktop.core.domain.model.MachineSkillInstallStep.TARGETS,
        )
      },
      acknowledge = { coroutineScope.launch { state = viewModel.acknowledgeMachineSkillPostMortem() } },
      updateQuery = { query -> state = viewModel.updateMachineSkillManagerQuery(query) },
      updateOwnership = { filter -> state = viewModel.updateMachineSkillOwnershipFilter(filter) },
      updateHealth = { filter -> state = viewModel.updateMachineSkillHealthFilter(filter) },
      updateAgent = { agent -> state = viewModel.updateMachineSkillAgentFilter(agent) },
      selectSkill = { name -> state = viewModel.selectMachineSkill(name) },
      managerAction = { action ->
        if (action == skillbill.desktop.feature.skillbill.ui.MachineSkillManagerAction.REVEAL ||
          action == skillbill.desktop.feature.skillbill.ui.MachineSkillManagerAction.EDIT
        ) {
          coroutineScope.launch { state = viewModel.revealMachineSkillSource() }
        } else {
          state = viewModel.beginMachineSkillManagerAction(action.name)
        }
      },
      selectAuthority = { path -> state = viewModel.selectMachineSkillAuthoritativeSource(path) },
      toggleManagerTarget = { id -> state = viewModel.toggleMachineSkillManagerTarget(id) },
      previewManagerAction = { coroutineScope.launch { state = viewModel.previewMachineSkillManagerAction() } },
      applyManagerAction = { coroutineScope.launch { state = viewModel.applyMachineSkillManagerAction() } },
    ),
    scaffoldWizardCallbacks = ScaffoldWizardCallbacks(
      onSelectKind = { kind ->
        state = viewModel.selectScaffoldWizardKind(kind)
      },
      onFormChanged = { transform ->
        state = viewModel.updateScaffoldForm(transform)
      },
      onAddBaselineLayer = {
        state = viewModel.addScaffoldBaselineLayer()
      },
      onAddSuggestedBaselineLayer = {
        state = viewModel.addSuggestedScaffoldBaselineLayer()
      },
      onEditBaselineLayer = { index, transform ->
        state = viewModel.editScaffoldBaselineLayer(index, transform)
      },
      onRemoveBaselineLayer = { index ->
        state = viewModel.removeScaffoldBaselineLayer(index)
      },
      onDirtyOverrideChanged = { override ->
        state = viewModel.setScaffoldDirtyOverride(override)
      },
      onChooseAddonLocationPath = ::runChooseAddonLocationPath,
      onPlan = ::runScaffoldDryRun,
      onRun = ::runScaffoldExecute,
      onAcknowledgeFailure = {
        state = viewModel.acknowledgeScaffoldFailure()
      },
      onDismiss = {
        state = viewModel.dismissScaffoldWizard()
      },
    ),
    onShowDeleteContextMenu = onShowDeleteContextMenu@{ node ->
      val target = resolveDeletionTarget(node) ?: return@onShowDeleteContextMenu
      if (target.isBuiltIn()) return@onShowDeleteContextMenu
      state = viewModel.showConfirmDeletion(target)
      runPreviewRemoval()
    },
    confirmDeletionCallbacks = ConfirmDeletionCallbacks(
      onAcknowledgedChanged = { acknowledged ->
        state = viewModel.setRemovalAcknowledged(acknowledged)
      },
      onConfirmDelete = ::runExecuteRemoval,
      onDismiss = {
        state = viewModel.dismissConfirmDeletion()
      },
      onAcknowledgeFailure = {
        state = viewModel.acknowledgeRemovalFailure()
      },
    ),
    firstRunSetupCallbacks = FirstRunSetupCallbacks(
      onAgentSelectionChanged = { agentId, selected ->
        state = viewModel.selectFirstRunAgent(agentId, selected)
      },
      onPlatformSelectionChanged = { slug, selected ->
        state = viewModel.selectFirstRunPlatform(slug, selected)
      },
      onTelemetryChanged = { level ->
        state = viewModel.selectFirstRunTelemetry(level)
      },
      onBack = {
        state = viewModel.retreatFirstRunStep()
      },
      onNext = {
        state = viewModel.advanceFirstRunStep()
      },
      onApply = ::runFirstRunApply,
      onRetry = {
        state = viewModel.retryFirstRunSetup()
      },
      onFinish = {
        state = viewModel.finishFirstRunSetup()
      },
      onDismiss = {
        state = viewModel.dismissFirstRunSetup()
      },
    ),
  )
}

private const val REPO_CHANGE_REFRESH_DEBOUNCE_MILLIS: Long = 500L

internal fun canAutoRefreshRepoSnapshot(state: SkillBillState): Boolean = state.selectedRepoPath != null &&
  state.repoStatus.state == RepoLoadState.LOADED &&
  state.busyOperation == null &&
  !state.editor.saveInProgress

internal fun mergeRepoFileChangeKind(current: RepoFileChangeKind?, next: RepoFileChangeKind): RepoFileChangeKind =
  current ?: next

internal fun executeGeneratedArtifactSelection(
  artifactPath: String,
  resolveTreeItemId: (String) -> String?,
  selectTreeItem: (String) -> Unit,
): Boolean {
  val resolvedTreeItemId = resolveTreeItemId(artifactPath) ?: return false
  selectTreeItem(resolvedTreeItemId)
  return true
}

internal fun resolveDeletionTarget(
  node: skillbill.desktop.core.domain.model.SkillBillTreeItem,
): skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget? {
  val kind = node.kind
  return when (kind) {
    skillbill.desktop.core.domain.model.TreeItemKind.SKILL -> {
      val skillName = node.metadata?.skillName ?: return null
      skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget.HorizontalSkill(skillName = skillName)
    }
    skillbill.desktop.core.domain.model.TreeItemKind.PLATFORM_PACK -> {
      val rawId = node.id.substringAfterLast('|', missingDelimiterValue = "")
      val slug = if (rawId.startsWith("platform:")) rawId.removePrefix("platform:") else node.label
      if (slug.isBlank()) {
        null
      } else {
        skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget.PlatformPack(platform = slug)
      }
    }
    skillbill.desktop.core.domain.model.TreeItemKind.ADD_ON -> {
      val path = node.authoredPath ?: return null
      if (node.external) {
        val metadata = node.metadata ?: return null
        val sourceRoot = metadata.externalSourcePath ?: return null
        val platform = metadata.platform ?: return null
        val fileName = path.substringAfterLast('/')
        skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget.ExternalAddOn(
          sourceRootAbsolutePath = sourceRoot,
          platform = platform,
          fileName = fileName,
        )
      } else {
        skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget.AddOn(relativePath = path)
      }
    }
    else -> null
  }
}

internal fun DesktopSkillRemovalTarget.isBuiltIn(): Boolean = when (this) {
  is DesktopSkillRemovalTarget.HorizontalSkill ->
    DesktopSkillRemovalTarget.isProtectedHorizontalName(skillName)
  is DesktopSkillRemovalTarget.PlatformPack ->
    DesktopSkillRemovalTarget.isProtectedPlatformName(platform)
  is DesktopSkillRemovalTarget.AddOn -> false
  is DesktopSkillRemovalTarget.ExternalAddOn -> false
}
