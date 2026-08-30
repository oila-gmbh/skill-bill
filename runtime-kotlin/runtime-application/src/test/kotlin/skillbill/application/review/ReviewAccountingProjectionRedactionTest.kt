package skillbill.application.review

import skillbill.application.review.model.ReviewPrelaunchExpansion
import skillbill.contracts.JsonSupport
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.ports.review.model.ReviewAccountingRecord
import skillbill.review.context.ReviewTreeAccounting
import skillbill.review.context.model.ReviewAccountingCounters
import skillbill.review.context.model.ReviewAccountingInput
import skillbill.review.context.model.ReviewAccountingSummary
import skillbill.review.context.model.ReviewLaneSegmentAccounting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReviewAccountingProjectionRedactionTest {
  private val forbidden = listOf("DIFF_SECRET", "RUBRIC_SECRET", "TOOL_OUTPUT_SECRET")

  @Test fun `projection of a real review contains bounded metadata and no content bodies`() {
    val (recorder, recorded) = recordedReview()
    val serialized = recorded.toBoundedPayload().toString()

    assertTrue(
      recorder.parentPrompts.isNotEmpty() &&
        recorder.parentPrompts.all { prompt -> forbidden.dropLast(1).all { prompt.contains(it) } },
      "The projection proof needs a run whose prompts actually carried rubric bodies and owned paths.",
    )
    forbidden.forEach { assertFalse(serialized.contains(it), "Accounting projection leaked '$it'.") }
    assertTrue(recorded.aggregateCounters.launchBytes > 0)
  }

  @Test fun `projection contains bounded metadata and no content bodies`() {
    val serialized = summary().toBoundedPayload().toString()

    assertTrue(serialized.contains("tool_calls=2"))
  }

  @Test fun `projection exposes exactly the bounded contract keys`() {
    val payload = summary().toBoundedPayload()

    assertEquals(
      setOf(
        "contract_version",
        "kind",
        "review_id",
        "packet_digest",
        "parent",
        "lanes",
        "commit_routing_accounting",
        "parent_analysis_consumption",
        "integration",
        "aggregate_counters",
      ),
      payload.keys,
    )
    assertEquals(REVIEW_CONTEXT_CONTRACT_VERSION, payload["contract_version"])
    val parent = requireNotNull(JsonSupport.anyToStringAnyMap(payload["parent"]))
    assertEquals(
      setOf(
        "lane",
        "assignment_digest",
        "launch_bytes",
        "evidence_bytes",
        "result_bytes",
        "expansions",
        "tool_calls",
        "model_turns",
        "inclusive_counters",
        "terminal_outcome",
      ),
      parent.keys,
    )
    val counters = requireNotNull(JsonSupport.anyToStringAnyMap(payload["aggregate_counters"]))
    assertEquals(
      setOf("launch_bytes", "evidence_bytes", "result_bytes", "expansions", "tool_calls", "model_turns"),
      counters.keys,
    )
  }

  @Test fun `lane nodes project bundle composition and segment accounting`() {
    val digest = "a".repeat(64)
    val summary = ReviewTreeAccounting.summarize(
      "review-id",
      "packet-digest",
      ReviewAccountingInput(
        lane = "parent",
        assignmentDigest = "assignment-digest",
        children = listOf(
          ReviewAccountingInput(
            lane = "architecture",
            assignmentDigest = "architecture-digest",
            terminalOutcome = "incomplete",
            bundleCompositionDigest = digest,
            segmentAccounting = listOf(ReviewLaneSegmentAccounting("seg-000", 128, 2, digest)),
            unreviewedSegmentIds = listOf("unreviewable"),
          ),
        ),
      ),
    )
    val lane = requireNotNull(JsonSupport.anyToStringAnyMapList((summary.toBoundedPayload()["lanes"]))).single()
    assertEquals(digest, lane["bundle_composition_digest"])
    assertEquals(listOf("unreviewable"), lane["unreviewed_segment_ids"])
    val segments = requireNotNull(JsonSupport.anyToStringAnyMapList(lane["segment_accounting"]))
    assertEquals("seg-000", segments.single()["segment_id"])
    assertEquals(128L, segments.single()["measured_bytes"])
    assertEquals(2, segments.single()["entry_count"])
  }

  @Test fun `incomplete broker-refusal accounting projects refused segment ids only`() {
    val digest = "a".repeat(64)
    val summary = ReviewTreeAccounting.summarize(
      "review-id",
      "packet-digest",
      ReviewAccountingInput(
        lane = "parent",
        assignmentDigest = "assignment-digest",
        children = listOf(
          ReviewAccountingInput(
            lane = "architecture",
            assignmentDigest = "architecture-digest",
            terminalOutcome = "incomplete",
            bundleCompositionDigest = digest,
            segmentAccounting = listOf(ReviewLaneSegmentAccounting("seg-000", 128, 2, digest)),
            unreviewedSegmentIds = listOf("seg-evidence-refused"),
          ),
        ),
      ),
    )
    val lane = requireNotNull(JsonSupport.anyToStringAnyMapList((summary.toBoundedPayload()["lanes"]))).single()
    assertEquals(listOf("seg-evidence-refused"), lane["unreviewed_segment_ids"])
    assertFalse(lane.toString().contains("evidence-unreviewable"))
  }

  @Test fun `bounded payload survives the durable record contract`() {
    val recorded = recordedReview().second
    val payload = recorded.toBoundedPayload()

    val record = ReviewAccountingRecord(recorded.reviewId, recorded.packetDigest, payload)

    assertEquals(payload, record.boundedPayload)
    forbidden.forEach { assertFalse(record.boundedPayload.toString().contains(it)) }
  }

  @Test fun `durable record rejects an accounting payload carrying content`() {
    val leaking = summary().toBoundedPayload() + ("prompt" to "PROMPT_SECRET")

    val failure = runCatching { ReviewAccountingRecord("review-id", "packet-digest", leaking) }.exceptionOrNull()

    assertTrue(failure is IllegalArgumentException, "Content-bearing accounting payload must fail loudly.")
  }

  /** A production review run whose diff, rubric, and lane result all carry secrets. */
  private fun recordedReview(): Pair<ReviewRecorder, ReviewAccountingSummary> {
    val recorder = ReviewRecorder()
    val result = reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
        diff = diffForPaths("src/DIFF_SECRET.kt"),
        response = {
          RecordedWorkerResponse(
            stdout = "TOOL_OUTPUT_SECRET ".repeat(8),
          )
        },
        rubricBody = { "RUBRIC_SECRET ".repeat(8) },
      ),
      recorder,
    ).run(
      harnessRequest(
        prelaunchExpansions = listOf(
          ReviewPrelaunchExpansion(
            "parallel-code-review",
            "src/DIFF_SECRET.kt",
            "The redaction test measures one explicitly authorized complete-file expansion.",
          ),
        ),
      ),
    )

    return recorder to assertNotNull(result.accountingSummary)
  }

  private fun summary() = ReviewTreeAccounting.summarize(
    "review-id",
    "packet-digest",
    ReviewAccountingInput(
      lane = "parent",
      assignmentDigest = "assignment-digest",
      counters = ReviewAccountingCounters(10, 20, 30, 1, 2, 3),
      children = listOf(
        ReviewAccountingInput(
          lane = "architecture",
          assignmentDigest = "architecture-digest",
          counters = ReviewAccountingCounters(11, 22, 33, 1, 1, 1),
        ),
      ),
    ),
  )
}
