package skillbill.desktop.feature.skillbill.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillBillFrameEditorTabInteractionTest {

  @Test
  fun `tree single click switches only to already open editor tabs`() {
    val openTabs = setOf("skill-one", "generated-wrapper")

    assertTrue(treeSingleClickSwitchesToOpenTab("skill-one", openTabs))
    assertTrue(treeSingleClickSwitchesToOpenTab("generated-wrapper", openTabs))
    assertFalse(treeSingleClickSwitchesToOpenTab("skill-two", openTabs))
  }

  @Test
  fun `tree row semantics distinguish open editor tabs`() {
    assertEquals("Open in editor tab", treeRowStateDescription(open = true))
    assertEquals("Not open in editor tab", treeRowStateDescription(open = false))
  }
}
