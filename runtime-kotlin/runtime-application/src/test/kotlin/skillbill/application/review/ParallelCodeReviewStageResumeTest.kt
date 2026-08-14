package skillbill.application.review

import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageReached
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParallelCodeReviewStageResumeTest {
  private val pack = sparseReviewPack(
    slug = "kotlin",
    requiredArea = "architecture",
    pathAreas = mapOf("testing" to listOf("src/test/")),
  )

  @Test
  fun `a completed review pass resumes into verification without relaunching lanes`() {
    val recorder = ReviewRecorder()
    val config = delegatedConfig()
    reviewHarness(config, recorder).run(delegatedRequest())
    assertTrue(
      recorder.durableStageBoundaries.any {
        it.stage == ReviewStage.REVIEW && it.reached == ReviewStageReached.REACHED
      },
    )
    val afterFirst = recorder.specialistLaunches.size
    assertTrue(afterFirst > 0)

    val resumed = reviewHarness(config, recorder).run(delegatedRequest())
    assertEquals(
      afterFirst,
      recorder.specialistLaunches.size,
      "A resume after a recorded review boundary must not re-launch specialist lanes.",
    )
    val resume = assertNotNull(resumed.stageResume)
    assertTrue(resume.holdsDurableResult(ReviewStage.REVIEW))
    assertEquals(ReviewStage.VERIFICATION, resume.reentryStage)
  }

  @Test
  fun `a run holding verification results resumes into adjudication with verdicts retained`() {
    val recorder = ReviewRecorder()
    val config = delegatedConfig()
    reviewHarness(config, recorder).run(delegatedRequest())
    val verdict = ReviewFindingVerdict(
      stage = ReviewStage.VERIFICATION,
      findingRef = "F-001",
      claimVerdict = ReviewClaimVerdict.CONFIRMED,
      citations = listOf(ReviewFindingCitation("src/Main.kt", 1)),
      recordedAt = "2026-08-14T08:00:00Z",
    )
    recorder.durableStageBoundaries += ReviewStageBoundary(
      ReviewStage.VERIFICATION,
      ReviewStageReached.REACHED,
      "2026-08-14T08:01:00Z",
    )
    recorder.durableFindingVerdicts += verdict

    val resumed = reviewHarness(config, recorder).run(delegatedRequest())
    val resume = assertNotNull(resumed.stageResume)
    assertEquals(ReviewStage.ADJUDICATION, resume.reentryStage)
    assertEquals(listOf(verdict), recorder.durableFindingVerdicts.toList())
  }

  @Test
  fun `no resolvable spec records a closed none reason and skips stage 2`() {
    val recorder = ReviewRecorder()
    reviewHarness(delegatedConfig(), recorder).run(delegatedRequest())
    assertEquals("not_applicable_scope", recorder.durableSpecProjection?.absenceReason)
    assertTrue(
      recorder.durableStageBoundaries.any {
        it.stage == ReviewStage.ADJUDICATION && it.reached == ReviewStageReached.NOT_REACHED
      },
    )
  }

  private fun delegatedRequest() = harnessRequest(
    reviewRunId = RUN_ID,
    codeReviewMode = CodeReviewExecutionMode.DELEGATED,
  )

  private fun delegatedConfig(): ReviewHarnessConfig {
    val paths = listOf("src/Main.kt", "src/test/AppTest.kt")
    val shas = paths.indices.map { index ->
      if (index == paths.lastIndex) HARNESS_HEAD_REVISION else "c$index"
    }
    return ReviewHarnessConfig(
      manifests = listOf(pack),
      diff = diffForPaths(*paths.toTypedArray()),
      response = { RecordedWorkerResponse() },
      commits = paths.mapIndexed { index, path ->
        RecordedCommit(shas[index], "commit touching $path", diffForPaths(path))
      },
    )
  }

  private companion object {
    const val RUN_ID = "review-run-stage-resume"
  }
}

private val ReviewRecorder.specialistLaunches: List<GoalRunnerSubtaskLaunchRequest>
  get() = parentLaunches.filter { it.skillRunRequest.issueKey == "code-review-parallel" }
