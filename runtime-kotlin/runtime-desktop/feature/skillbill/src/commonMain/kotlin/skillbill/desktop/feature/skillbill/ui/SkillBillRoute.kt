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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
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

  SkillBillFrame(
    state = state,
    canNavigateBack = canNavigateBack,
    onNavigateBack = { if (state.busyOperation == null) onNavigateBack() },
    onRepoPathChanged = { repoPath ->
      if (state.busyOperation == null) {
        state = viewModel.updateRepoPathText(repoPath)
      }
    },
    onRepoSelected = { repoPath ->
      if (state.busyOperation == null) {
        state = viewModel.beginSelectRepoPath(repoPath)
        val request = viewModel.repoLoadRequest(repoPath = repoPath, preserveSelection = false)
        coroutineScope.launch {
          val result = withContext(Dispatchers.Default) { viewModel.loadRepo(request) }
          state = viewModel.finishSelectRepoPath(result)
          // AC10: after a successful repo switch we want a fresh status snapshot + history.
          runGitRefresh()
          loadHistory()
        }
      }
    },
    onChooseRepoDirectory = {
      if (state.busyOperation == null) {
        state = viewModel.busyState(SkillBillBusyOperation.CHOOSE_DIRECTORY)
        val repoPath = chooseRepoDirectory(state.repoPathText)
        if (repoPath.isNullOrBlank()) {
          state = viewModel.chooseRepoDirectory(repoPath)
        } else {
          state = viewModel.beginSelectRepoPath(repoPath)
          val request = viewModel.repoLoadRequest(repoPath = repoPath, preserveSelection = false)
          coroutineScope.launch {
            val result = withContext(Dispatchers.Default) { viewModel.loadRepo(request) }
            state = viewModel.finishSelectRepoPath(result)
            runGitRefresh()
            loadHistory()
          }
        }
      }
    },
    onRefresh = {
      if (state.busyOperation == null) {
        state = viewModel.beginRefresh()
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
    },
    onValidate = {
      if (state.busyOperation == null) {
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
    },
    onRender = {
      if (state.busyOperation == null) {
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
    },
    onActiveDockTabChanged = { tab ->
      if (state.busyOperation == null) {
        state = viewModel.setActiveDockTab(tab)
      }
    },
    onTreeItemSelected = { itemId ->
      if (state.busyOperation == null) {
        state = viewModel.selectTreeItem(itemId)
        // AC7: tree selection narrows history to commits that touched the authored path of the
        // selected tree item. Falls back to no filter when the selection has no authored path.
        val authoredPath = state.editor.authoredPath
        viewModel.setHistoryPathFilter(authoredPath)
        loadHistory()
        state = viewModel.state()
        onSourceRouteSelected(itemId)
      }
    },
    onTreeItemExpandedToggled = { itemId ->
      if (state.busyOperation == null) {
        state = viewModel.toggleExpanded(itemId)
      }
    },
    onMoveTreeSelection = { delta ->
      if (state.busyOperation == null) {
        state = viewModel.moveSelection(delta)
      }
    },
    onValidationIssueSelected = { issue ->
      if (state.busyOperation == null) {
        state = viewModel.revealValidationIssue(issue)
      }
    },
    // F-106: clipboard side effect is hoisted into the route so the inspector stays pure.
    onCopyIssueSource = { sourcePath ->
      clipboardManager.setText(AnnotatedString(sourcePath))
    },
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
      val request = viewModel.beginStage(listOf(path))
      state = viewModel.state()
      coroutineScope.launch {
        val result = withContext(Dispatchers.Default) { viewModel.runStage(request) }
        // AC10: stage updates status. Token is shared with refresh so finishGitRefresh handles it.
        state = viewModel.finishGitRefresh(result)
      }
    },
    onUnstageChangedFile = { path ->
      val request = viewModel.beginUnstage(listOf(path))
      state = viewModel.state()
      coroutineScope.launch {
        val result = withContext(Dispatchers.Default) { viewModel.runStage(request) }
        state = viewModel.finishGitRefresh(result)
      }
    },
    onRefreshGit = { runGitRefresh() },
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
    recentlyCopiedKey = recentlyCopiedKey,
  )
}

// F-X-512: duration in milliseconds the "copied" affordance stays visible before the route clears
// the key. Kept conservative so the affordance is visible but not distracting.
private const val COPY_FEEDBACK_DURATION_MILLIS: Long = 1500L
