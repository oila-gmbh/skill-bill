package skillbill.cli

import skillbill.cli.codereview.parseReviewPrelaunchExpansion
import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.review.context.model.ReviewEvidenceLimits
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CliCodeReviewDriverRuntimeTest {
  @Test
  fun `expansion parser preserves discovered agent skill lanes and aliases`() {
    for (lane in listOf("codex:bill-kotlin-code-review-ui", "bill-kotlin-code-review-ui", "parallel-code-review")) {
      val request = parseReviewPrelaunchExpansion("$lane:src/View.kt=caller context")
      assertEquals(lane, request.lane)
      assertEquals("src/View.kt", request.path)
      assertEquals("caller context", request.reachabilityReason)
    }
    assertFailsWith<InvalidReviewContextSchemaError> {
      parseReviewPrelaunchExpansion(
        "codex:bill-kotlin-code-review-ui:src/View.kt=" + "x".repeat(ReviewEvidenceLimits.FIELD_CHARACTERS + 1),
      )
    }
  }

  @Test
  fun `code-review rejects removed agent2 option`() {
    val result = CliRuntime.run(
      listOf("code-review", "--agent2", "claude"),
      CliRuntimeContext(),
    )

    assertEquals(1, result.exitCode)
    assertContains(result.stdout, "agent2")
  }

  @Test
  fun `code-review rejects removed model2 option`() {
    val result = CliRuntime.run(
      listOf("code-review", "--model2", "gpt-4"),
      CliRuntimeContext(),
    )

    assertEquals(1, result.exitCode)
    assertContains(result.stdout, "model2")
  }
}
