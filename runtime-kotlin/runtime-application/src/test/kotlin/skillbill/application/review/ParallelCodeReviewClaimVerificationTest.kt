package skillbill.application.review

import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageReached
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParallelCodeReviewClaimVerificationTest {
  private val pack = sparseReviewPack(
    slug = "kotlin",
    requiredArea = "architecture",
    pathAreas = mapOf("testing" to listOf("src/test/")),
  )

  @Test
  fun `every merged finding at every severity is verified in inline and delegated`() {
    val modes = listOf(
      CodeReviewExecutionMode.INLINE to inlineConfig(),
      CodeReviewExecutionMode.DELEGATED to delegatedConfig(),
    )
    modes.forEach { (mode, config) ->
      val recorder = ReviewRecorder()
      reviewHarness(config, recorder).run(harnessRequest(reviewRunId = "verify-$mode", codeReviewMode = mode))
      val findings = recorder.durableFindingVerdicts.filter { it.stage == ReviewStage.VERIFICATION }
      assertEquals(2, findings.size, "mode=$mode")
      assertEquals(setOf("F-001", "F-002"), findings.map { it.findingRef }.toSet())
      assertTrue(findings.all { it.claimVerdict == ReviewClaimVerdict.CONFIRMED }, "mode=$mode")
      assertEquals(2, recorder.verificationLaunches.size, "mode=$mode")
      assertTrue(
        recorder.durableStageBoundaries.any {
          it.stage == ReviewStage.VERIFICATION && it.reached == ReviewStageReached.REACHED
        },
        "mode=$mode",
      )
    }
  }

  @Test
  fun `resume after the review pass reenters verification without relaunching lanes or settled findings`() {
    val recorder = ReviewRecorder()
    val first = reviewHarness(delegatedConfig(), recorder).run(delegatedRequest())
    assertEquals(2, recorder.verificationLaunches.size)
    assertTrue(assertNotNull(first.stageResume).holdsDurableResult(ReviewStage.REVIEW))
    val specialistCount = recorder.specialistLaunches.size
    val verificationCount = recorder.verificationLaunches.size
    recorder.durableFindingVerdicts.removeAll { it.findingRef == "F-002" }
    recorder.durableStageBoundaries.removeAll { it.stage == ReviewStage.VERIFICATION }

    val resumed = reviewHarness(delegatedConfig(), recorder).run(delegatedRequest())
    assertEquals(specialistCount, recorder.specialistLaunches.size)
    assertEquals(verificationCount + 1, recorder.verificationLaunches.size)
    val prompt = recorder.verificationLaunches.last().skillRunRequest.promptOverride.orEmpty()
    assertTrue("F-002" in prompt)
    assertFalse("F-001" in prompt)
    val resume = assertNotNull(resumed.stageResume)
    assertTrue(resume.holdsDurableResult(ReviewStage.VERIFICATION))
    assertEquals(ReviewStage.ADJUDICATION, resume.reentryStage)
    assertTrue(recorder.durableIntegrationPass != null)
    assertTrue(recorder.durableStageBoundaries.any { it.stage == ReviewStage.REVIEW })
    assertTrue(recorder.durableStageBoundaries.any { it.stage == ReviewStage.VERIFICATION })
  }

  private fun delegatedRequest() = harnessRequest(
    reviewRunId = RUN_ID,
    codeReviewMode = CodeReviewExecutionMode.DELEGATED,
  )

  private fun delegatedConfig(): ReviewHarnessConfig = verificationConfig()

  private fun inlineConfig(): ReviewHarnessConfig = verificationConfig()

  private fun verificationConfig(): ReviewHarnessConfig {
    val paths = listOf("src/Main.kt", "src/test/AppTest.kt")
    val shas = paths.indices.map { index ->
      if (index == paths.lastIndex) HARNESS_HEAD_REVISION else "c$index"
    }
    return ReviewHarnessConfig(
      manifests = listOf(pack),
      diff = diffForPaths(*paths.toTypedArray()),
      response = { request ->
        when (request.skillRunRequest.issueKey) {
          "code-review-parallel" -> RecordedWorkerResponse(stdout = FINDINGS)
          ReviewClaimVerificationRunner.ISSUE_KEY -> RecordedWorkerResponse(stdout = CONFIRMED)
          else -> RecordedWorkerResponse()
        }
      },
      commits = paths.mapIndexed { index, path ->
        RecordedCommit(shas[index], "commit touching $path", diffForPaths(path))
      },
    )
  }

  private companion object {
    const val RUN_ID = "review-run-claim-verify"
    const val FINDINGS =
      "- [F-001] Major | High | specialist=bill-kotlin-code-review-architecture | " +
        "path=\"src/Main.kt\" | line=1 | null is unchecked\n" +
        "- [F-002] Nit | Low | specialist=bill-kotlin-code-review-testing | " +
        "path=\"src/test/AppTest.kt\" | line=1 | test name is vague"
    const val CONFIRMED = """{"claim_verdict":"confirmed"}"""
  }
}

private val ReviewRecorder.verificationLaunches: List<GoalRunnerSubtaskLaunchRequest>
  get() = parentLaunches.filter { it.skillRunRequest.issueKey == ReviewClaimVerificationRunner.ISSUE_KEY }

private val ReviewRecorder.specialistLaunches: List<GoalRunnerSubtaskLaunchRequest>
  get() = parentLaunches.filter { it.skillRunRequest.issueKey == "code-review-parallel" }
