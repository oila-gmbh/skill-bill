package skillbill.desktop.feature.skillbill.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import skillbill.desktop.core.designsystem.SkillBillMaterialTheme
import skillbill.desktop.core.domain.model.DesktopWorkItem
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.WorkListLoadState
import skillbill.desktop.core.domain.model.WorkListState
import kotlin.test.Test

class SkillBillWorkSectionTest {
  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `Work renders structured field headers an estimated item and stable test tags`() = runComposeUiTest {
    setContent {
      SkillBillMaterialTheme {
        NavigationPane(
          paneWidth = 300.dp,
          repoPath = "",
          repoStatus = RepoLoadStatus.empty,
          treeItems = emptyList(),
          selectedNodeId = null,
          openEditorTabIds = emptySet(),
          expandedNodeIds = emptySet(),
          busyOperation = null,
          policyLabel = "Policy",
          readOnlyModeLabel = "Read-only",
          onRepoPathChanged = {},
          onRepoSelected = {},
          onChooseRepoDirectory = {},
          onNodeSelected = {},
          onNodeOpened = {},
          onNodeExpandedToggled = {},
          onMoveSelection = {},
          workList = WorkListState(
            expanded = true,
            loadState = WorkListLoadState.POPULATED,
            items = listOf(
              DesktopWorkItem(
                issueKey = null,
                workflowKind = "feature-task-runtime",
                workflowId = "wftr-117",
                startedAt = "2026-05-01 14:00:00 CEST",
                currentState = "running",
                stateEnteredAt = "2026-05-01 14:00:00 CEST",
                stateEnteredAtEstimated = true,
              ),
            ),
          ),
        )
      }
    }

    onNodeWithTag("work-section-toggle").assertIsDisplayed()
    onNodeWithTag("work-section-refresh").assertIsDisplayed()
    onNodeWithTag("work-section-status").assertIsDisplayed()
    onNodeWithTag("work-section-list-viewport").assertIsDisplayed()
    onNodeWithTag("work-section-field-headers").assertIsDisplayed()
    onNodeWithTag("work-section-row-wftr-117").assertIsDisplayed()
    onNodeWithText("ISSUE").assertIsDisplayed()
    onNodeWithText("Unknown issue").assertIsDisplayed()
  }
}
