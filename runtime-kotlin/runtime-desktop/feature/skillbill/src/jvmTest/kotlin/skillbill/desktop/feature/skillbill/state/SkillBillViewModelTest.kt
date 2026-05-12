package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillStatusBar
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.domain.model.ValidationIssue
import skillbill.desktop.core.domain.model.ValidationRunState
import skillbill.desktop.core.domain.model.ValidationSeverity
import skillbill.desktop.core.domain.model.ValidationSummary
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
import skillbill.desktop.core.domain.service.ValidationGateway
import skillbill.desktop.core.testing.FakeAuthoringGateway
import skillbill.desktop.core.testing.FakeGitGateway
import skillbill.desktop.core.testing.FakeRecentRepoRepository
import skillbill.desktop.core.testing.FakeRepoSessionService
import skillbill.desktop.core.testing.FakeSkillTreeService
import skillbill.desktop.core.testing.FakeValidationGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
      validationGateway = FakeValidationGateway(),
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
      validationGateway = FakeValidationGateway(),
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
  fun `keyboard movement skips children of collapsed groups`() {
    val viewModel = newViewModel(
      skillTreeService = MutableSkillTreeService(
        listOf(
          SkillBillTreeItem(
            id = "group-a",
            label = "Group A",
            kind = TreeItemKind.GROUP,
            children = listOf(
              SkillBillTreeItem(id = "a-one", label = "A One", kind = TreeItemKind.SKILL),
              SkillBillTreeItem(id = "a-two", label = "A Two", kind = TreeItemKind.SKILL),
            ),
          ),
          SkillBillTreeItem(
            id = "group-b",
            label = "Group B",
            kind = TreeItemKind.GROUP,
            children = listOf(
              SkillBillTreeItem(id = "b-one", label = "B One", kind = TreeItemKind.SKILL),
              SkillBillTreeItem(id = "b-two", label = "B Two", kind = TreeItemKind.SKILL),
            ),
          ),
        ),
      ),
    )
    viewModel.selectRepoPath("/repo")
    viewModel.toggleExpanded("group-b")

    val visited = mutableListOf<String?>()
    repeat(5) { visited += viewModel.moveSelection(1).selectedTreeItemId }

    assertEquals(listOf<String?>("group-a", "a-one", "a-two", "group-b", "group-b"), visited)
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

  @Test
  fun `validate with no repo open is no-op and stays unavailable`() {
    val validationGateway = FakeValidationGateway()
    val viewModel = newViewModel(validationGateway = validationGateway)

    val request = viewModel.beginValidate()
    val running = viewModel.state()
    val result = viewModel.runValidate(request)
    val finished = viewModel.finishValidate(result)

    assertEquals(ValidationRunState.RUNNING, running.validation.state)
    assertEquals(ValidationRunState.UNAVAILABLE, finished.validation.state)
    assertEquals(1, validationGateway.validateCallCount)
  }

  @Test
  fun `successful validation transitions running to passed and clears stale issues`() {
    val staleSummary = ValidationSummary(
      state = ValidationRunState.FAILED,
      issues = listOf(
        ValidationIssue(
          severity = ValidationSeverity.ERROR,
          code = null,
          message = "stale issue",
          sourcePath = "skills/old.md",
        ),
      ),
    )
    val validationGateway = FakeValidationGateway(scriptedSummary = staleSummary)
    val viewModel = newViewModel(validationGateway = validationGateway)
    viewModel.selectRepoPath("/repo")

    val seededRequest = viewModel.beginValidate()
    val seededFinish = viewModel.finishValidate(viewModel.runValidate(seededRequest))
    assertEquals(ValidationRunState.FAILED, seededFinish.validation.state)
    assertEquals(1, seededFinish.validation.issues.size)

    // Now flip gateway to PASSED and validate again.
    validationGateway.scriptedSummary = ValidationSummary(state = ValidationRunState.PASSED)
    val nextRequest = viewModel.beginValidate()
    val running = viewModel.state()
    assertEquals(ValidationRunState.RUNNING, running.validation.state)
    val passed = viewModel.finishValidate(viewModel.runValidate(nextRequest))

    assertEquals(ValidationRunState.PASSED, passed.validation.state)
    assertTrue(passed.validation.issues.isEmpty())
    assertNull(passed.busyOperation)
  }

  @Test
  fun `failed validation preserves editor selection`() {
    val validationGateway = FakeValidationGateway()
    val viewModel = newViewModel(validationGateway = validationGateway)
    viewModel.selectRepoPath("/repo")
    val selected = viewModel.selectTreeItem("skill-one")
    val editorBefore = selected.editor

    validationGateway.scriptedSummary = ValidationSummary(
      state = ValidationRunState.FAILED,
      issues = listOf(
        ValidationIssue(
          severity = ValidationSeverity.ERROR,
          code = null,
          message = "boom",
          sourcePath = null,
        ),
      ),
    )
    val request = viewModel.beginValidate()
    val finished = viewModel.finishValidate(viewModel.runValidate(request))

    assertEquals(ValidationRunState.FAILED, finished.validation.state)
    assertEquals(editorBefore, finished.editor)
  }

  @Test
  fun `stale validation finish is ignored when a newer operation has started`() {
    val validationGateway = FakeValidationGateway(
      scriptedSummary = ValidationSummary(state = ValidationRunState.PASSED),
    )
    val viewModel = newViewModel(validationGateway = validationGateway)
    viewModel.selectRepoPath("/repo")
    val staleRequest = viewModel.beginValidate()
    val staleResult = viewModel.runValidate(staleRequest)
    // A newer operation starts (e.g. refresh)
    viewModel.beginRefresh()
    val state = viewModel.finishValidate(staleResult)

    // Refresh operation is still in flight; validation should not have overwritten its busy state.
    assertEquals(SkillBillBusyOperation.REFRESH, state.busyOperation)
    // F-101: the stale finish must unwind the RUNNING validation marker; otherwise the validation
    // slice would stay stuck on RUNNING after the unrelated operation completes.
    assertEquals(ValidationRunState.UNAVAILABLE, state.validation.state)
  }

  @Test
  fun `status bar reflects every validation run state`() {
    val validationGateway = FakeValidationGateway()
    val viewModel = newViewModel(validationGateway = validationGateway)
    viewModel.selectRepoPath("/repo")

    val unavailable = viewModel.state()
    assertEquals(ValidationRunState.UNAVAILABLE, unavailable.validation.state)

    val firstRequest = viewModel.beginValidate()
    val running = viewModel.state()
    assertEquals(ValidationRunState.RUNNING, running.validation.state)

    validationGateway.scriptedSummary = ValidationSummary(state = ValidationRunState.PASSED)
    val passed = viewModel.finishValidate(viewModel.runValidate(firstRequest))
    assertEquals(ValidationRunState.PASSED, passed.validation.state)

    val secondRequest = viewModel.beginValidate()
    validationGateway.scriptedSummary = ValidationSummary(
      state = ValidationRunState.FAILED,
      issues = listOf(
        ValidationIssue(
          severity = ValidationSeverity.ERROR,
          code = null,
          message = "x",
          sourcePath = null,
        ),
      ),
    )
    val failed = viewModel.finishValidate(viewModel.runValidate(secondRequest))
    assertEquals(ValidationRunState.FAILED, failed.validation.state)
  }

  @Test
  fun `inspector and bottom dock share the same validation issues list`() {
    val issues = listOf(
      ValidationIssue(
        severity = ValidationSeverity.ERROR,
        code = "E1",
        message = "missing",
        sourcePath = "skills/a.md",
      ),
      ValidationIssue(
        severity = ValidationSeverity.WARNING,
        code = "W1",
        message = "old",
        sourcePath = "skills/b.md",
      ),
    )
    val validationGateway = FakeValidationGateway(
      scriptedSummary = ValidationSummary(state = ValidationRunState.FAILED, issues = issues),
    )
    val viewModel = newViewModel(validationGateway = validationGateway)
    viewModel.selectRepoPath("/repo")
    val request = viewModel.beginValidate()
    val state = viewModel.finishValidate(viewModel.runValidate(request))

    // The state exposes a single validation slice that drives both surfaces.
    assertEquals(issues, state.validation.issues)
  }

  @Test
  fun `revealValidationIssue selects resolved id and expands ancestor`() {
    val validationGateway = FakeValidationGateway(
      resolveBySourcePath = mapOf("skills/bill-alpha/content.md" to "skill-one"),
    )
    val viewModel = newViewModel(validationGateway = validationGateway)
    viewModel.selectRepoPath("/repo")
    val issue = ValidationIssue(
      severity = ValidationSeverity.ERROR,
      code = null,
      message = "boom",
      sourcePath = "skills/bill-alpha/content.md",
    )

    val state = viewModel.revealValidationIssue(issue)

    assertEquals("skill-one", state.selectedTreeItemId)
    assertTrue("skills" in state.expandedNodeIds)
  }

  @Test
  fun `stale finishValidate after a refresh restores the pre-RUNNING validation summary`() {
    val passedSummary = ValidationSummary(state = ValidationRunState.PASSED)
    val validationGateway = FakeValidationGateway(scriptedSummary = passedSummary)
    val viewModel = newViewModel(validationGateway = validationGateway)
    viewModel.selectRepoPath("/repo")

    // Seed the slice with a known PASSED state.
    val firstRequest = viewModel.beginValidate()
    val sealed = viewModel.finishValidate(viewModel.runValidate(firstRequest))
    assertEquals(ValidationRunState.PASSED, sealed.validation.state)

    // Begin a second validation but preempt it with a refresh before finish lands.
    val staleRequest = viewModel.beginValidate()
    assertEquals(ValidationRunState.RUNNING, viewModel.state().validation.state)
    viewModel.beginRefresh()
    val staleResult = viewModel.runValidate(staleRequest)
    val afterStale = viewModel.finishValidate(staleResult)

    // F-101: the stale finish must unwind RUNNING back to the previous-summary captured at
    // beginValidate time (PASSED in this scenario), not leave validation stuck on RUNNING.
    assertEquals(ValidationRunState.PASSED, afterStale.validation.state)
    // Refresh is still the in-flight op.
    assertEquals(SkillBillBusyOperation.REFRESH, afterStale.busyOperation)
  }

  @Test
  fun `successful refresh on same repo resets validation to UNAVAILABLE`() {
    val passedSummary = ValidationSummary(state = ValidationRunState.PASSED)
    val validationGateway = FakeValidationGateway(scriptedSummary = passedSummary)
    val viewModel = newViewModel(validationGateway = validationGateway)
    viewModel.selectRepoPath("/repo")

    val seeded = viewModel.finishValidate(viewModel.runValidate(viewModel.beginValidate()))
    assertEquals(ValidationRunState.PASSED, seeded.validation.state)

    val refreshed = viewModel.refresh()

    // F-103: a refresh re-reads on-disk state; the prior validation result is now stale and must
    // be cleared even when the repo path is unchanged.
    assertEquals(ValidationRunState.UNAVAILABLE, refreshed.validation.state)
  }

  @Test
  fun `revealValidationIssue with unresolvable path leaves selection unchanged`() {
    val validationGateway = FakeValidationGateway()
    val viewModel = newViewModel(validationGateway = validationGateway)
    viewModel.selectRepoPath("/repo")
    val before = viewModel.selectTreeItem("skill-one")
    val issue = ValidationIssue(
      severity = ValidationSeverity.ERROR,
      code = null,
      message = "boom",
      sourcePath = "nope.md",
    )

    val state = viewModel.revealValidationIssue(issue)

    assertEquals(before.selectedTreeItemId, state.selectedTreeItemId)
  }

  private fun newViewModel(
    repoSessionService: RepoSessionService = FakeRepoSessionService(),
    skillTreeService: SkillTreeService = FakeSkillTreeService(defaultSkillTree()),
    authoringGateway: AuthoringGateway = FakeAuthoringGateway(),
    gitGateway: GitGateway = FakeGitGateway(),
    validationGateway: ValidationGateway = FakeValidationGateway(),
    recentRepoRepository: FakeRecentRepoRepository = FakeRecentRepoRepository(),
  ): SkillBillViewModel = SkillBillViewModel(
    repoSessionService = repoSessionService,
    skillTreeService = skillTreeService,
    authoringGateway = authoringGateway,
    gitGateway = gitGateway,
    validationGateway = validationGateway,
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
