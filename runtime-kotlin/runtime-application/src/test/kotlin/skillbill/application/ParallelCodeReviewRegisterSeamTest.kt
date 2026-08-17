package skillbill.application

import skillbill.application.model.ParallelReviewScope
import skillbill.application.review.REGISTER_ABSENT_TERMINAL_STATUS
import skillbill.application.review.parseLaneRegisterSeam
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.review.context.model.ReviewRegisterParseSeamException
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParallelCodeReviewRegisterSeamTest {
  @Test
  fun `a zero-exit lane without a findings register fails instead of reporting clean coverage`() {
    val blocked = """
      This session has no worker-launch capability and no bound evidence broker.
      Per the contract I am not running the review inline as a single prompt, and I am not
      emitting a findings register — zero [F-XXX] lines here means not executed, not clean.
    """.trimIndent()
    val launcher = GoalRunnerSubtaskLauncher { request ->
      AgentRunLaunchFacts(
        agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
        exitStatus = 0,
        stdout = blocked,
        stderr = "",
        timedOut = false,
        spawnFailed = false,
      )
    }
    val runner = runner(launcher, diffResolver = RecordingDiffResolver(default = diffFor("A.kt")))

    val result = runner.run(
      baseRequest(scope = ParallelReviewScope.STAGED).copy(codeReviewMode = CodeReviewExecutionMode.DELEGATED),
    )

    assertFalse(result.lane1.success)
    assertEquals(REGISTER_ABSENT_TERMINAL_STATUS, result.lane1.accounting?.terminalStatus)
    val reason = result.lane1.failureReason.orEmpty()
    assertTrue(reason.contains("did not emit a findings register"))
    assertTrue(reason.contains("${blocked.toByteArray().size} bytes"), "no-candidates state reports byte count")
    assertTrue(
      reason.contains("This session has no worker-launch capability"),
      "no-candidates state carries an excerpt",
    )
    assertTrue(result.mergeResult.findings.isEmpty())
    val coverage = assertNotNull(result.coverage)
    assertFalse(coverage.isCleanCoverage)
    assertTrue(coverage.render().contains("Coverage: NOT clean"))
  }

  @Test
  fun `a near-miss register line is reported as format drift rather than a review that did not execute`() {
    val drifted = "[F-1] Major | High | a.kt:3 | x"
    val runner = runner(
      stdoutLauncher(drifted),
      diffResolver = RecordingDiffResolver(default = diffFor("A.kt")),
    )

    val result = runner.run(
      baseRequest(scope = ParallelReviewScope.STAGED).copy(codeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertFalse(result.lane1.success)
    assertEquals(REGISTER_ABSENT_TERMINAL_STATUS, result.lane1.accounting?.terminalStatus)
    val reason = assertNotNull(result.lane1.failureReason)
    assertTrue(reason.contains("format drift"), "drift state must be named: $reason")
    assertTrue(reason.contains(drifted), "the offending line must be named: $reason")
    assertTrue(reason.contains("unmatched_candidate_line"), "the typed rejection reason must be given: $reason")
    assertFalse(reason.contains("did not execute"), "a drifted register did execute the review: $reason")
  }

  @Test
  fun `an oversized lane body is bounded by the excerpt cap instead of leaking into the register`() {
    val body = "prose ".repeat(2_000)
    val runner = runner(
      stdoutLauncher(body),
      diffResolver = RecordingDiffResolver(default = diffFor("A.kt")),
    )

    val result = runner.run(
      baseRequest(scope = ParallelReviewScope.STAGED).copy(codeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    val reason = assertNotNull(result.lane1.failureReason)
    assertFalse(reason.contains(body.trim()), "the full lane output must never reach the failure reason")
    assertTrue(reason.length < body.length, "the excerpt must be bounded well below the lane body")
  }

  @Test
  fun `an admissible register produces findings with no absence verdict and no register-absent accounting`() {
    val runner = runner(
      stdoutLauncher("[F-001] Major | High | path=\"A.kt\" | line=1 | admissible"),
      diffResolver = RecordingDiffResolver(default = diffFor("A.kt")),
    )

    val result = runner.run(
      baseRequest(scope = ParallelReviewScope.STAGED).copy(codeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertTrue(result.lane1.success)
    assertNull(result.lane1.failureReason)
    assertNotEquals(REGISTER_ABSENT_TERMINAL_STATUS, result.lane1.accounting?.terminalStatus)
    assertTrue(result.mergeResult.findings.isNotEmpty())
  }

  @Test
  fun `a throwing parse at the register seam escapes as a typed seam error instead of an empty register`() {
    val laneBody = "lane body line ".repeat(200)
    val thrown = assertFailsWith<ReviewRegisterParseSeamException> {
      parseLaneRegisterSeam(laneBody, lane = "lane-1") { error(laneBody) }
    }

    assertEquals("attributeInlineFindings", thrown.seam)
    assertEquals("lane-1", thrown.lane)
    val message = thrown.message.orEmpty()
    assertFalse(message.contains(laneBody), "the seam error must not carry the full lane output body")
    assertTrue(
      message.length < laneBody.length,
      "the cause detail must be bounded even when the parser echoes the lane body: ${message.length}",
    )
  }

  @Test
  fun `a partially drifted register keeps its admitted findings and still reports the dropped candidate`() {
    val drifted = "[F-2] Major | High | a.kt:3 | dropped"
    val runner = runner(
      stdoutLauncher("[F-001] Major | High | path=\"A.kt\" | line=1 | admissible\n$drifted"),
      diffResolver = RecordingDiffResolver(default = diffFor("A.kt")),
    )

    val result = runner.run(
      baseRequest(scope = ParallelReviewScope.STAGED).copy(codeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertTrue(result.lane1.success)
    assertNull(result.lane1.failureReason)
    assertEquals(1, result.mergeResult.findings.size)
    val diagnostic = assertNotNull(
      result.lane1.droppedCandidateDiagnostic,
      "a silently dropped candidate line must still be reported",
    )
    assertTrue(diagnostic.contains(drifted), "the dropped line must be named: $diagnostic")
    assertTrue(diagnostic.contains("unmatched_candidate_line"), "the rejection reason must be given: $diagnostic")
  }

  @Test
  fun `a parser fault at the register seam escapes runner run as a typed error instead of an absence verdict`() {
    val runner = createRunner(
      stdoutLauncher("prose with no register"),
      RunnerFixtureConfig(
        diffResolver = RecordingDiffResolver(default = diffFor("A.kt")),
        registerParse = { error("parser exploded") },
      ),
    )

    val thrown = assertFailsWith<ReviewRegisterParseSeamException> {
      runner.run(
        baseRequest(scope = ParallelReviewScope.STAGED).copy(codeReviewMode = CodeReviewExecutionMode.INLINE),
      )
    }

    assertEquals("attributeInlineFindings", thrown.seam)
    assertTrue(thrown.message.orEmpty().contains(thrown.lane), "the failing lane must be named: ${thrown.message}")
  }

  private fun stdoutLauncher(stdout: String) = GoalRunnerSubtaskLauncher { request ->
    AgentRunLaunchFacts(
      agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
      exitStatus = 0,
      stdout = stdout,
      stderr = "",
      timedOut = false,
      spawnFailed = false,
    )
  }
}
