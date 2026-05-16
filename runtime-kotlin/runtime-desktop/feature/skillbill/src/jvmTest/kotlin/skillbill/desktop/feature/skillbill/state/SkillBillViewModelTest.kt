package skillbill.desktop.feature.skillbill.state

import androidx.compose.ui.input.key.Key
import skillbill.desktop.core.domain.model.AuthoredContentDocument
import skillbill.desktop.core.domain.model.CommandPaletteAction
import skillbill.desktop.core.domain.model.CommandPaletteResult
import skillbill.desktop.core.domain.model.CommandPaletteResultKind
import skillbill.desktop.core.domain.model.CommitEntry
import skillbill.desktop.core.domain.model.DirtyEditorPromptReason
import skillbill.desktop.core.domain.model.DockTab
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.GeneratedArtifactDetail
import skillbill.desktop.core.domain.model.RenderBlock
import skillbill.desktop.core.domain.model.RenderRunState
import skillbill.desktop.core.domain.model.RenderSummary
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
import skillbill.desktop.core.domain.service.RenderGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
import skillbill.desktop.core.domain.service.ValidationGateway
import skillbill.desktop.core.testing.FakeAuthoringGateway
import skillbill.desktop.core.testing.FakeGitGateway
import skillbill.desktop.core.testing.FakePrPublishingGateway
import skillbill.desktop.core.testing.FakeRecentRepoRepository
import skillbill.desktop.core.testing.FakeRenderGateway
import skillbill.desktop.core.testing.FakeRepoSessionService
import skillbill.desktop.core.testing.FakeSkillTreeService
import skillbill.desktop.core.testing.FakeValidationGateway
import skillbill.desktop.feature.skillbill.ui.CommandPaletteActions
import skillbill.desktop.feature.skillbill.ui.executeCommandPaletteResult
import skillbill.desktop.feature.skillbill.ui.executeGeneratedArtifactSelection
import skillbill.desktop.feature.skillbill.ui.generatedArtifactRowActivatesForKey
import skillbill.desktop.feature.skillbill.ui.generatedArtifactRowContentDescription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
  fun `selecting governed skill loads full authored content and editing marks dirty`() {
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-one", "line 1\nline 2\n")
    }
    val viewModel = newViewModel(authoringGateway = authoringGateway)
    viewModel.selectRepoPath("/repo")

    val loaded = viewModel.selectTreeItem("skill-one")
    val edited = viewModel.updateEditorDraft("line 1\nchanged\n")

    assertEquals("line 1\nline 2\n", loaded.editor.draftContent)
    assertEquals(SkillBillStatusBar.EDITABLE_MODE_LABEL, loaded.statusBar.readOnlyModeLabel)
    assertTrue(edited.editor.dirty)
    assertEquals("dirty", edited.statusBar.readOnlyModeLabel)
  }

  @Test
  fun `save writes through authoring gateway and successful save clears dirty draft`() {
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-one", "before\n")
    }
    val viewModel = newViewModel(authoringGateway = authoringGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    viewModel.updateEditorDraft("after\n")

    val request = viewModel.beginSaveEditor()
    val saved = viewModel.finishSaveEditor(viewModel.runSaveEditor(assertNotNull(request)))

    assertEquals(1, authoringGateway.saveCallCount)
    assertEquals("skill-one", authoringGateway.lastSavedTreeItemId)
    assertEquals("after\n", authoringGateway.lastSavedBody)
    assertFalse(saved.editor.dirty)
    assertEquals("after\n", saved.editor.draftContent)
  }

  @Test
  fun `revert reloads latest saved authored text`() {
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-one", "saved\n")
    }
    val viewModel = newViewModel(authoringGateway = authoringGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    viewModel.updateEditorDraft("draft\n")
    authoringGateway.putDocument("skill-one", "latest saved\n")

    val reverted = viewModel.revertEditorDraft()

    assertFalse(reverted.editor.dirty)
    assertEquals("latest saved\n", reverted.editor.draftContent)
  }

  @Test
  fun `save failure preserves draft and surfaces runtime message`() {
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-one", "before\n")
      saveFailureMessage = "validator rejected content"
    }
    val viewModel = newViewModel(authoringGateway = authoringGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    viewModel.updateEditorDraft("broken draft\n")

    val request = viewModel.beginSaveEditor()
    val failed = viewModel.finishSaveEditor(viewModel.runSaveEditor(assertNotNull(request)))

    assertTrue(failed.editor.dirty)
    assertEquals("broken draft\n", failed.editor.draftContent)
    assertEquals("validator rejected content", failed.editor.saveErrorMessage)
  }

  @Test
  fun `dirty selection change prompts for discard or cancel before mutating selection`() {
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-one", "one\n")
      putDocument("skill-two", "two\n")
    }
    val viewModel = newViewModel(
      skillTreeService = FakeSkillTreeService(skillTree("skill-one", "skill-two")),
      authoringGateway = authoringGateway,
    )
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    viewModel.updateEditorDraft("dirty\n")

    val prompted = viewModel.selectTreeItem("skill-two")
    val canceled = viewModel.cancelDirtyEditorPrompt()
    val promptedAgain = viewModel.selectTreeItem("skill-two")
    val discarded = viewModel.discardDirtyEditorPrompt()

    assertEquals("skill-one", prompted.selectedTreeItemId)
    assertEquals(DirtyEditorPromptReason.SELECTION_CHANGE, prompted.dirtyEditorPrompt?.reason)
    assertEquals("skill-one", canceled.selectedTreeItemId)
    assertNull(canceled.dirtyEditorPrompt)
    assertEquals(DirtyEditorPromptReason.SELECTION_CHANGE, promptedAgain.dirtyEditorPrompt?.reason)
    assertEquals("skill-two", discarded.selectedTreeItemId)
    assertFalse(discarded.editor.dirty)
  }

  @Test
  fun `dirty refresh prompts before reloading tree data`() {
    val repoSessionService = CountingRepoSessionService()
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-one", "one\n")
    }
    val viewModel = newViewModel(repoSessionService = repoSessionService, authoringGateway = authoringGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    viewModel.updateEditorDraft("dirty\n")

    val prompted = viewModel.beginRefresh()
    val discarded = viewModel.discardDirtyEditorPrompt()

    assertEquals(listOf("/repo"), repoSessionService.openedRepoPaths)
    assertEquals(DirtyEditorPromptReason.REFRESH, prompted.dirtyEditorPrompt?.reason)
    assertEquals(SkillBillBusyOperation.REFRESH, discarded.busyOperation)
  }

  @Test
  fun `draft changes are ignored while repo refresh is in flight`() {
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-one", "saved\n")
    }
    val viewModel = newViewModel(authoringGateway = authoringGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")

    val refreshState = viewModel.beginRefresh()
    val request = viewModel.repoLoadRequest(repoPath = "/repo", preserveSelection = true)
    val editedDuringRefresh = viewModel.updateEditorDraft("draft after refresh started\n")
    authoringGateway.putDocument("skill-one", "reloaded\n")
    val refreshed = viewModel.finishRepoLoad(viewModel.loadRepo(request))

    assertEquals(SkillBillBusyOperation.REFRESH, refreshState.busyOperation)
    assertFalse(editedDuringRefresh.editor.dirty)
    assertEquals("saved\n", editedDuringRefresh.editor.draftContent)
    assertFalse(refreshed.editor.dirty)
    assertEquals("reloaded\n", refreshed.editor.draftContent)
  }

  @Test
  fun `same repo refresh preserves active dock tab`() {
    val viewModel = newViewModel()
    viewModel.selectRepoPath("/repo")
    viewModel.setActiveDockTab(DockTab.Changes)

    viewModel.beginRefresh()
    val request = viewModel.repoLoadRequest(repoPath = "/repo", preserveSelection = true)
    val refreshed = viewModel.finishRepoLoad(viewModel.loadRepo(request))

    assertEquals(DockTab.Changes, refreshed.activeDockTab)
  }

  @Test
  fun `dirty repo switch prompts before opening target repo`() {
    val repoSessionService = CountingRepoSessionService()
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-one", "saved\n")
    }
    val viewModel = newViewModel(repoSessionService = repoSessionService, authoringGateway = authoringGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    viewModel.updateEditorDraft("dirty\n")

    val prompted = viewModel.beginSelectRepoPath("/other")
    val discarded = viewModel.discardDirtyEditorPrompt()

    assertEquals(listOf("/repo"), repoSessionService.openedRepoPaths)
    assertEquals("skill-one", prompted.selectedTreeItemId)
    assertEquals(DirtyEditorPromptReason.REPO_SWITCH, prompted.dirtyEditorPrompt?.reason)
    assertEquals("/other", prompted.dirtyEditorPrompt?.targetRepoPath)
    assertEquals(SkillBillBusyOperation.OPEN_REPO, discarded.busyOperation)
    assertEquals("/other", discarded.repoPathText)
    assertFalse(discarded.editor.dirty)
  }

  @Test
  fun `dirty choose directory prompts before opening chooser`() {
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-one", "saved\n")
    }
    val viewModel = newViewModel(authoringGateway = authoringGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    viewModel.updateEditorDraft("dirty\n")

    val prompted = viewModel.beginChooseRepoDirectory()
    val discarded = viewModel.discardDirtyEditorPrompt()

    assertEquals(DirtyEditorPromptReason.CHOOSE_DIRECTORY, prompted.dirtyEditorPrompt?.reason)
    assertEquals(SkillBillBusyOperation.CHOOSE_DIRECTORY, discarded.busyOperation)
    assertFalse(discarded.editor.dirty)
  }

  @Test
  fun `successful editor save can be followed by git refresh`() {
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-one", "before\n")
    }
    val gitGateway = FakeGitGateway()
    val viewModel = newViewModel(authoringGateway = authoringGateway, gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    viewModel.updateEditorDraft("after\n")

    val saveRequest = viewModel.beginSaveEditor()
    val saved = viewModel.finishSaveEditor(viewModel.runSaveEditor(assertNotNull(saveRequest)))
    val refreshRequest = viewModel.beginGitRefresh()
    viewModel.finishGitRefresh(viewModel.runGitRefresh(refreshRequest))

    assertFalse(saved.editor.dirty)
    assertEquals(1, gitGateway.snapshotForCallCount)
  }

  @Test
  fun `draft changes are ignored while git refresh is in flight`() {
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-one", "saved\n")
    }
    val viewModel = newViewModel(authoringGateway = authoringGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")

    val refreshRequest = viewModel.beginGitRefresh()
    val editedDuringRefresh = viewModel.updateEditorDraft("draft during git refresh\n")
    val refreshed = viewModel.finishGitRefresh(viewModel.runGitRefresh(refreshRequest))

    assertTrue(editedDuringRefresh.changesBusy)
    assertFalse(editedDuringRefresh.editor.dirty)
    assertEquals("saved\n", editedDuringRefresh.editor.draftContent)
    assertFalse(refreshed.editor.dirty)
    assertEquals("saved\n", refreshed.editor.draftContent)
  }

  @Test
  fun `invalid repo selection surfaces error and is not remembered`() {
    val recentRepoRepository = FakeRecentRepoRepository()
    val viewModel = SkillBillViewModel(
      repoSessionService = InvalidRepoSessionService(),
      skillTreeService = FakeSkillTreeService(emptyList()),
      authoringGateway = FakeAuthoringGateway(),
      gitGateway = FakeGitGateway(),
      prPublishingGateway = FakePrPublishingGateway(),
      validationGateway = FakeValidationGateway(),
      renderGateway = FakeRenderGateway(),
      recentRepoRepository = recentRepoRepository,
      scaffoldGateway = skillbill.desktop.core.testing.scaffold.FakeScaffoldGateway(),
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
      prPublishingGateway = FakePrPublishingGateway(),
      validationGateway = FakeValidationGateway(),
      renderGateway = FakeRenderGateway(),
      recentRepoRepository = FakeRecentRepoRepository(),
      scaffoldGateway = skillbill.desktop.core.testing.scaffold.FakeScaffoldGateway(),
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
    viewModel.toggleExpanded(loaded.treeItems.single().id)
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

    assertEquals("skills", first.selectedTreeItemId)
  }

  @Test
  fun `tree nodes are collapsed by default and reveal children after user expansion`() {
    val viewModel = newViewModel(
      skillTreeService = MutableSkillTreeService(
        listOf(
          SkillBillTreeItem(
            id = "platform-pack-skills",
            label = "Platform Packs",
            kind = TreeItemKind.GROUP,
            children = listOf(
              SkillBillTreeItem(
                id = "platform:kotlin",
                label = "kotlin",
                kind = TreeItemKind.PLATFORM_PACK,
                children = listOf(
                  SkillBillTreeItem(
                    id = "skill:bill-kotlin-code-review",
                    label = "bill-kotlin-code-review",
                    kind = TreeItemKind.SKILL,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    )
    val loaded = viewModel.selectRepoPath("/repo")

    assertFalse("platform-pack-skills" in loaded.expandedNodeIds)
    assertFalse("platform:kotlin" in loaded.expandedNodeIds)
    assertEquals("platform-pack-skills", viewModel.moveSelection(1).selectedTreeItemId)
    viewModel.toggleExpanded("platform-pack-skills")
    assertEquals("platform:kotlin", viewModel.moveSelection(1).selectedTreeItemId)
    viewModel.toggleExpanded("platform:kotlin")
    assertEquals("skill:bill-kotlin-code-review", viewModel.moveSelection(1).selectedTreeItemId)
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

    assertEquals(listOf<String?>("group-a", "group-b", "b-one", "b-two", "b-two"), visited)
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
  fun `selected validation validates only the selected skill`() {
    val selectedSummary = ValidationSummary(
      state = ValidationRunState.FAILED,
      issues = listOf(
        ValidationIssue(
          severity = ValidationSeverity.ERROR,
          code = "BILL001",
          message = "selected issue",
          sourcePath = "skills/skill-one/content.md",
        ),
      ),
    )
    val validationGateway = FakeValidationGateway(
      scriptedSummary = ValidationSummary(state = ValidationRunState.PASSED),
      scriptedSummariesByTreeItemId = mapOf("skill-one" to selectedSummary),
    )
    val viewModel = newViewModel(
      validationGateway = validationGateway,
      skillTreeService = MutableSkillTreeService(skillTree("skill-one", "skill-two")),
    )
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")

    val request = viewModel.beginValidateSelected()
    val running = viewModel.state()
    val finished = viewModel.finishValidate(viewModel.runValidate(request))

    assertEquals(ValidationRunState.RUNNING, running.validation.state)
    assertEquals(DockTab.Validation, running.activeDockTab)
    assertEquals(ValidationRunState.FAILED, finished.validation.state)
    assertEquals("selected issue", finished.validation.issues.single().message)
    assertEquals(0, validationGateway.validateCallCount)
    assertEquals(1, validationGateway.validateSelectedCallCount)
    assertEquals(listOf("skill-one"), validationGateway.requestedValidationTreeItemIds)
  }

  @Test
  fun `selected validation with no selection stays unavailable and does not run repo validation`() {
    val validationGateway =
      FakeValidationGateway(scriptedSummary = ValidationSummary(state = ValidationRunState.PASSED))
    val viewModel = newViewModel(validationGateway = validationGateway)
    viewModel.selectRepoPath("/repo")

    val finished = viewModel.finishValidate(viewModel.runValidate(viewModel.beginValidateSelected()))

    assertEquals(ValidationRunState.UNAVAILABLE, finished.validation.state)
    assertEquals(0, validationGateway.validateCallCount)
    assertEquals(0, validationGateway.validateSelectedCallCount)
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
  fun `renderable is false for groups and generated artifacts but true for governed kinds`() {
    val viewModel = newViewModel(
      authoringGateway = KindKeyedAuthoringGateway(
        mapOf(
          "skill-one" to EditorPlaceholder(title = "skill-one", detail = "", kind = "horizontal skill"),
          "skill-two" to EditorPlaceholder(title = "skill-two", detail = "", kind = "platform pack skill"),
          "addon" to EditorPlaceholder(title = "addon", detail = "", kind = "add-on"),
          "agent" to EditorPlaceholder(title = "agent", detail = "", kind = "native agent"),
          "generated" to EditorPlaceholder(title = "generated", detail = "", kind = "generated artifact"),
          "skills" to EditorPlaceholder(title = "skills", detail = "", kind = null),
        ),
      ),
      skillTreeService = MutableSkillTreeService(
        listOf(
          SkillBillTreeItem(
            id = "skills",
            label = "Skills",
            kind = TreeItemKind.GROUP,
            children = listOf(
              SkillBillTreeItem(id = "skill-one", label = "skill-one", kind = TreeItemKind.SKILL),
              SkillBillTreeItem(id = "skill-two", label = "skill-two", kind = TreeItemKind.SKILL),
              SkillBillTreeItem(id = "addon", label = "addon", kind = TreeItemKind.ADD_ON),
              SkillBillTreeItem(id = "agent", label = "agent", kind = TreeItemKind.NATIVE_AGENT),
              SkillBillTreeItem(
                id = "generated",
                label = "generated",
                kind = TreeItemKind.GENERATED_ARTIFACT,
              ),
            ),
          ),
        ),
      ),
    )
    viewModel.selectRepoPath("/repo")

    assertTrue(viewModel.selectTreeItem("skill-one").renderable)
    assertTrue(viewModel.selectTreeItem("skill-two").renderable)
    assertTrue(viewModel.selectTreeItem("addon").renderable)
    assertTrue(viewModel.selectTreeItem("agent").renderable)
    assertFalse(viewModel.selectTreeItem("generated").renderable)
    assertFalse(viewModel.selectTreeItem("skills").renderable)
  }

  @Test
  fun `beginRender flips state to RUNNING and activates Console dock tab`() {
    val renderGateway = FakeRenderGateway()
    val viewModel = newViewModel(
      authoringGateway = SkillKindAuthoringGateway,
      renderGateway = renderGateway,
    )
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")

    viewModel.beginRender()
    val running = viewModel.state()

    assertEquals(RenderRunState.RUNNING, running.render.state)
    assertEquals(DockTab.Console, running.activeDockTab)
    assertEquals(SkillBillBusyOperation.RENDER, running.busyOperation)
  }

  @Test
  fun `successful render transitions running to passed and records blocks and artifacts`() {
    val summary = RenderSummary(
      state = RenderRunState.PASSED,
      blocks = listOf(RenderBlock(header = "===== SKILL.md: skills/x/SKILL.md =====", content = "# x\n")),
      generatedArtifacts = listOf(GeneratedArtifactDetail("skills/x/SKILL.md", "Generated runtime wrapper")),
      durationMillis = 12L,
    )
    val renderGateway = FakeRenderGateway(scriptedSummary = summary)
    val viewModel = newViewModel(
      authoringGateway = SkillKindAuthoringGateway,
      renderGateway = renderGateway,
    )
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    val request = viewModel.beginRender()
    val finished = viewModel.finishRender(viewModel.runRender(request))

    assertEquals(RenderRunState.PASSED, finished.render.state)
    assertEquals(1, finished.render.blocks.size)
    assertEquals(1, finished.render.generatedArtifacts.size)
    assertNull(finished.busyOperation)
    assertEquals(1, renderGateway.callCount)
    assertEquals("skill-one", renderGateway.lastRequestedTreeItemId)
  }

  @Test
  fun `render all renders every renderable tree item and aggregates output`() {
    val renderGateway = FakeRenderGateway(
      scriptedSummariesByTreeItemId = mapOf(
        "skill-one" to passedRenderSummary("skills/one/SKILL.md"),
        "skill-two" to passedRenderSummary("skills/two/SKILL.md"),
        "addon" to passedRenderSummary("platform-packs/kotlin/addons/addon.md"),
        "agent" to passedRenderSummary("skills/bill-alpha/native-agents/agent.md"),
      ),
    )
    val viewModel = newViewModel(
      renderGateway = renderGateway,
      skillTreeService = MutableSkillTreeService(
        listOf(
          SkillBillTreeItem(
            id = "skills",
            label = "Skills",
            kind = TreeItemKind.GROUP,
            children = listOf(
              SkillBillTreeItem(id = "skill-one", label = "Skill One", kind = TreeItemKind.SKILL),
              SkillBillTreeItem(id = "skill-two", label = "Skill Two", kind = TreeItemKind.PLATFORM_PACK),
              SkillBillTreeItem(id = "addon", label = "Addon", kind = TreeItemKind.ADD_ON),
              SkillBillTreeItem(id = "agent", label = "Agent", kind = TreeItemKind.NATIVE_AGENT),
              SkillBillTreeItem(id = "generated", label = "SKILL.md", kind = TreeItemKind.GENERATED_ARTIFACT),
            ),
          ),
        ),
      ),
    )
    viewModel.selectRepoPath("/repo")

    val request = viewModel.beginRenderAll()
    val finished = viewModel.finishRender(viewModel.runRender(request))

    assertEquals(RenderRunState.PASSED, finished.render.state)
    assertEquals(listOf("skill-one", "skill-two", "addon", "agent"), renderGateway.requestedTreeItemIds)
    assertEquals(4, finished.render.generatedArtifacts.size)
    assertTrue(finished.render.blocks.any { it.header == "===== render target: Skill One (skill-one) =====" })
    assertFalse(renderGateway.requestedTreeItemIds.contains("generated"))
  }

  @Test
  fun `render all returns failed when any target fails but still renders remaining targets`() {
    val renderGateway = FakeRenderGateway(
      scriptedSummariesByTreeItemId = mapOf(
        "skill-one" to passedRenderSummary("skills/one/SKILL.md"),
        "skill-two" to RenderSummary(
          state = RenderRunState.FAILED,
          runtimeExceptionName = "IllegalStateException",
          runtimeExceptionMessage = "boom",
        ),
      ),
    )
    val viewModel = newViewModel(
      renderGateway = renderGateway,
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

    val finished = viewModel.finishRender(viewModel.runRender(viewModel.beginRenderAll()))

    assertEquals(RenderRunState.FAILED, finished.render.state)
    assertEquals("RenderAllFailed", finished.render.runtimeExceptionName)
    assertEquals("1 render target(s) failed.", finished.render.runtimeExceptionMessage)
    assertEquals(listOf("skill-one", "skill-two"), renderGateway.requestedTreeItemIds)
    assertTrue(finished.render.blocks.any { it.content.contains("exception: IllegalStateException: boom") })
  }

  @Test
  fun `failed render surfaces runtime exception name and message in the summary`() {
    val failedSummary = RenderSummary(
      state = RenderRunState.FAILED,
      blocks = emptyList(),
      generatedArtifacts = emptyList(),
      durationMillis = 7L,
      runtimeExceptionName = "IllegalStateException",
      runtimeExceptionMessage = "boom",
    )
    val renderGateway = FakeRenderGateway(scriptedSummary = failedSummary)
    val viewModel = newViewModel(
      authoringGateway = SkillKindAuthoringGateway,
      renderGateway = renderGateway,
    )
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    val request = viewModel.beginRender()
    val finished = viewModel.finishRender(viewModel.runRender(request))

    assertEquals(RenderRunState.FAILED, finished.render.state)
    assertEquals("IllegalStateException", finished.render.runtimeExceptionName)
    assertEquals("boom", finished.render.runtimeExceptionMessage)
  }

  @Test
  fun `stale finishRender after refresh unwinds RUNNING and restores previousSummary`() {
    val passedSummary = RenderSummary(
      state = RenderRunState.PASSED,
      blocks = emptyList(),
      durationMillis = 1L,
    )
    val renderGateway = FakeRenderGateway(scriptedSummary = passedSummary)
    val viewModel = newViewModel(
      authoringGateway = SkillKindAuthoringGateway,
      renderGateway = renderGateway,
    )
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    // Seed with a known PASSED render.
    val sealed = viewModel.finishRender(viewModel.runRender(viewModel.beginRender()))
    assertEquals(RenderRunState.PASSED, sealed.render.state)

    // Begin a second render but preempt it with a refresh before finish lands.
    val staleRequest = viewModel.beginRender()
    assertEquals(RenderRunState.RUNNING, viewModel.state().render.state)
    viewModel.beginRefresh()
    val staleResult = viewModel.runRender(staleRequest)
    val afterStale = viewModel.finishRender(staleResult)

    // F-101: stale finish must unwind RUNNING back to the previous-summary captured at beginRender
    // time (PASSED in this scenario). Refresh has not yet completed, so refresh's reset-to-UNAVAILABLE
    // has not landed; the stale finish must restore PASSED rather than leave the slice on RUNNING.
    assertEquals(RenderRunState.PASSED, afterStale.render.state)
    assertEquals(SkillBillBusyOperation.REFRESH, afterStale.busyOperation)
  }

  @Test
  fun `refresh resets render summary to UNAVAILABLE even with prior PASSED state`() {
    val renderGateway = FakeRenderGateway(
      scriptedSummary = RenderSummary(state = RenderRunState.PASSED, durationMillis = 5L),
    )
    val viewModel = newViewModel(
      authoringGateway = SkillKindAuthoringGateway,
      renderGateway = renderGateway,
    )
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    val sealed = viewModel.finishRender(viewModel.runRender(viewModel.beginRender()))
    assertEquals(RenderRunState.PASSED, sealed.render.state)

    val refreshed = viewModel.refresh()

    assertEquals(RenderRunState.UNAVAILABLE, refreshed.render.state)
  }

  @Test
  fun `selectTreeItem to a different node resets render slice to UNAVAILABLE`() {
    // F-202: render output is keyed by tree-item id; switching selection must not leave the prior
    // PASSED summary attached.
    val passedSummary = RenderSummary(
      state = RenderRunState.PASSED,
      blocks = listOf(RenderBlock(header = "===== SKILL.md: skills/x/SKILL.md =====", content = "# x\n")),
      generatedArtifacts = listOf(GeneratedArtifactDetail("skills/x/SKILL.md", "Generated runtime wrapper")),
      durationMillis = 9L,
    )
    val renderGateway = FakeRenderGateway(scriptedSummary = passedSummary)
    val viewModel = newViewModel(
      authoringGateway = SkillKindAuthoringGateway,
      renderGateway = renderGateway,
      skillTreeService = MutableSkillTreeService(
        listOf(
          SkillBillTreeItem(
            id = "skills",
            label = "Skills",
            kind = TreeItemKind.GROUP,
            children = listOf(
              SkillBillTreeItem(id = "skill-one", label = "skill-one", kind = TreeItemKind.SKILL),
              SkillBillTreeItem(id = "skill-two", label = "skill-two", kind = TreeItemKind.SKILL),
            ),
          ),
        ),
      ),
    )
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    val sealed = viewModel.finishRender(viewModel.runRender(viewModel.beginRender()))
    assertEquals(RenderRunState.PASSED, sealed.render.state)

    val switched = viewModel.selectTreeItem("skill-two")

    assertEquals(RenderRunState.UNAVAILABLE, switched.render.state)
    assertTrue(switched.render.blocks.isEmpty())
    assertTrue(switched.render.generatedArtifacts.isEmpty())
  }

  @Test
  fun `moveSelection to a different node resets render slice to UNAVAILABLE`() {
    // F-202: keyboard-driven selection change must also reset the render slice.
    val passedSummary = RenderSummary(
      state = RenderRunState.PASSED,
      blocks = listOf(RenderBlock(header = "===== SKILL.md: skills/x/SKILL.md =====", content = "# x\n")),
      generatedArtifacts = listOf(GeneratedArtifactDetail("skills/x/SKILL.md", "Generated runtime wrapper")),
      durationMillis = 9L,
    )
    val renderGateway = FakeRenderGateway(scriptedSummary = passedSummary)
    val viewModel = newViewModel(
      authoringGateway = SkillKindAuthoringGateway,
      renderGateway = renderGateway,
      skillTreeService = MutableSkillTreeService(
        listOf(
          SkillBillTreeItem(
            id = "skills",
            label = "Skills",
            kind = TreeItemKind.GROUP,
            children = listOf(
              SkillBillTreeItem(id = "skill-one", label = "skill-one", kind = TreeItemKind.SKILL),
              SkillBillTreeItem(id = "skill-two", label = "skill-two", kind = TreeItemKind.SKILL),
            ),
          ),
        ),
      ),
    )
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    val sealed = viewModel.finishRender(viewModel.runRender(viewModel.beginRender()))
    assertEquals(RenderRunState.PASSED, sealed.render.state)

    val moved = viewModel.moveSelection(1)

    assertEquals("skill-two", moved.selectedTreeItemId)
    assertEquals(RenderRunState.UNAVAILABLE, moved.render.state)
    assertTrue(moved.render.blocks.isEmpty())
    assertTrue(moved.render.generatedArtifacts.isEmpty())
  }

  @Test
  fun `setActiveDockTab updates state and is observable through state()`() {
    val viewModel = newViewModel()
    viewModel.selectRepoPath("/repo")

    val updated = viewModel.setActiveDockTab(DockTab.Console)

    assertEquals(DockTab.Console, updated.activeDockTab)
  }

  @Test
  fun `runRender returns unavailable when no tree item is selected`() {
    val renderGateway = FakeRenderGateway(
      scriptedSummary = RenderSummary(state = RenderRunState.PASSED, durationMillis = 1L),
    )
    val viewModel = newViewModel(renderGateway = renderGateway)
    viewModel.selectRepoPath("/repo")
    val request = viewModel.beginRender()
    val finished = viewModel.finishRender(viewModel.runRender(request))

    assertEquals(RenderRunState.UNAVAILABLE, finished.render.state)
    // Gateway is not consulted when there is no selection — selection is a precondition for renderable.
    assertEquals(0, renderGateway.callCount)
    assertNotNull(request)
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

  @Test
  fun `opening command palette exposes query state and ranked results`() {
    val viewModel = newViewModel()
    viewModel.selectRepoPath("/repo")

    val state = viewModel.openCommandPalette()

    assertTrue(state.commandPalette.open)
    assertEquals("", state.commandPalette.query)
    assertEquals(0, state.commandPalette.selectedResultIndex)
    assertTrue(state.commandPalette.results.isNotEmpty())
    assertEquals("command.refresh", state.commandPalette.results.first().id)
  }

  @Test
  fun `palette tree results preserve exact tree item ids`() {
    val viewModel = newViewModel(
      skillTreeService = MutableSkillTreeService(
        listOf(
          SkillBillTreeItem(
            id = "skills",
            label = "Skills",
            kind = TreeItemKind.GROUP,
            children = listOf(
              SkillBillTreeItem(
                id = "skill-alpha",
                label = "bill-alpha",
                kind = TreeItemKind.SKILL,
                authoredPath = "skills/bill-alpha/content.md",
              ),
            ),
          ),
        ),
      ),
    )
    viewModel.selectRepoPath("/repo")
    viewModel.openCommandPalette()

    val state = viewModel.updateCommandPaletteQuery("alpha")
    val result = state.commandPalette.results.first { it.action == CommandPaletteAction.SELECT_TREE_ITEM }

    assertEquals("skill-alpha", result.id)
    assertEquals("skill-alpha", result.treeItemId)
  }

  @Test
  fun `palette tree search matches item kind even when path is the subtitle`() {
    val viewModel = newViewModel(
      skillTreeService = MutableSkillTreeService(
        listOf(
          SkillBillTreeItem(
            id = "source-group",
            label = "Sources",
            kind = TreeItemKind.GROUP,
            children = listOf(
              SkillBillTreeItem(
                id = "native-agent-alpha",
                label = "Alpha worker",
                kind = TreeItemKind.NATIVE_AGENT,
                authoredPath = "skills/bill-alpha/native-agents/alpha.md",
              ),
            ),
          ),
        ),
      ),
    )
    viewModel.selectRepoPath("/repo")
    viewModel.openCommandPalette()

    val state = viewModel.updateCommandPaletteQuery("native")

    assertTrue(state.commandPalette.results.any { it.treeItemId == "native-agent-alpha" })
  }

  @Test
  fun `palette empty repo state only exposes repo session commands`() {
    val viewModel = newViewModel()

    val state = viewModel.openCommandPalette()

    assertEquals(listOf("command.open-repository"), state.commandPalette.results.map { it.id })
    assertTrue(state.commandPalette.results.single().enabled)
  }

  @Test
  fun `palette disabled commands explain missing prerequisites`() {
    val viewModel = newViewModel(repoSessionService = InvalidRepoSessionService())

    val state = viewModel.selectRepoPath("/not-skill-bill").let { viewModel.openCommandPalette() }
    val validate = state.commandPalette.results.first { it.id == "command.validate" }
    val validateSelected = state.commandPalette.results.first { it.id == "command.validate-selected" }
    val render = state.commandPalette.results.first { it.id == "command.render" }
    val renderAll = state.commandPalette.results.first { it.id == "command.render-all" }
    val showChanges = state.commandPalette.results.first { it.id == "command.show-changes" }
    val showHistory = state.commandPalette.results.first { it.id == "command.show-history" }

    assertFalse(validate.enabled)
    assertEquals("Open a valid Skill Bill repository first.", validate.disabledReason)
    assertFalse(validateSelected.enabled)
    assertEquals("Open a valid Skill Bill repository first.", validateSelected.disabledReason)
    assertFalse(render.enabled)
    assertEquals("Open a valid Skill Bill repository first.", render.disabledReason)
    assertFalse(renderAll.enabled)
    assertEquals("Open a valid Skill Bill repository first.", renderAll.disabledReason)
    assertFalse(showChanges.enabled)
    assertEquals("Open a valid Skill Bill repository first.", showChanges.disabledReason)
    assertFalse(showHistory.enabled)
    assertEquals("Open a valid Skill Bill repository first.", showHistory.disabledReason)
  }

  @Test
  fun `palette command execution dispatches visible commands through shared callbacks`() {
    val refresh = CommandPaletteResult(
      id = "command.refresh",
      title = "Refresh",
      subtitle = "",
      marker = "rf",
      kind = CommandPaletteResultKind.COMMAND,
      action = CommandPaletteAction.REFRESH,
    )
    val validate = refresh.copy(
      id = "command.validate",
      title = "Validate",
      marker = "ok",
      action = CommandPaletteAction.VALIDATE,
    )
    val validateSelected = refresh.copy(
      id = "command.validate-selected",
      title = "Validate selected",
      marker = "vs",
      action = CommandPaletteAction.VALIDATE_SELECTED,
    )
    val openRepository = refresh.copy(
      id = "command.open-repository",
      title = "Open repository",
      marker = "op",
      action = CommandPaletteAction.OPEN_REPOSITORY,
    )
    val showChanges = refresh.copy(
      id = "command.show-changes",
      title = "Show changes",
      marker = "chg",
      action = CommandPaletteAction.SHOW_CHANGES,
    )
    val showHistory = refresh.copy(
      id = "command.show-history",
      title = "Show history",
      marker = "hst",
      action = CommandPaletteAction.SHOW_HISTORY,
    )
    val render = refresh.copy(
      id = "command.render",
      title = "Render selected",
      marker = "rc",
      action = CommandPaletteAction.RENDER,
    )
    val renderAll = refresh.copy(
      id = "command.render-all",
      title = "Render all",
      marker = "ra",
      action = CommandPaletteAction.RENDER_ALL,
    )
    var openRepositoryCount = 0
    var refreshCount = 0
    var validateCount = 0
    var validateSelectedCount = 0
    var renderCount = 0
    var renderAllCount = 0
    var showChangesCount = 0
    var showHistoryCount = 0
    val actions = paletteActions(
      openRepository = { openRepositoryCount += 1 },
      refresh = { refreshCount += 1 },
      validate = { validateCount += 1 },
      validateSelected = { validateSelectedCount += 1 },
      render = { renderCount += 1 },
      renderAll = { renderAllCount += 1 },
      showChanges = { showChangesCount += 1 },
      showHistory = { showHistoryCount += 1 },
    )

    assertTrue(executeCommandPaletteResult(openRepository, actions))
    assertTrue(executeCommandPaletteResult(refresh, actions))
    assertTrue(executeCommandPaletteResult(validate, actions))
    assertTrue(executeCommandPaletteResult(validateSelected, actions))
    assertTrue(executeCommandPaletteResult(render, actions))
    assertTrue(executeCommandPaletteResult(renderAll, actions))
    assertTrue(executeCommandPaletteResult(showChanges, actions))
    assertTrue(executeCommandPaletteResult(showHistory, actions))

    assertEquals(1, openRepositoryCount)
    assertEquals(1, refreshCount)
    assertEquals(1, validateCount)
    assertEquals(1, validateSelectedCount)
    assertEquals(1, renderCount)
    assertEquals(1, renderAllCount)
    assertEquals(1, showChangesCount)
    assertEquals(1, showHistoryCount)
  }

  @Test
  fun `palette includes open repository and dock commands`() {
    val viewModel = newViewModel()
    viewModel.selectRepoPath("/repo")

    val resultIds = viewModel.openCommandPalette().commandPalette.results.map { it.id }

    assertTrue("command.open-repository" in resultIds)
    assertTrue("command.show-changes" in resultIds)
    assertTrue("command.show-history" in resultIds)
  }

  @Test
  fun `palette results rebuild after repo refresh validation save and git status mutations`() {
    val treeService = MutableSkillTreeService(skillTree("skill-one"))
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-one", "saved\n")
    }
    val validationGateway = FakeValidationGateway(
      scriptedSummary = ValidationSummary(state = ValidationRunState.PASSED),
    )
    val viewModel = newViewModel(
      skillTreeService = treeService,
      authoringGateway = authoringGateway,
      validationGateway = validationGateway,
    )

    viewModel.selectRepoPath("/repo")
    viewModel.openCommandPalette()
    treeService.items = skillTree("skill-one", "skill-two")
    val refreshed = viewModel.refresh()
    assertTrue(refreshed.commandPalette.results.any { it.treeItemId == "skill-two" })

    val runningValidation = viewModel.beginValidate()
    val validateDuringRun = viewModel.state().commandPalette.results.first { it.id == "command.validate" }
    assertEquals("Wait for validation to finish.", validateDuringRun.disabledReason)
    val afterValidation = viewModel.finishValidate(viewModel.runValidate(runningValidation))
    assertTrue(afterValidation.commandPalette.results.first { it.id == "command.validate" }.enabled)

    viewModel.selectTreeItem("skill-one")
    val dirty = viewModel.updateEditorDraft("changed\n")
    assertTrue(dirty.commandPalette.results.first { it.id == "command.save" }.enabled)
    val saveRequest = viewModel.beginSaveEditor()
    val saveDuringRun = viewModel.state().commandPalette.results.first { it.id == "command.save" }
    assertEquals("Wait for save to finish.", saveDuringRun.disabledReason)
    viewModel.finishSaveEditor(viewModel.runSaveEditor(assertNotNull(saveRequest)))

    viewModel.beginGitRefresh()
    val gitDuringRun = viewModel.state().commandPalette.results.first { it.id == "command.refresh-git" }
    assertEquals("Wait for Git status refresh to finish.", gitDuringRun.disabledReason)
  }

  @Test
  fun `palette results rebuild after scaffold refresh hook`() {
    val treeService = MutableSkillTreeService(skillTree("skill-one"))
    val viewModel = newViewModel(skillTreeService = treeService)
    viewModel.selectRepoPath("/repo")
    viewModel.openCommandPalette()

    treeService.items = skillTree("skill-one", "scaffolded-skill")
    val refreshed = viewModel.refreshAfterScaffold()

    assertTrue(refreshed.commandPalette.results.any { it.treeItemId == "scaffolded-skill" })
  }

  @Test
  fun `palette keyboard navigation selection can execute selected result`() {
    val viewModel = newViewModel()
    viewModel.selectRepoPath("/repo")
    viewModel.openCommandPalette()

    val moved = viewModel.moveCommandPaletteSelection(1)
    val selected = moved.commandPalette.results[moved.commandPalette.selectedResultIndex]
    var validateCount = 0

    assertEquals("command.validate", selected.id)
    assertTrue(
      executeCommandPaletteResult(
        selected,
        paletteActions(validate = { validateCount += 1 }),
      ),
    )
    assertEquals(1, validateCount)
  }

  @Test
  fun `generated artifact resolver exposes only existing tree item ids`() {
    val artifactPath = "skills/bill-alpha/SKILL.md"
    val artifactId = "generated-alpha"
    val treeService = MutableSkillTreeService(
      items = generatedArtifactTree(artifactId, artifactPath),
      generatedArtifactIdsByPath = mapOf(
        artifactPath to artifactId,
        "skills/missing/SKILL.md" to "missing-id",
      ),
    )
    val viewModel = newViewModel(skillTreeService = treeService)
    viewModel.selectRepoPath("/repo")

    assertEquals(artifactId, viewModel.resolveGeneratedArtifactTreeItemId(artifactPath))
    assertNull(viewModel.resolveGeneratedArtifactTreeItemId("skills/unknown/SKILL.md"))
    assertNull(viewModel.resolveGeneratedArtifactTreeItemId("skills/missing/SKILL.md"))
  }

  @Test
  fun `generated artifact selection no-ops when resolver cannot match a tree item`() {
    var selectedTreeItemId: String? = null

    val executed = executeGeneratedArtifactSelection(
      artifactPath = "skills/missing/SKILL.md",
      resolveTreeItemId = { null },
      selectTreeItem = { selectedTreeItemId = it },
    )

    assertFalse(executed)
    assertNull(selectedTreeItemId)
  }

  @Test
  fun `generated artifact selection uses tree selection fan-out for editor route and history filter`() {
    val artifactPath = "skills/bill-alpha/SKILL.md"
    val artifactId = "generated-alpha"
    val gitGateway = FakeGitGateway(
      scriptedCommits = listOf(
        CommitEntry(
          shortHash = "abc1234",
          fullHash = "abc123456789",
          author = "A",
          isoDate = "2025-04-30T14:22:00+00:00",
          subject = "Generate wrapper",
          changedPaths = listOf(artifactPath),
        ),
      ),
    )
    val authoringGateway = FakeAuthoringGateway().apply {
      documentsByTreeItemId[artifactId] = AuthoredContentDocument(
        treeItemId = artifactId,
        title = "SKILL.md",
        skillName = null,
        kind = "generated artifact",
        authoredPath = artifactPath,
        text = "# Generated\n",
        editable = false,
        readOnlyReason = "Generated runtime wrapper",
      )
    }
    val viewModel = newViewModel(
      skillTreeService = MutableSkillTreeService(
        items = generatedArtifactTree(artifactId, artifactPath),
        generatedArtifactIdsByPath = mapOf(artifactPath to artifactId),
      ),
      authoringGateway = authoringGateway,
      gitGateway = gitGateway,
    )
    viewModel.selectRepoPath("/repo")
    var sourceRouteSelection: String? = null

    val executed = executeGeneratedArtifactSelection(
      artifactPath = artifactPath,
      resolveTreeItemId = viewModel::resolveGeneratedArtifactTreeItemId,
      selectTreeItem = { itemId ->
        val previousSelection = viewModel.state().selectedTreeItemId
        val selected = viewModel.selectTreeItem(itemId)
        if (selected.selectedTreeItemId != previousSelection) {
          viewModel.setHistoryPathFilter(selected.editor.authoredPath)
          val request = viewModel.beginLoadHistory()
          viewModel.finishLoadHistory(viewModel.runLoadHistory(request))
          selected.selectedTreeItemId?.let { sourceRouteSelection = it }
        }
      },
    )
    val selected = viewModel.state()

    assertTrue(executed)
    assertEquals(artifactId, selected.selectedTreeItemId)
    assertTrue("generated-artifacts" in selected.expandedNodeIds)
    assertFalse(selected.editor.editable)
    assertEquals("generated artifact", selected.editor.kind)
    assertEquals(artifactPath, selected.historyPathFilter)
    assertEquals(artifactPath, gitGateway.lastRecentCommitsPathFilter)
    assertEquals(artifactId, sourceRouteSelection)
  }

  @Test
  fun `generated artifact selection prompts before leaving dirty editor`() {
    val artifactPath = "skills/bill-alpha/SKILL.md"
    val artifactId = "generated-alpha"
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-one", "original\n")
    }
    val viewModel = newViewModel(
      skillTreeService = MutableSkillTreeService(
        items = generatedArtifactTree(artifactId, artifactPath),
        generatedArtifactIdsByPath = mapOf(artifactPath to artifactId),
      ),
      authoringGateway = authoringGateway,
    )
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    viewModel.updateEditorDraft("changed\n")

    executeGeneratedArtifactSelection(
      artifactPath = artifactPath,
      resolveTreeItemId = viewModel::resolveGeneratedArtifactTreeItemId,
      selectTreeItem = viewModel::selectTreeItem,
    )
    val prompted = viewModel.state()

    assertEquals(DirtyEditorPromptReason.SELECTION_CHANGE, prompted.dirtyEditorPrompt?.reason)
    assertEquals(artifactId, prompted.dirtyEditorPrompt?.targetTreeItemId)
  }

  @Test
  fun `generated artifact row semantics announce artifact path`() {
    val artifact = GeneratedArtifactDetail("skills/bill-alpha/SKILL.md", "Generated runtime wrapper")

    assertEquals("Open artifact: skills/bill-alpha/SKILL.md", generatedArtifactRowContentDescription(artifact))
    assertTrue(generatedArtifactRowActivatesForKey(Key.Enter))
    assertTrue(generatedArtifactRowActivatesForKey(Key.NumPadEnter))
    assertTrue(generatedArtifactRowActivatesForKey(Key.Spacebar))
  }

  private fun newViewModel(
    repoSessionService: RepoSessionService = FakeRepoSessionService(),
    skillTreeService: SkillTreeService = FakeSkillTreeService(defaultSkillTree()),
    authoringGateway: AuthoringGateway = FakeAuthoringGateway(),
    gitGateway: GitGateway = FakeGitGateway(),
    prPublishingGateway: skillbill.desktop.core.domain.service.PrPublishingGateway = FakePrPublishingGateway(),
    validationGateway: ValidationGateway = FakeValidationGateway(),
    renderGateway: RenderGateway = FakeRenderGateway(),
    recentRepoRepository: FakeRecentRepoRepository = FakeRecentRepoRepository(),
    scaffoldGateway: skillbill.desktop.core.domain.service.RuntimeScaffoldGateway =
      skillbill.desktop.core.testing.scaffold.FakeScaffoldGateway(),
  ): SkillBillViewModel = SkillBillViewModel(
    repoSessionService = repoSessionService,
    skillTreeService = skillTreeService,
    authoringGateway = authoringGateway,
    gitGateway = gitGateway,
    prPublishingGateway = prPublishingGateway,
    validationGateway = validationGateway,
    renderGateway = renderGateway,
    recentRepoRepository = recentRepoRepository,
    scaffoldGateway = scaffoldGateway,
  )
}

private fun paletteActions(
  selectTreeItem: (String) -> Unit = {},
  openRepository: () -> Unit = {},
  refresh: () -> Unit = {},
  validate: () -> Unit = {},
  validateSelected: () -> Unit = {},
  render: () -> Unit = {},
  renderAll: () -> Unit = {},
  showChanges: () -> Unit = {},
  showHistory: () -> Unit = {},
  save: () -> Unit = {},
  refreshGitStatus: () -> Unit = {},
  openScaffoldWizard: (skillbill.desktop.core.domain.model.ScaffoldKind) -> Unit = {},
): CommandPaletteActions = CommandPaletteActions(
  selectTreeItem = selectTreeItem,
  openRepository = openRepository,
  refresh = refresh,
  validate = validate,
  validateSelected = validateSelected,
  render = render,
  renderAll = renderAll,
  showChanges = showChanges,
  showHistory = showHistory,
  save = save,
  refreshGitStatus = refreshGitStatus,
  openScaffoldWizard = openScaffoldWizard,
)

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

private class MutableSkillTreeService(
  var items: List<SkillBillTreeItem>,
  var generatedArtifactIdsByPath: Map<String, String> = emptyMap(),
) : SkillTreeService {
  override fun treeFor(session: RepoSession?): List<SkillBillTreeItem> = items

  @Suppress("UNUSED_PARAMETER")
  override fun resolveGeneratedArtifactTreeItemId(session: RepoSession?, artifactPath: String): String? =
    generatedArtifactIdsByPath[artifactPath]
}

private class StaticAuthoringGateway(private val editor: EditorPlaceholder) : AuthoringGateway {
  override fun describeSelection(treeItemId: String): EditorPlaceholder = editor
}

private class KindKeyedAuthoringGateway(
  private val byId: Map<String, EditorPlaceholder>,
) : AuthoringGateway {
  override fun describeSelection(treeItemId: String): EditorPlaceholder =
    byId[treeItemId] ?: EditorPlaceholder(title = treeItemId, detail = "")
}

private val SkillKindAuthoringGateway: AuthoringGateway = object : AuthoringGateway {
  override fun describeSelection(treeItemId: String): EditorPlaceholder = EditorPlaceholder(
    title = treeItemId,
    detail = "",
    skillName = treeItemId,
    kind = "horizontal skill",
  )
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

private fun passedRenderSummary(artifactPath: String): RenderSummary = RenderSummary(
  state = RenderRunState.PASSED,
  blocks = listOf(RenderBlock(header = "===== SKILL.md: $artifactPath =====", content = "# generated\n")),
  generatedArtifacts = listOf(GeneratedArtifactDetail(artifactPath, "Generated runtime wrapper")),
  durationMillis = 1L,
)

private fun generatedArtifactTree(artifactId: String, artifactPath: String): List<SkillBillTreeItem> = listOf(
  SkillBillTreeItem(
    id = "skills",
    label = "Skills",
    kind = TreeItemKind.GROUP,
    children = listOf(
      SkillBillTreeItem(id = "skill-one", label = "skill-one", kind = TreeItemKind.SKILL),
    ),
  ),
  SkillBillTreeItem(
    id = "generated-artifacts",
    label = "Generated Artifacts",
    kind = TreeItemKind.GROUP,
    children = listOf(
      SkillBillTreeItem(
        id = artifactId,
        label = artifactPath,
        kind = TreeItemKind.GENERATED_ARTIFACT,
        authoredPath = artifactPath,
        status = "read-only",
        editable = false,
        readOnlyLabel = "RO",
      ),
    ),
  ),
)
