package skillbill.application.goalrunner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
