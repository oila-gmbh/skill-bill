package skillbill.desktop.feature.skillbill.state

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.ScreenScope
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.SkillBillState
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
  private var currentState =
    createState(selectedRepoPath = recentRepoRepository.recentRepoPath(), selectedTreeItemId = null)

  fun state(selectedTreeItemId: String? = currentState.selectedTreeItemId): SkillBillState {
    currentState = createState(
      selectedRepoPath = recentRepoRepository.recentRepoPath(),
      selectedTreeItemId = selectedTreeItemId,
    )
    return currentState
  }

  fun selectRepoPath(repoPath: String): SkillBillState {
    recentRepoRepository.rememberRepoPath(repoPath)
    currentState = createState(selectedRepoPath = recentRepoRepository.recentRepoPath(), selectedTreeItemId = null)
    return currentState
  }

  fun selectTreeItem(itemId: String): SkillBillState {
    currentState = createState(selectedRepoPath = currentState.selectedRepoPath, selectedTreeItemId = itemId)
    return currentState
  }

  private fun createState(selectedRepoPath: String?, selectedTreeItemId: String?): SkillBillState {
    val session = selectedRepoPath?.let(repoSessionService::open)
    val treeItems = skillTreeService.treeFor(session).map(SkillBillTreeItem::snapshot)
    val resolvedTreeItemId = selectedTreeItemId?.takeIf { treeItemId ->
      treeItemId in treeItems.flatten().map(SkillBillTreeItem::id).toSet()
    }
    return SkillBillState(
      selectedRepoPath = session?.repoPath,
      treeItems = treeItems,
      selectedTreeItemId = resolvedTreeItemId,
      editor = resolvedTreeItemId?.let(authoringGateway::describeSelection) ?: EditorPlaceholder.empty,
      sourceControl = gitGateway.statusFor(session),
    )
  }
}

private fun List<SkillBillTreeItem>.flatten(): List<SkillBillTreeItem> =
  flatMap { item -> listOf(item) + item.children.flatten() }

private fun SkillBillTreeItem.snapshot(): SkillBillTreeItem = copy(children = children.map(SkillBillTreeItem::snapshot))
