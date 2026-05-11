package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
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

  @Test
  fun `invalid repo selection surfaces error and is not remembered`() {
    val recentRepoRepository = FakeRecentRepoRepository()
    val viewModel = SkillBillViewModel(
      repoSessionService = InvalidRepoSessionService(),
      skillTreeService = FakeSkillTreeService(emptyList()),
      authoringGateway = FakeAuthoringGateway(),
      gitGateway = FakeGitGateway(),
      recentRepoRepository = recentRepoRepository,
    )

    val state = viewModel.selectRepoPath("/not-skill-bill")

    assertEquals(RepoLoadState.INVALID, state.repoStatus.state)
    assertEquals("/not-skill-bill", state.selectedRepoPath)
    assertEquals(null, recentRepoRepository.recentRepoPath())
    assertEquals(emptyList(), state.treeItems)
  }

  @Test
  fun `explicit refresh reloads tree state`() {
    val repoSessionService = CountingRepoSessionService()
    val skillTreeService =
      MutableSkillTreeService(
        listOf(
          SkillBillTreeItem(
            id = "skills",
            label = "Skills",
            kind = TreeItemKind.GROUP,
            children = listOf(SkillBillTreeItem(id = "skill-one", label = "Skill One", kind = TreeItemKind.SKILL)),
          ),
        ),
      )
    val viewModel = SkillBillViewModel(
      repoSessionService = repoSessionService,
      skillTreeService = skillTreeService,
      authoringGateway = FakeAuthoringGateway(),
      gitGateway = FakeGitGateway(),
      recentRepoRepository = FakeRecentRepoRepository(),
    )
    viewModel.selectRepoPath("/repo")
    skillTreeService.items =
      listOf(
        SkillBillTreeItem(
          id = "skills",
          label = "Skills",
          kind = TreeItemKind.GROUP,
          children = listOf(
            SkillBillTreeItem(id = "skill-one", label = "Skill One", kind = TreeItemKind.SKILL),
            SkillBillTreeItem(id = "skill-two", label = "Skill Two", kind = TreeItemKind.SKILL),
          ),
        ),
      )

    val state = viewModel.refresh()

    assertEquals(listOf("skill-one", "skill-two"), state.treeItems.single().children.map { it.id })
    assertEquals(listOf("/repo", "/repo"), repoSessionService.openedRepoPaths)
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

private class InvalidRepoSessionService : RepoSessionService {
  override fun open(repoPath: String): RepoSession = RepoSession(
    repoPath = repoPath,
    isRecognizedSkillBillRepo = false,
    loadStatus = RepoLoadStatus(
      state = RepoLoadState.INVALID,
      message = "Invalid repo",
    ),
  )
}

private class CountingRepoSessionService : RepoSessionService {
  val openedRepoPaths = mutableListOf<String>()

  override fun open(repoPath: String): RepoSession {
    openedRepoPaths += repoPath
    return RepoSession(
      repoPath = repoPath,
      isRecognizedSkillBillRepo = true,
      loadStatus = RepoLoadStatus(
        state = RepoLoadState.LOADED,
        message = "Loaded",
      ),
    )
  }
}

private class MutableSkillTreeService(var items: List<SkillBillTreeItem>) : SkillTreeService {
  override fun treeFor(session: RepoSession?): List<SkillBillTreeItem> = items
}
