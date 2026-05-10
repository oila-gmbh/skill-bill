@file:Suppress("FunctionName")

package skillbill.desktop.feature.workbench.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import skillbill.desktop.feature.workbench.di.WorkbenchComponentFactory

@Composable
fun WorkbenchRoute(componentFactory: WorkbenchComponentFactory) {
  val component = remember(componentFactory) { componentFactory.create() }
  val viewModel = component.viewModel
  var state by remember(viewModel) { mutableStateOf(viewModel.state()) }

  WorkbenchFrame(
    state = state,
    onRepoSelected = { repoPath -> state = viewModel.selectRepoPath(repoPath) },
    onTreeItemSelected = { itemId -> state = viewModel.selectTreeItem(itemId) },
  )
}
