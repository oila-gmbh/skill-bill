package skillbill.application.review

import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.review.model.ReviewProcessOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewOutputClassificationTest {
  @Test fun `only a normal zero-exit envelope can be admitted or repaired`() {
    val valid = classifyReviewOutput(
      facts(FactsFixture(exitStatus = 0, stdout = "NO_FINDINGS")),
      resultEnvelopeValid = true,
    )
    assertEquals(ReviewProcessOutcome.ZERO_EXIT, valid.processOutcome)
    assertEquals(ReviewOutputAdmission.SUCCESS, valid.admission)

    val repairable = classifyReviewOutput(
      facts(FactsFixture(exitStatus = 0, stdout = "not-an-envelope")),
      resultEnvelopeValid = false,
    )
    assertEquals(ReviewOutputAdmission.SCHEMA_REPAIR_ELIGIBLE, repairable.admission)
  }

  @Test fun `empty zero-exit output is a missing result and is not repairable`() {
    val classification = classifyReviewOutput(facts(FactsFixture(exitStatus = 0)), resultEnvelopeValid = false)
    assertEquals(ReviewOutputAdmission.REJECTED, classification.admission)
  }

  @Test fun `process and lifecycle failures are never admitted through schema repair`() {
    listOf(
      facts(FactsFixture(timedOut = true)),
      facts(FactsFixture(interrupted = true)),
      facts(FactsFixture(exitStatus = 7)),
      facts(FactsFixture(spawnFailed = true)),
      facts(FactsFixture(exitStatus = 0, stdoutTruncated = true)),
    ).forEach { launchFacts ->
      val classification = classifyReviewOutput(launchFacts, resultEnvelopeValid = false)
      assertEquals(ReviewOutputAdmission.REJECTED, classification.admission)
      require(classification.processOutcome != ReviewProcessOutcome.ZERO_EXIT)
    }
  }

  private data class FactsFixture(
    val exitStatus: Int? = null,
    val stdout: String = "",
    val timedOut: Boolean = false,
    val interrupted: Boolean = false,
    val spawnFailed: Boolean = false,
    val stdoutTruncated: Boolean = false,
  )

  private fun facts(fixture: FactsFixture) = AgentRunLaunchFacts(
    agent = InstallAgent.CODEX,
    exitStatus = fixture.exitStatus,
    stdout = fixture.stdout,
    stderr = "",
    timedOut = fixture.timedOut,
    interrupted = fixture.interrupted,
    spawnFailed = fixture.spawnFailed,
    stdoutTruncated = fixture.stdoutTruncated,
  )
}
