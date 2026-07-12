package skillbill.desktop.feature.skillbill.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import skillbill.desktop.core.designsystem.SkillBillMaterialTheme
import skillbill.desktop.core.domain.model.DesktopWorkItem
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.WorkListLoadState
import skillbill.desktop.core.domain.model.WorkListState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillBillWorkSectionTest {
  @Test
  fun `Work table maps horizontal direction keys to horizontal scrolling`() {
    assertEquals(-160, workHorizontalScrollDelta(Key.DirectionLeft))
    assertEquals(160, workHorizontalScrollDelta(Key.DirectionRight))
    assertEquals(null, workHorizontalScrollDelta(Key.DirectionDown))
  }

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
    onNodeWithContentDescription("Work table. Use left and right arrow keys to inspect all columns.").assertIsDisplayed()
    onNodeWithTag("work-section-field-headers").assertIsDisplayed()
    onNodeWithTag("work-section-row-wftr-117").assertIsDisplayed()
    onNodeWithTag("work-section-cell-wftr-117-state-since", useUnmergedTree = true).assertIsNotDisplayed()
    onNodeWithText("ISSUE").assertIsDisplayed()
    onNodeWithText("Unknown issue").assertIsDisplayed()
    onNodeWithContentDescription(
      "Issue: Unknown issue. Kind: feature-task-runtime. Workflow: wftr-117. " +
        "Started: 2026-05-01 14:00:00 CEST. State: running. " +
        "State since: 2026-05-01 14:00:00 CEST, estimated",
    ).assertIsDisplayed()
    onNodeWithTag("work-section-list-viewport").requestFocus().performKeyInput {
      pressKey(Key.DirectionRight)
      pressKey(Key.DirectionRight)
      pressKey(Key.DirectionRight)
      pressKey(Key.DirectionRight)
      pressKey(Key.DirectionRight)
      pressKey(Key.DirectionRight)
    }
    waitForIdle()
    onNodeWithTag("work-section-cell-wftr-117-state-since", useUnmergedTree = true).assertIsDisplayed()
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `Work supports expand collapse refresh and every async display state`() = runComposeUiTest {
    val state = mutableStateOf(WorkListState())
    var refreshes = 0
    var treeMoves = 0
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
          onMoveSelection = { treeMoves += 1 },
          workList = state.value,
          onWorkToggled = {
            state.value = if (state.value.expanded) {
              WorkListState()
            } else {
              WorkListState(expanded = true, loadState = WorkListLoadState.LOADING)
            }
          },
          onWorkRefreshed = { refreshes += 1 },
        )
      }
    }

    onNodeWithTag("work-section-toggle").performClick()
    onNodeWithText("Loading work…").assertIsDisplayed()
    onNodeWithTag("work-section-refresh").assertIsNotEnabled()
    runOnIdle { state.value = WorkListState(expanded = true, loadState = WorkListLoadState.EMPTY) }
    onNodeWithText("No persisted work.").assertIsDisplayed()
    onNodeWithTag("work-section-refresh").performClick()
    assertEquals(1, refreshes)

    runOnIdle {
      state.value = WorkListState(
        expanded = true,
        loadState = WorkListLoadState.ERROR,
        errorMessage = "Database unavailable",
      )
    }
    onNodeWithText("Database unavailable").assertIsDisplayed()
    onNodeWithText("Could not load work.").assertIsDisplayed()

    runOnIdle {
      state.value = WorkListState(
        expanded = true,
        loadState = WorkListLoadState.POPULATED,
        items = (1..20).map { index -> workItem("wftr-$index", "2026-05-01 14:00:$index CEST") },
      )
    }
    onNodeWithTag("work-section-list-viewport").assertIsDisplayed()
    onNodeWithTag("work-section-row-wftr-1").assertIsDisplayed()
    onNodeWithTag("work-section-list-viewport").requestFocus().performKeyInput {
      pressKey(Key.PageDown)
      pressKey(Key.DirectionRight)
    }
    assertEquals(0, treeMoves)
    onNodeWithTag("work-section-row-wftr-8").assertIsDisplayed()

    onNodeWithTag("work-section-toggle").performClick()
    assertTrue(onAllNodesWithTag("work-section-list-viewport").fetchSemanticsNodes().isEmpty())
  }

  private fun workItem(workflowId: String, startedAt: String) = DesktopWorkItem(
    issueKey = "SKILL-117",
    workflowKind = "feature-task-runtime",
    workflowId = workflowId,
    startedAt = startedAt,
    currentState = "running",
    stateEnteredAt = startedAt,
    stateEnteredAtEstimated = false,
  )
}
