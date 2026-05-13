package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.ChangedFile
import skillbill.desktop.core.domain.model.ChangedFileGroup
import skillbill.desktop.core.domain.model.ChangesSnapshot
import skillbill.desktop.core.domain.model.CommitEntry
import skillbill.desktop.core.domain.model.GitAheadBehind
import skillbill.desktop.core.domain.model.GitOperationResult
import skillbill.desktop.core.domain.model.GitPublishingStatus
import skillbill.desktop.core.domain.model.GitPushTarget
import skillbill.desktop.core.domain.model.RenderRunState
import skillbill.desktop.core.domain.model.RenderSummary
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
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
import skillbill.desktop.core.testing.FakeRecentRepoRepository
import skillbill.desktop.core.testing.FakeRenderGateway
import skillbill.desktop.core.testing.FakeSkillTreeService
import skillbill.desktop.core.testing.FakeValidationGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillBillViewModelGitTest {

  @Test
  fun `git refresh updates changes snapshot through begin run finish triplet`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "skills/x.md", group = ChangedFileGroup.UNSTAGED, statusCode = "M")),
      ),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")

    val request = viewModel.beginGitRefresh()
    assertTrue(viewModel.state().changesBusy)
    val result = viewModel.runGitRefresh(request)
    val state = viewModel.finishGitRefresh(result)

    assertFalse(state.changesBusy)
    assertEquals(1, state.changes.files.size)
    assertEquals(1, gitGateway.snapshotForCallCount)
  }

  @Test
  fun `stale finishGitRefresh after a newer changes-slice op keeps current state (F-A01)`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "a.md", group = ChangedFileGroup.UNSTAGED, statusCode = "M")),
      ),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    // Seed a known snapshot first.
    val seededState = viewModel.refreshGit()
    assertEquals(1, seededState.changes.files.size)

    // Begin a refresh, then start a newer git op (another refresh) before finishing the first.
    val staleRequest = viewModel.beginGitRefresh()
    val staleResult = viewModel.runGitRefresh(staleRequest)
    // A newer refresh starts and finishes.
    viewModel.refreshGit()
    val afterStale = viewModel.finishGitRefresh(staleResult)

    // F-A01: the stale finish must not stomp the newer slice. The "keep current" rule means we
    // return state unchanged when a newer changes-slice op is in flight — whichever op finishes
    // LAST is the source of truth. Here the newer refresh already finished and cleared busy.
    assertEquals(1, afterStale.changes.files.size)
    assertFalse(afterStale.changesBusy)
  }

  @Test
  fun `stale finish does not overwrite newer stage effect (F-A01)`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "x.md", group = ChangedFileGroup.UNSTAGED, statusCode = "M")),
      ),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()

    // Begin a refresh, then start a newer stage before finishing the first refresh. The stage
    // moves x.md from UNSTAGED to STAGED.
    val staleRefreshRequest = viewModel.beginGitRefresh()
    val staleRefreshResult = viewModel.runGitRefresh(staleRefreshRequest)
    val stageRequest = viewModel.beginStage(listOf("x.md"))
    val stageResult = viewModel.runStage(stageRequest)
    viewModel.finishGitRefresh(stageResult)
    val afterStaleRefresh = viewModel.finishGitRefresh(staleRefreshResult)

    // F-A01: the stale refresh-finish must NOT restore the pre-stage snapshot — that would silently
    // lose the staging effect. Whichever finishes last (here, the stage) is the source of truth.
    assertEquals(ChangedFileGroup.STAGED, afterStaleRefresh.changes.files.single().group)
  }

  @Test
  fun `repo switch resets every per-snapshot git slice (F-103)`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "old.md", group = ChangedFileGroup.UNSTAGED, statusCode = "M")),
      ),
      scriptedCommits = listOf(commitEntry("alpha")),
    )
    val repoSessionService = GitTestMutableRepoSessionService(loadedSession("/repo"))
    val viewModel = newViewModel(repoSessionService = repoSessionService, gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()
    viewModel.finishLoadHistory(viewModel.runLoadHistory(viewModel.beginLoadHistory()))
    val seeded = viewModel.state()
    assertEquals(1, seeded.changes.files.size)
    assertEquals(1, seeded.history.size)

    repoSessionService.nextSession = loadedSession("/other")
    val switched = viewModel.refresh()

    // F-103: every git slice mirrors on-disk state and must reset on repo switch.
    assertTrue(switched.changes.files.isEmpty())
    assertTrue(switched.history.isEmpty())
    assertEquals("", switched.selectedDiff)
    assertNull(switched.selectedChangedFile)
    assertNull(switched.historyPathFilter)
  }

  @Test
  fun `selectTreeItem resets selectedDiff and history path filter slices (F-202)`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "skills/a.md", group = ChangedFileGroup.UNSTAGED, statusCode = "M")),
      ),
      scriptedDiff = "diff --git a/skills/a.md\n+update",
    )
    val viewModel = newViewModel(
      gitGateway = gitGateway,
      skillTreeService = GitTestMutableSkillTreeService(
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
    viewModel.refreshGit()
    val diffRequest = viewModel.selectChangedFile("skills/a.md")
    assertNotNull(diffRequest)
    viewModel.finishDiff(viewModel.runDiff(diffRequest))
    viewModel.setHistoryPathFilter("skills/a.md")
    val seeded = viewModel.state()
    assertEquals("skills/a.md", seeded.selectedChangedFile?.path)
    assertTrue(seeded.selectedDiff.contains("+update"))

    val switched = viewModel.selectTreeItem("skill-two")

    // F-202 mirror: per-selection-keyed slices reset on tree-selection change.
    assertNull(switched.selectedChangedFile)
    assertEquals("", switched.selectedDiff)
    assertNull(switched.historyPathFilter)
  }

  @Test
  fun `selecting a changed file routes diff through diffFor with correct staged flag`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(
          ChangedFile(path = "staged.md", group = ChangedFileGroup.STAGED, statusCode = "A"),
          ChangedFile(path = "unstaged.md", group = ChangedFileGroup.UNSTAGED, statusCode = "M"),
        ),
      ),
      scriptedDiff = "diff!",
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()

    val stagedRequest = viewModel.selectChangedFile("staged.md")
    assertNotNull(stagedRequest)
    viewModel.finishDiff(viewModel.runDiff(stagedRequest))
    assertEquals(true, gitGateway.lastDiffRequestedStaged)

    val unstagedRequest = viewModel.selectChangedFile("unstaged.md")
    assertNotNull(unstagedRequest)
    viewModel.finishDiff(viewModel.runDiff(unstagedRequest))
    assertEquals(false, gitGateway.lastDiffRequestedStaged)
  }

  @Test
  fun `stage and unstage update snapshot via FakeGitGateway and clear busy state`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "x.md", group = ChangedFileGroup.UNSTAGED, statusCode = "M")),
      ),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()

    val stageRequest = viewModel.beginStage(listOf("x.md"))
    val stageResult = viewModel.runStage(stageRequest)
    val staged = viewModel.finishGitRefresh(stageResult)
    assertEquals(ChangedFileGroup.STAGED, staged.changes.files.single().group)
    assertEquals(1, gitGateway.stageCallCount)
    assertFalse(staged.changesBusy)

    val unstageRequest = viewModel.beginUnstage(listOf("x.md"))
    val unstageResult = viewModel.runStage(unstageRequest)
    val unstaged = viewModel.finishGitRefresh(unstageResult)
    assertEquals(ChangedFileGroup.UNSTAGED, unstaged.changes.files.single().group)
    assertEquals(1, gitGateway.unstageCallCount)
  }

  @Test
  fun `gateway error message surfaces in changes snapshot without changing other slices (AC11)`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(files = emptyList(), errorMessage = null),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    val beforeValidation = viewModel.state().validation

    gitGateway.scriptedSnapshotErrorMessage = "fatal: not a git repository"
    val state = viewModel.refreshGit()

    assertEquals("fatal: not a git repository", state.changes.errorMessage)
    // AC11: other app state must not change because of a git error.
    assertEquals(beforeValidation, state.validation)
  }

  @Test
  fun `history loads via FakeGitGateway and respects path filter`() {
    val gitGateway = FakeGitGateway(
      scriptedCommits = listOf(
        commitEntry(subject = "alpha", paths = listOf("skills/a.md")),
        commitEntry(subject = "beta", paths = listOf("skills/b.md")),
      ),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")

    val state = viewModel.finishLoadHistory(viewModel.runLoadHistory(viewModel.beginLoadHistory()))
    assertEquals(2, state.history.size)
    assertEquals(1, gitGateway.recentCommitsCallCount)

    viewModel.setHistoryPathFilter("skills/a.md")
    val filtered = viewModel.finishLoadHistory(viewModel.runLoadHistory(viewModel.beginLoadHistory()))
    assertEquals(1, filtered.history.size)
    assertEquals("alpha", filtered.history.single().subject)
    assertEquals("skills/a.md", gitGateway.lastRecentCommitsPathFilter)
  }

  @Test
  fun `history empty state remains when no repo open (AC5)`() {
    val gitGateway = FakeGitGateway(scriptedCommits = listOf(commitEntry("alpha")))
    val viewModel = newViewModel(gitGateway = gitGateway)
    // No selectRepoPath call -> session is null.

    val state = viewModel.finishLoadHistory(viewModel.runLoadHistory(viewModel.beginLoadHistory()))

    // AC5: when no Git repo is open, history must stay empty regardless of scripted commits — the
    // gateway short-circuits on a null session, and the UI's hasRepoOpen branch renders the empty
    // state. Pinning both invariants (empty history + null selectedRepoPath) protects AC5.
    assertTrue(state.history.isEmpty())
    assertNull(state.selectedRepoPath)
  }

  @Test
  fun `selectChangedFile to unknown path clears the selection`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(files = emptyList()),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()

    val request = viewModel.selectChangedFile("never-existed.md")

    assertNull(request)
    val state = viewModel.state()
    assertNull(state.selectedChangedFile)
    assertEquals("", state.selectedDiff)
  }

  // F-T02: prove every AC10 refresh seam (render, validate, manual refresh, stage, unstage) advances
  // gitGateway.snapshotForCallCount. The VM-level helpers exercised here mirror the route-level
  // fan-out: callers run their begin/run/finish triplet, then invoke refreshGit() (or its async
  // equivalent in production) to update the Changes tab.

  @Test
  fun `manual refreshGit advances snapshotForCallCount (F-T02 AC10)`() {
    val gitGateway = FakeGitGateway()
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    val before = gitGateway.snapshotForCallCount

    viewModel.refreshGit()

    assertTrue(
      gitGateway.snapshotForCallCount > before,
      "expected snapshotForCallCount to advance after manual refresh; " +
        "before=$before now=${gitGateway.snapshotForCallCount}",
    )
  }

  @Test
  fun `post-validate refresh advances snapshotForCallCount (F-T02 AC10)`() {
    val gitGateway = FakeGitGateway()
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    // Drive validate triplet, then post-validate refresh as the route does.
    val request = viewModel.beginValidate()
    viewModel.finishValidate(viewModel.runValidate(request))
    val before = gitGateway.snapshotForCallCount

    viewModel.refreshGit()

    assertTrue(
      gitGateway.snapshotForCallCount > before,
      "expected snapshotForCallCount to advance after validate refresh",
    )
  }

  @Test
  fun `post-render refresh advances snapshotForCallCount (F-T02 AC10)`() {
    val gitGateway = FakeGitGateway()
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    val request = viewModel.beginRender()
    viewModel.finishRender(viewModel.runRender(request))
    val before = gitGateway.snapshotForCallCount

    viewModel.refreshGit()

    assertTrue(
      gitGateway.snapshotForCallCount > before,
      "expected snapshotForCallCount to advance after render refresh",
    )
  }

  @Test
  fun `stage advances snapshotForCallCount via gateway stage (F-T02 AC10)`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "x.md", group = ChangedFileGroup.UNSTAGED, statusCode = "M")),
      ),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()
    val beforeStage = gitGateway.stageCallCount
    val beforeSnapshot = gitGateway.snapshotForCallCount

    val request = viewModel.beginStage(listOf("x.md"))
    viewModel.finishGitRefresh(viewModel.runStage(request))

    assertEquals(beforeStage + 1, gitGateway.stageCallCount)
    // FakeGitGateway.stage calls snapshotFor internally to return the updated snapshot.
    assertTrue(gitGateway.snapshotForCallCount > beforeSnapshot)
  }

  @Test
  fun `unstage advances snapshotForCallCount via gateway unstage (F-T02 AC10)`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "x.md", group = ChangedFileGroup.STAGED, statusCode = "M")),
      ),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()
    val beforeUnstage = gitGateway.unstageCallCount
    val beforeSnapshot = gitGateway.snapshotForCallCount

    val request = viewModel.beginUnstage(listOf("x.md"))
    viewModel.finishGitRefresh(viewModel.runStage(request))

    assertEquals(beforeUnstage + 1, gitGateway.unstageCallCount)
    assertTrue(gitGateway.snapshotForCallCount > beforeSnapshot)
  }

  // F-T03: AC11 negative paths — when each gateway path throws, the relevant slice surfaces the
  // error and other slices stay unchanged.

  @Test
  fun `throwOnDiff surfaces empty diff without changing other slices (F-T03 AC11)`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "x.md", group = ChangedFileGroup.UNSTAGED, statusCode = "M")),
      ),
      throwOnDiff = IllegalStateException("diff exploded"),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()
    val beforeValidation = viewModel.state().validation
    val beforeRender = viewModel.state().render

    val request = viewModel.selectChangedFile("x.md")
    assertNotNull(request)
    val state = viewModel.finishDiff(viewModel.runDiff(request))

    // The diff slice surfaces the failure as empty text (runCatching default), and unrelated slices
    // (validation, render, tree selection) are untouched.
    assertEquals("", state.selectedDiff)
    assertEquals(beforeValidation, state.validation)
    assertEquals(beforeRender, state.render)
  }

  @Test
  fun `throwOnCommits surfaces history error without changing other slices (F-T03 AC11)`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "x.md", group = ChangedFileGroup.UNSTAGED, statusCode = "M")),
      ),
      throwOnCommits = IllegalStateException("log exploded"),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()
    val beforeValidation = viewModel.state().validation
    val beforeChanges = viewModel.state().changes.files

    val state = viewModel.finishLoadHistory(viewModel.runLoadHistory(viewModel.beginLoadHistory()))

    assertNotNull(state.historyErrorMessage)
    assertTrue(state.history.isEmpty())
    // Unrelated slices are untouched.
    assertEquals(beforeValidation, state.validation)
    assertEquals(beforeChanges, state.changes.files)
  }

  @Test
  fun `throwOnStage surfaces error and preserves existing snapshot files (F-T03 AC11 + F-A02)`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "x.md", group = ChangedFileGroup.UNSTAGED, statusCode = "M")),
      ),
      throwOnStage = IllegalStateException("stage exploded"),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()
    val beforeValidation = viewModel.state().validation

    val request = viewModel.beginStage(listOf("x.md"))
    val state = viewModel.finishGitRefresh(viewModel.runStage(request))

    // F-A02: stage failure surfaces an error but does NOT blank out the prior file list.
    assertNotNull(state.changes.errorMessage)
    assertEquals(1, state.changes.files.size)
    assertEquals("x.md", state.changes.files.single().path)
    assertEquals(beforeValidation, state.validation)
  }

  @Test
  fun `throwOnUnstage surfaces error and preserves existing snapshot files (F-T03 AC11 + F-A02)`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "x.md", group = ChangedFileGroup.STAGED, statusCode = "M")),
      ),
      throwOnUnstage = IllegalStateException("unstage exploded"),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()
    val beforeValidation = viewModel.state().validation

    val request = viewModel.beginUnstage(listOf("x.md"))
    val state = viewModel.finishGitRefresh(viewModel.runStage(request))

    assertNotNull(state.changes.errorMessage)
    assertEquals(1, state.changes.files.size)
    assertEquals(ChangedFileGroup.STAGED, state.changes.files.single().group)
    assertEquals(beforeValidation, state.validation)
  }

  @Test
  fun `refreshGit retains selected file when it still exists and clears when it does not`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "x.md", group = ChangedFileGroup.UNSTAGED, statusCode = "M")),
      ),
      scriptedDiff = "d",
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()
    val req = viewModel.selectChangedFile("x.md")
    assertNotNull(req)
    viewModel.finishDiff(viewModel.runDiff(req))

    // File still exists across refresh -> selection preserved.
    val stillThere = viewModel.refreshGit()
    assertEquals("x.md", stillThere.selectedChangedFile?.path)

    // File disappears -> selection cleared.
    gitGateway.scriptedSnapshot = ChangesSnapshot(files = emptyList())
    val cleared = viewModel.refreshGit()
    assertNull(cleared.selectedChangedFile)
    assertEquals("", cleared.selectedDiff)
  }

  @Test
  fun `commit is disabled until staged changes and message are present`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "x.md", group = ChangedFileGroup.STAGED, statusCode = "M")),
      ),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    val refreshed = viewModel.refreshGit()
    assertFalse(refreshed.canCommit)

    val noMessageRequest = viewModel.beginCommit()
    assertNull(noMessageRequest)
    val ready = viewModel.updateCommitMessage("publish x")

    assertTrue(ready.canCommit)
    assertNotNull(viewModel.beginCommit())
  }

  @Test
  fun `commit stays disabled for unstaged only and generated only changes`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(
          ChangedFile(path = "unstaged.md", group = ChangedFileGroup.UNSTAGED, statusCode = "M"),
          ChangedFile(path = "generated.md", group = ChangedFileGroup.GENERATED, statusCode = "M", isGenerated = true),
        ),
      ),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()

    val state = viewModel.updateCommitMessage("publish x")

    assertFalse(state.canCommit)
    assertNull(viewModel.beginCommit())
  }

  @Test
  fun `commit and push do not start while git refresh is in flight`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "x.md", group = ChangedFileGroup.STAGED, statusCode = "M")),
      ),
      scriptedPublishingStatus = GitPublishingStatus(
        pushTarget = GitPushTarget(remoteName = "origin", branchName = "feature"),
      ),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()
    viewModel.updateCommitMessage("publish x")

    viewModel.beginGitRefresh()

    assertNull(viewModel.beginCommit())
    assertNull(viewModel.beginPush())
    assertEquals(0, gitGateway.commitCallCount)
    assertEquals(0, gitGateway.pushCallCount)
  }

  @Test
  fun `commit runs validation before git commit and blocks on failed validation`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "x.md", group = ChangedFileGroup.STAGED, statusCode = "M")),
      ),
    )
    val validationGateway = FakeValidationGateway(scriptedSummary = failedValidationSummary())
    val viewModel = newViewModel(gitGateway = gitGateway, validationGateway = validationGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()
    viewModel.updateCommitMessage("publish x")

    val request = viewModel.beginCommit()
    assertNotNull(request)
    val state = viewModel.finishCommit(viewModel.runCommit(request))

    assertEquals(1, validationGateway.validateCallCount)
    assertEquals(0, gitGateway.commitCallCount)
    assertTrue(state.commitValidationFailed)
  }

  @Test
  fun `failed validation override commits and refreshes changes history and publishing status`() {
    val refreshedStatus = GitPublishingStatus(
      pushTarget = GitPushTarget(remoteName = "origin", branchName = "feature"),
      aheadBehind = GitAheadBehind(ahead = 1, behind = 0),
    )
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "x.md", group = ChangedFileGroup.STAGED, statusCode = "M")),
      ),
      scriptedCommits = listOf(commitEntry("published", paths = listOf("x.md"))),
      scriptedPublishingStatus = refreshedStatus,
    )
    val validationGateway = FakeValidationGateway(scriptedSummary = failedValidationSummary())
    val viewModel = newViewModel(gitGateway = gitGateway, validationGateway = validationGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()
    viewModel.updateCommitMessage("publish x")
    val blocked = viewModel.beginCommit()
    assertNotNull(blocked)
    viewModel.finishCommit(viewModel.runCommit(blocked))
    val beforeSnapshots = gitGateway.snapshotForCallCount
    val beforeHistory = gitGateway.recentCommitsCallCount

    val overrideRequest = viewModel.beginCommit(allowFailedValidation = true)
    assertNotNull(overrideRequest)
    val state = viewModel.finishCommit(viewModel.runCommit(overrideRequest))

    assertEquals(1, gitGateway.commitCallCount)
    assertTrue(gitGateway.snapshotForCallCount > beforeSnapshots)
    assertTrue(gitGateway.recentCommitsCallCount > beforeHistory)
    assertEquals("", state.commitMessage)
    assertEquals(1, state.history.size)
    assertEquals(refreshedStatus.pushTarget, state.pushTarget)
  }

  @Test
  fun `failed validation override is invalidated when staged authored files change`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "x.md", group = ChangedFileGroup.STAGED, statusCode = "M")),
      ),
    )
    val validationGateway = FakeValidationGateway(scriptedSummary = failedValidationSummary())
    val viewModel = newViewModel(gitGateway = gitGateway, validationGateway = validationGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()
    viewModel.updateCommitMessage("publish x")
    val blocked = viewModel.beginCommit()
    assertNotNull(blocked)
    viewModel.finishCommit(viewModel.runCommit(blocked))
    gitGateway.scriptedSnapshot = ChangesSnapshot(
      files = listOf(
        ChangedFile(path = "y.md", group = ChangedFileGroup.STAGED, statusCode = "A"),
      ),
    )
    viewModel.refreshGit()

    val staleOverride = viewModel.beginCommit(allowFailedValidation = true)

    assertNull(staleOverride)
    assertEquals(0, gitGateway.commitCallCount)
    assertFalse(viewModel.state().commitValidationFailed)
  }

  @Test
  fun `commit success replaces stale snapshot history and publishing state`() {
    val beforeStatus = GitPublishingStatus(
      pushTarget = GitPushTarget(remoteName = "origin", branchName = "feature"),
      aheadBehind = GitAheadBehind(ahead = 1, behind = 0),
    )
    val afterStatus = beforeStatus.copy(aheadBehind = GitAheadBehind(ahead = 0, behind = 0))
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "x.md", group = ChangedFileGroup.STAGED, statusCode = "M")),
      ),
      scriptedPublishingStatus = beforeStatus,
    )
    val viewModel = newViewModel(
      gitGateway = gitGateway,
      validationGateway = FakeValidationGateway(scriptedSummary = ValidationSummary(state = ValidationRunState.PASSED)),
    )
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()
    viewModel.updateCommitMessage("publish x")
    val request = viewModel.beginCommit()
    assertNotNull(request)
    gitGateway.scriptedSnapshot = ChangesSnapshot(files = emptyList())
    gitGateway.scriptedCommits = listOf(commitEntry("publish x", paths = listOf("x.md")))
    gitGateway.scriptedPublishingStatus = afterStatus

    val state = viewModel.finishCommit(viewModel.runCommit(request))

    assertTrue(state.changes.files.isEmpty())
    assertEquals(listOf("publish x"), state.history.map { it.subject })
    assertEquals(afterStatus.aheadBehind, state.aheadBehind)
    assertEquals("", state.commitMessage)
  }

  @Test
  fun `commit git error is visible and preserves existing changes`() {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(ChangedFile(path = "x.md", group = ChangedFileGroup.STAGED, statusCode = "M")),
      ),
      scriptedCommitResult = GitOperationResult.failed("git exited with code 1: no identity"),
    )
    val viewModel = newViewModel(
      gitGateway = gitGateway,
      validationGateway = FakeValidationGateway(scriptedSummary = ValidationSummary(state = ValidationRunState.PASSED)),
    )
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()
    viewModel.updateCommitMessage("publish x")

    val request = viewModel.beginCommit()
    assertNotNull(request)
    val state = viewModel.finishCommit(viewModel.runCommit(request))

    assertEquals("git exited with code 1: no identity", state.commitErrorMessage)
    assertEquals(listOf("x.md"), state.changes.files.map { it.path })
  }

  @Test
  fun `refreshGit exposes push target ahead behind and compare URL`() {
    val status = GitPublishingStatus(
      pushTarget = GitPushTarget(remoteName = "origin", branchName = "feature"),
      aheadBehind = GitAheadBehind(ahead = 2, behind = 1),
      compareUrl = "https://github.com/acme/repo/compare/feature",
    )
    val gitGateway = FakeGitGateway(scriptedPublishingStatus = status)
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")

    val state = viewModel.refreshGit()

    assertEquals(status.pushTarget, state.pushTarget)
    assertEquals(status.aheadBehind, state.aheadBehind)
    assertEquals(status.compareUrl, state.compareUrl)
  }

  @Test
  fun `push to likely canonical remote is blocked until explicit confirmation`() {
    val target = GitPushTarget(
      remoteName = "origin",
      branchName = "main",
      isLikelyCanonical = true,
      canonicalWarning = "origin may be canonical",
    )
    val gitGateway = FakeGitGateway(scriptedPublishingStatus = GitPublishingStatus(pushTarget = target))
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()

    val blocked = viewModel.beginPush()
    val state = viewModel.state()

    assertNull(blocked)
    assertTrue(state.canonicalPushConfirmationRequired)
    assertEquals(0, gitGateway.pushCallCount)
  }

  @Test
  fun `confirmed push refreshes ahead behind and compare URL`() {
    val target = GitPushTarget(remoteName = "origin", branchName = "feature", isLikelyCanonical = true)
    val before = GitPublishingStatus(
      pushTarget = target,
      aheadBehind = GitAheadBehind(ahead = 1, behind = 0),
      compareUrl = "https://github.com/acme/repo/compare/feature",
    )
    val after = before.copy(aheadBehind = GitAheadBehind(ahead = 0, behind = 0))
    val gitGateway = FakeGitGateway(scriptedPublishingStatus = before)
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()
    viewModel.beginPush()
    gitGateway.scriptedPublishingStatus = after

    val request = viewModel.beginPush(allowCanonicalRemote = true)
    assertNotNull(request)
    val state = viewModel.finishPush(viewModel.runPush(request))

    assertEquals(1, gitGateway.pushCallCount)
    assertEquals(after.aheadBehind, state.aheadBehind)
    assertEquals(after.compareUrl, state.compareUrl)
    assertFalse(state.canonicalPushConfirmationRequired)
  }

  @Test
  fun `refresh clears stale canonical push confirmation when target changes`() {
    val targetA = GitPushTarget(remoteName = "origin", branchName = "main", isLikelyCanonical = true)
    val targetB = GitPushTarget(remoteName = "origin", branchName = "feature", isLikelyCanonical = true)
    val gitGateway = FakeGitGateway(scriptedPublishingStatus = GitPublishingStatus(pushTarget = targetA))
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()
    viewModel.beginPush()
    assertTrue(viewModel.state().canonicalPushConfirmationRequired)
    gitGateway.scriptedPublishingStatus = GitPublishingStatus(pushTarget = targetB)

    val refreshed = viewModel.refreshGit()
    val staleConfirmation = viewModel.beginPush(allowCanonicalRemote = true)

    assertFalse(refreshed.canonicalPushConfirmationRequired)
    assertNull(staleConfirmation)
    assertEquals(0, gitGateway.pushCallCount)
  }

  @Test
  fun `push git error is visible and preserves previous publish state`() {
    val before = GitPublishingStatus(
      pushTarget = GitPushTarget(remoteName = "origin", branchName = "feature"),
      aheadBehind = GitAheadBehind(ahead = 1, behind = 0),
    )
    val gitGateway = FakeGitGateway(
      scriptedPublishingStatus = before,
      scriptedPushResult = GitOperationResult.failed("git exited with code 128: rejected"),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()

    val request = viewModel.beginPush()
    assertNotNull(request)
    val state = viewModel.finishPush(viewModel.runPush(request))

    assertEquals("git exited with code 128: rejected", state.pushErrorMessage)
    assertEquals(before.pushTarget, state.pushTarget)
    assertEquals(before.aheadBehind, state.aheadBehind)
  }

  // ---- helpers ----

  private fun newViewModel(
    repoSessionService: RepoSessionService = LoadedRepoSessionService(),
    skillTreeService: SkillTreeService = FakeSkillTreeService(defaultTree()),
    authoringGateway: AuthoringGateway = FakeAuthoringGateway(),
    gitGateway: GitGateway = FakeGitGateway(),
    validationGateway: ValidationGateway = FakeValidationGateway(),
    renderGateway: RenderGateway = FakeRenderGateway(),
    recentRepoRepository: FakeRecentRepoRepository = FakeRecentRepoRepository(),
  ): SkillBillViewModel = SkillBillViewModel(
    repoSessionService = repoSessionService,
    skillTreeService = skillTreeService,
    authoringGateway = authoringGateway,
    gitGateway = gitGateway,
    validationGateway = validationGateway,
    renderGateway = renderGateway,
    recentRepoRepository = recentRepoRepository,
  )

  private fun commitEntry(
    subject: String,
    paths: List<String> = emptyList(),
    shortHash: String = subject.padEnd(7, 'x').take(7),
    fullHash: String = subject.padEnd(40, '0').take(40),
  ): CommitEntry = CommitEntry(
    shortHash = shortHash,
    fullHash = fullHash,
    author = "test",
    isoDate = "2025-04-30T14:22:00+00:00",
    subject = subject,
    changedPaths = paths,
  )

  private fun failedValidationSummary(): ValidationSummary = ValidationSummary(
    state = ValidationRunState.FAILED,
    issues = listOf(
      ValidationIssue(
        severity = ValidationSeverity.ERROR,
        code = "T-001",
        message = "Validation failed",
        sourcePath = "skills/x.md",
      ),
    ),
  )

  // Confirm the unused parameters don't trigger warnings.
  @Suppress("unused")
  private fun referenceUnused(): Pair<ValidationSummary, RenderSummary> =
    ValidationSummary(state = ValidationRunState.UNAVAILABLE) to
      RenderSummary(state = RenderRunState.UNAVAILABLE)
}

private fun defaultTree(): List<SkillBillTreeItem> = listOf(
  SkillBillTreeItem(
    id = "skills",
    label = "Skills",
    kind = TreeItemKind.GROUP,
    children = listOf(
      SkillBillTreeItem(id = "skill-one", label = "skill-one", kind = TreeItemKind.SKILL),
    ),
  ),
)

private fun loadedSession(repoPath: String): RepoSession = RepoSession(
  repoPath = repoPath,
  isRecognizedSkillBillRepo = true,
  loadStatus = RepoLoadStatus(state = RepoLoadState.LOADED, message = "Loaded"),
)

private class LoadedRepoSessionService : RepoSessionService {
  override fun open(repoPath: String): RepoSession = loadedSession(repoPath)
}

private class GitTestMutableRepoSessionService(var nextSession: RepoSession) : RepoSessionService {
  override fun open(repoPath: String): RepoSession =
    nextSession.copy(repoPath = nextSession.repoPath.ifBlank { repoPath })
}

private class GitTestMutableSkillTreeService(var items: List<SkillBillTreeItem>) : SkillTreeService {
  override fun treeFor(session: RepoSession?): List<SkillBillTreeItem> = items
}
