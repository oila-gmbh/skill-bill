package skillbill.application.review

import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.ports.persistence.model.ReviewAccountingRecord
import skillbill.review.context.ReviewTreeAccounting
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewAccountingCounters
import skillbill.review.context.model.ReviewAccountingInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewAccountingProjectionRedactionTest {
  private val forbidden = listOf(
    "PROMPT_SECRET",
    "DIFF_SECRET",
    "SOURCE_SECRET",
    "GUIDANCE_SECRET",
    "RUBRIC_SECRET",
    "TOOL_OUTPUT_SECRET",
  )

  @Test fun `projection contains bounded metadata and no content bodies`() {
    val serialized = summary().toBoundedPayload().toString()

    forbidden.forEach { assertFalse(serialized.contains(it), "Accounting projection leaked '$it'.") }
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

  @Test fun `bounded payload survives the durable record contract`() {
    val payload = summary().toBoundedPayload()

    val record = ReviewAccountingRecord("review-id", "packet-digest", payload)

    assertEquals(payload, record.boundedPayload)
    forbidden.forEach { assertFalse(record.boundedPayload.toString().contains(it)) }
  }

  @Test fun `durable record rejects an accounting payload carrying content`() {
    val leaking = summary().toBoundedPayload() + ("prompt" to "PROMPT_SECRET")

    val failure = runCatching { ReviewAccountingRecord("review-id", "packet-digest", leaking) }.exceptionOrNull()

    assertTrue(failure is IllegalArgumentException, "Content-bearing accounting payload must fail loudly.")
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
