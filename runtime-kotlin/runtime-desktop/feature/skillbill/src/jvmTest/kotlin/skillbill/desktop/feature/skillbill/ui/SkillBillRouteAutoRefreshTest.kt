package skillbill.desktop.feature.skillbill.ui

import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillBillRouteAutoRefreshTest {
  @Test
  fun `auto repo snapshot refresh waits while editor save is running`() {
    val clean = loadedState()
    val saveInProgress = clean.copy(editor = clean.editor.copy(saveInProgress = true))

    assertTrue(canAutoRefreshRepoSnapshot(clean))
    assertFalse(canAutoRefreshRepoSnapshot(saveInProgress))
  }

  @Test
  fun `auto refresh waits while repo operation is running`() {
    val busy = loadedState().copy(busyOperation = SkillBillBusyOperation.REFRESH)

    assertFalse(canAutoRefreshRepoSnapshot(busy))
  }

  private fun loadedState(): SkillBillState = SkillBillState(
    selectedRepoPath = "/repo",
    repoStatus = RepoLoadStatus(state = RepoLoadState.LOADED, message = "Loaded"),
    treeItems = emptyList(),
    selectedTreeItemId = null,
    editor = EditorPlaceholder(title = "content.md", detail = "content.md", editable = true, dirty = false),
  )
}
