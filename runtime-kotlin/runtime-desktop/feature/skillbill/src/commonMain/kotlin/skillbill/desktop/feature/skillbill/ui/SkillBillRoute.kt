@file:Suppress("FunctionName")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import skillbill.desktop.core.common.browser.BrowserLaunchFailure
import skillbill.desktop.core.common.browser.BrowserLaunchOutcome
import skillbill.desktop.core.common.browser.BrowserLauncher
import skillbill.desktop.core.domain.model.CommandPaletteResult
import skillbill.desktop.core.domain.model.DirtyEditorPromptReason
import skillbill.desktop.core.domain.model.DockTab
import skillbill.desktop.core.domain.model.GitOperationResult
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.ui.di.rememberScreenComponent
import skillbill.desktop.feature.skillbill.di.SkillBillComponent
import skillbill.desktop.feature.skillbill.state.PublishRunResult

@Suppress("DEPRECATION")
@Composable
fun SkillBillRoute(
  selectedSourceId: String?,
  canNavigateBack: Boolean,
  onNavigateBack: () -> Unit,
  onSourceRouteSelected: (String) -> Unit = {},
) {
  val component = rememberScreenComponent<SkillBillComponent>()
  val browserLauncher = component.browserLauncher
  val viewModel = component.viewModel
  val coroutineScope = rememberCoroutineScope()
  val clipboardManager = LocalClipboardManager.current
  var state by remember(viewModel, selectedSourceId) { mutableStateOf(viewModel.state(selectedSourceId)) }
  // F-X-512: transient "copied" feedback. We set this key when a copy callback fires, and a
  // LaunchedEffect keyed on this state clears it after a short delay so the UI flashes "copied".
  var recentlyCopiedKey by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(recentlyCopiedKey) {
    if (recentlyCopiedKey != null) {
      delay(COPY_FEEDBACK_DURATION_MILLIS)
      recentlyCopiedKey = null
    }
  }
  var recentlyOpenedCompareUrlKey by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(recentlyOpenedCompareUrlKey) {
    if (recentlyOpenedCompareUrlKey != null) {
      delay(COPY_FEEDBACK_DURATION_MILLIS)
      recentlyOpenedCompareUrlKey = null
    }
  }

  // Centralized git refresh fan-out. AC10: every editing/validation/scaffold/render seam funnels
  // through this single entry point so the Changes tab always reflects on-disk state. begin/run/finish
  // captures all VM fields on the caller dispatcher before hopping to Dispatchers.Default (F-102).
  fun runGitRefresh() {
    val request = viewModel.beginGitRefresh()
    state = viewModel.state()
    coroutineScope.launch {
      val result = withContext(Dispatchers.Default) { viewModel.runGitRefresh(request) }
      state = viewModel.finishGitRefresh(result)
    }
  }

  fun loadHistory() {
    val request = viewModel.beginLoadHistory()
    state = viewModel.state()
    coroutineScope.launch {
      val result = withContext(Dispatchers.Default) { viewModel.runLoadHistory(request) }
      state = viewModel.finishLoadHistory(result)
    }
  }

  fun runCommit(allowFailedValidation: Boolean = false) {
    val request = viewModel.beginCommit(allowFailedValidation)
    state = viewModel.state()
    if (request != null) {
      coroutineScope.launch {
        val result = withContext(Dispatchers.Default) { viewModel.runCommit(request) }
        state = viewModel.finishCommit(result)
      }
    }
  }

  fun openPublishLink(url: String) {
    coroutineScope.launch {
      val outcome = withContext(Dispatchers.Default) {
        openCompareUrlSafely(url = url, browserLauncher = browserLauncher)
      }
      when (outcome) {
        BrowserLaunchOutcome.Opened -> {
          if (recentlyCopiedKey == url) {
            recentlyCopiedKey = null
          }
          recentlyOpenedCompareUrlKey = url
        }
        is BrowserLaunchOutcome.Failed -> {
          if (recentlyOpenedCompareUrlKey == url) {
            recentlyOpenedCompareUrlKey = null
          }
          clipboardManager.setText(AnnotatedString(url))
          recentlyCopiedKey = url
        }
      }
    }
  }

  fun runPublish(allowFailedValidation: Boolean = false, allowCanonicalRemote: Boolean = false) {
    val request = viewModel.beginPublish(
      allowFailedValidation = allowFailedValidation,
      allowCanonicalRemote = allowCanonicalRemote,
    )
    state = viewModel.state()
    if (request != null) {
      coroutineScope.launch {
        var finished = false
        try {
          val result = withContext(Dispatchers.Default) { viewModel.runPublish(request) }
          state = viewModel.finishPublish(result)
          finished = true
          state.publishLink?.url?.let(::openPublishLink)
        } finally {
          if (!finished) {
            withContext(NonCancellable) {
              state = viewModel.finishPublish(
                PublishRunResult(
                  request = request,
                  validationSummary = request.previousValidationSummary,
                  pushResult = GitOperationResult.failed("Publish was cancelled."),
                ),
              )
            }
          }
        }
      }
    }
  }

  fun runEditorSave() {
    val request = viewModel.beginSaveEditor()
    state = viewModel.state()
    if (request != null) {
      coroutineScope.launch {
        val result = withContext(Dispatchers.Default) { viewModel.runSaveEditor(request) }
        state = viewModel.finishSaveEditor(result)
        if (result.result.success) {
          runGitRefresh()
        }
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
      val result = withContext(Dispatchers.Default) { viewModel.loadRepo(request) }
      state = if (preserveSelection) {
        viewModel.finishRepoLoad(result)
      } else {
        viewModel.finishSelectRepoPath(result)
      }
      afterLoad()
    }
  }

  fun runChooseRepoDirectory() {
    val repoPath = chooseRepoDirectory(state.repoPathText)
    if (repoPath.isNullOrBlank()) {
      state = viewModel.chooseRepoDirectory(repoPath)
    } else {
      state = viewModel.beginSelectRepoPath(repoPath)
      if (state.dirtyEditorPrompt == null && state.busyOperation == SkillBillBusyOperation.OPEN_REPO) {
        runRepoLoad(preserveSelection = false, repoPath = state.repoPathText) {
          runGitRefresh()
          loadHistory()
        }
      }
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
          viewModel.setHistoryPathFilter(state.editor.authoredPath)
          loadHistory()
          state = viewModel.state()
          onSourceRouteSelected(selected)
        }
      }
      DirtyEditorPromptReason.REFRESH -> {
        if (state.busyOperation == SkillBillBusyOperation.REFRESH) {
          runRepoLoad(preserveSelection = true) {
            runGitRefresh()
            loadHistory()
          }
        }
      }
      DirtyEditorPromptReason.REPO_SWITCH -> {
        if (state.busyOperation == SkillBillBusyOperation.OPEN_REPO) {
          runRepoLoad(preserveSelection = false, repoPath = state.repoPathText) {
            runGitRefresh()
            loadHistory()
          }
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

  fun runPush(allowCanonicalRemote: Boolean = false) {
    val request = viewModel.beginPush(allowCanonicalRemote)
    state = viewModel.state()
    if (request != null) {
      coroutineScope.launch {
        val result = withContext(Dispatchers.Default) { viewModel.runPush(request) }
        state = viewModel.finishPush(result)
      }
    }
  }

  fun canStartRepoScopedAction(): Boolean = state.busyOperation == null &&
    !state.publishBusy &&
    !state.commitBusy &&
    !state.commitValidationRunning &&
    !state.pushBusy

  fun runTreeItemSelection(itemId: String) {
    if (canStartRepoScopedAction()) {
      val previousSelection = state.selectedTreeItemId
      state = viewModel.selectTreeItem(itemId)
      if (state.selectedTreeItemId != previousSelection) {
        // AC7: tree selection narrows history to commits that touched the authored path of the
        // selected tree item. Falls back to no filter when the selection has no authored path.
        val authoredPath = state.editor.authoredPath
        viewModel.setHistoryPathFilter(authoredPath)
        loadHistory()
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

  LaunchedEffect(viewModel) {
    if (state.selectedRepoPath != null && state.repoStatus.state == RepoLoadState.LOADED) {
      runGitRefresh()
      loadHistory()
    }
  }

  fun runRefresh() {
    if (canStartRepoScopedAction()) {
      state = viewModel.beginRefresh()
      if (state.dirtyEditorPrompt == null) {
        val request = viewModel.repoLoadRequest(
          repoPath = state.selectedRepoPath ?: state.repoPathText,
          preserveSelection = true,
        )
        coroutineScope.launch {
          val result = withContext(Dispatchers.Default) { viewModel.loadRepo(request) }
          state = viewModel.finishRepoLoad(result)
          // AC10: manual refresh refreshes git status + history.
          runGitRefresh()
          loadHistory()
        }
      }
    }
  }

  fun runValidate() {
    if (canStartRepoScopedAction()) {
      // beginValidate returns a request that captures the active token, the current session, and the
      // pre-RUNNING validation summary, so the dispatcher work below does not read mutable VM fields
      // off-thread. (F-102)
      val request = viewModel.beginValidate()
      state = viewModel.state()
      coroutineScope.launch {
        val result = withContext(Dispatchers.Default) { viewModel.runValidate(request) }
        state = viewModel.finishValidate(result)
        // AC10: validation may touch on-disk state via scripts; refresh git status afterwards.
        runGitRefresh()
      }
    }
  }

  fun runValidateSelected() {
    if (canStartRepoScopedAction()) {
      val request = viewModel.beginValidateSelected()
      state = viewModel.state()
      coroutineScope.launch {
        val result = withContext(Dispatchers.Default) { viewModel.runValidate(request) }
        state = viewModel.finishValidate(result)
        runGitRefresh()
      }
    }
  }

  fun runRender() {
    if (canStartRepoScopedAction()) {
      // F-102 mirror: beginRender captures token + session + treeItemId + previousRenderSummary on
      // the caller dispatcher so the dispatcher work below does not read mutable VM fields off-thread.
      val request = viewModel.beginRender()
      state = viewModel.state()
      coroutineScope.launch {
        val result = withContext(Dispatchers.Default) { viewModel.runRender(request) }
        state = viewModel.finishRender(result)
        // AC10: render generates SKILL.md and pointer artifacts; refresh git status afterwards.
        runGitRefresh()
      }
    }
  }

  fun runRenderAll() {
    if (canStartRepoScopedAction()) {
      val request = viewModel.beginRenderAll()
      state = viewModel.state()
      coroutineScope.launch {
        val result = withContext(Dispatchers.Default) { viewModel.runRender(request) }
        state = viewModel.finishRender(result)
        runGitRefresh()
      }
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

  fun showDockTab(tab: DockTab) {
    if (canStartRepoScopedAction()) {
      state = viewModel.setActiveDockTab(tab)
    }
  }

  fun runOpenScaffoldWizard(kind: ScaffoldKind) {
    // F-002/F-404/F-407-arch: the catalog snapshot performs filesystem I/O (it walks
    // `platform-packs/`). Use the begin/run/finish triplet: capture VM state on Main, hop to
    // `Dispatchers.Default` for the fetch, then return to the UI dispatcher to mutate state.
    // The triplet guarantees VM private `var` state is never read off the Main dispatcher.
    if (canStartRepoScopedAction()) {
      val request = viewModel.beginOpenScaffoldWizard(kind) ?: return
      coroutineScope.launch {
        val response = withContext(Dispatchers.Default) { viewModel.runOpenScaffoldWizard(request) }
        state = viewModel.finishOpenScaffoldWizard(response)
      }
    }
  }

  fun runScaffoldDryRun() {
    val request = viewModel.beginScaffoldDryRun()
    state = viewModel.state()
    if (request != null) {
      coroutineScope.launch {
        val result = withContext(Dispatchers.Default) { viewModel.runScaffoldDryRun(request) }
        state = viewModel.finishScaffoldDryRun(request, result)
      }
    }
  }

  fun runScaffoldExecute() {
    val request = viewModel.beginScaffoldExecute()
    state = viewModel.state()
    if (request != null) {
      coroutineScope.launch {
        val result = withContext(Dispatchers.Default) { viewModel.runScaffoldExecute(request) }
        state = viewModel.finishScaffoldExecute(request, result)
        // F-105/F-T01: on success we must refresh the tree, select the authored source for the
        // new artifact (NOT a generated wrapper, AC7), then auto-dismiss the wizard so the user
        // sees the new artifact in the tree (AC6).
        // F-403: refreshAfterScaffold previously ran loadRepo on the UI dispatcher; use the
        // begin/run/finish triplet to keep heavy I/O off the UI thread.
        // F-406: bypass the dirty-editor gate — the scaffold runtime just mutated the repo, the
        // "do not lose unsaved edits" invariant does not apply.
        // F-407-plat: gate the success fan-out on what the VM actually accepted, not the local
        // `result` variable. If the user dismissed mid-flight (or kind-switched), `finishScaffoldExecute`
        // takes the stale-token branch and refuses to apply the result — the wizard's
        // `executionResult` will NOT be a Success in that case (it will be null because the
        // wizard was cleared, or unchanged for a different kind). Reading post-finish state ensures
        // we never auto-refresh / auto-select / auto-dismiss against the user's explicit intent.
        val acceptedSuccess = state.scaffoldWizard?.executionResult as?
          skillbill.desktop.core.domain.model.ScaffoldRunResult.Success
        if (acceptedSuccess != null) {
          val refreshRequest = viewModel.beginRefreshAfterScaffold()
          state = viewModel.state()
          val refreshResult = withContext(Dispatchers.Default) { viewModel.loadRepo(refreshRequest) }
          state = viewModel.finishRefreshAfterScaffold(refreshResult)
          viewModel.resolveAuthoredTreeItemForScaffold(acceptedSuccess.result)?.let { itemId ->
            state = viewModel.selectTreeItem(itemId)
            state.selectedTreeItemId?.let(onSourceRouteSelected)
          }
          state = viewModel.dismissScaffoldWizard()
          // F-408-arch: every other refresh path (runChooseRepoDirectory, DirtyEditorPromptReason.REFRESH/REPO_SWITCH,
          // runRepoLoad) calls BOTH runGitRefresh() AND loadHistory() as a fan-out. The scaffold-success
          // path was previously skipping loadHistory(), leaving the History tab reflecting pre-scaffold
          // state until the next refresh. Align with the standard fan-out.
          runGitRefresh()
          loadHistory()
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
        validate = ::runValidate,
        validateSelected = ::runValidateSelected,
        render = ::runRender,
        renderAll = ::runRenderAll,
        showChanges = { showDockTab(DockTab.Changes) },
        showHistory = { showDockTab(DockTab.History) },
        save = {
          if (canStartRepoScopedAction()) {
            runEditorSave()
          }
        },
        refreshGitStatus = {
          if (canStartRepoScopedAction()) {
            runGitRefresh()
          }
        },
        openScaffoldWizard = ::runOpenScaffoldWizard,
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
          runRepoLoad(preserveSelection = false, repoPath = state.repoPathText) {
            // AC10: after a successful repo switch we want a fresh status snapshot + history.
            runGitRefresh()
            loadHistory()
          }
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
    onValidate = ::runValidate,
    onValidateSelected = ::runValidateSelected,
    onRender = ::runRender,
    onRenderAll = ::runRenderAll,
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
    onActiveDockTabChanged = { tab ->
      if (canStartRepoScopedAction()) {
        state = viewModel.setActiveDockTab(tab)
      }
    },
    onTreeItemSelected = ::runTreeItemSelection,
    onTreeItemExpandedToggled = { itemId ->
      if (canStartRepoScopedAction()) {
        state = viewModel.toggleExpanded(itemId)
      }
    },
    onMoveTreeSelection = { delta ->
      if (canStartRepoScopedAction()) {
        state = viewModel.moveSelection(delta)
      }
    },
    onValidationIssueSelected = { issue ->
      if (canStartRepoScopedAction()) {
        state = viewModel.revealValidationIssue(issue)
      }
    },
    onGeneratedArtifactResolvable = { artifactPath ->
      viewModel.resolveGeneratedArtifactTreeItemId(artifactPath) != null
    },
    onGeneratedArtifactSelected = ::runGeneratedArtifactSelection,
    onChangedFileSelected = { path ->
      val request = viewModel.selectChangedFile(path)
      state = viewModel.state()
      if (request != null) {
        coroutineScope.launch {
          val result = withContext(Dispatchers.Default) { viewModel.runDiff(request) }
          state = viewModel.finishDiff(result)
        }
      }
    },
    onStageChangedFile = { path ->
      if (canStartRepoScopedAction()) {
        val request = viewModel.beginStage(listOf(path))
        state = viewModel.state()
        coroutineScope.launch {
          val result = withContext(Dispatchers.Default) { viewModel.runStage(request) }
          // AC10: stage updates status. Token is shared with refresh so finishGitRefresh handles it.
          state = viewModel.finishGitRefresh(result)
        }
      }
    },
    onUnstageChangedFile = { path ->
      if (canStartRepoScopedAction()) {
        val request = viewModel.beginUnstage(listOf(path))
        state = viewModel.state()
        coroutineScope.launch {
          val result = withContext(Dispatchers.Default) { viewModel.runStage(request) }
          state = viewModel.finishGitRefresh(result)
        }
      }
    },
    onRefreshGit = {
      if (canStartRepoScopedAction()) {
        runGitRefresh()
      }
    },
    onCommitMessageChanged = { message ->
      if (!state.publishBusy && !state.commitBusy && !state.commitValidationRunning) {
        state = viewModel.updateCommitMessage(message)
      }
    },
    onPublishPrTitleChanged = { title ->
      state = viewModel.updatePublishPrTitle(title)
    },
    onPublishPrBodyChanged = { body ->
      state = viewModel.updatePublishPrBody(body)
    },
    onPublishDraftChanged = { draft ->
      state = viewModel.setPublishDraft(draft)
    },
    onPublishPathSelectionChanged = { path, selected ->
      state = viewModel.setPublishPathSelected(path, selected)
    },
    onPublish = {
      runPublish()
    },
    onPublishAfterFailedValidation = {
      runPublish(
        allowFailedValidation = true,
        allowCanonicalRemote = state.pushTarget?.isLikelyCanonical == true,
      )
    },
    onCommit = {
      runCommit(allowFailedValidation = false)
    },
    onCommitAfterFailedValidation = {
      runCommit(allowFailedValidation = true)
    },
    onPush = {
      runPush(allowCanonicalRemote = false)
    },
    onConfirmCanonicalPush = {
      runPush(allowCanonicalRemote = true)
    },
    onConfirmCanonicalPublish = {
      runPublish(
        allowFailedValidation = state.commitValidationFailed,
        allowCanonicalRemote = true,
      )
    },
    onOpenCompareUrl = { url ->
      coroutineScope.launch {
        val outcome = withContext(Dispatchers.Default) {
          openCompareUrlSafely(url = url, browserLauncher = browserLauncher)
        }
        when (outcome) {
          BrowserLaunchOutcome.Opened -> {
            if (recentlyCopiedKey == url) {
              recentlyCopiedKey = null
            }
            recentlyOpenedCompareUrlKey = url
          }
          is BrowserLaunchOutcome.Failed -> {
            if (recentlyOpenedCompareUrlKey == url) {
              recentlyOpenedCompareUrlKey = null
            }
            clipboardManager.setText(AnnotatedString(url))
            recentlyCopiedKey = url
          }
        }
      }
    },
    onCopyChangedFilePath = { path ->
      // F-106 mirror: clipboard side effect lives in the route; UI emits pure copy callbacks.
      clipboardManager.setText(AnnotatedString(path))
      // F-X-512: flash "copied" affordance for the matching row.
      recentlyCopiedKey = path
    },
    onCopyCommitHash = { hash ->
      clipboardManager.setText(AnnotatedString(hash))
      recentlyCopiedKey = hash
    },
    onClearHistoryPathFilter = {
      viewModel.setHistoryPathFilter(null)
      loadHistory()
      state = viewModel.state()
    },
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
    scaffoldWizardCallbacks = ScaffoldWizardCallbacks(
      onSelectKind = { kind ->
        state = viewModel.selectScaffoldWizardKind(kind)
      },
      onFormChanged = { transform ->
        state = viewModel.updateScaffoldForm(transform)
      },
      onDirtyOverrideChanged = { override ->
        state = viewModel.setScaffoldDirtyOverride(override)
      },
      onPlan = ::runScaffoldDryRun,
      onRun = ::runScaffoldExecute,
      onAcknowledgeFailure = {
        state = viewModel.acknowledgeScaffoldFailure()
      },
      onDismiss = {
        state = viewModel.dismissScaffoldWizard()
      },
    ),
    recentlyCopiedKey = recentlyCopiedKey,
    recentlyOpenedCompareUrlKey = recentlyOpenedCompareUrlKey,
  )
}

// F-X-512: duration in milliseconds the "copied" affordance stays visible before the route clears
// the key. Kept conservative so the affordance is visible but not distracting.
private const val COPY_FEEDBACK_DURATION_MILLIS: Long = 1500L

internal fun executeGeneratedArtifactSelection(
  artifactPath: String,
  resolveTreeItemId: (String) -> String?,
  selectTreeItem: (String) -> Unit,
): Boolean {
  val resolvedTreeItemId = resolveTreeItemId(artifactPath) ?: return false
  selectTreeItem(resolvedTreeItemId)
  return true
}

internal fun handleCompareUrlActivation(
  url: String,
  browserLauncher: BrowserLauncher,
  copyUrl: (String) -> Unit,
): BrowserLaunchOutcome {
  val outcome = openCompareUrlSafely(url = url, browserLauncher = browserLauncher)
  if (outcome is BrowserLaunchOutcome.Failed) {
    copyUrl(url)
  }
  return outcome
}

internal fun openCompareUrlSafely(url: String, browserLauncher: BrowserLauncher): BrowserLaunchOutcome = try {
  browserLauncher.openCompareUrl(url)
} catch (exception: Exception) {
  BrowserLaunchOutcome.Failed(BrowserLaunchFailure.LaunchFailed(exception.message))
}
