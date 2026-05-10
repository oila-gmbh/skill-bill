package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.testing.FakeAuthoringGateway
import skillbill.desktop.core.testing.FakeGitGateway
import skillbill.desktop.core.testing.FakeRecentRepoRepository
import skillbill.desktop.core.testing.FakeRepoSessionService
import skillbill.desktop.core.testing.FakeSkillTreeService
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillBillViewModelTest {
  @Test
  fun `selecting repo refreshes tree and source control state`() {
    val viewModel = newViewModel()

    val state = viewModel.selectRepoPath("/repo")

    assertEquals("/repo", state.selectedRepoPath)
    assertEquals("main", state.sourceControl.branchLabel)
    assertEquals("skill-one", state.treeItems.single().children.single().id)
  }

  @Test
  fun `unknown tree selection clears editor selection`() {
    val viewModel = newViewModel()
    viewModel.selectRepoPath("/repo")

    val state = viewModel.selectTreeItem("missing")

    assertEquals(null, state.selectedTreeItemId)
    assertEquals("No source selected", state.editor.title)
  }

  @Test
  fun `route selected source drives editor selection`() {
    val viewModel = newViewModel()
    viewModel.selectRepoPath("/repo")

    val state = viewModel.state("skill-one")

    assertEquals("skill-one", state.selectedTreeItemId)
    assertEquals("skill-one", state.editor.title)
  }

  private fun newViewModel(): SkillBillViewModel = SkillBillViewModel(
    repoSessionService = FakeRepoSessionService(),
    skillTreeService =
    FakeSkillTreeService(
      listOf(
        SkillBillTreeItem(
          id = "skills",
          label = "Skills",
          kind = TreeItemKind.GROUP,
          children =
          listOf(
            SkillBillTreeItem(
              id = "skill-one",
              label = "Skill One",
              kind = TreeItemKind.PLACEHOLDER,
            ),
          ),
        ),
      ),
    ),
    authoringGateway = FakeAuthoringGateway(),
    gitGateway = FakeGitGateway(),
    recentRepoRepository = FakeRecentRepoRepository(),
  )
}
