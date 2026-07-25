package skillbill.application

import skillbill.application.model.ParallelCodeReviewRequest
import skillbill.application.model.ParallelReviewScope
import skillbill.workflow.model.CodeReviewExecutionMode
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * SKILL-142 AC-007: when `parallel:<agent>` is active both lanes share the resolved tier, and a
 * light lane paired with a full-depth lane is rejected before either lane starts.
 */
class ParallelCodeReviewSharedTierTest {
  private fun request(
    codeReviewMode: CodeReviewExecutionMode,
    resolvedTier: CodeReviewExecutionMode?,
  ) = ParallelCodeReviewRequest(
    agent1Id = "claude",
    agent2Id = "codex",
    scope = ParallelReviewScope.BRANCH,
    repoRoot = Path.of("."),
    timeout = null,
    codeReviewMode = codeReviewMode,
    resolvedTier = resolvedTier,
  )

  @Test
  fun `lane 2 receives the same tier lane 1 resolved to`() {
    val resolved = request(CodeReviewExecutionMode.AUTO, CodeReviewExecutionMode.INLINE)
    assertEquals(CodeReviewExecutionMode.INLINE, resolved.lane2Tier)

    val delegated = request(CodeReviewExecutionMode.AUTO, CodeReviewExecutionMode.DELEGATED)
    assertEquals(CodeReviewExecutionMode.DELEGATED, delegated.lane2Tier)
  }

  @Test
  fun `a light lane paired with a full-depth lane is rejected before either lane starts`() {
    assertFailsWith<IllegalArgumentException> {
      request(CodeReviewExecutionMode.DELEGATED, CodeReviewExecutionMode.INLINE)
    }
    assertFailsWith<IllegalArgumentException> {
      request(CodeReviewExecutionMode.INLINE, CodeReviewExecutionMode.DELEGATED)
    }
  }

  @Test
  fun `an explicit mode matching the resolved tier is accepted`() {
    assertEquals(
      CodeReviewExecutionMode.INLINE,
      request(CodeReviewExecutionMode.INLINE, CodeReviewExecutionMode.INLINE).lane2Tier,
    )
  }

  @Test
  fun `auto is never itself a resolved lane tier`() {
    assertFailsWith<IllegalArgumentException> {
      request(CodeReviewExecutionMode.AUTO, CodeReviewExecutionMode.AUTO)
    }
  }
}
