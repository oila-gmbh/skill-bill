package skillbill.desktop.feature.skillbill.ui

import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.domain.model.SourceControlStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillBillRouteAutoRefreshTest {
  @Test
  fun `auto git refresh is allowed while editor has protected draft`() {
    val clean = loadedState()
    val dirty = clean.copy(editor = clean.editor.copy(dirty = true))
    val saveInProgress = clean.copy(editor = clean.editor.copy(saveInProgress = true))

    assertTrue(canAutoRefreshGitStatus(clean))
    assertTrue(canAutoRefreshGitStatus(dirty))
    assertTrue(canAutoRefreshGitStatus(saveInProgress))
  }

  @Test
  fun `auto repo snapshot refresh waits while editor save is running`() {
    val clean = loadedState()
    val saveInProgress = clean.copy(editor = clean.editor.copy(saveInProgress = true))

    assertTrue(canAutoRefreshRepoSnapshot(clean))
    assertFalse(canAutoRefreshRepoSnapshot(saveInProgress))
  }

  @Test
  fun `repo snapshot change dominates pending git status changes`() {
    val pendingGit = mergeRepoFileChangeKind(null, RepoFileChangeKind.GitStatus)
    val pendingSnapshot = mergeRepoFileChangeKind(pendingGit, RepoFileChangeKind.RepoSnapshot)
    val stillSnapshot = mergeRepoFileChangeKind(pendingSnapshot, RepoFileChangeKind.GitStatus)

    assertEquals(RepoFileChangeKind.GitStatus, pendingGit)
    assertEquals(RepoFileChangeKind.RepoSnapshot, pendingSnapshot)
    assertEquals(RepoFileChangeKind.RepoSnapshot, stillSnapshot)
  }

  @Test
  fun `auto refresh waits while repo operation is running`() {
    val busy = loadedState().copy(busyOperation = SkillBillBusyOperation.REFRESH)

    assertFalse(canAutoRefreshGitStatus(busy))
  }

  private fun loadedState(): SkillBillState = SkillBillState(
    selectedRepoPath = "/repo",
    repoStatus = RepoLoadStatus(state = RepoLoadState.LOADED, message = "Loaded"),
    treeItems = emptyList(),
    selectedTreeItemId = null,
    editor = EditorPlaceholder(title = "content.md", detail = "content.md", editable = true, dirty = false),
    sourceControl = SourceControlStatus(branchLabel = "main", summary = ""),
  )
}
