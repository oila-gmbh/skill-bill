package skillbill.application.review

import skillbill.review.context.ReviewTreeAccounting
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewAccountingCounters
import skillbill.review.context.model.ReviewAccountingInput
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewAccountingProjectionRedactionTest {
  @Test fun `projection contains bounded metadata and no content bodies`() {
    val forbidden = listOf("PROMPT_SECRET", "DIFF_SECRET", "SOURCE_SECRET", "GUIDANCE_SECRET", "TOOL_SECRET")
    val summary = ReviewTreeAccounting.summarize(
      "review-id",
      "packet-digest",
      ReviewAccountingInput(
        lane = "parent",
        assignmentDigest = "assignment-digest",
        counters = ReviewAccountingCounters(10, 20, 30, 1, 2, 3),
        usage = ProviderTokenUsage(100, 40, 20, 5, 120),
      ),
    )

    val serialized = summary.toBoundedPayload().toString()

    forbidden.forEach { assertFalse(serialized.contains(it)) }
    assertTrue(serialized.contains("fresh_token_approximation=80"))
    assertTrue(serialized.contains("tool_calls=2"))
  }
}
