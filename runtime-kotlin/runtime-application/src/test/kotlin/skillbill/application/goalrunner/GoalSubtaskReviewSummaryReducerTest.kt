package skillbill.application.goalrunner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalSubtaskReviewSummaryReducerTest {
  @Test
  fun `compact summaries remove paths lines hunks and duplicate findings`() {
    val summary = GoalSubtaskReviewSummaryReducer.fromOutput(
      mapOf(
        "produced_outputs" to mapOf(
          "findings" to listOf(
            mapOf("severity" to "major", "message" to "src/main/kotlin/OrderService.kt:42 @@ -1,2 +1,2 @@ OrderService misses validation"),
            mapOf("severity" to "major", "message" to "src/main/kotlin/OrderService.kt:42 @@ -1,2 +1,2 @@ OrderService misses validation"),
            mapOf("severity" to "minor", "message" to "/tmp/work/Repository.kt:8 Repository leaks a detail"),
          ),
        ),
      ),
    )

    assertEquals(2, summary.size)
    assertEquals("OrderService", summary.first().label)
    val rendered = summary.joinToString(" ") { "${it.label} ${it.text}" }
    assertFalse("src/" in rendered)
    assertFalse("/tmp/" in rendered)
    assertFalse(Regex(":\\d+").containsMatchIn(rendered))
    assertFalse("@@" in rendered)
  }

  @Test
  fun `compact summaries prefer structured labels and remove bare filenames`() {
    val summary = GoalSubtaskReviewSummaryReducer.fromOutput(
      mapOf(
        "produced_outputs" to mapOf(
          "findings" to listOf(
            mapOf(
              "severity" to "major",
              "class_or_symbol" to "CheckoutService.submit",
              "message" to "CheckoutService.kt:17 @@ -4,6 +4,9 @@ submit permits an invalid transition",
            ),
            mapOf(
              "severity" to "major",
              "symbol" to "CheckoutService.submit",
              "message" to "src/CheckoutService.kt:17 submit bypasses the aggregate invariant",
            ),
            mapOf(
              "severity" to "minor",
              "class_or_symbol" to "src/LegacyCheckout.kt:28",
              "message" to "LegacyCheckout.kt:28 needs a separate note",
            ),
          ),
        ),
      ),
    )

    assertEquals(2, summary.size)
    val checkout = summary.first { it.label == "CheckoutService.submit" }
    val legacy = summary.first { it.label == "LegacyCheckout" }
    assertFalse("CheckoutService.kt" in checkout.text)
    assertFalse(Regex("(?:^|\\s)[A-Za-z0-9_.-]+\\.(?:kt|java)(?:\\s|$)").containsMatchIn(checkout.text))
    assertTrue(checkout.text.contains("invalid transition"))
    assertFalse("/" in legacy.label)
    assertFalse(Regex(":\\d+").containsMatchIn(legacy.label))
  }
}
