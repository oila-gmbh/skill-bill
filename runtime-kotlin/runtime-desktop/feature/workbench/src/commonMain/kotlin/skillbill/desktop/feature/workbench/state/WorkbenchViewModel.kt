package skillbill.desktop.feature.workbench.state

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.ScreenScope
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.WorkbenchState
import skillbill.desktop.core.domain.model.WorkbenchTreeItem
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(ScreenScope::class)
class WorkbenchViewModel(
  private val repoSessionService: RepoSessionService,
  private val skillTreeService: SkillTreeService,
  private val authoringGateway: AuthoringGateway,
  private val gitGateway: GitGateway,
) {
  private var currentState = createState(selectedRepoPath = null, selectedTreeItemId = null)

  fun state(): WorkbenchState = currentState

  fun selectRepoPath(repoPath: String): WorkbenchState {
    currentState = createState(selectedRepoPath = repoPath, selectedTreeItemId = null)
    return currentState
  }

  fun selectTreeItem(itemId: String): WorkbenchState {
    val knownItemIds = currentState.treeItems.flatten().map(WorkbenchTreeItem::id).toSet()
    currentState =
      if (itemId in knownItemIds) {
        currentState.copy(
          selectedTreeItemId = itemId,
          editor = authoringGateway.describeSelection(itemId),
        )
      } else {
        currentState.copy(selectedTreeItemId = null, editor = EditorPlaceholder.empty)
      }
    return currentState
  }

  private fun createState(selectedRepoPath: String?, selectedTreeItemId: String?): WorkbenchState {
    val session = selectedRepoPath?.let(repoSessionService::open)
    return WorkbenchState(
      selectedRepoPath = session?.repoPath,
      treeItems = skillTreeService.treeFor(session).map(WorkbenchTreeItem::snapshot),
      selectedTreeItemId = selectedTreeItemId,
      editor = selectedTreeItemId?.let(authoringGateway::describeSelection) ?: EditorPlaceholder.empty,
      sourceControl = gitGateway.statusFor(session),
    )
  }
}

private fun List<WorkbenchTreeItem>.flatten(): List<WorkbenchTreeItem> =
  flatMap { item -> listOf(item) + item.children.flatten() }

private fun WorkbenchTreeItem.snapshot(): WorkbenchTreeItem = copy(children = children.map(WorkbenchTreeItem::snapshot))
