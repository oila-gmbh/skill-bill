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
    val runtime = repoRoot.resolve("skills/bill-feature-task-runtime/content.md").readText()
    val goal = repoRoot.resolve("skills/bill-feature-goal/content.md").readText()

    assertTrue(runtime.contains("Review runs exactly once"))
    assertTrue(runtime.contains("verify_findings"))
    assertTrue(runtime.contains("may trigger at most one `implement_fix` round"))
    assertTrue(runtime.contains("advances to `validate` even when verified findings remain"))
    assertTrue(runtime.contains("without a second review or verification pass"))
    assertFalse(runtime.contains("Blocker and Major findings both reopen"))

    assertTrue(goal.contains("runs review once"))
    assertTrue(goal.contains("verify_findings"))
    assertTrue(goal.contains("at most one bounded `implement_fix` round"))
    assertTrue(goal.contains("advances to `validate` even when verified findings remain unfixed"))
    assertFalse(goal.contains("Blocker and Major findings both reopen"))

    assertTrue(runtime.contains("titles-only heading catalog"))
    assertTrue(runtime.contains("scoped boundary memory"))
    assertTrue(runtime.contains("boundary_context_unavailable"))
    assertTrue(runtime.contains("Whole"))
    assertTrue(runtime.contains("`history.md` or `decisions.md` files and unselected entry bodies never belong"))
    assertFalse(runtime.contains("delivers whole `history.md`"))

    assertTrue(goal.contains("titles-only heading catalog"))
    assertTrue(goal.contains("scoped boundary memory"))
    assertTrue(goal.contains("boundary_context_unavailable"))
    assertTrue(goal.contains("skill-bill goal findings --issue-key"))
    assertFalse(goal.contains("delivers whole `history.md`"))
  }
}
