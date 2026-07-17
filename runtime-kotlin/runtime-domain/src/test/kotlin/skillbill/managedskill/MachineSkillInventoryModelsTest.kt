package skillbill.managedskill

import skillbill.managedskill.model.normalizeManagedSkillName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MachineSkillInventoryModelsTest {
  @Test
  fun `normalization groups case variants with locale independent safe names`() {
    assertEquals("sample-skill", normalizeManagedSkillName(" Sample-Skill "))
    assertNull(normalizeManagedSkillName("../sample"))
    assertNull(normalizeManagedSkillName("skill_name"))
  }
}
