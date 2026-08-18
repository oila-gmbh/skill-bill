package skillbill.application.review

import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.review.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.ports.review.model.GovernedReviewEvidenceEndpointDescriptor
import skillbill.review.model.ReviewEvidenceBoundaryAccounting
import skillbill.review.model.ReviewStageDegradationReason
import skillbill.workflow.model.CodeReviewExecutionMode
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParallelCodeReviewEvidenceBoundaryTest {
  @Test
  fun `broker bind failure emits unbound degradation and a non-success lane`() {
    val recorder = ReviewRecorder()
    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
        diff = diffForPaths("src/Repo.kt"),
        evidenceBrokerFactory = ReviewEvidenceBrokerFactory { error("broker construction failed") },
      ),
      recorder,
    ).run(
      harnessRequest(
        agent2Id = null,
        reviewRunId = "rvw-195-unbound",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ),
    )

    assertFalse(result.lane1.success)
    assertEquals("governed evidence broker construction failed", result.lane1.failureReason)
    val unbound = recorder.stageDegradations.filter {
      it.reason == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNBOUND_BROKER
    }
    assertEquals(1, unbound.size)
    assertEquals(ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM, unbound.single().seam)
    assertEquals("unbound", unbound.single().actual)
  }

  @Test
  fun `endpoint bind failure emits the unbound degradation and launches nothing`() {
    val recorder = ReviewRecorder()
    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
        diff = diffForPaths("src/Repo.kt"),
        evidenceEndpointBinder = skillbill.ports.review.GovernedReviewEvidenceEndpointBinder { _, _ ->
          error("endpoint bind failed")
        },
        parentLaunch = { error("a governed review must not launch when its evidence endpoint is unbound") },
      ),
      recorder,
    ).run(
      harnessRequest(
        agent2Id = null,
        reviewRunId = "rvw-195-endpoint-unbound",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ),
    )

    assertFalse(result.lane1.success)
    assertEquals("governed evidence broker endpoint failed", result.lane1.failureReason)
    val unbound = recorder.stageDegradations.filter {
      it.reason == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNBOUND_BROKER
    }
    assertEquals(1, unbound.size)
    assertEquals(ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM, unbound.single().seam)
  }

  @Test
  fun `governed launch with locators and zero authorized reads emits one unexercised-boundary record`() {
    val recorder = ReviewRecorder()
    reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
        diff = diffForPaths("src/Repo.kt"),
      ),
      recorder,
    ).run(
      harnessRequest(
        agent2Id = null,
        reviewRunId = "rvw-195-unexercised",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ),
    )

    val unexercised = recorder.stageDegradations.filter {
      it.reason == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNEXERCISED
    }
    assertEquals(1, unexercised.size)
    assertEquals(ReviewEvidenceBoundaryAccounting.GOVERNED_EVIDENCE_SEAM, unexercised.single().seam)
    assertEquals("authorized_reads=0", unexercised.single().actual)
    assertEquals(1, recorder.parentLaunches.size)
    assertNotNull(recorder.parentLaunches.single().skillRunRequest.reviewEvidenceBroker)
  }

  @Test
  fun `a lane reporting an authorized read with zero evidence bytes suppresses the unexercised-boundary record`() {
    val recorder = ReviewRecorder()
    val defaults = ReviewHarnessConfig(
      manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
      diff = diffForPaths("src/Repo.kt"),
    )
    val result = reviewHarness(
      defaults.copy(
        evidenceBrokerFactory = ReviewEvidenceBrokerFactory { binding ->
          val inner = defaults.evidenceBrokerFactory.brokerFor(binding)
          object : ReviewEvidenceBroker by inner {
            override fun accounting() = inner.accounting().copy(authorizedReadCount = 1)
          }
        },
      ),
      recorder,
    ).run(
      harnessRequest(
        agent2Id = null,
        reviewRunId = "rvw-195-zero-byte",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ),
    )

    assertEquals(1, result.lane1.accounting?.authorizedReadCount)
    assertEquals(0L, result.lane1.accounting?.evidenceBytes)
    assertTrue(
      recorder.stageDegradations.none {
        it.reason == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNEXERCISED
      },
    )
  }

  @Test
  fun `parse rejection of an inadmissible path emits one rejected-candidate record carrying the count`() {
    val recorder = ReviewRecorder()
    val rejectedLocation =
      "- [F-001] Major | High | path=\"/tmp/outside.kt\" | line=3 | outside the packet"
    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
        diff = diffForPaths("src/Repo.kt"),
        response = { RecordedWorkerResponse(stdout = rejectedLocation) },
      ),
      recorder,
    ).run(
      harnessRequest(
        agent2Id = null,
        reviewRunId = "rvw-195-rejected",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ),
    )

    val rejected = recorder.stageDegradations.filter {
      it.reason == ReviewStageDegradationReason.REGISTER_CANDIDATES_REJECTED
    }
    assertEquals(1, rejected.size)
    assertEquals("rejected_candidates=1", rejected.single().actual)
    assertEquals("review.register.parse", rejected.single().seam)
    assertEquals(emptyList(), result.mergeResult.findings)
  }

  @Test
  fun `a governed review whose launcher never starts still tears down the bound endpoint`() {
    val directory = Files.createTempDirectory("skill-bill-review-evidence-")
    val socketPath = directory.resolve("evidence.sock")
    val configPath = directory.resolve("mcp.json")
    Files.createFile(socketPath)
    Files.createFile(configPath)

    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
        diff = diffForPaths("src/Repo.kt"),
        evidenceEndpointBinder = GovernedReviewEvidenceEndpointBinder { lane, _ ->
          object : GovernedReviewEvidenceEndpointHandle {
            override val descriptor = GovernedReviewEvidenceEndpointDescriptor(
              lane = lane,
              socketPath = socketPath,
              mcpConfigPath = configPath,
              token = "unavailable-cli",
            )

            override fun close() {
              Files.deleteIfExists(socketPath)
              Files.deleteIfExists(configPath)
              Files.deleteIfExists(directory)
            }
          }
        },
        response = { RecordedWorkerResponse(spawnFailed = true, processStarted = false, exitStatus = null) },
      ),
      ReviewRecorder(),
    ).run(
      harnessRequest(
        agent2Id = null,
        reviewRunId = "rvw-195-unavailable-cli",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ),
    )

    assertFalse(result.lane1.success)
    assertFalse(Files.exists(socketPath))
    assertFalse(Files.exists(directory))
  }

  @Test
  fun `unsupported provider omits unbound-broker and unexercised evidence records`() {
    val recorder = ReviewRecorder()
    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
        diff = diffForPaths("src/Repo.kt"),
        parentLaunch = { request ->
          UnsupportedAgentRunLaunch(
            agent = InstallAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
            reason = "not configured for this repo",
          )
        },
      ),
      recorder,
    ).run(
      harnessRequest(
        agent2Id = null,
        reviewRunId = "rvw-195-unsupported",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ),
    )

    assertFalse(result.lane1.success)
    assertEquals(UNSUPPORTED_PROVIDER_TERMINAL_STATUS, result.lane1.accounting?.terminalStatus)
    assertTrue(
      recorder.stageDegradations.none {
        it.reason == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNBOUND_BROKER ||
          it.reason == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNEXERCISED
      },
    )
  }

  @Test
  fun `bound unread parent lane does not hide the other lane unbound evidence record`() {
    val recorder = ReviewRecorder()
    val binds = AtomicInteger()
    val defaults = ReviewHarnessConfig(
      manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
      diff = diffForPaths("src/Repo.kt"),
    )
    reviewHarness(
      defaults.copy(
        evidenceBrokerFactory = ReviewEvidenceBrokerFactory { binding ->
          if (binds.incrementAndGet() > 1) error("second parent bind failed")
          defaults.evidenceBrokerFactory.brokerFor(binding)
        },
      ),
      recorder,
    ).run(
      harnessRequest(
        reviewRunId = "rvw-195-mixed-lanes",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ),
    )

    val reasons = recorder.stageDegradations.map { it.reason }
    assertEquals(1, reasons.count { it == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNBOUND_BROKER })
    assertEquals(1, reasons.count { it == ReviewStageDegradationReason.EVIDENCE_BOUNDARY_UNEXERCISED })
  }
}
