package skillbill.application.review

import skillbill.application.model.ReviewPrelaunchExpansion
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.ports.persistence.model.ReviewAccountingRecord
import skillbill.review.context.ReviewTreeAccounting
import skillbill.review.context.model.ProviderTokenUsage
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
      "The projection proof needs a run whose prompts actually carried the content bodies.",
    )
    forbidden.forEach { assertFalse(serialized.contains(it), "Accounting projection leaked '$it'.") }
    assertEquals(
      recorder.parentPrompts.size * 1_000L,
      recorded.aggregateDirectUsage.inputTokens,
      "The measured provider dimensions survive the projection the bodies do not.",
    )
    assertTrue(serialized.contains("fresh_token_approximation="))
  }

  @Test fun `projection contains bounded metadata and no content bodies`() {
    val serialized = summary().toBoundedPayload().toString()

    assertTrue(serialized.contains("fresh_token_approximation=80"))
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
        "aggregate_counters",
        "aggregate_direct_usage",
        "aggregate_inclusive_usage",
        "budget_regression",
      ),
      payload.keys,
    )
    assertEquals(REVIEW_CONTEXT_CONTRACT_VERSION, payload["contract_version"])
    @Suppress("UNCHECKED_CAST")
    val parent = payload["parent"] as Map<String, Any?>
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
        "provider_usage",
        "direct_usage",
        "inclusive_usage",
        "terminal_outcome",
      ),
      parent.keys,
    )
    @Suppress("UNCHECKED_CAST")
    val counters = payload["aggregate_counters"] as Map<String, Any?>
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

    @Suppress("UNCHECKED_CAST")
    val lane = (summary.toBoundedPayload()["lanes"] as List<Map<String, Any?>>).single()
    assertEquals(digest, lane["bundle_composition_digest"])
    assertEquals(listOf("unreviewable"), lane["unreviewed_segment_ids"])
    @Suppress("UNCHECKED_CAST")
    val segments = lane["segment_accounting"] as List<Map<String, Any?>>
    assertEquals("seg-000", segments.single()["segment_id"])
    assertEquals(128L, segments.single()["measured_bytes"])
    assertEquals(2, segments.single()["entry_count"])
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
            usage = ProviderTokenUsage(1_000, 400, 200, 50, 1_200),
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
      usage = ProviderTokenUsage(100, 40, 20, 5, 120),
      children = listOf(
        ReviewAccountingInput(
          lane = "architecture",
          assignmentDigest = "architecture-digest",
          counters = ReviewAccountingCounters(11, 22, 33, 1, 1, 1),
          usage = ProviderTokenUsage(10, 4, 2, 1, 12),
        ),
      ),
    ),
  )
}
