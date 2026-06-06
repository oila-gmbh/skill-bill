package skillbill.application

import skillbill.application.model.FeatureImplementFinishedRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureImplementTelemetryValidatorTest {
  @Test
  fun `finished telemetry rejects malformed session ids`() {
    val error = validateFeatureImplementFinished(validFinishedRequest(sessionId = "bad-session"))

    assertEquals("session_id must be a feature-implement session id.", error)
  }

  @Test
  fun `finished telemetry rejects blank child skill names`() {
    val error = validateFeatureImplementFinished(
      validFinishedRequest(childSteps = listOf(mapOf("skill" to ""))),
    )

    assertEquals("child_steps skill must not be blank.", error)
  }

  @Test
  fun `finished telemetry validates known child step payload fields`() {
    assertEquals(
      "child_steps total_findings is required.",
      validateFeatureImplementFinished(validFinishedRequest(childSteps = listOf(mapOf("skill" to "bill-code-review")))),
    )
    assertEquals(
      "child_steps unresolved_findings is required.",
      validateFeatureImplementFinished(
        validFinishedRequest(
          childSteps = listOf(
            mapOf(
              "skill" to "bill-code-review",
              "total_findings" to 1,
              "accepted_findings" to 1,
              "rejected_findings" to 0,
            ),
          ),
        ),
      ),
    )
    assertEquals(
      "child_steps failing check details are required for bill-code-check.",
      validateFeatureImplementFinished(
        validFinishedRequest(
          childSteps = listOf(
            mapOf(
              "skill" to "bill-code-check",
              "result" to "fail",
              "iterations" to 1,
              "initial_failure_count" to 2,
              "final_failure_count" to 1,
            ),
          ),
        ),
      ),
    )
    assertEquals(
      "child_steps pr_created is required.",
      validateFeatureImplementFinished(
        validFinishedRequest(childSteps = listOf(mapOf("skill" to "bill-pr-description"))),
      ),
    )
  }

  private fun validFinishedRequest(
    sessionId: String = "fis-20260606-120000-ab12",
    childSteps: List<Map<String, Any?>> = emptyList(),
  ): FeatureImplementFinishedRequest = FeatureImplementFinishedRequest(
    sessionId = sessionId,
    completionStatus = "completed",
    planCorrectionCount = 0,
    planTaskCount = 1,
    planPhaseCount = 1,
    featureFlagUsed = false,
    filesCreated = 0,
    filesModified = 1,
    tasksCompleted = 1,
    reviewIterations = 1,
    auditResult = "all_pass",
    auditIterations = 1,
    validationResult = "pass",
    boundaryHistoryWritten = false,
    prCreated = false,
    featureFlagPattern = "none",
    boundaryHistoryValue = "none",
    planDeviationNotes = "",
    childSteps = childSteps,
  )
}
