package skillbill.application.review

import skillbill.review.model.ReviewClaimVerdict
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParallelCodeReviewStandaloneEntryTest {
  @Test
  fun `null agent2 launches one parent lane and assembles a register with stage verdicts`() {
    val pack = sparseReviewPack(
      slug = "kotlin",
      requiredArea = "architecture",
      pathAreas = mapOf("testing" to listOf("src/test/")),
    )
    val recorder = ReviewRecorder()
    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(pack),
        diff = diffForPaths("src/Main.kt"),
        response = { request ->
          when (request.skillRunRequest.issueKey) {
            "code-review-parallel" -> RecordedWorkerResponse(stdout = FINDING)
            ReviewClaimVerificationRunner.ISSUE_KEY -> RecordedWorkerResponse(stdout = CONFIRMED)
            else -> RecordedWorkerResponse()
          }
        },
      ),
      recorder,
    ).run(
      harnessRequest(
        agent2Id = null,
        reviewRunId = "standalone-single-lane",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ),
    )

    assertEquals(
      1,
      recorder.parentLaunches.count { it.skillRunRequest.issueKey == "code-review-parallel" },
    )
    assertTrue(result.mergeResult.findings.isNotEmpty())
    assertTrue(result.mergeResult.formattedOutput.contains("claim_verdict"))
    assertTrue(result.mergeResult.findings.any { it.claimVerdict == ReviewClaimVerdict.CONFIRMED })
  }

  private companion object {
    const val FINDING =
      "- [F-001] Major | High | specialist=bill-kotlin-code-review-architecture | " +
        "path=\"src/Main.kt\" | line=1 | null is unchecked"
    const val CONFIRMED = """{"claim_verdict":"confirmed"}"""
  }
}
