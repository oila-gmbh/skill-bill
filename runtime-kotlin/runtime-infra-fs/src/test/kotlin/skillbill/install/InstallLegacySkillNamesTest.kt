package skillbill.install

import skillbill.install.support.legacySkillBillCleanupNames
import kotlin.test.Test
import kotlin.test.assertContains

class InstallLegacySkillNamesTest {
  @Test
  fun `current feature skill still has mdp cleanup name`() {
    val cleanupNames = legacySkillBillCleanupNames(listOf("bill-feature"))

    assertContains(cleanupNames, "mdp-feature")
  }
}
