package skillbill.install

import kotlin.test.Test
import kotlin.test.assertContains

class InstallLegacySkillNamesTest {
  @Test
  fun `feature task runtime install alias resolves through canonical feature task cleanup names`() {
    val cleanupNames = legacySkillBillCleanupNames(listOf("bill-feature-task"))

    assertContains(cleanupNames, "bill-feature-task-runtime")
    assertContains(cleanupNames, "mdp-feature-task-runtime")
  }
}
