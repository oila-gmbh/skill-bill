package skillbill.desktop.feature.skillbill.state

import androidx.compose.ui.input.key.Key
import skillbill.desktop.core.domain.model.CommandPaletteAction
import skillbill.desktop.core.domain.model.CommandPaletteResult
import skillbill.desktop.core.domain.model.CommandPaletteResultKind
import skillbill.desktop.core.domain.model.DirtyEditorPromptReason
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.GeneratedArtifactDetail
import skillbill.desktop.core.domain.model.InstalledWorkspaceAvailability
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillStatusBar
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
import skillbill.desktop.core.testing.FakeAuthoringGateway
import skillbill.desktop.core.testing.FakeRecentRepoRepository
import skillbill.desktop.core.testing.FakeRepoSessionService
import skillbill.desktop.core.testing.FakeSkillTreeService
import skillbill.desktop.core.testing.workspace.FakeInstalledWorkspaceLocator
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
  fun `selecting repo refreshes tree`() {
    val viewModel = newViewModel()

    val state = viewModel.selectRepoPath("/repo")

    assertEquals("/repo", state.selectedRepoPath)
    assertEquals("/repo", state.repoPathText)
    assertEquals("skill-one", state.treeItems.single().children.single().id)
    assertEquals(1, state.statusBar.targetCount)
    assertEquals("/repo", state.statusBar.repoPathLabel)
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
  fun `dirty refresh runs quietly and keeps draft while reloading tree data`() {
    val repoSessionService = CountingRepoSessionService()
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-one", "one\n")
    }
    val viewModel = newViewModel(repoSessionService = repoSessionService, authoringGateway = authoringGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.selectTreeItem("skill-one")
    viewModel.updateEditorDraft("dirty\n")

    val started = viewModel.beginRefresh()
    authoringGateway.putDocument("skill-one", "reloaded\n")
    val refreshed = viewModel.finishRefresh()

    assertEquals(listOf("/repo", "/repo"), repoSessionService.openedRepoPaths)
    assertNull(started.dirtyEditorPrompt)
    assertNull(started.busyOperation)
    assertTrue(refreshed.editor.dirty)
    assertEquals("dirty\n", refreshed.editor.draftContent)
  }

  @Test
  fun `draft changes are preserved while quiet repo refresh is in flight`() {
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
    val refreshed = viewModel.finishRefresh(viewModel.loadRepo(request))

    assertNull(refreshState.busyOperation)
    assertTrue(editedDuringRefresh.editor.dirty)
    assertEquals("draft after refresh started\n", editedDuringRefresh.editor.draftContent)
    assertTrue(refreshed.editor.dirty)
    assertEquals("draft after refresh started\n", refreshed.editor.draftContent)
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
  fun `invalid repo selection surfaces error and is not remembered`() {
    val recentRepoRepository = FakeRecentRepoRepository()
    val viewModel = SkillBillViewModel(
      repoSessionService = InvalidRepoSessionService(),
      skillTreeService = FakeSkillTreeService(emptyList()),
      authoringGateway = FakeAuthoringGateway(),
      recentRepoRepository = recentRepoRepository,
      scaffoldGateway = skillbill.desktop.core.testing.scaffold.FakeScaffoldGateway(),
      firstRunGateway = defaultFirstRunGateway(),
      desktopPreferenceStore = completedFirstRunStore(),
      skillRemoveGateway = skillbill.desktop.core.testing.skillremove.FakeSkillRemoveGateway(),
      installedWorkspaceLocator = FakeInstalledWorkspaceLocator(),
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
      recentRepoRepository = FakeRecentRepoRepository(),
      scaffoldGateway = skillbill.desktop.core.testing.scaffold.FakeScaffoldGateway(),
      firstRunGateway = defaultFirstRunGateway(),
      desktopPreferenceStore = completedFirstRunStore(),
      skillRemoveGateway = skillbill.desktop.core.testing.skillremove.FakeSkillRemoveGateway(),
      installedWorkspaceLocator = FakeInstalledWorkspaceLocator(),
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
  fun `begin and finish refresh quietly reload runtime data`() {
    val repoSessionService = CountingRepoSessionService()
    val viewModel = newViewModel(repoSessionService = repoSessionService)
    viewModel.selectRepoPath("/repo")
    repoSessionService.openedRepoPaths.clear()

    val busy = viewModel.beginRefresh()

    assertNull(busy.busyOperation)
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
    val installSetup = state.commandPalette.results.first { it.id == "command.install-setup" }

    assertFalse(installSetup.enabled)
    assertEquals("Open a valid Skill Bill repository first.", installSetup.disabledReason)
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
    val openRepository = refresh.copy(
      id = "command.open-repository",
      title = "Open repository",
      marker = "op",
      action = CommandPaletteAction.OPEN_REPOSITORY,
    )
    val installSetup = refresh.copy(
      id = "command.install-setup",
      title = "Install setup",
      marker = "in",
      action = CommandPaletteAction.INSTALL_SETUP,
    )
    var openRepositoryCount = 0
    var refreshCount = 0
    var installSetupCount = 0
    val actions = paletteActions(
      openRepository = { openRepositoryCount += 1 },
      refresh = { refreshCount += 1 },
      openInstallSetup = { installSetupCount += 1 },
    )

    assertTrue(executeCommandPaletteResult(openRepository, actions))
    assertTrue(executeCommandPaletteResult(refresh, actions))
    assertTrue(executeCommandPaletteResult(installSetup, actions))

    assertEquals(1, openRepositoryCount)
    assertEquals(1, refreshCount)
    assertEquals(1, installSetupCount)
  }

  @Test
  fun `palette includes open repository and dock commands`() {
    val viewModel = newViewModel()
    viewModel.selectRepoPath("/repo")

    val resultIds = viewModel.openCommandPalette().commandPalette.results.map { it.id }

    assertTrue("command.open-repository" in resultIds)
    assertTrue("command.install-setup" in resultIds)
  }

  @Test
  fun `palette results rebuild after repo refresh and save mutations`() {
    val treeService = MutableSkillTreeService(skillTree("skill-one"))
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-one", "saved\n")
    }
    val viewModel = newViewModel(
      skillTreeService = treeService,
      authoringGateway = authoringGateway,
    )

    viewModel.selectRepoPath("/repo")
    viewModel.openCommandPalette()
    treeService.items = skillTree("skill-one", "skill-two")
    val refreshed = viewModel.refresh()
    assertTrue(refreshed.commandPalette.results.any { it.treeItemId == "skill-two" })

    viewModel.selectTreeItem("skill-one")
    val dirty = viewModel.updateEditorDraft("changed\n")
    assertTrue(dirty.commandPalette.results.first { it.id == "command.save" }.enabled)
    val saveRequest = viewModel.beginSaveEditor()
    val saveDuringRun = viewModel.state().commandPalette.results.first { it.id == "command.save" }
    assertEquals("Wait for save to finish.", saveDuringRun.disabledReason)
    viewModel.finishSaveEditor(viewModel.runSaveEditor(assertNotNull(saveRequest)))
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
    val refresh = moved.commandPalette.results.first { it.id == "command.refresh" }
    var executedCount = 0

    assertEquals(1, moved.commandPalette.selectedResultIndex)
    assertTrue(
      executeCommandPaletteResult(
        refresh,
        paletteActions(refresh = { executedCount += 1 }),
      ),
    )
    assertEquals(1, executedCount)
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

  @Test
  fun `available installed workspace opens recognized session at startup without picker or recents write`() {
    val recentRepoRepository = FakeRecentRepoRepository()
    val viewModel = newViewModel(
      recentRepoRepository = recentRepoRepository,
      installedWorkspaceLocator = FakeInstalledWorkspaceLocator(
        result = InstalledWorkspaceAvailability(path = INSTALLED_ROOT, availability = true),
      ),
    )

    val state = viewModel.state()

    assertEquals(INSTALLED_ROOT, state.selectedRepoPath)
    assertEquals(RepoLoadState.LOADED, state.repoStatus.state)
    assertTrue(state.treeItems.isNotEmpty())
    assertNull(recentRepoRepository.recentRepoPath())
  }

  @Test
  fun `no installed workspace falls back to recent path at startup`() {
    val recentRepoRepository = FakeRecentRepoRepository(initialRepoPath = "/recent-repo")
    val viewModel = newViewModel(
      recentRepoRepository = recentRepoRepository,
      installedWorkspaceLocator = FakeInstalledWorkspaceLocator(
        result = InstalledWorkspaceAvailability(path = "", availability = false),
      ),
    )

    val state = viewModel.state()

    assertEquals("/recent-repo", state.selectedRepoPath)
    assertEquals(RepoLoadState.LOADED, state.repoStatus.state)
  }

  @Test
  fun `default-open never writes recents while picker-open does`() {
    val defaultOpenRecents = FakeRecentRepoRepository()
    newViewModel(
      recentRepoRepository = defaultOpenRecents,
      installedWorkspaceLocator = FakeInstalledWorkspaceLocator(
        result = InstalledWorkspaceAvailability(path = INSTALLED_ROOT, availability = true),
      ),
    )
    assertNull(defaultOpenRecents.recentRepoPath())

    val pickerRecents = FakeRecentRepoRepository()
    val pickerViewModel = newViewModel(recentRepoRepository = pickerRecents)
    pickerViewModel.selectRepoPath("/cloned-repo")

    assertEquals("/cloned-repo", pickerRecents.recentRepoPath())
  }

  @Test
  fun `clone session can return to installed workspace without overwriting recents`() {
    val recentRepoRepository = FakeRecentRepoRepository()
    val viewModel = newViewModel(
      recentRepoRepository = recentRepoRepository,
      installedWorkspaceLocator = FakeInstalledWorkspaceLocator(
        result = InstalledWorkspaceAvailability(path = INSTALLED_ROOT, availability = true),
      ),
    )

    val cloneState = viewModel.selectRepoPath("/cloned-repo")
    assertEquals("/cloned-repo", cloneState.selectedRepoPath)
    assertTrue(cloneState.canReturnToInstalledWorkspace)
    assertEquals("/cloned-repo", recentRepoRepository.recentRepoPath())

    val begun = viewModel.beginReturnToInstalledWorkspace()
    assertNull(begun.dirtyEditorPrompt)
    assertEquals(SkillBillBusyOperation.OPEN_REPO, begun.busyOperation)
    val installedState = viewModel.finishRepoLoad(
      viewModel.loadRepo(viewModel.repoLoadRequest(repoPath = begun.repoPathText, preserveSelection = false)),
    )

    assertEquals(INSTALLED_ROOT, installedState.selectedRepoPath)
    assertFalse(installedState.canReturnToInstalledWorkspace)
    assertEquals("/cloned-repo", recentRepoRepository.recentRepoPath())
  }

  @Test
  fun `dirty clone session prompts before returning to installed workspace`() {
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-one", "saved\n")
    }
    val recentRepoRepository = FakeRecentRepoRepository()
    val viewModel = newViewModel(
      authoringGateway = authoringGateway,
      recentRepoRepository = recentRepoRepository,
      installedWorkspaceLocator = FakeInstalledWorkspaceLocator(
        result = InstalledWorkspaceAvailability(path = INSTALLED_ROOT, availability = true),
      ),
    )
    viewModel.selectRepoPath("/cloned-repo")
    viewModel.selectTreeItem("skill-one")
    viewModel.updateEditorDraft("dirty\n")

    val prompted = viewModel.beginReturnToInstalledWorkspace()
    assertEquals(DirtyEditorPromptReason.RETURN_TO_INSTALLED_WORKSPACE, prompted.dirtyEditorPrompt?.reason)
    assertEquals(INSTALLED_ROOT, prompted.dirtyEditorPrompt?.targetRepoPath)
    assertEquals("/cloned-repo", prompted.selectedRepoPath)

    val discarded = viewModel.discardDirtyEditorPrompt()
    assertEquals(SkillBillBusyOperation.OPEN_REPO, discarded.busyOperation)
    assertEquals(INSTALLED_ROOT, discarded.repoPathText)
    assertFalse(discarded.editor.dirty)
  }

  @Test
  fun `available installed workspace wins over recent path and leaves recents untouched`() {
    val recentRepoRepository = FakeRecentRepoRepository(initialRepoPath = "/recent-repo")
    val viewModel = newViewModel(
      recentRepoRepository = recentRepoRepository,
      installedWorkspaceLocator = FakeInstalledWorkspaceLocator(
        result = InstalledWorkspaceAvailability(path = INSTALLED_ROOT, availability = true),
      ),
    )

    val state = viewModel.state()

    assertEquals(INSTALLED_ROOT, state.selectedRepoPath)
    assertEquals("/recent-repo", recentRepoRepository.recentRepoPath())
  }

  @Test
  fun `blank installed workspace path falls back to recent path`() {
    val recentRepoRepository = FakeRecentRepoRepository(initialRepoPath = "/recent-repo")
    val viewModel = newViewModel(
      recentRepoRepository = recentRepoRepository,
      installedWorkspaceLocator = FakeInstalledWorkspaceLocator(
        result = InstalledWorkspaceAvailability(path = "   ", availability = true),
      ),
    )

    val state = viewModel.state()

    assertEquals("/recent-repo", state.selectedRepoPath)
    assertFalse(state.canReturnToInstalledWorkspace)
  }

  @Test
  fun `saving in installed session writes under installed root and never writes recents`() {
    val recentRepoRepository = FakeRecentRepoRepository()
    val authoringGateway = FakeAuthoringGateway().apply { putDocument("skill-one", "before\n") }
    val viewModel = newViewModel(
      authoringGateway = authoringGateway,
      recentRepoRepository = recentRepoRepository,
      installedWorkspaceLocator = FakeInstalledWorkspaceLocator(
        result = InstalledWorkspaceAvailability(path = INSTALLED_ROOT, availability = true),
      ),
    )
    viewModel.selectTreeItem("skill-one")
    viewModel.updateEditorDraft("after\n")

    val request = assertNotNull(viewModel.beginSaveEditor())
    val saved = viewModel.finishSaveEditor(viewModel.runSaveEditor(request))

    assertEquals(INSTALLED_ROOT, request.session?.repoPath)
    assertEquals("after\n", authoringGateway.lastSavedBody)
    assertFalse(saved.editor.dirty)
    assertNull(recentRepoRepository.recentRepoPath())
  }

  private fun newViewModel(
    repoSessionService: RepoSessionService = FakeRepoSessionService(),
    skillTreeService: SkillTreeService = FakeSkillTreeService(defaultSkillTree()),
    authoringGateway: AuthoringGateway = FakeAuthoringGateway(),
    recentRepoRepository: FakeRecentRepoRepository = FakeRecentRepoRepository(),
    scaffoldGateway: skillbill.desktop.core.domain.service.RuntimeScaffoldGateway =
      skillbill.desktop.core.testing.scaffold.FakeScaffoldGateway(),
    firstRunGateway: skillbill.desktop.core.domain.service.DesktopFirstRunGateway = defaultFirstRunGateway(),
    desktopPreferenceStore: skillbill.desktop.core.datastore.DesktopPreferenceStore = completedFirstRunStore(),
    skillRemoveGateway: skillbill.desktop.core.domain.service.RuntimeSkillRemoveGateway =
      skillbill.desktop.core.testing.skillremove.FakeSkillRemoveGateway(),
    installedWorkspaceLocator: FakeInstalledWorkspaceLocator = FakeInstalledWorkspaceLocator(),
  ): SkillBillViewModel = SkillBillViewModel(
    repoSessionService = repoSessionService,
    skillTreeService = skillTreeService,
    authoringGateway = authoringGateway,
    recentRepoRepository = recentRepoRepository,
    scaffoldGateway = scaffoldGateway,
    firstRunGateway = firstRunGateway,
    desktopPreferenceStore = desktopPreferenceStore,
    skillRemoveGateway = skillRemoveGateway,
    installedWorkspaceLocator = installedWorkspaceLocator,
  )
}

private fun defaultFirstRunGateway(): skillbill.desktop.core.domain.service.DesktopFirstRunGateway =
  skillbill.desktop.core.testing.install.FakeDesktopFirstRunGateway(
    discoveryResult = skillbill.desktop.core.domain.model.FirstRunDiscoveryResult.Success(
      skillbill.desktop.core.domain.model.FirstRunSetupDiscovery(
        agents = emptyList(),
        platformPacks = emptyList(),
      ),
    ),
    planResult = skillbill.desktop.core.domain.model.FirstRunPlanResult.Failed("not scripted"),
    applyResult = skillbill.desktop.core.domain.model.FirstRunApplyResult.Failed(
      skillbill.desktop.core.domain.model.FirstRunInstallOutcome(
        status = skillbill.desktop.core.domain.model.FirstRunInstallStatus.FAILURE,
        title = "not scripted",
      ),
    ),
  )

private fun completedFirstRunStore(): skillbill.desktop.core.datastore.DesktopPreferenceStore =
  skillbill.desktop.core.testing.FakeDesktopPreferenceStore(
    initialFirstRunPreferences = skillbill.desktop.core.datastore.DesktopFirstRunPreferences(completed = true),
  )

private fun paletteActions(
  selectTreeItem: (String) -> Unit = {},
  openRepository: () -> Unit = {},
  refresh: () -> Unit = {},
  save: () -> Unit = {},
  openInstallSetup: () -> Unit = {},
  openScaffoldWizard: (skillbill.desktop.core.domain.model.ScaffoldKind) -> Unit = {},
): CommandPaletteActions = CommandPaletteActions(
  selectTreeItem = selectTreeItem,
  openRepository = openRepository,
  refresh = refresh,
  save = save,
  openInstallSetup = openInstallSetup,
  openScaffoldWizard = openScaffoldWizard,
)

private const val INSTALLED_ROOT = "/home/tester/.skill-bill"

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
