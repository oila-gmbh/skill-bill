package skillbill.desktop.core.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillBillColorTest {
  @Test
  fun `design system uses README hero palette`() {
    assertEquals(Color(0xFF0B0B0D), SkillBillInk)
    assertEquals(Color(0xFF121216), SkillBillPanel)
    assertEquals(Color(0xFF2A2A31), SkillBillLine)
    assertEquals(Color(0xFFF4C430), SkillBillYellow)
    assertEquals(Color(0xFFB7B1A0), SkillBillMuted)
    assertEquals(Color(0xFF60D394), SkillBillGreen)
  }
}
