package skillbill.desktop.feature.workbench.state

import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.domain.model.WorkbenchTreeItem
import skillbill.desktop.core.testing.FakeAuthoringGateway
import skillbill.desktop.core.testing.FakeGitGateway
import skillbill.desktop.core.testing.FakeRepoSessionService
import skillbill.desktop.core.testing.FakeSkillTreeService
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkbenchViewModelTest {
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

  private fun newViewModel(): WorkbenchViewModel = WorkbenchViewModel(
    repoSessionService = FakeRepoSessionService(),
    skillTreeService =
    FakeSkillTreeService(
      listOf(
        WorkbenchTreeItem(
          id = "skills",
          label = "Skills",
          kind = TreeItemKind.GROUP,
          children =
          listOf(
            WorkbenchTreeItem(
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
  )
}
