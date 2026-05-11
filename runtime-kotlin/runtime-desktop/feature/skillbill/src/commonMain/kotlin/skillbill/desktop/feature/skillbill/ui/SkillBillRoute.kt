@file:Suppress("FunctionName")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.ui.di.rememberScreenComponent
import skillbill.desktop.feature.skillbill.di.SkillBillComponent

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
  )
}
