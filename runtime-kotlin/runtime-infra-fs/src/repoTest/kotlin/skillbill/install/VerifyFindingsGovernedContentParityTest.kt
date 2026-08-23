package skillbill.install

import skillbill.testing.repoRootFromTest
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifyFindingsGovernedContentParityTest {
  @Test
  fun `governed feature skills describe one review verify-then-fix-once and advance to validate`() {
    val repoRoot = repoRootFromTest()
    val goal = repoRoot.resolve("skills/bill-feature-goal/content.md").readText()

    assertTrue(goal.contains("runs review once"))
    assertTrue(goal.contains("verify_findings"))
    assertTrue(goal.contains("at most one bounded `implement_fix` round"))
    assertTrue(goal.contains("advances to `validate` even when verified findings remain unfixed"))
    assertFalse(goal.contains("Blocker and Major findings both reopen"))

    assertTrue(goal.contains("titles-only heading catalog"))
    assertTrue(goal.contains("scoped boundary memory"))
    assertTrue(goal.contains("boundary_context_unavailable"))
    assertTrue(goal.contains("skill-bill goal findings --issue-key"))
    assertFalse(goal.contains("delivers whole `history.md`"))
  }
}
