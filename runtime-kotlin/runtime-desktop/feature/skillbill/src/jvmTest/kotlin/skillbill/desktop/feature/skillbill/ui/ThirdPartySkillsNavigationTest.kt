package skillbill.desktop.feature.skillbill.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import skillbill.desktop.core.designsystem.SkillBillMaterialTheme
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.TreeItemKind
import kotlin.test.Test
import kotlin.test.assertEquals

class ThirdPartySkillsNavigationTest {
  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `machine group renders count status refresh and accessible tree semantics`() = runComposeUiTest {
    val actions = mutableListOf<String>()
    val root = SkillBillTreeItem(
      id = "machine:third-party-skills",
      label = "Third-Party Skills",
      kind = TreeItemKind.GROUP,
      status = "1",
      external = true,
      children = listOf(
        SkillBillTreeItem(
          id = "machine:third-party-skills:skill:demo",
          label = "Demo",
          kind = TreeItemKind.SKILL,
          status = "managed",
          external = true,
        ),
      ),
    )
    setContent {
      SkillBillMaterialTheme {
        NavigationPane(
          paneWidth = 300.dp,
          repoPath = "",
          repoStatus = RepoLoadStatus.empty,
          treeItems = listOf(root),
          selectedNodeId = root.children.single().id,
          openEditorTabIds = emptySet(),
          expandedNodeIds = setOf(root.id),
          busyOperation = null,
          policyLabel = "Policy",
          readOnlyModeLabel = "Read-only",
          onRepoPathChanged = {},
          onRepoSelected = {},
          onChooseRepoDirectory = {},
          onNodeSelected = { actions += "select:$it" },
          onNodeOpened = { actions += "open:$it" },
          onNodeExpandedToggled = { actions += "toggle:$it" },
          onMoveSelection = {},
          onMachineSkillsRefreshed = { actions += "refresh" },
        )
      }
    }

    onNodeWithText("Third-Party Skills").assertIsDisplayed()
    onNodeWithText("1").assertIsDisplayed()
    onNodeWithText("Demo").assertIsDisplayed()
    onNodeWithText("managed").assertIsDisplayed()
    onNodeWithContentDescription("Toggle group Third-Party Skills")
      .assertHasClickAction()
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "expanded"))
    onNodeWithText("Demo")
      .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not open in editor tab, managed"))
    onNodeWithContentDescription("Refresh Third-Party Skills").performClick()
    onNodeWithContentDescription("Toggle group Third-Party Skills").performClick()

    assertEquals(listOf("refresh", "toggle:${root.id}"), actions)
  }
}
