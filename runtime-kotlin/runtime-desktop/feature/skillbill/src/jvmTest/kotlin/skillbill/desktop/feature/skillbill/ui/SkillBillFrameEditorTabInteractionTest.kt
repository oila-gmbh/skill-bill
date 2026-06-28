package skillbill.desktop.feature.skillbill.ui

import dev.skillbill.designsystem.generated.resources.Res
import dev.skillbill.designsystem.generated.resources.nav_tree_row_not_open_cd
import dev.skillbill.designsystem.generated.resources.nav_tree_row_open_cd
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
  fun `tree row state description maps open state to the correct resource`() {
    assertEquals(Res.string.nav_tree_row_open_cd, treeRowStateDescriptionRes(open = true))
    assertEquals(Res.string.nav_tree_row_not_open_cd, treeRowStateDescriptionRes(open = false))
  }
}
