package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillStatusBar
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
import skillbill.desktop.core.testing.FakeAuthoringGateway
import skillbill.desktop.core.testing.FakeGitGateway
import skillbill.desktop.core.testing.FakeRecentRepoRepository
import skillbill.desktop.core.testing.FakeRepoSessionService
import skillbill.desktop.core.testing.FakeSkillTreeService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SkillBillViewModelTest {
  @Test
  fun `selecting repo refreshes tree and source control state`() {
    val viewModel = newViewModel()

    val state = viewModel.selectRepoPath("/repo")

    assertEquals("/repo", state.selectedRepoPath)
    assertEquals("/repo", state.repoPathText)
    assertEquals("main", state.sourceControl.branchLabel)
    assertEquals("skill-one", state.treeItems.single().children.single().id)
    assertEquals(1, state.statusBar.targetCount)
    assertEquals("/repo", state.statusBar.repoPathLabel)
    assertEquals("main", state.statusBar.branchLabel)
    assertEquals(SkillBillStatusBar.READ_ONLY_MODE_LABEL, state.statusBar.readOnlyModeLabel)
    assertEquals(SkillBillStatusBar.POLICY_LABEL, state.statusBar.policyLabel)
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

  @Test
  fun `state reads and tree selection do not reopen repo`() {
    val repoSessionService = CountingRepoSessionService()
    val viewModel = newViewModel(repoSessionService = repoSessionService)
    viewModel.selectRepoPath("/repo")

    viewModel.state()
    viewModel.selectTreeItem("skill-one")

    assertEquals(listOf("/repo"), repoSessionService.openedRepoPaths)
  }

  @Test
  fun `repo path text can change without opening repo`() {
    val repoSessionService = CountingRepoSessionService()
    val viewModel = newViewModel(repoSessionService = repoSessionService)

    val state = viewModel.updateRepoPathText("/typed")

    assertEquals("/typed", state.repoPathText)
    assertEquals(emptyList(), repoSessionService.openedRepoPaths)
    assertEquals(RepoLoadState.EMPTY, state.repoStatus.state)
  }

  @Test
  fun `invalid refresh clears stale tree and selected item`() {
    val repoSessionService = MutableRepoSessionService(loadedSession("/repo"))
    val skillTreeService = MutableSkillTreeService(skillTree("skill-one"))
    val viewModel = newViewModel(repoSessionService = repoSessionService, skillTreeService = skillTreeService)
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    repoSessionService.nextSession = invalidSession("/repo")
    skillTreeService.items = emptyList()

    val state = viewModel.refresh()

    assertEquals(RepoLoadState.INVALID, state.repoStatus.state)
    assertEquals(emptyList(), state.treeItems)
    assertNull(state.selectedTreeItemId)
    assertEquals("No source selected", state.editor.title)
  }

  @Test
  fun `refresh preserves selection only when item still exists in same repo`() {
    val repoSessionService = MutableRepoSessionService(loadedSession("/repo"))
    val skillTreeService = MutableSkillTreeService(skillTree("skill-one"))
    val viewModel = newViewModel(repoSessionService = repoSessionService, skillTreeService = skillTreeService)
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    skillTreeService.items = skillTree("skill-one", "skill-two")

    val preserved = viewModel.refresh()

    assertEquals("skill-one", preserved.selectedTreeItemId)

    skillTreeService.items = skillTree("skill-two")
    val deleted = viewModel.refresh()

    assertNull(deleted.selectedTreeItemId)
  }

  @Test
  fun `refresh clears selection when repo changes`() {
    val repoSessionService = MutableRepoSessionService(loadedSession("/repo"))
    val skillTreeService = MutableSkillTreeService(skillTree("skill-one"))
    val viewModel = newViewModel(repoSessionService = repoSessionService, skillTreeService = skillTreeService)
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    repoSessionService.nextSession = loadedSession("/other")

    val state = viewModel.refresh()

    assertNull(state.selectedTreeItemId)
  }

  @Test
  fun `chooser result opens through repo selection flow`() {
    val repoSessionService = CountingRepoSessionService()
    val viewModel = newViewModel(repoSessionService = repoSessionService)

    val state = viewModel.chooseRepoDirectory("/chosen")

    assertEquals("/chosen", state.repoPathText)
    assertEquals("/chosen", state.selectedRepoPath)
    assertEquals(listOf("/chosen"), repoSessionService.openedRepoPaths)
  }

  @Test
  fun `chooser cancel clears busy state`() {
    val viewModel = newViewModel()
    viewModel.busyState(SkillBillBusyOperation.CHOOSE_DIRECTORY)

    val state = viewModel.chooseRepoDirectory(null)

    assertNull(state.busyOperation)
  }

  @Test
  fun `begin and finish repo selection expose busy state around runtime load`() {
    val repoSessionService = CountingRepoSessionService()
    val viewModel = newViewModel(repoSessionService = repoSessionService)

    val busy = viewModel.beginSelectRepoPath("/repo")

    assertEquals(SkillBillBusyOperation.OPEN_REPO, busy.busyOperation)
    assertEquals("/repo", busy.repoPathText)
    assertEquals(emptyList(), repoSessionService.openedRepoPaths)

    val loaded = viewModel.finishSelectRepoPath()

    assertNull(loaded.busyOperation)
    assertEquals(listOf("/repo"), repoSessionService.openedRepoPaths)
    assertEquals(RepoLoadState.LOADED, loaded.repoStatus.state)
  }

  @Test
  fun `begin and finish refresh expose busy state around runtime reload`() {
    val repoSessionService = CountingRepoSessionService()
    val viewModel = newViewModel(repoSessionService = repoSessionService)
    viewModel.selectRepoPath("/repo")
    repoSessionService.openedRepoPaths.clear()

    val busy = viewModel.beginRefresh()

    assertEquals(SkillBillBusyOperation.REFRESH, busy.busyOperation)
    assertEquals(emptyList(), repoSessionService.openedRepoPaths)

    val refreshed = viewModel.finishRefresh()

    assertNull(refreshed.busyOperation)
    assertEquals(listOf("/repo"), repoSessionService.openedRepoPaths)
    assertEquals(RepoLoadState.LOADED, refreshed.repoStatus.state)
  }

  @Test
  fun `stale repo open result cannot overwrite newer repo state`() {
    val repoSessionService = MutableRepoSessionService(loadedSession("/old"))
    val skillTreeService = MutableSkillTreeService(skillTree("old-skill"))
    val viewModel = newViewModel(repoSessionService = repoSessionService, skillTreeService = skillTreeService)
    viewModel.beginSelectRepoPath("/old")
    val staleRequest = viewModel.repoLoadRequest(repoPath = "/old", preserveSelection = false)
    val staleResult = viewModel.loadRepo(staleRequest)
    repoSessionService.nextSession = loadedSession("/new")
    skillTreeService.items = skillTree("new-skill")
    viewModel.beginSelectRepoPath("/new")
    val currentRequest = viewModel.repoLoadRequest(repoPath = "/new", preserveSelection = false)

    val currentState = viewModel.finishSelectRepoPath(viewModel.loadRepo(currentRequest))
    val afterStaleResult = viewModel.finishSelectRepoPath(staleResult)

    assertEquals("/new", currentState.selectedRepoPath)
    assertEquals("/new", afterStaleResult.selectedRepoPath)
    assertEquals(listOf("new-skill"), afterStaleResult.treeItems.single().children.map { it.id })
  }

  @Test
  fun `stale refresh result cannot overwrite newer open state`() {
    val repoSessionService = MutableRepoSessionService(loadedSession("/repo"))
    val skillTreeService = MutableSkillTreeService(skillTree("skill-one"))
    val viewModel = newViewModel(repoSessionService = repoSessionService, skillTreeService = skillTreeService)
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    viewModel.beginRefresh()
    val staleRequest = viewModel.repoLoadRequest(repoPath = "/repo", preserveSelection = true)
    skillTreeService.items = skillTree("skill-one", "stale-skill")
    val staleResult = viewModel.loadRepo(staleRequest)
    repoSessionService.nextSession = loadedSession("/new")
    skillTreeService.items = skillTree("new-skill")
    viewModel.beginSelectRepoPath("/new")
    val currentRequest = viewModel.repoLoadRequest(repoPath = "/new", preserveSelection = false)

    viewModel.finishSelectRepoPath(viewModel.loadRepo(currentRequest))
    val afterStaleResult = viewModel.finishRepoLoad(staleResult)

    assertEquals("/new", afterStaleResult.selectedRepoPath)
    assertEquals(listOf("new-skill"), afterStaleResult.treeItems.single().children.map { it.id })
    assertNull(afterStaleResult.selectedTreeItemId)
  }

  @Test
  fun `expand collapse does not reload runtime data or change selection`() {
    val repoSessionService = CountingRepoSessionService()
    val viewModel = newViewModel(repoSessionService = repoSessionService)
    val loaded = viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")

    val collapsed = viewModel.toggleExpanded(loaded.treeItems.single().id)

    assertFalse(loaded.treeItems.single().id in collapsed.expandedNodeIds)
    assertEquals("skill-one", collapsed.selectedTreeItemId)
    assertEquals(listOf("/repo"), repoSessionService.openedRepoPaths)
  }

  @Test
  fun `keyboard movement selects through visible nodes`() {
    val viewModel = newViewModel(
      skillTreeService = MutableSkillTreeService(
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
        ),
      ),
    )
    viewModel.selectRepoPath("/repo")

    val first = viewModel.moveSelection(1)
    val second = viewModel.moveSelection(1)

    assertEquals("skills", first.selectedTreeItemId)
    assertEquals("skill-one", second.selectedTreeItemId)
  }

  @Test
  fun `generated artifact selection remains read only in presentation state`() {
    val viewModel = newViewModel(
      skillTreeService = MutableSkillTreeService(
        listOf(
          SkillBillTreeItem(
            id = "generated",
            label = "Generated",
            kind = TreeItemKind.GROUP,
            children = listOf(
              SkillBillTreeItem(
                id = "generated-skill",
                label = "skills/bill-alpha/SKILL.md",
                kind = TreeItemKind.GENERATED_ARTIFACT,
                editable = false,
                readOnlyLabel = "RO",
              ),
            ),
          ),
        ),
      ),
      authoringGateway = StaticAuthoringGateway(
        EditorPlaceholder(
          title = "SKILL.md",
          detail = "Generated runtime wrapper",
          kind = "generated artifact",
          editable = false,
          readOnlyLabel = "RO",
        ),
      ),
    )
    viewModel.selectRepoPath("/repo")

    val state = viewModel.selectTreeItem("generated-skill")

    assertFalse(state.treeItems.single().children.single().editable)
    assertEquals("RO", state.treeItems.single().children.single().readOnlyLabel)
    assertFalse(state.editor.editable)
    assertEquals("RO", state.editor.readOnlyLabel)
  }

  @Test
  fun `busy state disables conflicting presentation actions`() {
    val viewModel = newViewModel()

    val state = viewModel.busyState(SkillBillBusyOperation.REFRESH)

    assertEquals(SkillBillBusyOperation.REFRESH, state.busyOperation)
  }

  private fun newViewModel(
    repoSessionService: RepoSessionService = FakeRepoSessionService(),
    skillTreeService: SkillTreeService = FakeSkillTreeService(defaultSkillTree()),
    authoringGateway: AuthoringGateway = FakeAuthoringGateway(),
    gitGateway: GitGateway = FakeGitGateway(),
    recentRepoRepository: FakeRecentRepoRepository = FakeRecentRepoRepository(),
  ): SkillBillViewModel = SkillBillViewModel(
    repoSessionService = repoSessionService,
    skillTreeService = skillTreeService,
    authoringGateway = authoringGateway,
    gitGateway = gitGateway,
    recentRepoRepository = recentRepoRepository,
  )
}

