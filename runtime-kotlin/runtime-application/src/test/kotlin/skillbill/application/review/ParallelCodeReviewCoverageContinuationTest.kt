package skillbill.application.review

import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.NativeReviewOperationProtocol
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceCatalogEntry
import skillbill.ports.review.model.ReviewEvidenceDiscoveryRequest
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.scaffold.model.ReviewLaneCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParallelCodeReviewCoverageContinuationTest {
  @Test
  fun `partial inline delivery continues on the same broker until coverage completes`() {
    val recorder = ReviewRecorder()
    val state = PartialDeliveryState()
    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = dualLaneManifests(),
        diff = diffForPaths("src/shared/Shared.kt", "src/core/Core.kt", "src/secure/Auth.kt"),
        simulateEvidenceReads = false,
        response = { launch -> partialDeliveryResponse(state, launch) },
      ),
      recorder,
    ).run(
      harnessRequest(reviewRunId = "rvw-237-partial-complete", codeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertEquals(2, state.launchIndex)
    assertEquals(2, recorder.parentLaunches.count { it.skillRunRequest.issueKey == "code-review" })
    assertTrue(result.lane1.success)
    assertNull(result.lane1.failureReason)
    assertEquals(4, assertNotNull(result.lane1.accounting).deliveredEvidenceUnits)
    assertTrue(result.mergeResult.output.contains("slice one"))
    assertTrue(result.mergeResult.output.contains("slice two"))
    assertEquals(2, result.mergeResult.findings.size)
    assertTrue(assertNotNull(result.coverage).isCleanCoverage)
  }

  @Test
  fun `zero progress on the continuation slice stays terminal incomplete`() {
    val recorder = ReviewRecorder()
    var launchIndex = 0
    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = dualLaneManifests(),
        diff = diffForPaths("src/shared/Shared.kt", "src/core/Core.kt", "src/secure/Auth.kt"),
        simulateEvidenceReads = false,
        response = { launch ->
          if (launch.skillRunRequest.issueKey != "code-review") {
            RecordedWorkerResponse()
          } else {
            launchIndex += 1
            val protocol = requireNotNull(launch.skillRunRequest.nativeReviewOperations)
            val broker = requireNotNull(launch.skillRunRequest.reviewEvidenceBroker)
            val lane = broker.accounting().lane
            when (launchIndex) {
              1 -> {
                deliverScenarioEvidence(protocol, lane, "partial")
                RecordedWorkerResponse(stdout = "verdict: approved")
              }
              2 -> RecordedWorkerResponse(stdout = "verdict: approved")
              else -> error("unexpected launch index $launchIndex")
            }
          }
        },
      ),
      recorder,
    ).run(
      harnessRequest(reviewRunId = "rvw-237-zero-progress", codeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertEquals(2, launchIndex)
    assertFalse(result.lane1.success)
    assertEquals("Required review evidence remains undelivered.", result.lane1.failureReason)
    assertEquals(2, assertNotNull(result.lane1.accounting).deliveredEvidenceUnits)
  }

  @Test
  fun `lane evidence budget exhaustion does not launch another worker`() {
    val recorder = ReviewRecorder()
    var launchIndex = 0
    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
        diff = diffForChanges("src/A.kt" to "a", "src/B.kt" to "b"),
        budget = ReviewContextBudgetPolicy.DEFAULT.copy(
          maxEvidenceResultBytes = 3,
          maxLaneEvidenceBytes = 3,
        ),
        simulateEvidenceReads = false,
        response = { launch ->
          if (launch.skillRunRequest.issueKey != "code-review") {
            RecordedWorkerResponse()
          } else {
            launchIndex += 1
            val protocol = requireNotNull(launch.skillRunRequest.nativeReviewOperations)
            val broker = requireNotNull(launch.skillRunRequest.reviewEvidenceBroker)
            val lane = broker.accounting().lane
            protocol.discover(ReviewEvidenceDiscoveryRequest()).entries.forEach { entry ->
              protocol.read(
                ReviewEvidenceBatchRequest.of(
                  ReviewEvidenceRequest(lane, entry.path, selector = entry.selector),
                ),
              )
            }
            RecordedWorkerResponse(stdout = "verdict: approved")
          }
        },
      ),
      recorder,
    ).run(
      harnessRequest(reviewRunId = "rvw-237-budget", codeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertEquals(1, launchIndex)
    assertFalse(result.lane1.success)
    assertEquals(
      "lane_evidence_bytes",
      assertNotNull(result.lane1.accounting, result.lane1.failureReason).terminalOutcome?.budgetKind,
    )
  }

  @Test
  fun `timeout on the first slice does not launch a continuation worker`() {
    val recorder = ReviewRecorder()
    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = dualLaneManifests(),
        diff = diffForPaths("src/shared/Shared.kt", "src/core/Core.kt", "src/secure/Auth.kt"),
        simulateEvidenceReads = false,
        response = { launch ->
          if (launch.skillRunRequest.issueKey != "code-review") {
            RecordedWorkerResponse()
          } else {
            deliverScenarioEvidence(
              requireNotNull(launch.skillRunRequest.nativeReviewOperations),
              requireNotNull(launch.skillRunRequest.reviewEvidenceBroker).accounting().lane,
              "partial",
            )
            RecordedWorkerResponse(stdout = "verdict: approved", timedOut = true)
          }
        },
      ),
      recorder,
    ).run(
      harnessRequest(reviewRunId = "rvw-237-timeout", codeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertEquals(1, recorder.parentLaunches.count { it.skillRunRequest.issueKey == "code-review" })
    assertFalse(result.lane1.success)
    assertEquals("agent timed out", result.lane1.failureReason)
  }

  @Test
  fun `one shot full delivery still launches once`() {
    val recorder = ReviewRecorder()
    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = dualLaneManifests(),
        diff = diffForPaths("src/shared/Shared.kt", "src/core/Core.kt", "src/secure/Auth.kt"),
        simulateEvidenceReads = true,
      ),
      recorder,
    ).run(
      harnessRequest(reviewRunId = "rvw-237-one-shot", codeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertEquals(1, recorder.parentLaunches.count { it.skillRunRequest.issueKey == "code-review" })
    assertTrue(result.lane1.success)
    assertTrue(assertNotNull(result.coverage).isCleanCoverage)
  }

  private fun dualLaneManifests() = listOf(
    reviewPack(
      "kotlin",
      listOf("architecture", "security"),
      routingSignals = listOf("*.kt"),
    ).copy(
      laneConditions = mapOf(
        "architecture" to ReviewLaneCondition(path = listOf("src/shared/", "src/core/")),
        "security" to ReviewLaneCondition(path = listOf("src/shared/", "src/secure/")),
      ),
    ),
  )

  private fun partialDeliveryResponse(
    state: PartialDeliveryState,
    launch: GoalRunnerSubtaskLaunchRequest,
  ): RecordedWorkerResponse {
    if (launch.skillRunRequest.issueKey != "code-review") return RecordedWorkerResponse()
    state.launchIndex += 1
    val protocol = requireNotNull(launch.skillRunRequest.nativeReviewOperations)
    val broker = requireNotNull(launch.skillRunRequest.reviewEvidenceBroker)
    if (state.heldBroker == null) {
      state.heldBroker = broker
    } else {
      assertEquals(state.heldBroker, broker)
    }
    val lane = broker.accounting().lane
    return when (state.launchIndex) {
      1 -> {
        val entry = protocol.discover(ReviewEvidenceDiscoveryRequest()).entries
          .single { it.path == "src/shared/Shared.kt" }
        deliverEntry(protocol, lane, entry)
        state.deliveredSelectors += entry.selector
        RecordedWorkerResponse(
          stdout = "[F-001] Major | High | src/shared/Shared.kt:1 | slice one\nverdict: approved",
        )
      }
      2 -> {
        val page = protocol.discover(ReviewEvidenceDiscoveryRequest())
        assertTrue(page.entries.none { it.selector in state.deliveredSelectors })
        page.entries.forEach { deliverEntry(protocol, lane, it) }
        RecordedWorkerResponse(
          stdout = "[F-002] Major | High | src/core/Core.kt:1 | slice two\nverdict: approved",
        )
      }
      else -> error("unexpected launch index ${state.launchIndex}")
    }
  }

  private fun deliverEntry(protocol: NativeReviewOperationProtocol, lane: String, entry: ReviewEvidenceCatalogEntry) {
    val response = protocol.read(
      ReviewEvidenceBatchRequest.of(
        ReviewEvidenceRequest(lane, entry.path, selector = entry.selector),
      ),
    )
    protocol.confirmDelivery(requireNotNull(response.deliveryReceipt))
  }

  private fun deliverScenarioEvidence(protocol: NativeReviewOperationProtocol, lane: String, scenario: String) {
    val entries = protocol.discover(ReviewEvidenceDiscoveryRequest()).entries
    val reads = when (scenario) {
      "partial" -> entries.filter { it.path == "src/shared/Shared.kt" }
      "full" -> entries
      else -> emptyList()
    }
    reads.forEach { deliverEntry(protocol, lane, it) }
  }
}

private class PartialDeliveryState {
  var launchIndex = 0
  var heldBroker: ReviewEvidenceBroker? = null
  val deliveredSelectors = mutableSetOf<String>()
}
