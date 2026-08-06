package skillbill.application.review

import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.context.model.ReviewIntegrationTerminalOutcome
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end proof over the production composition that a delegated review runs exactly one bounded
 * integration pass after its specialist lanes finish, that the pass does not scale with commit
 * count, and that specialist completion and integration completion are distinct durable boundaries.
 */
class ParallelCodeReviewIntegrationPassTest {
  private val pack = sparseReviewPack(
    slug = "kotlin",
    requiredArea = "architecture",
    pathAreas = mapOf(
      "persistence" to listOf("src/db/"),
      "security" to listOf("src/api/", "src/contract/"),
      "testing" to listOf("src/test/"),
      "ui" to listOf("src/ui/"),
    ),
  )

  private val sixCommitPaths = listOf(
    "src/ui/View.kt",
    "src/db/Repo.kt",
    "src/api/Auth.kt",
    "src/test/AppTest.kt",
    "src/contract/Api.kt",
    "src/contract/ApiV2.kt",
  )

  @Test fun `one bounded integration pass runs after the lanes and re-launches no specialist rubric`() {
    val recorder = ReviewRecorder()

    reviewHarness(delegatedConfig(sixCommitPaths), recorder).run(delegatedRequest())

    assertEquals(
      1,
      recorder.integrationLaunches.size,
      "A delegated review runs exactly one integration pass, not one per commit or per lane.",
    )
    val prompt = assertNotNull(recorder.integrationLaunches.single().skillRunRequest.promptOverride)
    assertFalse(
      prompt.contains("governed rubric body for"),
      "The integration pass must not re-launch a specialist rubric.",
    )
    assertTrue(prompt.contains("do not re-run their rubrics"))
    assertTrue(prompt.contains("Specialist lane summaries"))
  }

  @Test fun `specialist worker count equals lane count and does not move with commit count`() {
    val three = ReviewRecorder()
    val six = ReviewRecorder()

    reviewHarness(delegatedConfig(sixCommitPaths.take(3)), three).run(delegatedRequest())
    reviewHarness(delegatedConfig(sixCommitPaths), six).run(delegatedRequest())

    assertEquals(2, three.specialistLaunches.size)
    assertEquals(
      three.specialistLaunches.size,
      six.specialistLaunches.size,
      "Specialist worker count is the selected lane count; doubling the commits must not move it.",
    )
    assertEquals(1, three.integrationLaunches.size)
    assertEquals(1, six.integrationLaunches.size)
  }

  @Test fun `a cross-commit integration finding is reported with the commits it relates`() {
    val recorder = ReviewRecorder()

    val result = reviewHarness(
      delegatedConfig(sixCommitPaths) { request ->
        if (request.skillRunRequest.issueKey == INTEGRATION_ISSUE_KEY) {
          RecordedWorkerResponse(
            stdout = "- [F-001] Major | High | commits=c4,head-revision | " +
              "path=\"src/contract/ApiV2.kt\" | line=1 | contract drift across commits",
          )
        } else {
          RecordedWorkerResponse()
        }
      },
      recorder,
    ).run(delegatedRequest())

    val integration = assertNotNull(result.integration)
    assertEquals(ReviewIntegrationTerminalOutcome.COMPLETED, integration.terminalOutcome)
    val finding = integration.findings.single()
    assertEquals(listOf("c4", "head-revision"), finding.commitShas)
    assertEquals(ReviewIntegrationPassRunner.INTEGRATION_LANE, finding.specialistSkillName)
    assertTrue(result.mergeResult.formattedOutput.contains("contract drift across commits"))
  }

  // AC-001: an unusable cited commit costs its own finding, never the whole finished review.
  @Test fun `a finding naming an unknown commit is dropped instead of failing the finished review`() {
    val recorder = ReviewRecorder()

    val result = reviewHarness(
      delegatedConfig(sixCommitPaths) { request ->
        if (request.skillRunRequest.issueKey == INTEGRATION_ISSUE_KEY) {
          RecordedWorkerResponse(
            stdout = "- [F-001] Major | High | commits=c4,deadbeefdeadbeef | " +
              "path=\"src/contract/ApiV2.kt\" | line=1 | cites a commit that does not exist\n" +
              "- [F-002] Major | High | commits=c4,head-revision | " +
              "path=\"src/contract/Api.kt\" | line=1 | contract drift across commits",
          )
        } else {
          RecordedWorkerResponse()
        }
      },
      recorder,
    ).run(delegatedRequest())

    val integration = assertNotNull(result.integration)
    assertEquals(ReviewIntegrationTerminalOutcome.COMPLETED, integration.terminalOutcome)
    assertEquals(
      listOf(listOf("c4", "head-revision")),
      integration.findings.map { it.commitShas },
      "The hallucinated-commit finding drops; the usable cross-commit finding survives.",
    )
    assertTrue(result.mergeResult.formattedOutput.contains("contract drift across commits"))
  }

  @Test fun `a single-commit sequence skips the integration pass with a stated reason`() {
    val recorder = ReviewRecorder()

    val result = reviewHarness(delegatedConfig(sixCommitPaths.take(1)), recorder).run(delegatedRequest())

    val integration = assertNotNull(result.integration)
    assertEquals(ReviewIntegrationTerminalOutcome.SKIPPED_NOT_APPLICABLE, integration.terminalOutcome)
    assertTrue(integration.skipReason.orEmpty().contains("single commit"))
    assertTrue(recorder.integrationLaunches.isEmpty())
    assertNotNull(result.coverage?.integrationNotApplicableReason)
  }

