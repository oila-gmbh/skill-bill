package skillbill.application.review

import skillbill.application.model.ParallelReviewScope
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.workflow.model.CodeReviewExecutionMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Cross-seam regressions the surviving inline parallel review must keep holding. */
class ParallelCodeReviewRegressionTest {
  private val areas = listOf("architecture", "security", "testing")
  private val agentsBody = "AGENTS_BODY_SENTINEL ".repeat(400)
  private val parentBriefing = "PARENT_BRIEFING_SENTINEL ".repeat(400)

  @Test fun `long parent briefing and AGENTS bodies never reach a lane or its accounting`() {
    val recorder = ReviewRecorder()
    val repoRoot = Files.createTempDirectory("review-context-isolation")
    Files.writeString(repoRoot.resolve("AGENTS.md"), agentsBody)
    Files.writeString(repoRoot.resolve("parent-briefing.md"), parentBriefing)
    val runner = reviewHarness(config(), recorder)

    val result = runner.run(harnessRequest(repoRoot = repoRoot))

    recorder.parentPrompts.forEach { prompt ->
      assertFalse(prompt.contains("AGENTS_BODY_SENTINEL"), "A review lane saw a project-guidance body.")
      assertFalse(prompt.contains("PARENT_BRIEFING_SENTINEL"), "A review lane saw the parent briefing body.")
    }
    val summary = assertNotNull(result.accountingSummary)
    val serialized = summary.toBoundedPayload().toString()
    assertFalse(serialized.contains("AGENTS_BODY_SENTINEL"))
    assertFalse(serialized.contains("PARENT_BRIEFING_SENTINEL"))
    assertTrue(summary.aggregateCounters.launchBytes > 0, "The isolated launch projection remains measured.")
  }

  @Test fun `overlapping lane ownership assigns each hunk once and never doubles usage`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      config {
        RecordedWorkerResponse(
          stdout = finding("src/Repo.kt", specialist = "bill-kotlin-code-review-architecture"),
          usage = ProviderTokenUsage(500, 100, 50, 10, 550),
        )
      },
      recorder,
    )

    val result = runner.run(harnessRequest())
    val summary = assertNotNull(result.accountingSummary)

    val lanes = summary.lanes.filter { it.children.isEmpty() }
    assertEquals(
      lanes.size,
      lanes.map { it.assignmentDigest }.distinct().size,
      "Every owned lane carries its own assignment digest.",
    )
    assertEquals(lanes.size * 500L, summary.aggregateDirectUsage.inputTokens)
    assertEquals(
      summary.aggregateDirectUsage.inputTokens,
      summary.aggregateInclusiveUsage.inputTokens,
      "Overlapping ownership must not fold a session's usage in twice.",
    )
    assertTrue(result.mergeResult.formattedOutput.isNotBlank())
  }

  @Test fun `a failed lane reports its own failure without taking the other lane down`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      config { request ->
        if (request.invokedAgentId == "codex") {
          RecordedWorkerResponse(exitStatus = 1, stdout = "")
        } else {
          RecordedWorkerResponse()
        }
      },
      recorder,
    )

    val result = runner.run(harnessRequest())

    assertFalse(result.lane1.success)
    assertNotNull(result.lane1.failureReason)
    assertTrue(result.lane2.success)
  }

  @Test fun `each lane accounts its own launch bytes and terminal outcome`() {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(config { RecordedWorkerResponse(usage = ProviderTokenUsage(120, 20, 8)) }, recorder)

    val summary = assertNotNull(runner.run(harnessRequest()).accountingSummary)

    val lanes = summary.lanes.filter { it.children.isEmpty() }
    assertEquals(1, lanes.size, "No lane is relabeled or duplicated in the accounting tree.")
    assertEquals(lanes.size, lanes.map { it.lane }.distinct().size)
    lanes.forEach { lane ->
      assertTrue(lane.counters.launchBytes > 0)
      assertEquals("completed", lane.terminalOutcome)
    }
  }

  @Test fun `non-commit scopes keep their output and report commit-focused sequencing as not applicable`() {
    val branchResult = reviewHarness(config(), ReviewRecorder()).run(harnessRequest())

    listOf(
      ParallelReviewScope.STAGED,
      ParallelReviewScope.UNSTAGED,
      ParallelReviewScope.BRANCH,
    ).forEach { scope ->
      val recorder = ReviewRecorder()

      val result = reviewHarness(config(), recorder).run(
        harnessRequest(scope = scope, codeReviewMode = CodeReviewExecutionMode.DELEGATED),
      )

      assertEquals(
        branchResult.mergeResult.formattedOutput,
        result.mergeResult.formattedOutput,
        "Scope $scope must keep its existing merged output.",
      )
      val coverage = assertNotNull(result.coverage)
      assertNotNull(
        coverage.integrationNotApplicableReason,
        "Scope $scope has no commit sequence, so it must say so rather than stay silent.",
      )
      assertTrue(coverage.render().contains("not applicable"))
      assertTrue(
        recorder.parentLaunches.none { it.skillRunRequest.issueKey == "code-review-integration" },
        "A scope with no commit sequence launches no integration pass.",
      )
    }
  }

  private fun config(
    response: (GoalRunnerSubtaskLaunchRequest) -> RecordedWorkerResponse = { RecordedWorkerResponse() },
  ) = ReviewHarnessConfig(
    manifests = listOf(reviewPack("kotlin", areas, routingSignals = listOf("*.kt"))),
    diff = diffForPaths("src/Repo.kt"),
    response = response,
  )
}
