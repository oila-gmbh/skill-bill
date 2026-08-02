package skillbill.application.review

import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.review.model.ReviewProcessOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewOutputClassificationTest {
  @Test fun `only a normal zero-exit envelope can be admitted or repaired`() {
    val valid = classifyReviewOutput(facts(exitStatus = 0), resultEnvelopeValid = true)
    assertEquals(ReviewProcessOutcome.ZERO_EXIT, valid.processOutcome)
    assertEquals(ReviewOutputAdmission.SUCCESS, valid.admission)

    val repairable = classifyReviewOutput(facts(exitStatus = 0), resultEnvelopeValid = false)
    assertEquals(ReviewOutputAdmission.SCHEMA_REPAIR_ELIGIBLE, repairable.admission)
  }

  @Test fun `process and lifecycle failures are never admitted through schema repair`() {
    listOf(
      facts(exitStatus = null, timedOut = true),
      facts(exitStatus = null, interrupted = true),
      facts(exitStatus = 7),
      facts(exitStatus = null, spawnFailed = true),
      facts(exitStatus = 0, stdoutTruncated = true),
    ).forEach { launchFacts ->
      val classification = classifyReviewOutput(launchFacts, resultEnvelopeValid = false)
      assertEquals(ReviewOutputAdmission.REJECTED, classification.admission)
      require(classification.processOutcome != ReviewProcessOutcome.ZERO_EXIT)
    }
  }

  private fun facts(
    exitStatus: Int?,
    timedOut: Boolean = false,
    interrupted: Boolean = false,
    spawnFailed: Boolean = false,
    stdoutTruncated: Boolean = false,
  ) = AgentRunLaunchFacts(
    agent = InstallAgent.CODEX,
    exitStatus = exitStatus,
    stdout = "",
    stderr = "",
    timedOut = timedOut,
    interrupted = interrupted,
    spawnFailed = spawnFailed,
    stdoutTruncated = stdoutTruncated,
  )
}