  @Test fun `an inline review reports commit-focused sequencing as not applicable`() {
    val recorder = ReviewRecorder()

    val result = reviewHarness(delegatedConfig(sixCommitPaths), recorder)
      .run(delegatedRequest(mode = CodeReviewExecutionMode.INLINE))

    val integration = assertNotNull(result.integration)
    assertEquals(ReviewIntegrationTerminalOutcome.SKIPPED_NOT_APPLICABLE, integration.terminalOutcome)
    assertTrue(integration.skipReason.orEmpty().contains("inline"))
    assertTrue(recorder.integrationLaunches.isEmpty())
    assertTrue(
      assertNotNull(result.coverage).render().contains("not applicable"),
      "Inline runs must say commit-focused sequencing did not apply, not stay silent about it.",
    )
  }

  @Test fun `a crash between specialist completion and integration resumes into the integration pass alone`() {
    val recorder = ReviewRecorder()
    // First attempt: every lane completes, then the integration launch dies before reporting.
    reviewHarness(
      delegatedConfig(sixCommitPaths) { request ->
        RecordedWorkerResponse(spawnFailed = request.skillRunRequest.issueKey == INTEGRATION_ISSUE_KEY)
      },
      recorder,
    ).run(delegatedRequest(reviewRunId = RUN_ID))

    assertEquals(2, recorder.specialistLaunches.size)
    assertEquals(
      ReviewIntegrationTerminalOutcome.SPAWN_FAILURE.wireValue,
      assertNotNull(recorder.durableIntegrationPass).terminalOutcome,
    )

    val resumed = reviewHarness(delegatedConfig(sixCommitPaths), recorder).run(delegatedRequest(reviewRunId = RUN_ID))

    assertEquals(
      2,
      recorder.specialistLaunches.size,
      "A lane holding a durable complete result must not be re-run by the resume.",
    )
    assertEquals(2, recorder.integrationLaunches.size, "The resume re-runs the integration pass, and only it.")
    assertEquals(ReviewIntegrationTerminalOutcome.COMPLETED, assertNotNull(resumed.integration).terminalOutcome)
  }

  @Test fun `a resume holding a durable integration result re-runs neither boundary`() {
    val recorder = ReviewRecorder()
    reviewHarness(delegatedConfig(sixCommitPaths), recorder).run(delegatedRequest(reviewRunId = RUN_ID))
    val afterFirst = recorder.parentLaunches.size

    reviewHarness(delegatedConfig(sixCommitPaths), recorder).run(delegatedRequest(reviewRunId = RUN_ID))

    assertEquals(afterFirst, recorder.parentLaunches.size, "A settled review re-launches nothing on resume.")
  }

  @Test fun `an integration pass that times out is not a durable boundary`() {
    val recorder = ReviewRecorder()

    val result = reviewHarness(
      delegatedConfig(sixCommitPaths) { request ->
        RecordedWorkerResponse(timedOut = request.skillRunRequest.issueKey == INTEGRATION_ISSUE_KEY)
      },
      recorder,
    ).run(delegatedRequest(reviewRunId = RUN_ID))

    val integration = assertNotNull(result.integration)
    assertEquals(ReviewIntegrationTerminalOutcome.TIMEOUT, integration.terminalOutcome)
    assertFalse(integration.durable)
    assertTrue(integration.findings.isEmpty())
  }

  private fun delegatedRequest(
    mode: CodeReviewExecutionMode = CodeReviewExecutionMode.DELEGATED,
    reviewRunId: String? = null,
  ) = harnessRequest(reviewRunId = reviewRunId, codeReviewMode = mode)

  private fun delegatedConfig(
    paths: List<String>,
    response: (GoalRunnerSubtaskLaunchRequest) -> RecordedWorkerResponse = { RecordedWorkerResponse() },
  ): ReviewHarnessConfig {
    // The last commit of a range is the head revision, exactly as a real base..head walk resolves.
    val shas = paths.indices.map { index ->
      if (index == paths.lastIndex) HARNESS_HEAD_REVISION else "c$index"
    }
    return ReviewHarnessConfig(
      manifests = listOf(pack),
      diff = diffForPaths(*paths.toTypedArray()),
      response = response,
      commits = paths.mapIndexed { index, path ->
        RecordedCommit(shas[index], "commit touching $path", diffForPaths(path))
      },
    )
  }

  private companion object {
    const val RUN_ID = "review-run-integration"
    const val INTEGRATION_ISSUE_KEY = "code-review-integration"
  }
}

/** Integration-pass launches, split from the specialist parent launches by their issue key. */
private val ReviewRecorder.integrationLaunches: List<GoalRunnerSubtaskLaunchRequest>
  get() = parentLaunches.filter { it.skillRunRequest.issueKey == "code-review-integration" }

private val ReviewRecorder.specialistLaunches: List<GoalRunnerSubtaskLaunchRequest>
  get() = parentLaunches.filter { it.skillRunRequest.issueKey == "code-review-parallel" }