private fun defaultSkillTree(): List<SkillBillTreeItem> = listOf(
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
)

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

private class MutableRepoSessionService(var nextSession: RepoSession) : RepoSessionService {
  override fun open(repoPath: String): RepoSession =
    nextSession.copy(repoPath = nextSession.repoPath.ifBlank { repoPath })
}

private class MutableSkillTreeService(var items: List<SkillBillTreeItem>) : SkillTreeService {
  override fun treeFor(session: RepoSession?): List<SkillBillTreeItem> = items
}

private class StaticAuthoringGateway(private val editor: EditorPlaceholder) : AuthoringGateway {
  override fun describeSelection(treeItemId: String): EditorPlaceholder = editor
}

private fun loadedSession(repoPath: String): RepoSession = RepoSession(
  repoPath = repoPath,
  isRecognizedSkillBillRepo = true,
  loadStatus = RepoLoadStatus(
    state = RepoLoadState.LOADED,
    message = "Loaded",
  ),
)

private fun invalidSession(repoPath: String): RepoSession = RepoSession(
  repoPath = repoPath,
  isRecognizedSkillBillRepo = false,
  loadStatus = RepoLoadStatus(
    state = RepoLoadState.INVALID,
    message = "Invalid",
  ),
)

private fun skillTree(vararg ids: String): List<SkillBillTreeItem> = listOf(
  SkillBillTreeItem(
    id = "skills",
    label = "Skills",
    kind = TreeItemKind.GROUP,
    children = ids.map { id -> SkillBillTreeItem(id = id, label = id, kind = TreeItemKind.SKILL) },
  ),
)
