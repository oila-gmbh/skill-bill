@file:Suppress("FunctionName")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
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
        }
      }
    },
    onTreeItemSelected = { itemId ->
      if (state.busyOperation == null) {
        state = viewModel.selectTreeItem(itemId)
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
  )
}
