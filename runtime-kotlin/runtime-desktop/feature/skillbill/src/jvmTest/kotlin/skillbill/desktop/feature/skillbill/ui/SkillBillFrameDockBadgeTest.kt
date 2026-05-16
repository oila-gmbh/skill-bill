package skillbill.desktop.feature.skillbill.ui

import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.TreeItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillBillFrameDockBadgeTest {
  @Test
  fun `dock badge count preserves two digit counts and caps large counts`() {
    assertNull(dockBadgeCountText(0))
    assertEquals("9", dockBadgeCountText(9))
    assertEquals("10", dockBadgeCountText(10))
    assertEquals("99+", dockBadgeCountText(100))
  }

  @Test
  fun `selected navigation node expands and collapses from keyboard command`() {
    val treeItems = listOf(
      SkillBillTreeItem(
        id = "skills",
        label = "Skills",
        kind = TreeItemKind.GROUP,
        children = listOf(
          SkillBillTreeItem(id = "skill-one", label = "Skill One", kind = TreeItemKind.SKILL),
        ),
      ),
    )
    val toggled = mutableListOf<String>()

    assertTrue(
      toggleSelectedNavigationExpansion(
        treeItems = treeItems,
        selectedNodeId = "skills",
        expandedNodeIds = emptySet(),
        expand = true,
        onNodeExpandedToggled = toggled::add,
      ),
    )
    assertEquals(listOf("skills"), toggled)

    assertTrue(
      toggleSelectedNavigationExpansion(
        treeItems = treeItems,
        selectedNodeId = "skills",
        expandedNodeIds = setOf("skills"),
        expand = false,
        onNodeExpandedToggled = toggled::add,
      ),
    )
    assertEquals(listOf("skills", "skills"), toggled)
  }

  @Test
  fun `selected navigation expansion ignores leaf nodes`() {
    val treeItems = listOf(
      SkillBillTreeItem(id = "skill-one", label = "Skill One", kind = TreeItemKind.SKILL),
    )
    val toggled = mutableListOf<String>()

    assertFalse(
      toggleSelectedNavigationExpansion(
        treeItems = treeItems,
        selectedNodeId = "skill-one",
        expandedNodeIds = emptySet(),
        expand = true,
        onNodeExpandedToggled = toggled::add,
      ),
    )
    assertEquals(emptyList(), toggled)
  }
}
