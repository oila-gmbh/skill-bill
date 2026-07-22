package skillbill.application.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewDiffEvidenceTest {
  @Test
  fun `routing evidence contains changed lines but not unchanged hunk context`() {
    val evidence = ReviewDiffEvidence.parse(
      """
      diff --git a/src/Main.kt b/src/Main.kt
      --- a/src/Main.kt
      +++ b/src/Main.kt
      @@ -1,2 +1,2 @@
       @Composable fun unchangedContext() = Unit
      -val before = 1
      +val after = 2
      """.trimIndent(),
    )

    assertEquals(listOf("src/Main.kt"), evidence.files.map { it.path })
    assertTrue(evidence.files.single().changedContent.contains("val after"))
    assertFalse(evidence.files.single().changedContent.contains("unchangedContext"))
    assertTrue(evidence.hunks.single().content.contains("unchangedContext"))
  }
}
