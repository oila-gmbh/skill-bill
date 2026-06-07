package skillbill.install

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class InstallLegacySkillNamesTest {
  @Test
  fun `feature task runtime is not a legacy alias for canonical feature task`() {
    val cleanupNames = legacySkillBillCleanupNames(listOf("bill-feature-task"))

    assertFalse("bill-feature-task-runtime" in cleanupNames)
    assertFalse("mdp-feature-task-runtime" in cleanupNames)
  }

  @Test
  fun `feature task runtime current skill still has mdp cleanup name`() {
    val cleanupNames = legacySkillBillCleanupNames(listOf("bill-feature-task-runtime"))

    assertFalse("bill-feature-task-runtime" in cleanupNames)
    assertContains(cleanupNames, "mdp-feature-task-runtime")
  }
}
