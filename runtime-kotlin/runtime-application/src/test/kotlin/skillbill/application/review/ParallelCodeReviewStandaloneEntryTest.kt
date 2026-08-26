package skillbill.application.review

import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParallelCodeReviewStandaloneEntryTest {
  @Test
  fun `single parent lane keeps parent prose without a findings register`() {
    val pack = sparseReviewPack(
      slug = "kotlin",
      requiredArea = "architecture",
      pathAreas = mapOf("testing" to listOf("src/test/")),
    )
    val recorder = ReviewRecorder()
    val prose = "Null is unchecked in Main.\nverdict: changes_requested"
    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(pack),
        diff = diffForPaths("src/Main.kt"),
        response = { request ->
          when (request.skillRunRequest.issueKey) {
            "code-review" -> RecordedWorkerResponse(stdout = prose)
            else -> RecordedWorkerResponse()
          }
        },
      ),
      recorder,
    ).run(
      harnessRequest(
        reviewRunId = "standalone-single-lane",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ),
    )

    assertEquals(
      1,
      recorder.parentLaunches.count { it.skillRunRequest.issueKey == "code-review" },
    )
    assertTrue(result.mergeResult.findings.isEmpty())
    assertEquals(prose, result.mergeResult.formattedOutput)
    result.accountingSummary?.lanes?.let { lanes ->
      assertTrue(lanes.none { it.lane == "parallel-agent-2" })
    }
  }
}
