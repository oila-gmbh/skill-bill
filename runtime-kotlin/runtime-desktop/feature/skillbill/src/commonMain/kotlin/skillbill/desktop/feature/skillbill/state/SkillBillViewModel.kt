package skillbill.desktop.feature.skillbill.state

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.ScreenScope
import skillbill.desktop.core.datastore.DesktopPreferenceStore
import skillbill.desktop.core.domain.model.DesktopSkillRemovalResult
import skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget
import skillbill.desktop.core.domain.model.FirstRunSetupState
import skillbill.desktop.core.domain.model.FirstRunTelemetryLevel
import skillbill.desktop.core.domain.model.ScaffoldBaselineLayerForm
import skillbill.desktop.core.domain.model.ScaffoldCatalogSnapshot
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.ScaffoldOutcome
import skillbill.desktop.core.domain.model.ScaffoldRunResult
import skillbill.desktop.core.domain.model.ScaffoldWizardFormFields
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillState
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
  private val viewState = SkillBillViewState(authoringGateway, computeInitialFirstRunSetup())
  private val repoController = SkillBillRepoController(
    viewState,
    repoSessionService,
    skillTreeService,
    recentRepoRepository,
    installedWorkspaceLocator,
  )
  private val editorController = SkillBillEditorController(viewState, repoController, authoringGateway)
  private val scaffoldController = SkillBillScaffoldController(viewState, scaffoldGateway)
  private val firstRunController = SkillBillFirstRunController(
    viewState,
    repoController,
    firstRunGateway,
    desktopPreferenceStore,
    installedWorkspaceLocator,
  )
  private val removalController = SkillBillRemovalController(viewState, skillRemoveGateway)

  private fun computeInitialFirstRunSetup(): FirstRunSetupState? {
    if (desktopPreferenceStore.firstRunPreferences.value.completed || firstRunGateway.hasExistingInstall()) {
      return null
    }
    val legacyFallback = desktopPreferenceStore.firstRunPreferences.value.toLegacySetupRequestOrNull()
    return firstRunGateway.latestReusableSetupRequest(legacyFallback)?.toFirstRunSetupState() ?: FirstRunSetupState()
  }

  fun state(selectedTreeItemId: String? = viewState.currentState.selectedTreeItemId): SkillBillState =
    repoController.state(selectedTreeItemId)

  fun updateRepoPathText(repoPath: String): SkillBillState = repoController.updateRepoPathText(repoPath)

  fun selectRepoPath(repoPath: String = viewState.repoPathText): SkillBillState =
    repoController.selectRepoPath(repoPath)

  fun beginSelectRepoPath(repoPath: String = viewState.repoPathText): SkillBillState =
    repoController.beginSelectRepoPath(repoPath)

  fun finishSelectRepoPath(repoPath: String = viewState.repoPathText): SkillBillState =
    repoController.finishSelectRepoPath(repoPath)

  fun finishSelectRepoPath(result: RepoLoadResult): SkillBillState = repoController.finishSelectRepoPath(result)

  suspend fun finishSelectRepoPathAndRemember(result: RepoLoadResult): SkillBillState =
    repoController.finishSelectRepoPathAndRemember(result)

  fun beginReturnToInstalledWorkspace(): SkillBillState = repoController.beginReturnToInstalledWorkspace()

  fun selectTreeItem(itemId: String): SkillBillState = repoController.selectTreeItem(itemId)

  fun resolveGeneratedArtifactTreeItemId(artifactPath: String): String? =
    repoController.resolveGeneratedArtifactTreeItemId(artifactPath)

  fun refresh(): SkillBillState = repoController.refresh()

  fun beginRefreshAfterScaffold(): RepoLoadRequest = repoController.beginRefreshAfterScaffold()

  fun finishRefreshAfterScaffold(result: RepoLoadResult): SkillBillState =
    repoController.finishRefreshAfterScaffold(result)

  fun refreshAfterScaffold(): SkillBillState = repoController.refreshAfterScaffold()

  fun beginRefresh(): SkillBillState = repoController.beginRefresh()

  fun finishRefresh(): SkillBillState = repoController.finishRefresh()

  fun finishRefresh(result: RepoLoadResult): SkillBillState = repoController.finishRefresh(result)

  fun toggleExpanded(itemId: String): SkillBillState = repoController.toggleExpanded(itemId)

  fun moveSelection(delta: Int): SkillBillState = repoController.moveSelection(delta)

  fun chooseRepoDirectory(repoPath: String?): SkillBillState = repoController.chooseRepoDirectory(repoPath)

  fun beginChooseRepoDirectory(): SkillBillState = repoController.beginChooseRepoDirectory()

  fun busyState(operation: SkillBillBusyOperation): SkillBillState = repoController.busyState(operation)

  fun repoLoadRequest(repoPath: String, preserveSelection: Boolean): RepoLoadRequest =
    repoController.repoLoadRequest(repoPath, preserveSelection)

  fun loadRepo(request: RepoLoadRequest): RepoLoadResult = repoController.loadRepo(request)

  fun finishRepoLoad(result: RepoLoadResult): SkillBillState = repoController.finishRepoLoad(result)

  fun beginStartup(): StartupRequest? = repoController.beginStartup()

  suspend fun runStartup(request: StartupRequest): StartupResult = repoController.runStartup(request)

  fun finishStartup(result: StartupResult): SkillBillState = repoController.finishStartup(result)

  fun updateEditorDraft(text: String): SkillBillState = editorController.updateEditorDraft(text)

  fun revertEditorDraft(): SkillBillState = editorController.revertEditorDraft()

  fun beginSaveEditor(): EditorSaveRequest? = editorController.beginSaveEditor()

  fun runSaveEditor(request: EditorSaveRequest): EditorSaveResult = editorController.runSaveEditor(request)

  fun finishSaveEditor(result: EditorSaveResult): SkillBillState = editorController.finishSaveEditor(result)

  fun cancelDirtyEditorPrompt(): SkillBillState = editorController.cancelDirtyEditorPrompt()

  fun discardDirtyEditorPrompt(): SkillBillState = editorController.discardDirtyEditorPrompt()

  fun openCommandPalette(): SkillBillState = with(viewState) {
    commandPaletteOpen = true
    commandPaletteSelectedResultIndex = 0
    currentState = createState()
    currentState
  }

  fun closeCommandPalette(): SkillBillState = with(viewState) {
    commandPaletteOpen = false
    currentState = createState()
    currentState
  }

  fun updateCommandPaletteQuery(query: String): SkillBillState = with(viewState) {
    commandPaletteQuery = query
    commandPaletteSelectedResultIndex = 0
    currentState = createState()
    currentState
  }

  fun moveCommandPaletteSelection(delta: Int): SkillBillState = with(viewState) {
    val lastIndex = currentState.commandPalette.results.lastIndex
    commandPaletteSelectedResultIndex =
      if (lastIndex < 0) {
        0
      } else {
        (commandPaletteSelectedResultIndex + delta).coerceIn(0, lastIndex)
      }
    currentState = createState()
    currentState
  }

  fun beginFirstRunDiscovery(): FirstRunDiscoveryRequest? = firstRunController.beginFirstRunDiscovery()

  fun openFirstRunSetup(): SkillBillState = firstRunController.openFirstRunSetup()

  suspend fun runFirstRunDiscovery(request: FirstRunDiscoveryRequest): FirstRunDiscoveryResponse =
    firstRunController.runFirstRunDiscovery(request)

  fun finishFirstRunDiscovery(response: FirstRunDiscoveryResponse): SkillBillState =
    firstRunController.finishFirstRunDiscovery(response)

  fun selectFirstRunAgent(agentId: String, selected: Boolean): SkillBillState =
    firstRunController.selectFirstRunAgent(agentId, selected)

  fun selectFirstRunPlatform(slug: String, selected: Boolean): SkillBillState =
    firstRunController.selectFirstRunPlatform(slug, selected)

  fun selectFirstRunTelemetry(level: FirstRunTelemetryLevel): SkillBillState =
    firstRunController.selectFirstRunTelemetry(level)

  fun advanceFirstRunStep(): SkillBillState = firstRunController.advanceFirstRunStep()

  fun retreatFirstRunStep(): SkillBillState = firstRunController.retreatFirstRunStep()

  fun beginFirstRunApply(): FirstRunApplyRequest? = firstRunController.beginFirstRunApply()

  suspend fun runFirstRunApply(request: FirstRunApplyRequest): FirstRunApplyResponse =
    firstRunController.runFirstRunApply(request)

  fun finishFirstRunApply(response: FirstRunApplyResponse): SkillBillState =
    firstRunController.finishFirstRunApply(response)

  fun finishFirstRunSetup(): SkillBillState = firstRunController.finishFirstRunSetup()

  fun dismissFirstRunSetup(): SkillBillState = firstRunController.dismissFirstRunSetup()

  fun retryFirstRunSetup(): SkillBillState = firstRunController.retryFirstRunSetup()

  fun openScaffoldWizard(kind: ScaffoldKind, snapshot: ScaffoldCatalogSnapshot): SkillBillState =
    scaffoldController.openScaffoldWizard(kind, snapshot)

  fun canOpenScaffoldWizard(): Boolean = scaffoldController.canOpenScaffoldWizard()

  fun beginOpenScaffoldWizard(kind: ScaffoldKind): ScaffoldCatalogRequest? =
    scaffoldController.beginOpenScaffoldWizard(kind)

  suspend fun runOpenScaffoldWizard(request: ScaffoldCatalogRequest): ScaffoldCatalogResponse =
    scaffoldController.runOpenScaffoldWizard(request)

  fun finishOpenScaffoldWizard(response: ScaffoldCatalogResponse): SkillBillState =
    scaffoldController.finishOpenScaffoldWizard(response)

  internal suspend fun fetchScaffoldCatalogSnapshot(): ScaffoldCatalogSnapshot =
    scaffoldController.fetchScaffoldCatalogSnapshot()

  fun selectScaffoldWizardKind(kind: ScaffoldKind): SkillBillState = scaffoldController.selectScaffoldWizardKind(kind)

  fun updateScaffoldForm(transform: (ScaffoldWizardFormFields) -> ScaffoldWizardFormFields): SkillBillState =
    scaffoldController.updateScaffoldForm(transform)

  fun addScaffoldBaselineLayer(layer: ScaffoldBaselineLayerForm? = null): SkillBillState =
    scaffoldController.addScaffoldBaselineLayer(layer)

  fun editScaffoldBaselineLayer(
    index: Int,
    transform: (ScaffoldBaselineLayerForm) -> ScaffoldBaselineLayerForm,
  ): SkillBillState = scaffoldController.editScaffoldBaselineLayer(index, transform)

  fun removeScaffoldBaselineLayer(index: Int): SkillBillState = scaffoldController.removeScaffoldBaselineLayer(index)

  fun addSuggestedScaffoldBaselineLayer(): SkillBillState = scaffoldController.addSuggestedScaffoldBaselineLayer()

  fun setScaffoldDirtyOverride(override: Boolean): SkillBillState =
    scaffoldController.setScaffoldDirtyOverride(override)

  fun dismissScaffoldWizard(): SkillBillState = scaffoldController.dismissScaffoldWizard()

  fun acknowledgeScaffoldFailure(): SkillBillState = scaffoldController.acknowledgeScaffoldFailure()

  fun beginScaffoldDryRun(): ScaffoldRunRequest? = scaffoldController.beginScaffoldDryRun()

  suspend fun runScaffoldDryRun(request: ScaffoldRunRequest): ScaffoldRunResult =
    scaffoldController.runScaffoldDryRun(request)

  fun finishScaffoldDryRun(request: ScaffoldRunRequest, result: ScaffoldRunResult): SkillBillState =
    scaffoldController.finishScaffoldDryRun(request, result)

  fun beginScaffoldExecute(): ScaffoldRunRequest? = scaffoldController.beginScaffoldExecute()

  suspend fun runScaffoldExecute(request: ScaffoldRunRequest): ScaffoldRunResult =
    scaffoldController.runScaffoldExecute(request)

  fun finishScaffoldExecute(request: ScaffoldRunRequest, result: ScaffoldRunResult): SkillBillState =
    scaffoldController.finishScaffoldExecute(request, result)

  fun resolveAuthoredTreeItemForScaffold(outcome: ScaffoldOutcome): String? =
    scaffoldController.resolveAuthoredTreeItemForScaffold(outcome)

  fun isScaffoldPlanAllowed(): Boolean = scaffoldController.isScaffoldPlanAllowed()

  fun showConfirmDeletion(target: DesktopSkillRemovalTarget): SkillBillState =
    removalController.showConfirmDeletion(target)

  fun dismissConfirmDeletion(): SkillBillState = removalController.dismissConfirmDeletion()

  fun setRemovalAcknowledged(acknowledged: Boolean): SkillBillState =
    removalController.setRemovalAcknowledged(acknowledged)

  fun acknowledgeRemovalFailure(): SkillBillState = removalController.acknowledgeRemovalFailure()

  fun beginPreviewRemoval(): SkillRemovalRunRequest? = removalController.beginPreviewRemoval()

  suspend fun runPreviewRemoval(request: SkillRemovalRunRequest): DesktopSkillRemovalResult =
    removalController.runPreviewRemoval(request)

  fun finishPreviewRemoval(request: SkillRemovalRunRequest, result: DesktopSkillRemovalResult): SkillBillState =
    removalController.finishPreviewRemoval(request, result)

  fun beginExecuteRemoval(): SkillRemovalRunRequest? = removalController.beginExecuteRemoval()

  suspend fun runExecuteRemoval(request: SkillRemovalRunRequest): DesktopSkillRemovalResult =
    removalController.runExecuteRemoval(request)

  fun finishExecuteRemoval(request: SkillRemovalRunRequest, result: DesktopSkillRemovalResult): SkillBillState =
    removalController.finishExecuteRemoval(request, result)

  fun showValidateAgentConfigsConsole(): SkillBillState = with(viewState) {
    activeValidateAgentConfigsToken += 1
    validateAgentConfigsSummary = ValidateAgentConfigsSummary(
      lines = emptyList(),
      exitCode = null,
      running = true,
    )
    currentState = createState()
    currentState
  }

  fun appendValidateAgentConfigsLines(lines: List<String>): SkillBillState = with(viewState) {
    val merged = validateAgentConfigsSummary.lines + lines
    validateAgentConfigsSummary = validateAgentConfigsSummary.copy(lines = merged)
    currentState = createState()
    currentState
  }

  fun finishValidateAgentConfigs(exitCode: Int): SkillBillState = with(viewState) {
    validateAgentConfigsSummary = validateAgentConfigsSummary.copy(running = false, exitCode = exitCode)
    currentState = createState()
    currentState
  }
}
